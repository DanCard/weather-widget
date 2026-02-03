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
        private const val SNAPSHOT_CUTOFF_HOUR = 20  // 8pm - don't save snapshots after this hour
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

    private suspend fun shouldSaveSnapshot(
        targetDate: String,
        forecastDate: String,
        lat: Double,
        lon: Double,
        source: String
    ): Boolean {
        val now = LocalTime.now()

        // Before cutoff time, always save
        if (now.hour < SNAPSHOT_CUTOFF_HOUR) {
            return true
        }

        // After cutoff time, only save if no snapshot exists yet for this target date + source
        val existingSnapshot = forecastSnapshotDao.getForecastForDateBySource(
            targetDate, forecastDate, lat, lon, source
        )

        if (existingSnapshot == null) {
            Log.d(TAG, "Saving snapshot after ${SNAPSHOT_CUTOFF_HOUR}:00 (no data saved yet for $source)")
            return true
        }

        Log.d(TAG, "Skipping snapshot save (after ${SNAPSHOT_CUTOFF_HOUR}:00 and data already exists)")
        return false
    }

    private suspend fun saveForecastSnapshot(
        weather: List<WeatherEntity>,
        lat: Double,
        lon: Double,
        source: String
    ) {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val tomorrowStr = today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Find tomorrow's forecast in the new weather data
        val tomorrowForecast = weather.find { it.date == tomorrowStr }

        if (tomorrowForecast != null) {
            if (!shouldSaveSnapshot(tomorrowStr, todayStr, lat, lon, source)) {
                return
            }

            // Save as 1-day-ahead forecast (forecasted today for tomorrow)
            val snapshot = ForecastSnapshotEntity(
                targetDate = tomorrowStr,
                forecastDate = todayStr,
                locationLat = lat,
                locationLon = lon,
                highTemp = tomorrowForecast.highTemp,
                lowTemp = tomorrowForecast.lowTemp,
                condition = tomorrowForecast.condition,
                source = source,
                fetchedAt = System.currentTimeMillis()
            )
            forecastSnapshotDao.insertSnapshot(snapshot)
            Log.d(TAG, "Saved forecast snapshot ($source): $todayStr forecast for $tomorrowStr - H:${snapshot.highTemp} L:${snapshot.lowTemp}")
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
        // Get all snapshots for this date and filter by source
        val snapshots = forecastSnapshotDao.getForecastsInRange(targetDate, targetDate, lat, lon)
        return snapshots.find { it.source == source }
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
        return weatherDao.getWeatherRangeBySource(sevenDaysAgo, thirtyDays, lat, lon, source)
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

        val source = newData.first().source
        val existingData = getCachedDataBySource(lat, lon, source)
        val existingByDate = existingData.associateBy { it.date }

        return newData.map { new ->
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

        // Save snapshots from both APIs (if available and before cutoff time)
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
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    private suspend fun fetchClimateNormalsGap(
        lat: Double,
        lon: Double,
        locationName: String,
        lastDate: LocalDate,
        targetDays: Int,
        source: String
    ): List<WeatherEntity> {
        if (!isDevicePluggedIn()) {
            Log.d(TAG, "fetchClimateNormalsGap: Skipping climate fetch (not plugged in)")
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
                    source = source
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
        // e.g., "Monday" at 6am = Monday's high, "Monday Night" at 6pm = Monday's low
        // Simply use the start date from each period
        forecast.forEachIndexed { index, period ->
            // Parse the date from the startTime (format: "2026-02-02T06:00:00-08:00")
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
