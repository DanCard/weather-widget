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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.widget.ApiPreference
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.weatherwidget.widget.WeatherWidgetWorker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.roundToInt

private const val TAG = "WeatherRepository"

@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val weatherDao: WeatherDao,
    private val forecastSnapshotDao: ForecastSnapshotDao,
    private val hourlyForecastDao: HourlyForecastDao,
    private val appLogDao: AppLogDao,
    private val nwsApi: NwsApi,
    private val openMeteoApi: OpenMeteoApi,
    private val widgetStateManager: WidgetStateManager,
    private val apiLogger: ApiLogger,
    private val temperatureInterpolator: TemperatureInterpolator
) {
    internal data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
    }
    companion object {
        private const val MONTH_IN_MILLIS = 30L * 24 * 60 * 60 * 1000
        private const val MIN_NETWORK_INTERVAL_MS = 600_000L // 10 minutes minimum between network attempts
    }

    // Toggle between APIs - alternate fairly between both
    private var useOpenMeteoFirst = false

    // Rate limiting for network fetches to prevent bursts. 
    // Persisted in SharedPreferences to survive process restarts.
    private var lastNetworkFetchTime: Long
        get() = prefs.getLong("last_network_fetch_time", 0L)
        set(value) = prefs.edit().putLong("last_network_fetch_time", value).apply()

    suspend fun getWeatherData(
        lat: Double,
        lon: Double,
        locationName: String,
        forceRefresh: Boolean = false,
        networkAllowed: Boolean = true
    ): Result<List<WeatherEntity>> {
        return try {
            val now = System.currentTimeMillis()
            val cached = getCachedData(lat, lon)
            
            // If not forced, check if cached data is fresh (within 30 mins)
            if (!forceRefresh && cached.isNotEmpty()) {
                val latestFetch = cached.maxByOrNull { it.fetchedAt }?.fetchedAt ?: 0L
                val isFresh = (now - latestFetch) < 30L * 60 * 1000 // 30 minutes
                if (isFresh) {
                    Log.d(TAG, "getWeatherData: Returning fresh cached data (${(now - latestFetch) / 1000}s old)")
                    return Result.success(cached)
                }
            }

            // If network is explicitly disallowed, return cache regardless of staleness
            if (!networkAllowed) {
                Log.d(TAG, "getWeatherData: Network disallowed for this request, returning cache")
                return Result.success(cached)
            }

            // Global rate limit for network fetches (even if forced, unless it's a very long time)
            // This prevents bursts from multiple workers starting at once
            val timeSinceLastFetch = now - lastNetworkFetchTime
            if (timeSinceLastFetch < MIN_NETWORK_INTERVAL_MS && cached.isNotEmpty()) {
                val reason = if (forceRefresh) "forced refresh" else "stale data"
                appLogDao.insert(AppLogEntity(tag = "NET_RATE_LIMIT", message = "Skipping $reason, last fetch ${timeSinceLastFetch/1000}s ago"))
                Log.w(TAG, "getWeatherData: Rate limiting network fetch ($reason). Last fetch was only ${timeSinceLastFetch}ms ago. Returning cache.")
                return Result.success(cached)
            }

            // Set rate limit timestamp to prevent concurrent bursts, but save the
            // previous value so we can restore it if the fetch fails entirely.
            val previousFetchTime = lastNetworkFetchTime
            lastNetworkFetchTime = now
            appLogDao.insert(AppLogEntity(tag = "NET_FETCH_START", message = "Forcing fetch: force=$forceRefresh"))
            val (nwsWeather, meteoWeather) = fetchFromBothApis(lat, lon, locationName)

            // If both APIs returned nothing, reset the rate limit so we can retry sooner
            if (nwsWeather == null && meteoWeather == null) {
                lastNetworkFetchTime = previousFetchTime
                appLogDao.insert(AppLogEntity(tag = "NET_FETCH_FAIL", message = "Both APIs returned null, rate limit reset"))
            }

            // Save both APIs' data, merging with existing to preserve non-zero values
            if (nwsWeather != null) {
                val merged = mergeWithExisting(nwsWeather, lat, lon)
                weatherDao.insertAll(merged)
                appLogDao.insert(AppLogEntity(tag = "NET_FETCH_SUCCESS", message = "NWS: Got ${nwsWeather.size} entries"))
            }
            if (meteoWeather != null) {
                val merged = mergeWithExisting(meteoWeather, lat, lon)
                weatherDao.insertAll(merged)
                appLogDao.insert(AppLogEntity(tag = "NET_FETCH_SUCCESS", message = "Meteo: Got ${meteoWeather.size} entries"))
            }

            // Fetch and save generic gap data once to cover any gaps in either API
            val lastNwsDate = nwsWeather?.map { LocalDate.parse(it.date) }?.maxOrNull()
            val lastMeteoDate = meteoWeather?.map { LocalDate.parse(it.date) }?.maxOrNull()
            val lastDateForGap = listOfNotNull(lastNwsDate, lastMeteoDate).minOrNull() ?: LocalDate.now()

            val gapWeather = fetchClimateNormalsGap(lat, lon, locationName, lastDateForGap, 30, "Generic")
            if (gapWeather.isNotEmpty()) {
                weatherDao.insertAll(gapWeather)
            }

            cleanOldData()
            // Return from database to include previously cached data (e.g., yesterday from Open-Meteo)
            Result.success(getCachedData(lat, lon))
        } catch (e: Exception) {
            // Reset rate limit so failed fetches don't block retries
            lastNetworkFetchTime = 0L
            appLogDao.insert(AppLogEntity(tag = "NET_FETCH_ERROR", message = "Exception: ${e.message}, rate limit reset"))
            Log.e(TAG, "getWeatherData: Fetch failed, rate limit reset", e)
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
        // We also skip climate normals (gap data) as they are static and not part of prediction history.
        val relevantForecasts = weather.filter { 
            val date = try { LocalDate.parse(it.date) } catch (e: Exception) { null }
            date != null && (date.isAfter(today) || date.isEqual(today)) && !it.isClimateNormal
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
                source = source,
                fetchedAt = fetchedAt
            )
        }

        if (snapshots.isNotEmpty()) {
            forecastSnapshotDao.insertAll(snapshots)
            appLogDao.insert(AppLogEntity(
                tag = "SNAPSHOT_SAVE", 
                message = "Saved ${snapshots.size} snapshots for $source. Range: ${snapshots.minOf { it.targetDate }} to ${snapshots.maxOf { it.targetDate }}"
            ))
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
     * Merges new weather data with existing data, preserving historical "Actual" records
     * and non-zero values. This ensures that history is not lost during partial API fetches.
     */
    private suspend fun mergeWithExisting(
        newData: List<WeatherEntity>,
        lat: Double,
        lon: Double
    ): List<WeatherEntity> {
        if (newData.isEmpty()) return emptyList()

        // Grouping by source because merge logic applies per-provider
        return newData.groupBy { it.source }.flatMap { (source, newItems) ->
            val existingData = getCachedDataBySource(lat, lon, source)
            val existingByDate = existingData.associateBy { it.date }
            val newByDate = newItems.associateBy { it.date }

            // START WITH ALL EXISTING DATES to ensure we don't drop anything
            val allDates = (existingByDate.keys + newByDate.keys).distinct()

            allDates.map { date ->
                val existing = existingByDate[date]
                val new = newByDate[date]

                when {
                    // Scenario 1: Only in DB, not in API -> KEEP DB (History preservation)
                    new == null && existing != null -> existing

                    // Scenario 2: Only in API, not in DB -> USE API (New forecast)
                    new != null && existing == null -> new

                    // Scenario 3: Both exist -> MERGE
                    new != null && existing != null -> {
                        // Special Case: If existing is a placeholder "Observed" record, 
                        // always allow overwriting it with the new (better) data.
                        val isPlaceholder = existing.condition == "Observed" || existing.condition == "Unknown"
                        
                        // AUDIT: Check if we are replacing Actual (History) with Forecast
                        if (existing.isActual && !new.isActual && !isPlaceholder) {
                            val existingTime = java.time.Instant.ofEpochMilli(existing.fetchedAt)
                                .atZone(java.time.ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                                
                            appLogDao.insert(AppLogEntity(
                                tag = "MERGE_CONFLICT",
                                message = "[$source] $date: Preserving ACTUAL record from $existingTime over new FORECAST"
                            ))
                            // Prioritize keeping the existing ACTUAL record if the new one is just a forecast
                            existing.copy(
                                highTemp = new.highTemp ?: existing.highTemp,
                                lowTemp = new.lowTemp ?: existing.lowTemp
                            )
                        } else {
                            // Merge nullable temperatures: use existing values where new data has nulls
                            new.copy(
                                highTemp = if (new.highTemp == null) existing.highTemp else new.highTemp,
                                lowTemp = if (new.lowTemp == null) existing.lowTemp else new.lowTemp
                            )
                        }
                    }
                    else -> throw IllegalStateException("Should not happen")
                }
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

    internal suspend fun fetchFromNws(
        lat: Double,
        lon: Double,
        locationName: String
    ): List<WeatherEntity> = coroutineScope {
        val gridPoint = nwsApi.getGridPoint(lat, lon)
        
        // Start forecast and hourly fetches in parallel
        val forecastDeferred = async { nwsApi.getForecast(gridPoint) }
        val hourlyDeferred = async {
            try {
                Log.d(TAG, "fetchFromNws: Fetching hourly forecasts from NWS...")
                val result = nwsApi.getHourlyForecast(gridPoint)
                Log.d(TAG, "fetchFromNws: Got ${result.size} NWS hourly periods")
                result
            } catch (e: Exception) {
                Log.e(TAG, "fetchFromNws: Failed to fetch hourly forecasts: ${e.message}")
                emptyList()
            }
        }

        val forecast = forecastDeferred.await()
        val hourlyForecast = hourlyDeferred.await()

        if (hourlyForecast.isNotEmpty()) {
            saveNwsHourlyForecasts(hourlyForecast, lat, lon)
        }

        val weatherByDate = mutableMapOf<String, Pair<Int?, Int?>>()
        val conditionByDate = mutableMapOf<String, String>()
        val stationByDate = mutableMapOf<String, String>()  // Track which station provided data
        val today = LocalDate.now()

        // Fetch last 7 days of actual observations if observation stations are available
        // Include today (daysAgo=0) to get today's actual high/low when it's evening
        try {
            if (gridPoint.observationStationsUrl != null) {
                val stationsUrl = gridPoint.observationStationsUrl!!
                
                // Fetch all 8 days in parallel
                val observationDeferreds = (0..7).map { daysAgo ->
                    val date = today.minusDays(daysAgo.toLong())
                    async {
                        val result = fetchDayObservations(stationsUrl, date)
                        date to result
                    }
                }

                observationDeferreds.forEach { deferred ->
                    val (date, observationData) = deferred.await()
                    val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    
                    if (observationData != null) {
                        weatherByDate[dateStr] = observationData.first to observationData.second
                        stationByDate[dateStr] = observationData.third  // Track station ID
                        conditionByDate[dateStr] = observationData.fourth // Use calculated condition
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
                // Only use forecast condition if we don't already have an observation
                if (conditionByDate[date] == null) {
                    conditionByDate[date] = period.shortForecast
                }
            } else {
                weatherByDate[date] = current.first to period.temperature
                // Ensure partial days have a condition (use night if day is missing/no observation)
                if (conditionByDate[date] == null) {
                    conditionByDate[date] = period.shortForecast
                }
            }
        }

        Log.d(TAG, "fetchFromNws: Parsed ${weatherByDate.size} days")

        weatherByDate.map { (date, temps) ->
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

    internal suspend fun fetchDayObservations(
        stationsUrl: String,
        date: LocalDate
    ): Quad<Int, Int, String, String>? {  // CHANGED: Now returns (high, low, stationId, condition)
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

                    val startTimeMs = System.currentTimeMillis()
                    val observations = nwsApi.getObservations(stationId, startTime, endTime)
                    val durationMs = System.currentTimeMillis() - startTimeMs
                    
                    if (observations.isEmpty()) {
                        apiLogger.logApiCall("NWS-Obs", false, "No observations", stationId, durationMs)
                        Log.w(TAG, "fetchDayObservations: No observations from $stationId for $date - trying next")
                        continue  // Try next station
                    }

                    apiLogger.logApiCall("NWS-Obs", true, null, stationId, durationMs)
                    Log.i(TAG, "fetchDayObservations: SUCCESS - Got ${observations.size} observations from $stationId for $date")

                    // Calculate high/low from observations (convert C to F) using Float math for precision
                    val temps: List<Int> = observations.map { obs: NwsApi.Observation -> 
                        (obs.temperatureCelsius * 1.8f + 32f).roundToInt() 
                    }
                    val high = temps.maxOrNull() ?: continue
                    val low = temps.minOrNull() ?: continue

                    // --- WEIGHTED CLOUD COVERAGE LOGIC ---
                    // Prioritize daylight hours (7 AM to 7 PM)
                    val daylightObservations = observations.filter { obs ->
                        try {
                            val dt = java.time.ZonedDateTime.parse(obs.timestamp)
                                .withZoneSameInstant(localZone)
                            dt.hour in 7..19
                        } catch (e: Exception) { true }
                    }.ifEmpty { observations }

                    val cloudScores = daylightObservations.map { obs ->
                        val desc = obs.textDescription.lowercase()
                        when {
                            desc.contains("mostly cloudy") -> 75
                            desc.contains("mostly clear") || desc.contains("mostly sunny") -> 25
                            desc.contains("partly") -> 50
                            desc.contains("cloudy") || desc.contains("overcast") -> 100
                            desc.contains("clear") || desc.contains("sunny") || desc.contains("fair") -> 0
                            else -> 50 // Default to middle for unknown
                        }
                    }

                    val averageCloudScore = if (cloudScores.isNotEmpty()) cloudScores.average() else 50.0
                    val finalCondition = when {
                        averageCloudScore <= 15 -> "Sunny"
                        averageCloudScore <= 35 -> "Mostly Sunny (25%)"
                        averageCloudScore <= 65 -> "Partly Cloudy (50%)"
                        averageCloudScore <= 85 -> "Mostly Cloudy (75%)"
                        else -> "Cloudy"
                    }

                    Log.i(TAG, "fetchDayObservations: Station $stationId provided data for $date (H:$high L:$low) Score: $averageCloudScore -> $finalCondition")

                    return Quad(high, low, stationId, finalCondition)

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

        return forecast.daily.map { daily ->
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
        val now = System.currentTimeMillis()
        val cutoff = now - MONTH_IN_MILLIS
        
        // Log cleanup stats
        try {
            // Forensic logging during cleanup
            val historyStart = LocalDate.now().minusDays(60).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val historyEnd = LocalDate.now().minusDays(31).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val latestWeather = weatherDao.getLatestWeather()
            val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
            val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
            
            // Count records before deletion
            val oldWeather = weatherDao.getWeatherRange(historyStart, historyEnd, lat, lon).size
            
            // Perform deletion
            weatherDao.deleteOldData(cutoff)
            forecastSnapshotDao.deleteOldSnapshots(cutoff)
            hourlyForecastDao.deleteOldForecasts(cutoff)
            
            // Maintain logs for 3 days (72 hours) to optimize space while keeping recent forensics
            val logCutoff = now - (3L * 24 * 60 * 60 * 1000)
            appLogDao.deleteOldLogs(logCutoff)

            if (oldWeather > 0) {
                val cutoffDate = Instant.ofEpochMilli(cutoff).atZone(ZoneId.systemDefault()).toLocalDate()
                appLogDao.insert(AppLogEntity(
                    tag = "DB_CLEANUP",
                    message = "Cleaned records older than $cutoffDate ($cutoff). Removed approx $oldWeather weather entries.",
                    level = "INFO"
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "cleanOldData: Failed to audit cleanup: ${e.message}")
            appLogDao.insert(AppLogEntity(
                tag = "DB_CLEANUP_ERROR",
                message = "Cleanup failed: ${e.message}",
                level = "ERROR"
            ))
        }
    }

    suspend fun getLatestLocation(): Pair<Double, Double>? {
        val latest = weatherDao.getLatestWeather()
        return latest?.let { it.locationLat to it.locationLon }
    }
}
