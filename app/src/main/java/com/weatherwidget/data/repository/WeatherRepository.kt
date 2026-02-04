package com.weatherwidget.data.repository

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.util.Log
import com.weatherwidget.data.ApiLogger
import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDao
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.util.TemperatureInterpolator
import java.time.LocalDateTime
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.widget.ApiPreference
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private const val TAG = "WeatherRepository"

@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val weatherDao: WeatherDao,
    private val forecastSnapshotDao: ForecastSnapshotDao,
    private val hourlyForecastDao: HourlyForecastDao,
    private val nwsApi: NwsApi,
    private val openMeteoApi: OpenMeteoApi,
    private val widgetStateManager: WidgetStateManager,
    private val apiLogger: ApiLogger,
    private val temperatureInterpolator: TemperatureInterpolator
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
    }
    companion object {
        private const val MONTH_IN_MILLIS = 30L * 24 * 60 * 60 * 1000
    }

    // Toggle between APIs - alternate fairly between both
    private var useOpenMeteoFirst = false

    suspend fun getWeatherData(
        lat: Double,
        lon: Double,
        locationName: String,
        forceRefresh: Boolean = false
    ): Result<List<WeatherEntity>> {
        return try {
            if (!forceRefresh) {
                val cached = getCachedData(lat, lon)
                if (cached.isNotEmpty()) {
                    return Result.success(cached)
                }
            }

            val (nwsWeather, meteoWeather) = fetchFromBothApis(lat, lon, locationName)

            // Save both APIs' data, merging with existing to preserve non-zero values
            // This handles the case where evening NWS only has "Tonight" (low) but
            // earlier fetch had "Today" (high) - we keep the high from earlier
            if (nwsWeather != null) {
                val merged = mergeWithExisting(nwsWeather, lat, lon)
                weatherDao.insertAll(merged)
            }
            if (meteoWeather != null) {
                val merged = mergeWithExisting(meteoWeather, lat, lon)
                weatherDao.insertAll(merged)
            }
            cleanOldData()
            // Return from database to include previously cached data (e.g., yesterday from Open-Meteo)
            Result.success(getCachedData(lat, lon))
        } catch (e: Exception) {
            val cached = getCachedData(lat, lon)
            if (cached.isNotEmpty()) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    private suspend fun saveForecastSnapshot(
        weather: List<WeatherEntity>,
        lat: Double,
        lon: Double,
        source: String
    ) {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val fetchedAt = System.currentTimeMillis()

        /*
        fetchFromNws function doesn't just get the forecast; it also explicitly fetches the last 7 days of actual observations (highs and lows
        that already happened) so the widget can show the "Yesterday" history.


        If we change the code to just date != null, the app will do the following every hour:
        1. Fetch the last 7 days of observations.
        2. Save them into the forecast_snapshots table as "snapshots" made today.


        The result:
        Your forecast_snapshots table would become cluttered with "predictions" of the past. For example, it would store a record saying: "Today (Feb 4), we predict that
        on Feb 1st, it was 65 degrees." Since Feb 1st is already over, that's not a forecast—it's just a record of history.


        My recommendation:
        Keep (date.isAfter(today) || date.isEqual(today)). This ensures the snapshot table stays focused on predictions (what we think will happen today and in the future)
        rather than re-saving old observations over and over again.

        */

        // Include all forecasts for today and the future.
        // We filter out past dates because NWS also returns historical observations
        // for the last 7 days which we don't want to store as 'forecast' snapshots.
        val relevantForecasts = weather.filter { 
            val date = try { LocalDate.parse(it.date) } catch (e: Exception) { null }
            date != null && (date.isAfter(today) || date.isEqual(today))
        }

        val snapshots = relevantForecasts.mapNotNull { forecast ->
            // Save even if partial, but not if both are null
            if (forecast.highTemp == null && forecast.lowTemp == null) return@mapNotNull null
            
            ForecastSnapshotEntity(
                targetDate = forecast.date,
                forecastDate = todayStr,
                locationLat = lat,
                locationLon = lon,
                highTemp = forecast.highTemp,
                lowTemp = forecast.lowTemp,
                condition = forecast.condition,
                // Use generic gap source for climate normals
                source = if (forecast.isClimateNormal) WidgetStateManager.SOURCE_GENERIC_GAP else source,
                fetchedAt = fetchedAt
            )
        }

        if (snapshots.isNotEmpty()) {
            forecastSnapshotDao.insertAll(snapshots)
            Log.d(TAG, "saveForecastSnapshot: Saved ${snapshots.size} snapshots for $source. Range: ${snapshots.minOf { it.targetDate }} to ${snapshots.maxOf { it.targetDate }}")
        }
    }

    suspend fun getForecastForDate(
        targetDate: String,
        lat: Double,
        lon: Double
    ): ForecastSnapshotEntity? {
        return forecastSnapshotDao.getForecastForDate(targetDate, lat, lon)
    }

    suspend fun getForecastForDateBySource(
        targetDate: String,
        lat: Double,
        lon: Double,
        source: String
    ): ForecastSnapshotEntity? {
        // Get all snapshots for this date and filter by source, falling back to GENERIC_GAP
        val snapshots = forecastSnapshotDao.getForecastsInRange(targetDate, targetDate, lat, lon)
        return snapshots.find { it.source == source } ?: snapshots.find { it.source == WidgetStateManager.SOURCE_GENERIC_GAP }
    }

    suspend fun getForecastsInRange(
        startDate: String,
        endDate: String,
        lat: Double,
        lon: Double
    ): List<ForecastSnapshotEntity> {
        return forecastSnapshotDao.getForecastsInRange(startDate, endDate, lat, lon)
    }

    suspend fun getWeatherRange(
        startDate: String,
        endDate: String,
        lat: Double,
        lon: Double
    ): List<WeatherEntity> {
        return weatherDao.getWeatherRange(startDate, endDate, lat, lon)
    }

    private suspend fun getCachedData(lat: Double, lon: Double): List<WeatherEntity> {
        val sevenDaysAgo = LocalDate.now().minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val thirtyDays = LocalDate.now().plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
        return weatherDao.getWeatherRange(sevenDaysAgo, thirtyDays, lat, lon)
    }

    suspend fun getCachedDataBySource(lat: Double, lon: Double, source: String): List<WeatherEntity> {
        val sevenDaysAgo = LocalDate.now().minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val thirtyDays = LocalDate.now().plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
        
        val sourceData = weatherDao.getWeatherRangeBySource(sevenDaysAgo, thirtyDays, lat, lon, source)
        val gapData = weatherDao.getWeatherRangeBySource(sevenDaysAgo, thirtyDays, lat, lon, WidgetStateManager.SOURCE_GENERIC_GAP)
        
        // Merge: prefer source data, fill gaps with generic gap data
        val mergedByDate = (gapData + sourceData).associateBy { it.date }
        return mergedByDate.values.sortedBy { it.date }
    }

    /**
     * Merges new weather data with existing data, preserving non-zero values.
     * This handles the case where NWS evening fetch only has "Tonight" (low temp)
     * but an earlier fetch had "Today" (high temp) - we keep the high from earlier.
     */
    private suspend fun mergeWithExisting(
        newData: List<WeatherEntity>,
        lat: Double,
        lon: Double
    ): List<WeatherEntity> {
        if (newData.isEmpty()) return newData

        return newData.groupBy { it.source }.flatMap { (source, items) ->
            val existingData = getCachedDataBySource(lat, lon, source)
            val existingByDate = existingData.associateBy { it.date }

            items.map { new ->
                val existing = existingByDate[new.date] ?: return@map new

                // Prioritization: If new is climate normal but existing is a real forecast, 
                // and the forecast has data, keep the existing one.
                if (new.isClimateNormal && !existing.isClimateNormal && (existing.highTemp != null || existing.lowTemp != null)) {
                    return@map existing
                }

                // Merge nullable temperatures: use existing values where new data has nulls
                new.copy(
                    highTemp = if (new.highTemp == null) existing.highTemp else new.highTemp,
                    lowTemp = if (new.lowTemp == null) existing.lowTemp else new.lowTemp
                )
            }
        }
    }

    /**
     * Fetches weather from both APIs concurrently and saves snapshots from both sources.
     * Returns both APIs' data (NWS first, Open-Meteo second) for storage.
     */
    private suspend fun fetchFromBothApis(
        lat: Double,
        lon: Double,
        locationName: String
    ): Pair<List<WeatherEntity>?, List<WeatherEntity>?> = coroutineScope {
        // Try both APIs concurrently
        val nwsDeferred = async {
            try {
                val startTime = System.currentTimeMillis()
                val result = fetchFromNws(lat, lon, locationName)
                val duration = System.currentTimeMillis() - startTime
                apiLogger.logApiCall("NWS", true, null, locationName, duration)
                Log.d(TAG, "fetchFromBothApis: NWS succeeded")
                result
            } catch (e: Exception) {
                Log.d(TAG, "fetchFromBothApis: NWS failed: ${e.message}")
                apiLogger.logApiCall("NWS", false, e.message ?: "Unknown error", locationName)
                null
            }
        }

        val meteoDeferred = async {
            try {
                val startTime = System.currentTimeMillis()
                val result = fetchFromOpenMeteo(lat, lon, locationName, days = 14)
                val duration = System.currentTimeMillis() - startTime
                apiLogger.logApiCall("Open-Meteo", true, null, locationName, duration)
                Log.d(TAG, "fetchFromBothApis: Open-Meteo succeeded")
                result
            } catch (e: Exception) {
                Log.d(TAG, "fetchFromBothApis: Open-Meteo failed: ${e.message}")
                apiLogger.logApiCall("Open-Meteo", false, e.message ?: "Unknown error", locationName)
                null
            }
        }

        val nwsResult = nwsDeferred.await()
        val meteoResult = meteoDeferred.await()

        // Save snapshots from both APIs (if available)
        nwsResult?.let { saveForecastSnapshot(it, lat, lon, "NWS") }
        meteoResult?.let { saveForecastSnapshot(it, lat, lon, "OPEN_METEO") }

        // If both failed, throw an exception
        if (nwsResult == null && meteoResult == null) {
            Log.e(TAG, "fetchFromBothApis: Both APIs failed")
            throw Exception("Both APIs failed")
        }

        nwsResult to meteoResult
    }

    private fun isDevicePluggedIn(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugType = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        
        val isPlugged = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugType > 0
                
        Log.d(TAG, "isDevicePluggedIn: status=$status, plugType=$plugType -> isPlugged=$isPlugged")
        return isPlugged
    }

    private suspend fun fetchClimateNormalsGap(
        lat: Double,
        lon: Double,
        locationName: String,
        lastDate: LocalDate,
        targetDays: Int,
        source: String
    ): List<WeatherEntity> {
        val isPlugged = isDevicePluggedIn()
        Log.d(TAG, "fetchClimateNormalsGap: Checking if plugged in: $isPlugged (Source Context: $source)")
        
        if (!isPlugged) {
            return emptyList()
        }

        val today = LocalDate.now()
        val targetDate = today.plusDays(targetDays.toLong())
        val startDate = lastDate.plusDays(1)

        if (!startDate.isBefore(targetDate) && !startDate.isEqual(targetDate)) {
            return emptyList()
        }

        return try {
            val startDateStr = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val endDateStr = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
            Log.d(TAG, "fetchClimateNormalsGap: Fetching climate normals from $startDateStr to $endDateStr for $source")
            
            val climateData = openMeteoApi.getClimateForecast(lat, lon, startDateStr, endDateStr)
            climateData.map { daily ->
                WeatherEntity(
                    date = daily.date,
                    locationLat = lat,
                    locationLon = lon,
                    locationName = locationName,
                    highTemp = daily.highTemp,
                    lowTemp = daily.lowTemp,
                    currentTemp = null,
                    condition = "Climate Avg",
                    isActual = false,
                    isClimateNormal = true,
                    source = WidgetStateManager.SOURCE_GENERIC_GAP
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchClimateNormalsGap: Failed to fetch climate normals: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchFromNws(
        lat: Double,
        lon: Double,
        locationName: String
    ): List<WeatherEntity> {
        val gridPoint = nwsApi.getGridPoint(lat, lon)
        val forecast = nwsApi.getForecast(gridPoint)
        Log.d(TAG, "fetchFromNws: Got ${forecast.size} periods")

        // Fetch and save hourly forecasts
        try {
            Log.d(TAG, "fetchFromNws: Fetching hourly forecasts from NWS...")
            val hourlyForecast = nwsApi.getHourlyForecast(gridPoint)
            Log.d(TAG, "fetchFromNws: Got ${hourlyForecast.size} NWS hourly periods")
            if (hourlyForecast.isNotEmpty()) {
                saveNwsHourlyForecasts(hourlyForecast, lat, lon)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchFromNws: Failed to fetch hourly forecasts: ${e.message}", e)
        }

        val weatherByDate = mutableMapOf<String, Pair<Int?, Int?>>()
        val conditionByDate = mutableMapOf<String, String>()
        val stationByDate = mutableMapOf<String, String>()  // Track which station provided data
        val today = LocalDate.now()

        // Fetch last 7 days of actual observations if observation stations are available
        // Include today (daysAgo=0) to get today's actual high/low when it's evening
        try {
            if (gridPoint.observationStationsUrl != null) {
                // Fetch observations for today and the last 7 days
                for (daysAgo in 0..7) {
                    val date = today.minusDays(daysAgo.toLong())
                    val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

                    val observationData = fetchDayObservations(gridPoint.observationStationsUrl, date)
                    if (observationData != null) {
                        weatherByDate[dateStr] = observationData.first to observationData.second
                        stationByDate[dateStr] = observationData.third  // Track station ID
                        conditionByDate[dateStr] = "Observed"
                        Log.d(TAG, "fetchFromNws: Got observations for $dateStr H=${observationData.first} L=${observationData.second} from station ${observationData.third}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchFromNws: Failed to fetch historical observations: ${e.message}")
        }

        // NWS returns periods with startTime - extract date from there
        // Each day has a daytime period (high) and nighttime period (low)
        forecast.forEachIndexed { index, period ->
            val date = try {
                val zonedDateTime = java.time.ZonedDateTime.parse(period.startTime)
                zonedDateTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse startTime ${period.startTime}, falling back to index-based calculation")
                today.plusDays((index / 2).toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            }
            val current = weatherByDate[date] ?: (null to null)

            Log.d(TAG, "  Period $index: ${period.name} isDaytime=${period.isDaytime} temp=${period.temperature} startTime=${period.startTime} -> date=$date")

            if (period.isDaytime) {
                weatherByDate[date] = period.temperature to current.second
                conditionByDate[date] = period.shortForecast
            } else {
                weatherByDate[date] = current.first to period.temperature
                // Ensure partial days have a condition (use night if day is missing)
                if (conditionByDate[date] == null) {
                    conditionByDate[date] = period.shortForecast
                }
            }
        }

        Log.d(TAG, "fetchFromNws: Parsed ${weatherByDate.size} days")

        val nwsResults = weatherByDate.map { (date, temps) ->
            WeatherEntity(
                date = date,
                locationLat = lat,
                locationLon = lon,
                locationName = locationName,
                highTemp = temps.first,
                lowTemp = temps.second,
                currentTemp = null,
                condition = conditionByDate[date] ?: "Unknown",
                isActual = LocalDate.parse(date).isBefore(LocalDate.now()),
                source = "NWS",
                stationId = stationByDate[date],
                isClimateNormal = false
            )
        }

        // Fill gap with climate normals up to 30 days
        val lastNwsDate = nwsResults.map { LocalDate.parse(it.date) }.maxOrNull() ?: today
        val climateGaps = fetchClimateNormalsGap(lat, lon, locationName, lastNwsDate, 30, "NWS")
        
        return nwsResults + climateGaps
    }

    private fun getCachedStations(stationsUrl: String): List<String>? {
        val key = "observation_stations_${stationsUrl.hashCode()}"
        val timeKey = "observation_stations_time_${stationsUrl.hashCode()}"

        val cacheTime = prefs.getLong(timeKey, 0L)
        val cacheTtl = 24 * 60 * 60 * 1000L  // 24 hours
        if (System.currentTimeMillis() - cacheTime > cacheTtl) {
            return null  // Cache expired
        }

        val cached = prefs.getString(key, null) ?: return null
        return cached.split(",").filter { it.isNotBlank() }
    }

    private fun cacheStations(stationsUrl: String, stations: List<String>) {
        val key = "observation_stations_${stationsUrl.hashCode()}"
        val timeKey = "observation_stations_time_${stationsUrl.hashCode()}"

        prefs.edit()
            .putString(key, stations.joinToString(","))
            .putLong(timeKey, System.currentTimeMillis())
            .apply()
    }

    private suspend fun fetchDayObservations(
        stationsUrl: String,
        date: LocalDate
    ): Triple<Int, Int, String>? {  // CHANGED: Now returns (high, low, stationId)
        try {
            // Try cached station list first
            var stations = getCachedStations(stationsUrl)
            if (stations == null || stations.isEmpty()) {
                Log.d(TAG, "fetchDayObservations: Fetching station list from API")
                stations = nwsApi.getObservationStations(stationsUrl)
                if (stations.isEmpty()) {
                    Log.w(TAG, "fetchDayObservations: No observation stations found")
                    return null
                }
                cacheStations(stationsUrl, stations)
            } else {
                Log.d(TAG, "fetchDayObservations: Using cached stations (${stations.size} total)")
            }

            // Try up to 5 stations
            val maxRetries = 5
            val stationsToTry = stations.take(maxRetries)

            for ((index, stationId) in stationsToTry.withIndex()) {
                Log.d(TAG, "fetchDayObservations: Trying station $stationId (${index + 1}/${stationsToTry.size}) for $date")

                try {
                    // Fetch observations for the specified day
                    val localZone = java.time.ZoneId.systemDefault()
                    val startTime = date.atStartOfDay(localZone)
                        .format(java.time.format.DateTimeFormatter.ISO_INSTANT)
                    val endTime = date.plusDays(1).atStartOfDay(localZone)
                        .format(java.time.format.DateTimeFormatter.ISO_INSTANT)

                    val observations = nwsApi.getObservations(stationId, startTime, endTime)
                    if (observations.isEmpty()) {
                        Log.w(TAG, "fetchDayObservations: No observations from $stationId for $date - trying next")
                        continue  // Try next station
                    }

                    Log.i(TAG, "fetchDayObservations: SUCCESS - Got ${observations.size} observations from $stationId for $date")

                    // Calculate high/low from observations (convert C to F)
                    val temps = observations.map { (it.temperatureCelsius * 9 / 5 + 32).toInt() }
                    val high = temps.maxOrNull() ?: continue
                    val low = temps.minOrNull() ?: continue

                    Log.i(TAG, "fetchDayObservations: Station $stationId provided data for $date (H:$high L:$low) after ${index + 1} attempts")

                    return Triple(high, low, stationId)  // CHANGED: Return station ID

                } catch (e: Exception) {
                    Log.w(TAG, "fetchDayObservations: Station $stationId failed for $date: ${e.message}")
                    // Continue to next station
                }
            }

            // All stations failed
            Log.w(TAG, "fetchDayObservations: All ${stationsToTry.size} stations failed for $date")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "fetchDayObservations: Error for $date: ${e.message}", e)
            return null
        }
    }

    private suspend fun fetchFromOpenMeteo(
        lat: Double,
        lon: Double,
        locationName: String,
        days: Int = 7
    ): List<WeatherEntity> {
        Log.d(TAG, "fetchFromOpenMeteo: Fetching for $lat, $lon (days=$days)")
        val forecast = openMeteoApi.getForecast(lat, lon, days)
        Log.d(TAG, "fetchFromOpenMeteo: Got ${forecast.daily.size} days from API")
        forecast.daily.forEach { d ->
            Log.d(TAG, "  API day: ${d.date} H=${d.highTemp} L=${d.lowTemp}")
        }
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Save hourly forecasts for interpolation
        if (forecast.hourly.isNotEmpty()) {
            saveHourlyForecasts(forecast.hourly, lat, lon)
        }

        val meteoResults = forecast.daily.map { daily ->
            WeatherEntity(
                date = daily.date,
                locationLat = lat,
                locationLon = lon,
                locationName = locationName,
                highTemp = daily.highTemp,
                lowTemp = daily.lowTemp,
                currentTemp = if (daily.date == today) forecast.currentTemp else null,
                condition = openMeteoApi.weatherCodeToCondition(daily.weatherCode),
                isActual = LocalDate.parse(daily.date).isBefore(LocalDate.now()),
                source = "Open-Meteo",
                isClimateNormal = false
            )
        }

        // Fill gap with climate normals up to 30 days
        val lastMeteoDate = meteoResults.map { LocalDate.parse(it.date) }.maxOrNull() ?: LocalDate.now()
        val climateGaps = fetchClimateNormalsGap(lat, lon, locationName, lastMeteoDate, 30, "Open-Meteo")

        return meteoResults + climateGaps
    }

    private suspend fun saveHourlyForecasts(
        hourlyForecasts: List<OpenMeteoApi.HourlyForecast>,
        lat: Double,
        lon: Double
    ) {
        val entities = hourlyForecasts.map { hourly ->
            HourlyForecastEntity(
                dateTime = hourly.dateTime,
                locationLat = lat,
                locationLon = lon,
                temperature = hourly.temperature,
                condition = openMeteoApi.weatherCodeToCondition(hourly.weatherCode),
                source = "OPEN_METEO",
                fetchedAt = System.currentTimeMillis()
            )
        }
        hourlyForecastDao.insertAll(entities)
        val sortedTimes = entities.map { it.dateTime }.sorted()
        Log.d(TAG, "saveHourlyForecasts: Saved ${entities.size} Open-Meteo hourly forecasts, range: ${sortedTimes.firstOrNull()} to ${sortedTimes.lastOrNull()}")
    }

    private suspend fun saveNwsHourlyForecasts(
        hourlyForecasts: List<NwsApi.HourlyForecastPeriod>,
        lat: Double,
        lon: Double
    ) {
        val entities = hourlyForecasts.mapNotNull { hourly ->
            // Convert NWS ISO 8601 format "2026-02-01T10:00:00-08:00" to "2026-02-01T10:00"
            val dateTime = try {
                val zonedDateTime = java.time.ZonedDateTime.parse(hourly.startTime)
                zonedDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            } catch (e: Exception) {
                Log.w(TAG, "saveNwsHourlyForecasts: Failed to parse time ${hourly.startTime}: ${e.message}")
                return@mapNotNull null
            }

            HourlyForecastEntity(
                dateTime = dateTime,
                locationLat = lat,
                locationLon = lon,
                temperature = hourly.temperature.toFloat(),
                condition = hourly.shortForecast,
                source = "NWS",
                fetchedAt = System.currentTimeMillis()
            )
        }
        hourlyForecastDao.insertAll(entities)
        val sortedTimes = entities.map { it.dateTime }.sorted()
        Log.d(TAG, "saveNwsHourlyForecasts: Saved ${entities.size} NWS hourly forecasts, range: ${sortedTimes.firstOrNull()} to ${sortedTimes.lastOrNull()}")
    }

    /**
     * Gets the interpolated current temperature based on hourly forecast data.
     * Returns null if no hourly data is available.
     */
    suspend fun getInterpolatedTemperature(
        lat: Double,
        lon: Double,
        currentTime: LocalDateTime = LocalDateTime.now()
    ): Float? {
        // Get hourly forecasts around the current time (3 hours before and after)
        val startTime = currentTime.minusHours(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val endTime = currentTime.plusHours(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))

        val hourlyForecasts = hourlyForecastDao.getHourlyForecasts(startTime, endTime, lat, lon)

        if (hourlyForecasts.isEmpty()) {
            Log.d(TAG, "getInterpolatedTemperature: No hourly forecasts available")
            return null
        }

        val interpolatedTemp = temperatureInterpolator.getInterpolatedTemperature(hourlyForecasts, currentTime)
        Log.d(TAG, "getInterpolatedTemperature: Interpolated temp = $interpolatedTemp at $currentTime")
        return interpolatedTemp
    }

    /**
     * Gets the next time the widget should update based on temperature change rate.
     */
    suspend fun getNextInterpolationUpdateTime(
        lat: Double,
        lon: Double,
        currentTime: LocalDateTime = LocalDateTime.now()
    ): LocalDateTime {
        val currentHour = currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val nextHour = currentTime.plusHours(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))

        val forecasts = hourlyForecastDao.getHourlyForecasts(currentHour, nextHour, lat, lon)

        val tempDiff = if (forecasts.size >= 2) {
            (forecasts[1].temperature - forecasts[0].temperature).toInt()
        } else {
            0
        }

        return temperatureInterpolator.getNextUpdateTime(currentTime, tempDiff)
    }

    private suspend fun cleanOldData() {
        val cutoff = System.currentTimeMillis() - MONTH_IN_MILLIS
        weatherDao.deleteOldData(cutoff)
        forecastSnapshotDao.deleteOldSnapshots(cutoff)
        hourlyForecastDao.deleteOldForecasts(cutoff)
    }

    suspend fun getLatestLocation(): Pair<Double, Double>? {
        val latest = weatherDao.getLatestWeather()
        return latest?.let { it.locationLat to it.locationLon }
    }
}
