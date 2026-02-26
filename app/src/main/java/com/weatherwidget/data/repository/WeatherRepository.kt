/**
 * Central weather data repository — coordinates fetching, caching, merging, and
 * serving weather data from three APIs (NWS, Open-Meteo, WeatherAPI).
 *
 * ## Responsibilities
 *
 * **Data access** — [getWeatherData] is the main entry point. It returns cached data
 * when fresh (<30 min), otherwise fetches from all APIs concurrently. Source-filtered
 * access is available via [getCachedDataBySource]. Forecast snapshots (for accuracy
 * tracking) are exposed through [getForecastForDate], [getForecastForDateBySource],
 * and [getForecastsInRange].
 *
 * **API orchestration** — [fetchFromAllApis] launches parallel fetches to NWS,
 * Open-Meteo, and WeatherAPI. Hidden sources are only fetched when charging (to
 * preserve accuracy data without wasting battery). A global rate limiter
 * ([MIN_NETWORK_INTERVAL_MS]) and a [Mutex] prevent burst fetches from multiple
 * workers.
 *
 * **NWS pipeline** — [fetchFromNws] is the most complex fetch path because NWS
 * provides separate forecast periods, hourly data, and historical observations
 * from nearby weather stations. It delegates to four helpers:
 * - [initPrecipFromHourly] — builds per-day precipitation probability from hourly data
 * - [fetchAndApplyObservations] — parallel station observation fetches for 8 days of history
 * - [applyForecastPeriods] — maps NWS day/night periods into weather/condition maps
 * - [logTodayDiagnostics] — condition override logic and transition change detection
 *
 * **Merge & persistence** — [mergeWithExisting] ensures new API data doesn't
 * overwrite historical actuals or drop dates missing from the latest fetch.
 * [saveForecastSnapshot] archives today's forecasts for later accuracy comparison.
 * Three API-specific hourly mappers funnel into [saveHourlyEntities] for the
 * shared insert-and-log tail.
 *
 * **Gap fill** — [fetchClimateNormalsGap] fills dates beyond API forecast horizons
 * with 10-year historical averages (2011–2020) from Open-Meteo's archive, stored
 * as [WeatherSource.GENERIC_GAP] entries.
 *
 * **Temperature interpolation** — [getInterpolatedTemperature] provides smooth
 * between-hour current temp estimates from cached hourly data (no network needed).
 * [getNextInterpolationUpdateTime] determines widget refresh cadence based on
 * temperature change rate.
 *
 * **Cleanup** — [cleanOldData] prunes weather, snapshot, hourly, and log records
 * older than 30 days (logs: 3 days).
 */
package com.weatherwidget.data.repository

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.ClimateNormalEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDao
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val TAG = "WeatherRepository"

@Singleton
class WeatherRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val weatherDao: WeatherDao,
        private val forecastSnapshotDao: ForecastSnapshotDao,
        private val hourlyForecastDao: HourlyForecastDao,
        private val appLogDao: AppLogDao,
        private val nwsApi: NwsApi,
        private val openMeteoApi: OpenMeteoApi,
        private val weatherApi: WeatherApi,
        private val widgetStateManager: WidgetStateManager,
        private val temperatureInterpolator: TemperatureInterpolator,
        private val climateNormalDao: ClimateNormalDao,
    ) {
        internal data class ObservationResult(
            val highTemp: Float,
            val lowTemp: Float,
            val stationId: String,
            val condition: String,
        )

        private val syncMutex = Mutex()

        private val prefs: SharedPreferences by lazy {
            context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
        }

        companion object {
            private const val MONTH_IN_MILLIS = 30L * 24 * 60 * 60 * 1000
            private const val FORECAST_FRESHNESS_MS = 2 * 60 * 60 * 1000L // 2 hours
            private const val CURRENT_TEMP_FRESHNESS_MS = 10 * 60 * 1000L // 10 minutes
            private const val MIN_NETWORK_INTERVAL_MS = 600_000L // 10 minutes minimum between network attempts
            private const val MAX_OBSERVATION_STATION_RETRIES = 5
            private const val OBSERVATION_STATIONS_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
            private const val DAYS_OF_HISTORY = 8
            private const val NWS_PERIOD_SUMMARY_COUNT = 8
        }

        // Rate limiting for network fetches to prevent bursts.
        // Persisted in SharedPreferences to survive process restarts.
        private var lastFullFetchTime: Long
            get() = prefs.getLong("last_full_fetch_time", 0L)
            set(value) = prefs.edit().putLong("last_full_fetch_time", value).apply()

        private var lastCurrentTempFetchTime: Long
            get() = prefs.getLong("last_current_temp_fetch_time", 0L)
            set(value) = prefs.edit().putLong("last_current_temp_fetch_time", value).apply()

        /** Expose rate limiter state for diagnostics (e.g., SYNC_START logging). */
        val lastNetworkFetchTimeMs: Long get() = lastFullFetchTime

        /**
         * Main entry point for weather data. Returns cached data if fresh (<30 min),
         * otherwise fetches from all APIs behind a rate limiter and mutex.
         *
         * @param forceRefresh bypass freshness check (still rate-limited)
         * @param networkAllowed if false, always returns cache regardless of staleness
         */
        suspend fun getWeatherData(
            lat: Double,
            lon: Double,
            locationName: String,
            forceRefresh: Boolean = false,
            networkAllowed: Boolean = true,
        ): Result<List<WeatherEntity>> {
            return try {
                val now = System.currentTimeMillis()
                val cached = getCachedData(lat, lon)

                // If not forced, check if cached data is fresh (within 2 hours)
                if (!forceRefresh && cached.isNotEmpty()) {
                    val latestFetch = cached.maxByOrNull { it.fetchedAt }?.fetchedAt ?: 0L
                    if ((now - latestFetch) < FORECAST_FRESHNESS_MS) {
                        Log.d(TAG, "getWeatherData: Returning fresh cached data (${(now - latestFetch) / 1000}s old)")
                        return Result.success(cached)
                    }
                }

                // If network is explicitly disallowed, return cache regardless of staleness
                if (!networkAllowed) {
                    Log.d(TAG, "getWeatherData: Network disallowed for this request, returning cache")
                    return Result.success(cached)
                }

                // Serialize network fetches to prevent parallel redundant bursts
                syncMutex.withLock {
                    // Re-check cache after acquiring lock - another thread might have just finished the fetch
                    val freshCached = getCachedData(lat, lon)
                    if (!forceRefresh && freshCached.isNotEmpty()) {
                        val latestFetch = freshCached.maxByOrNull { it.fetchedAt }?.fetchedAt ?: 0L
                        if ((System.currentTimeMillis() - latestFetch) < FORECAST_FRESHNESS_MS) {
                            Log.d(TAG, "getWeatherData: Found fresh data after acquiring lock, skipping fetch")
                            return Result.success(freshCached)
                        }
                    }

                    // Global rate limit for network fetches (even if forced, unless it's a very long time)
                    // This prevents bursts from multiple workers starting at once
                    val timeSinceLastFetch = System.currentTimeMillis() - lastFullFetchTime
                    if (timeSinceLastFetch < MIN_NETWORK_INTERVAL_MS && freshCached.isNotEmpty()) {
                        val reason = if (forceRefresh) "forced refresh" else "stale data"
                        appLogDao.log("NET_RATE_LIMIT", "Skipping $reason, last fetch ${timeSinceLastFetch / 1000}s ago, lastFullFetchTime=$lastFullFetchTime", "INFO")
                        return Result.success(freshCached)
                    }

                    appLogDao.log("NET_FETCH_START", "Forcing fetch: force=$forceRefresh")
                    val fetchStart = System.currentTimeMillis()
                    val (nwsWeather, meteoWeather, weatherApiWeather) = fetchFromAllApis(lat, lon, locationName)
                    val fetchDuration = System.currentTimeMillis() - fetchStart
                    appLogDao.log(
                        "NET_FETCH_DONE",
                        "APIs returned in ${fetchDuration}ms. NWS=${nwsWeather?.size ?: "null"}, Meteo=${meteoWeather?.size ?: "null"}, WAPI=${weatherApiWeather?.size ?: "null"}",
                    )

                    if (nwsWeather == null && meteoWeather == null && weatherApiWeather == null) {
                        appLogDao.log("NET_FETCH_FAIL", "All APIs returned null", "WARN")
                    }

                    // Save all APIs' data, merging with existing to preserve non-zero values
                    if (nwsWeather != null) {
                        val merged = mergeWithExisting(nwsWeather, lat, lon)
                        weatherDao.insertAll(merged)
                        appLogDao.log("NET_FETCH_SUCCESS", "NWS: Saved ${merged.size} entries (raw=${nwsWeather.size})", "INFO")
                    }
                    if (meteoWeather != null) {
                        val merged = mergeWithExisting(meteoWeather, lat, lon)
                        weatherDao.insertAll(merged)
                        appLogDao.log("NET_FETCH_SUCCESS", "Meteo: Saved ${merged.size} entries (raw=${meteoWeather.size})", "INFO")
                    }
                    if (weatherApiWeather != null) {
                        val merged = mergeWithExisting(weatherApiWeather, lat, lon)
                        weatherDao.insertAll(merged)
                        appLogDao.log("NET_FETCH_SUCCESS", "WeatherAPI: Saved ${merged.size} entries (raw=${weatherApiWeather.size})", "INFO")
                    }

                    // Fetch and save generic gap data once to cover any gaps in any API.
                    // If any provider is missing entirely, start gap fill at today so source-specific
                    // views (especially short-horizon WeatherAPI) still have fallback coverage.
                    val lastNwsDate = nwsWeather?.map { LocalDate.parse(it.date) }?.maxOrNull()
                    val lastMeteoDate = meteoWeather?.map { LocalDate.parse(it.date) }?.maxOrNull()
                    val lastWeatherApiDate = weatherApiWeather?.map { LocalDate.parse(it.date) }?.maxOrNull()
                    val missingAnySource =
                        nwsWeather.isNullOrEmpty() ||
                            meteoWeather.isNullOrEmpty() ||
                            weatherApiWeather.isNullOrEmpty()
                    val fallbackGapStartAnchor = if (missingAnySource) LocalDate.now().minusDays(1) else null
                    val lastDateForGap =
                        listOfNotNull(lastNwsDate, lastMeteoDate, lastWeatherApiDate, fallbackGapStartAnchor)
                            .minOrNull() ?: LocalDate.now()

                    val gapStart = System.currentTimeMillis()
                    val gapWeather = fetchClimateNormalsGap(lat, lon, locationName, lastDateForGap, 30)
                    if (gapWeather.isNotEmpty()) {
                        weatherDao.insertAll(gapWeather)
                    }
                    val gapDuration = System.currentTimeMillis() - gapStart

                    cleanOldData()
                    val totalEntries = getCachedData(lat, lon)
                    val totalDuration = System.currentTimeMillis() - fetchStart
                    // Set rate limit only after successful fetch — prevents stale timestamps
                    // from persisting if the process dies mid-fetch (e.g., reinstall, force-stop).
                    // syncMutex handles in-process burst prevention.
                    lastFullFetchTime = System.currentTimeMillis()
                    appLogDao.log("NET_FETCH_COMPLETE", "Total ${totalDuration}ms. gap=${gapDuration}ms/${gapWeather.size} entries. DB now has ${totalEntries.size} entries")
                    // Return from database to include previously cached data (e.g., yesterday from Open-Meteo)
                    Result.success(totalEntries)
                }
            } catch (e: Exception) {
                lastFullFetchTime = 0L
                val stackSummary = e.stackTrace.take(3).joinToString(" <- ") { "${it.fileName}:${it.lineNumber}" }
                appLogDao.log("NET_FETCH_ERROR", "${e.javaClass.simpleName}: ${e.message} [$stackSummary], rate limit reset", "ERROR")
                val cached = getCachedData(lat, lon)
                if (cached.isNotEmpty()) {
                    Result.success(cached)
                } else {
                    Result.failure(e)
                }
            }
        }

        /**
         * Archives today-and-future forecasts as snapshots for later accuracy comparison.
         * Skips historical observations and climate normals. Only saves entries where
         * at least one of highTemp/lowTemp is non-null.
         *
         * Implements value-change deduplication: only inserts a new snapshot if the
         * forecasted values (high, low, or condition) differ from the most recent
         * existing snapshot for that target date and source.
         */
        private suspend fun saveForecastSnapshot(
            weather: List<WeatherEntity>,
            lat: Double,
            lon: Double,
            source: String,
        ) {
            val now = LocalDateTime.now()
            val today = now.toLocalDate()
            val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val fetchedAt = System.currentTimeMillis()

            // Include only today and future dates: NWS returns 7 days of historical observations
            // which we don't want stored as "forecast" snapshots. Also skip climate normals.
            val relevantForecasts =
                weather.filter {
                    val date =
                        try {
                            LocalDate.parse(it.date)
                        } catch (e: Exception) {
                            null
                        }
                    date != null && (date.isAfter(today) || date.isEqual(today)) && !it.isClimateNormal
                }

            if (relevantForecasts.isEmpty()) return

            val weatherSource = WeatherSource.fromId(source)

            // Fetch latest existing snapshots for deduplication
            val startDate = relevantForecasts.minOf { it.date }
            val endDate = relevantForecasts.maxOf { it.date }
            val existingSnapshots =
                forecastSnapshotDao.getForecastsInRange(startDate, endDate, lat, lon)
                    .filter { it.source == source }
                    .groupBy { it.targetDate }
                    .mapValues { (_, forecasts) -> forecasts.maxByOrNull { it.fetchedAt } }

            val snapshots =
                relevantForecasts.mapNotNull { forecast ->
                    // Save even if partial, but not if both are null
                    if (forecast.highTemp == null && forecast.lowTemp == null) return@mapNotNull null

                    val latestExisting = existingSnapshots[forecast.date]

                    // For Open-Meteo, round to integer to avoid redundant snapshots on minor decimal changes
                    val currentHigh = if (source == WeatherSource.OPEN_METEO.id) forecast.highTemp?.roundToInt()?.toFloat() else forecast.highTemp
                    val currentLow = if (source == WeatherSource.OPEN_METEO.id) forecast.lowTemp?.roundToInt()?.toFloat() else forecast.lowTemp

                    val isChanged =
                        latestExisting == null ||
                            latestExisting.highTemp != currentHigh ||
                            latestExisting.lowTemp != currentLow ||
                            latestExisting.condition != forecast.condition

                    if (!isChanged) return@mapNotNull null

                    ForecastSnapshotEntity(
                        targetDate = forecast.date,
                        forecastDate = todayStr,
                        locationLat = lat,
                        locationLon = lon,
                        highTemp = currentHigh,
                        lowTemp = currentLow,
                        condition = forecast.condition,
                        source = weatherSource.id,
                        fetchedAt = fetchedAt,
                    )
                }

            if (snapshots.isNotEmpty()) {
                forecastSnapshotDao.insertAll(snapshots)
                appLogDao.log(
                    "SNAPSHOT_SAVE",
                    "Saved ${snapshots.size} unique snapshots for $source (from ${relevantForecasts.size} total). Range: ${snapshots.minOf { it.targetDate }} to ${snapshots.maxOf { it.targetDate }}",
                    "INFO",
                )
            } else if (relevantForecasts.isNotEmpty()) {
                appLogDao.log("SNAPSHOT_SKIP", "All ${relevantForecasts.size} forecasts for $source were identical to latest snapshots; skipping.")
            }
        }

        suspend fun getForecastForDate(
            targetDate: String,
            lat: Double,
            lon: Double,
        ): ForecastSnapshotEntity? {
            return forecastSnapshotDao.getForecastForDate(targetDate, lat, lon)
        }

        suspend fun getForecastForDateBySource(
            targetDate: String,
            lat: Double,
            lon: Double,
            source: WeatherSource,
        ): ForecastSnapshotEntity? {
            // Get all snapshots for this date and filter by source, falling back to GENERIC_GAP
            val snapshots = forecastSnapshotDao.getForecastsInRange(targetDate, targetDate, lat, lon)
            return snapshots.find { it.source == source.id } ?: snapshots.find { it.source == WeatherSource.GENERIC_GAP.id }
        }

        suspend fun getForecastsInRange(
            startDate: String,
            endDate: String,
            lat: Double,
            lon: Double,
        ): List<ForecastSnapshotEntity> {
            return forecastSnapshotDao.getForecastsInRange(startDate, endDate, lat, lon)
        }

        suspend fun getWeatherRange(
            startDate: String,
            endDate: String,
            lat: Double,
            lon: Double,
        ): List<WeatherEntity> {
            return weatherDao.getWeatherRange(startDate, endDate, lat, lon)
        }

        private suspend fun getCachedData(
            lat: Double,
            lon: Double,
        ): List<WeatherEntity> {
            val sevenDaysAgo = LocalDate.now().minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val thirtyDays = LocalDate.now().plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
            return weatherDao.getWeatherRange(sevenDaysAgo, thirtyDays, lat, lon)
        }

        suspend fun getCachedDataBySource(
            lat: Double,
            lon: Double,
            source: WeatherSource,
        ): List<WeatherEntity> {
            val sevenDaysAgo = LocalDate.now().minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val thirtyDays = LocalDate.now().plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)

            val sourceData = weatherDao.getWeatherRangeBySource(sevenDaysAgo, thirtyDays, lat, lon, source.id)
            val gapData = weatherDao.getWeatherRangeBySource(sevenDaysAgo, thirtyDays, lat, lon, WeatherSource.GENERIC_GAP.id)

            // Merge: prefer source data, fill gaps with generic gap data
            val mergedByDate = (gapData + sourceData).associateBy { it.date }
            return mergedByDate.values.sortedBy { it.date }
        }

        /**
         * Merges new API data with existing DB records per-source. Preserves historical
         * "Actual" records over incoming forecasts, and fills null temps from existing
         * data so partial fetches don't erase previously-known values.
         */
        private suspend fun mergeWithExisting(
            newData: List<WeatherEntity>,
            lat: Double,
            lon: Double,
        ): List<WeatherEntity> {
            if (newData.isEmpty()) return emptyList()

            // Grouping by source because merge logic applies per-provider
            return newData.groupBy { it.source }.flatMap { (sourceId, newItems) ->
                val weatherSource = WeatherSource.fromId(sourceId)
                val existingData = getCachedDataBySource(lat, lon, weatherSource)
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
                                val existingTime =
                                    Instant.ofEpochMilli(existing.fetchedAt)
                                        .atZone(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

                                appLogDao.log("MERGE_CONFLICT", "[$sourceId] $date: Preserving ACTUAL record from $existingTime over new FORECAST", "WARN")
                                // Prioritize keeping the existing ACTUAL record if the new one is just a forecast
                                existing.copy(
                                    highTemp = new.highTemp ?: existing.highTemp,
                                    lowTemp = new.lowTemp ?: existing.lowTemp,
                                )
                            } else {
                                // Merge nullable temperatures: use existing values where new data has nulls
                                new.copy(
                                    highTemp = if (new.highTemp == null) existing.highTemp else new.highTemp,
                                    lowTemp = if (new.lowTemp == null) existing.lowTemp else new.lowTemp,
                                )
                            }
                        }
                        else -> throw IllegalStateException("Should not happen")
                    }
                }
            }
        }

        /**
         * Determines whether a given source should be fetched right now.
         * Visible sources always fetch. Hidden sources only fetch when charging
         * (to preserve accuracy tracking data without wasting battery).
         */
        private fun shouldFetchSource(source: WeatherSource): Boolean {
            if (widgetStateManager.isSourceVisible(source)) return true
            val charging = isDevicePluggedIn()
            if (!charging) {
                Log.d(TAG, "HIDDEN_FETCH_SKIP: ${source.id} is hidden and device is not charging")
            }
            return charging
        }

        /** Fetches weather from all APIs concurrently and saves forecast snapshots. */
        private suspend fun fetchFromAllApis(
            lat: Double,
            lon: Double,
            locationName: String,
        ): Triple<List<WeatherEntity>?, List<WeatherEntity>?, List<WeatherEntity>?> =
            coroutineScope {
                val fetchNws = shouldFetchSource(WeatherSource.NWS)
                val fetchMeteo = shouldFetchSource(WeatherSource.OPEN_METEO)
                val fetchWapi = shouldFetchSource(WeatherSource.WEATHER_API)

                val nwsDeferred = if (fetchNws) async {
                    try {
                        val startTime = System.currentTimeMillis()
                        val result = fetchFromNws(lat, lon, locationName)
                        val duration = System.currentTimeMillis() - startTime
                        appLogDao.log("API_CALL", "NWS success durationMs=$duration location=$locationName")
                        Log.d(TAG, "fetchFromAllApis: NWS succeeded")
                        result
                    } catch (e: Exception) {
                        Log.d(TAG, "fetchFromAllApis: NWS failed: ${e.message}")
                        appLogDao.log("API_CALL", "NWS failure location=$locationName error=${e.message ?: "Unknown error"}", "WARN")
                        null
                    }
                } else null

                val meteoDeferred = if (fetchMeteo) async {
                    try {
                        val startTime = System.currentTimeMillis()
                        val result = fetchFromOpenMeteo(lat, lon, locationName, days = 7)
                        val duration = System.currentTimeMillis() - startTime
                        appLogDao.log("API_CALL", "Open-Meteo success durationMs=$duration location=$locationName")
                        Log.d(TAG, "fetchFromAllApis: Open-Meteo succeeded")
                        result
                    } catch (e: Exception) {
                        Log.d(TAG, "fetchFromAllApis: Open-Meteo failed: ${e.message}")
                        appLogDao.log("API_CALL", "Open-Meteo failure location=$locationName error=${e.message ?: "Unknown error"}", "WARN")
                        null
                    }
                } else null

                val weatherApiDeferred = if (fetchWapi) async {
                    try {
                        val startTime = System.currentTimeMillis()
                        val result = fetchFromWeatherApi(lat, lon, locationName, days = 14)
                        val duration = System.currentTimeMillis() - startTime
                        appLogDao.log("API_CALL", "WeatherAPI success durationMs=$duration location=$locationName")
                        Log.d(TAG, "fetchFromAllApis: WeatherAPI succeeded")
                        result
                    } catch (e: Exception) {
                        Log.d(TAG, "fetchFromAllApis: WeatherAPI failed: ${e.message}")
                        appLogDao.log("API_CALL", "WeatherAPI failure location=$locationName error=${e.message ?: "Unknown error"}", "WARN")
                        null
                    }
                } else null

                val nwsResult = nwsDeferred?.await()
                val meteoResult = meteoDeferred?.await()
                val weatherApiResult = weatherApiDeferred?.await()

                // Save snapshots from all fetched APIs
                try {
                    nwsResult?.let { saveForecastSnapshot(it, lat, lon, "NWS") }
                    meteoResult?.let { saveForecastSnapshot(it, lat, lon, "OPEN_METEO") }
                    weatherApiResult?.let { saveForecastSnapshot(it, lat, lon, "WEATHER_API") }
                } catch (e: Exception) {
                    Log.e(TAG, "fetchFromAllApis: saveForecastSnapshot failed: ${e.message}", e)
                }

                // If all attempted APIs failed (or none were attempted), throw
                if (nwsResult == null && meteoResult == null && weatherApiResult == null) {
                    if (!fetchNws && !fetchMeteo && !fetchWapi) {
                        Log.e(TAG, "fetchFromAllApis: All sources hidden and not charging")
                        throw Exception("All sources hidden and device not charging")
                    }
                    Log.e(TAG, "fetchFromAllApis: All APIs failed")
                    throw Exception("All APIs failed")
                }

                Triple(nwsResult, meteoResult, weatherApiResult)
            }

        private fun isDevicePluggedIn(): Boolean {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugType = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

            val isPlugged =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL ||
                    plugType > 0

            Log.d(TAG, "isDevicePluggedIn: status=$status, plugType=$plugType -> isPlugged=$isPlugged")
            return isPlugged
        }

        /**
         * Fills dates beyond API forecast horizons with 30-year historical averages
         * (1991–2020) from Open-Meteo's archive, stored as [WeatherSource.GENERIC_GAP].
         */
        private suspend fun fetchClimateNormalsGap(
            lat: Double,
            lon: Double,
            locationName: String,
            lastDate: LocalDate,
            targetDays: Int,
        ): List<WeatherEntity> {
            val today = LocalDate.now()
            val targetDate = today.plusDays(targetDays.toLong())
            val startDate = lastDate.plusDays(1)

            if (!startDate.isBefore(targetDate) && !startDate.isEqual(targetDate)) {
                return emptyList()
            }

            return try {
                val startDateStr = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val endDateStr = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                Log.d(TAG, "fetchClimateNormalsGap: Fetching climate normals from $startDateStr to $endDateStr")

                val normalsByMonthDay = getHistoricalNormalsByMonthDay(lat, lon)
                if (normalsByMonthDay.isEmpty()) {
                    return emptyList()
                }

                val results = mutableListOf<WeatherEntity>()
                var cursor = startDate
                while (!cursor.isAfter(targetDate)) {
                    val monthDay = MonthDay.from(cursor)
                    val normal = normalsByMonthDay[monthDay]
                    if (normal != null) {
                        results.add(
                            WeatherEntity(
                                date = cursor.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                locationLat = lat,
                                locationLon = lon,
                                locationName = locationName,
                                highTemp = normal.first.toFloat(),
                                lowTemp = normal.second.toFloat(),
                                currentTemp = null,
                                condition = "Historical Avg",
                                isActual = false,
                                isClimateNormal = true,
                                source = WeatherSource.GENERIC_GAP.id,
                            ),
                        )
                    }
                    cursor = cursor.plusDays(1)
                }

                results
            } catch (e: Exception) {
                Log.e(TAG, "fetchClimateNormalsGap: Failed to fetch climate normals: ${e.message}")
                emptyList()
            }
        }

        /**
         * Returns climate-model average high/low by MonthDay via Open-Meteo's
         * Climate API (pre-aggregated server-side, ~366 rows).
         * Persisted to Room so the fetch only happens once per location.
         * Location is rounded to 1 decimal place (~7 mi) to avoid re-fetching
         * for minor GPS drift.
         */
        private suspend fun getHistoricalNormalsByMonthDay(
            lat: Double,
            lon: Double,
        ): Map<MonthDay, Pair<Int, Int>> {
            val roundedLat = (lat * 10).roundToInt() / 10.0
            val roundedLon = (lon * 10).roundToInt() / 10.0
            val locationKey = "${roundedLat}_${roundedLon}"

            // Check database for persisted normals at this location
            val cached = climateNormalDao.getNormalsForLocation(locationKey)
            if (cached.isNotEmpty()) {
                Log.d(TAG, "getHistoricalNormalsByMonthDay: Loaded ${cached.size} persisted normals for $locationKey")
                return cached.associate { entity ->
                    val (month, day) = entity.monthDay.split("-").map { it.toInt() }
                    MonthDay.of(month, day) to (entity.highTemp to entity.lowTemp)
                }
            }

            // Use a leap year to ensure Feb 29 is included
            val startDate = "2020-01-01"
            val endDate = "2020-12-31"
            Log.d(TAG, "getHistoricalNormalsByMonthDay: Fetching climate normals for $locationKey")

            val climate =
                openMeteoApi.getClimateForecast(
                    lat = roundedLat,
                    lon = roundedLon,
                    startDate = startDate,
                    endDate = endDate,
                )
            if (climate.isEmpty()) return emptyMap()

            val normals = climate
                .mapNotNull { day ->
                    val parsed = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@mapNotNull null
                    MonthDay.from(parsed) to (day.highTemp.roundToInt() to day.lowTemp.roundToInt())
                }
                .toMap()

            // Persist to database, clearing any normals from a previous location
            val entities = normals.map { (md, temps) ->
                ClimateNormalEntity(
                    monthDay = "${md.monthValue.toString().padStart(2, '0')}-${md.dayOfMonth.toString().padStart(2, '0')}",
                    locationKey = locationKey,
                    highTemp = temps.first,
                    lowTemp = temps.second,
                )
            }
            climateNormalDao.deleteOtherLocations(locationKey)
            climateNormalDao.insertAll(entities)

            Log.d(TAG, "getHistoricalNormalsByMonthDay: Persisted ${normals.size} normals for $locationKey")
            return normals
        }

        /**
         * Fetches weather from NWS: grid-point lookup → parallel forecast + hourly fetch →
         * observation history from nearby stations → merge into WeatherEntity list.
         *
         * Delegates to [initPrecipFromHourly], [fetchAndApplyObservations],
         * [applyForecastPeriods], and [logTodayDiagnostics] for the heavy lifting.
         */
        internal suspend fun fetchFromNws(
            lat: Double,
            lon: Double,
            locationName: String,
        ): List<WeatherEntity> =
            coroutineScope {
                val gridPoint = nwsApi.getGridPoint(lat, lon)

                // Start forecast and hourly fetches in parallel
                val forecastDeferred = async { nwsApi.getForecast(gridPoint) }
                val hourlyDeferred =
                    async {
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
                val today = LocalDate.now()
                val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

                persistNwsPeriodSummary(gridPoint.forecastUrl, forecast)

                if (hourlyForecast.isNotEmpty()) {
                    saveNwsHourlyForecasts(hourlyForecast, lat, lon)
                }

                val weatherByDate = mutableMapOf<String, Pair<Float?, Float?>>()
                val conditionByDate = mutableMapOf<String, String>()
                val conditionSourceByDate = mutableMapOf<String, String>()
                val precipByDate = mutableMapOf<String, Int>()
                val stationByDate = mutableMapOf<String, String>()
                val highSourceByDate = mutableMapOf<String, String>()
                val lowSourceByDate = mutableMapOf<String, String>()

                initPrecipFromHourly(hourlyForecast, precipByDate)
                fetchAndApplyObservations(gridPoint, today, weatherByDate, stationByDate, conditionByDate, conditionSourceByDate, highSourceByDate, lowSourceByDate)
                val todayForecastPeriods = applyForecastPeriods(forecast, todayStr, weatherByDate, conditionByDate, conditionSourceByDate, highSourceByDate, lowSourceByDate, precipByDate)
                logTodayDiagnostics(todayStr, weatherByDate, highSourceByDate, lowSourceByDate, conditionByDate, conditionSourceByDate, todayForecastPeriods, lat, lon)

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
                        source = WeatherSource.NWS.id,
                        stationId = stationByDate[date],
                        precipProbability = precipByDate[date],
                        isClimateNormal = false,
                    )
                }
            }

        /** Populates precipByDate from hourly data for precise calendar-day matching. */
        private fun initPrecipFromHourly(
            hourlyForecast: List<NwsApi.HourlyForecastPeriod>,
            precipByDate: MutableMap<String, Int>,
        ) {
            if (hourlyForecast.isEmpty()) return
            hourlyForecast.forEach { hourly ->
                val date =
                    try {
                        ZonedDateTime.parse(hourly.startTime)
                            .toLocalDate()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE)
                    } catch (e: Exception) {
                        null
                    }
                if (date != null) {
                    val pop = hourly.precipProbability ?: 0
                    if (pop > (precipByDate[date] ?: 0)) {
                        precipByDate[date] = pop
                    }
                }
            }
            Log.d(TAG, "initPrecipFromHourly: Initialized precipByDate for ${precipByDate.size} days from hourly data")
        }

        /** Fetches last [DAYS_OF_HISTORY] days of actual observations and populates weather/station/condition maps. */
        private suspend fun fetchAndApplyObservations(
            gridPoint: NwsApi.GridPointInfo,
            today: LocalDate,
            weatherByDate: MutableMap<String, Pair<Float?, Float?>>,
            stationByDate: MutableMap<String, String>,
            conditionByDate: MutableMap<String, String>,
            conditionSourceByDate: MutableMap<String, String>,
            highSourceByDate: MutableMap<String, String>,
            lowSourceByDate: MutableMap<String, String>,
        ) = coroutineScope {
            try {
                if (gridPoint.observationStationsUrl != null) {
                    val stationsUrl = gridPoint.observationStationsUrl!!
                    appLogDao.log("OBS_BATCH_START", "Fetching $DAYS_OF_HISTORY days of history from $stationsUrl")

                    val observationDeferreds =
                        (0 until DAYS_OF_HISTORY).map { daysAgo ->
                            val date = today.minusDays(daysAgo.toLong())
                            async {
                                val result = fetchDayObservations(stationsUrl, date)
                                date to result
                            }
                        }

                    var successCount = 0
                    observationDeferreds.forEach { deferred ->
                        val (date, observationData) = deferred.await()
                        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

                        if (observationData != null) {
                            successCount++
                            weatherByDate[dateStr] = observationData.highTemp to observationData.lowTemp
                            stationByDate[dateStr] = observationData.stationId
                            highSourceByDate[dateStr] = "OBS:${observationData.stationId}"
                            lowSourceByDate[dateStr] = "OBS:${observationData.stationId}"
                            // For today, skip observation condition — the NWS daily forecast
                            // shortForecast is a better whole-day summary than the cloud-cover-only observation score.
                            if (date != today) {
                                conditionByDate[dateStr] = observationData.condition
                                conditionSourceByDate[dateStr] = "OBS:${observationData.stationId}"
                            }
                            Log.d(
                                TAG,
                                "fetchAndApplyObservations: Got observations for $dateStr H=${observationData.highTemp} L=${observationData.lowTemp} from station ${observationData.stationId} conditionSet=${date != today}",
                            )
                        }
                    }
                    appLogDao.log("OBS_BATCH_SUCCESS", "Got $successCount/$DAYS_OF_HISTORY days of history", "INFO")
                }
            } catch (e: Exception) {
                appLogDao.log("OBS_BATCH_ERROR", "Error: ${e.message}", "ERROR")
            }
        }

        /** Iterates NWS forecast periods, populating weather/condition/precip maps. Returns today's periods. */
        private fun applyForecastPeriods(
            forecast: List<NwsApi.ForecastPeriod>,
            todayStr: String,
            weatherByDate: MutableMap<String, Pair<Float?, Float?>>,
            conditionByDate: MutableMap<String, String>,
            conditionSourceByDate: MutableMap<String, String>,
            highSourceByDate: MutableMap<String, String>,
            lowSourceByDate: MutableMap<String, String>,
            precipByDate: MutableMap<String, Int>,
        ): List<NwsApi.ForecastPeriod> {
            val todayForecastPeriods = mutableListOf<NwsApi.ForecastPeriod>()

            forecast.forEachIndexed { index, period ->
                val date =
                    extractNwsForecastDate(period.startTime) ?: run {
                        Log.w(TAG, "Failed to parse startTime ${period.startTime}, skipping period index=$index name=${period.name}")
                        return@forEachIndexed
                    }
                if (date == todayStr) {
                    todayForecastPeriods += period
                }
                val current = weatherByDate[date] ?: (null to null)

                Log.d(
                    TAG,
                    "  Period $index: ${period.name} isDaytime=${period.isDaytime} temp=${period.temperature} startTime=${period.startTime} -> date=$date",
                )

                // Use daily period POP as a fallback ONLY if we don't have hourly data for this date.
                val pop = period.precipProbability
                if (pop != null && !precipByDate.containsKey(date)) {
                    precipByDate[date] = pop
                }

                if (period.isDaytime) {
                    weatherByDate[date] = period.temperature.toFloat() to current.second
                    highSourceByDate[date] = "FCST:${period.name}@${period.startTime}"
                    if (conditionByDate[date] == null) {
                        conditionByDate[date] = period.shortForecast
                        conditionSourceByDate[date] = "FCST:${period.name}@${period.startTime}"
                    }
                } else {
                    weatherByDate[date] = current.first to period.temperature.toFloat()
                    lowSourceByDate[date] = "FCST:${period.name}@${period.startTime}"
                    if (conditionByDate[date] == null) {
                        conditionByDate[date] = period.shortForecast
                        conditionSourceByDate[date] = "FCST:${period.name}@${period.startTime}"
                    }
                }
            }

            return todayForecastPeriods
        }

        /** Logs today's condition override and transition diagnostics. */
        private suspend fun logTodayDiagnostics(
            todayStr: String,
            weatherByDate: Map<String, Pair<Float?, Float?>>,
            highSourceByDate: Map<String, String>,
            lowSourceByDate: Map<String, String>,
            conditionByDate: MutableMap<String, String>,
            conditionSourceByDate: MutableMap<String, String>,
            todayForecastPeriods: List<NwsApi.ForecastPeriod>,
            lat: Double,
            lon: Double,
        ) {
            val firstTodayPeriod = todayForecastPeriods.firstOrNull()
            if (firstTodayPeriod != null && !firstTodayPeriod.isDaytime) {
                val previousCondition = conditionByDate[todayStr]
                val previousConditionSource = conditionSourceByDate[todayStr] ?: "UNKNOWN"
                conditionByDate[todayStr] = firstTodayPeriod.shortForecast
                conditionSourceByDate[todayStr] = "FCST_ACTIVE:${firstTodayPeriod.name}@${firstTodayPeriod.startTime}"
                appLogDao.log(
                    "NWS_TODAY_CONDITION_OVERRIDE",
                    "date=$todayStr firstPeriod=${firstTodayPeriod.name}@${firstTodayPeriod.startTime} " +
                        "isDaytime=${firstTodayPeriod.isDaytime} condition ${previousCondition ?: "null"}->${firstTodayPeriod.shortForecast} " +
                        "source $previousConditionSource->${conditionSourceByDate[todayStr]}",
                )
            }

            val todayTemps = weatherByDate[todayStr] ?: return
            val currentHigh = todayTemps.first
            val currentLow = todayTemps.second
            val highSource = highSourceByDate[todayStr] ?: "UNKNOWN"
            val lowSource = lowSourceByDate[todayStr] ?: "UNKNOWN"
            val condition = conditionByDate[todayStr] ?: "Unknown"
            val conditionSource = conditionSourceByDate[todayStr] ?: "UNKNOWN"
            val firstTodayPeriodSummary =
                firstTodayPeriod?.let {
                    val dayFlag = if (it.isDaytime) "D" else "N"
                    "${it.name}@${it.startTime}[$dayFlag]=${it.shortForecast}"
                } ?: "none"

            appLogDao.log(
                "NWS_TODAY_SOURCE",
                "date=$todayStr high=$currentHigh ($highSource) low=$currentLow ($lowSource) " +
                    "condition=$condition ($conditionSource) firstTodayPeriod=$firstTodayPeriodSummary",
            )

            val previous =
                forecastSnapshotDao.getForecastForDateBySource(
                    targetDate = todayStr,
                    forecastDate = todayStr,
                    lat = lat,
                    lon = lon,
                    source = "NWS",
                )

            val changed =
                previous != null &&
                    (
                        previous.highTemp != currentHigh ||
                            previous.lowTemp != currentLow ||
                            previous.condition != condition
                    )
            if (changed) {
                val previousFetched =
                    Instant.ofEpochMilli(previous.fetchedAt)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                appLogDao.log(
                    "NWS_TODAY_TRANSITION",
                    "date=$todayStr high ${previous.highTemp}->$currentHigh low ${previous.lowTemp}->$currentLow " +
                        "condition ${previous.condition}->$condition prevFetched=$previousFetched",
                )
            }
        }

        private suspend fun persistNwsPeriodSummary(
            forecastUrl: String,
            forecast: List<NwsApi.ForecastPeriod>,
        ) {
            if (forecast.isEmpty()) {
                appLogDao.log("NWS_PERIOD_SUMMARY", "periods=0 url=$forecastUrl", "WARN")
                return
            }

            val compact =
                forecast
                    .take(NWS_PERIOD_SUMMARY_COUNT)
                    .mapIndexed { index, period ->
                        val dayFlag = if (period.isDaytime) "D" else "N"
                        "$index:${period.name}@${period.startTime}=${period.temperature}${period.temperatureUnit}[$dayFlag]"
                    }
                    .joinToString("; ")

            appLogDao.log("NWS_PERIOD_SUMMARY", "periods=${forecast.size} url=$forecastUrl first8=$compact")
        }

        private fun extractNwsForecastDate(startTime: String): String? {
            // Typical NWS value: "2026-02-06T18:00:00-08:00"
            try {
                return ZonedDateTime.parse(startTime)
                    .toLocalDate()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (_: Exception) {
                // Continue to the next strategy.
            }

            try {
                return OffsetDateTime.parse(startTime)
                    .toLocalDate()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (_: Exception) {
                // Continue to best-effort date-prefix parsing.
            }

            return runCatching {
                LocalDate.parse(startTime.take(10), DateTimeFormatter.ISO_LOCAL_DATE)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
            }.getOrNull()
        }

        private fun getCachedStations(stationsUrl: String): List<String>? {
            val key = "observation_stations_${stationsUrl.hashCode()}"
            val timeKey = "observation_stations_time_${stationsUrl.hashCode()}"

            val cacheTime = prefs.getLong(timeKey, 0L)
            if (System.currentTimeMillis() - cacheTime > OBSERVATION_STATIONS_CACHE_TTL_MS) {
                return null // Cache expired
            }

            val cached = prefs.getString(key, null) ?: return null
            return cached.split(",").filter { it.isNotBlank() }
        }

        private fun cacheStations(
            stationsUrl: String,
            stations: List<String>,
        ) {
            val key = "observation_stations_${stationsUrl.hashCode()}"
            val timeKey = "observation_stations_time_${stationsUrl.hashCode()}"

            prefs.edit()
                .putString(key, stations.joinToString(","))
                .putLong(timeKey, System.currentTimeMillis())
                .apply()
        }

        /**
         * Fetches actual high/low/condition observations for a single day from NWS
         * weather stations. Tries up to [MAX_OBSERVATION_STATION_RETRIES] nearby
         * stations, using a 24-hour cached station list to reduce API calls.
         * Derives condition from weighted daylight-hour cloud coverage scores,
         * with precipitation observations overriding cloud-only conditions.
         */
        internal suspend fun fetchDayObservations(
            stationsUrl: String,
            date: LocalDate,
        ): ObservationResult? {
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

                val stationsToTry = stations.take(MAX_OBSERVATION_STATION_RETRIES)

                for ((index, stationId) in stationsToTry.withIndex()) {
                    Log.d(TAG, "fetchDayObservations: Trying station $stationId (${index + 1}/${stationsToTry.size}) for $date")

                    try {
                        // Fetch observations for the specified day
                        val localZone = ZoneId.systemDefault()
                        val startTime =
                            date.atStartOfDay(localZone)
                                .format(DateTimeFormatter.ISO_INSTANT)
                        val endTime =
                            date.plusDays(1).atStartOfDay(localZone)
                                .format(DateTimeFormatter.ISO_INSTANT)

                        val startTimeMs = System.currentTimeMillis()
                        val observations = nwsApi.getObservations(stationId, startTime, endTime)
                        val durationMs = System.currentTimeMillis() - startTimeMs

                        if (observations.isEmpty()) {
                            appLogDao.log("API_CALL", "NWS-Obs failure station=$stationId durationMs=$durationMs error=No observations", "WARN")
                            Log.w(TAG, "fetchDayObservations: No observations from $stationId for $date - trying next")
                            continue // Try next station
                        }

                        appLogDao.log("API_CALL", "NWS-Obs success station=$stationId durationMs=$durationMs")

                        // Calculate high/low from observations (convert C to F) using Float math for precision
                        val temps: List<Float> =
                            observations.map { obs: NwsApi.Observation ->
                                (obs.temperatureCelsius * 1.8f) + 32f
                            }
                        val high = temps.maxOrNull() ?: continue
                        val low = temps.minOrNull() ?: continue

                        // --- WEIGHTED CLOUD COVERAGE LOGIC ---
                        // Prioritize daylight hours (7 AM to 7 PM)
                        val daylightObservations =
                            observations.filter { obs ->
                                try {
                                    val dt =
                                        ZonedDateTime.parse(obs.timestamp)
                                            .withZoneSameInstant(localZone)
                                    dt.hour in 7..19
                                } catch (e: Exception) {
                                    true
                                }
                            }.ifEmpty { observations }

                        // Check for precipitation in any observation during the day
                        val precipObservations =
                            daylightObservations.filter { obs ->
                                val desc = obs.textDescription.lowercase()
                                desc.contains("rain") || desc.contains("drizzle") ||
                                    desc.contains("shower") || desc.contains("storm") ||
                                    desc.contains("thunder") || desc.contains("snow") ||
                                    desc.contains("sleet") || desc.contains("freezing")
                            }

                        val cloudScores =
                            daylightObservations.map { obs ->
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

                        // Precipitation overrides cloud-only condition
                        val finalCondition =
                            if (precipObservations.isNotEmpty()) {
                                val desc = precipObservations.first().textDescription.lowercase()
                                when {
                                    desc.contains("thunder") || desc.contains("storm") -> "Thunderstorm"
                                    desc.contains("snow") || desc.contains("blizzard") -> "Snow"
                                    desc.contains("sleet") || desc.contains("freezing") -> "Freezing Rain"
                                    else -> "Rain"
                                }
                            } else {
                                when {
                                    averageCloudScore <= 15 -> "Sunny"
                                    averageCloudScore <= 35 -> "Mostly Sunny (25%)"
                                    averageCloudScore <= 65 -> "Partly Cloudy (50%)"
                                    averageCloudScore <= 85 -> "Mostly Cloudy (75%)"
                                    else -> "Cloudy"
                                }
                            }

                        Log.i(
                            TAG,
                            "fetchDayObservations: Station $stationId provided data for $date (H:$high L:$low) Score: $averageCloudScore -> $finalCondition",
                        )

                        return ObservationResult(high, low, stationId, finalCondition)
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
            days: Int = 7,
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
                    currentTempObservedAt = if (daily.date == today) forecast.currentObservedAt else null,
                    condition = openMeteoApi.weatherCodeToCondition(daily.weatherCode),
                    isActual = LocalDate.parse(daily.date).isBefore(LocalDate.now()),
                    source = WeatherSource.OPEN_METEO.id,
                    precipProbability = daily.precipProbability,
                    isClimateNormal = false,
                )
            }
        }

        private suspend fun fetchFromWeatherApi(
            lat: Double,
            lon: Double,
            locationName: String,
            days: Int = 14,
        ): List<WeatherEntity> {
            Log.d(TAG, "fetchFromWeatherApi: Fetching for $lat, $lon (days=$days)")
            val forecast = weatherApi.getForecast(lat, lon, days)
            Log.d(TAG, "fetchFromWeatherApi: Got ${forecast.daily.size} days from API")
            forecast.daily.forEach { d ->
                Log.d(TAG, "  API day: ${d.date} H=${d.highTemp} L=${d.lowTemp}")
            }
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

            if (forecast.hourly.isNotEmpty()) {
                saveWeatherApiHourlyForecasts(forecast.hourly, lat, lon)
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
                    currentTempObservedAt = if (daily.date == today) forecast.currentObservedAt else null,
                    condition = daily.condition,
                    isActual = LocalDate.parse(daily.date).isBefore(LocalDate.now()),
                    source = WeatherSource.WEATHER_API.id,
                    precipProbability = daily.precipProbability,
                    isClimateNormal = false,
                )
            }
        }

        private suspend fun saveHourlyEntities(entities: List<HourlyForecastEntity>, label: String) {
            hourlyForecastDao.insertAll(entities)
            val sortedTimes = entities.map { it.dateTime }.sorted()
            Log.d(TAG, "$label: Saved ${entities.size} hourly forecasts, range: ${sortedTimes.firstOrNull()} to ${sortedTimes.lastOrNull()}")
        }

        private suspend fun saveHourlyForecasts(
            hourlyForecasts: List<OpenMeteoApi.HourlyForecast>,
            lat: Double,
            lon: Double,
        ) {
            val entities =
                hourlyForecasts.map { hourly ->
                    HourlyForecastEntity(
                        dateTime = hourly.dateTime,
                        locationLat = lat,
                        locationLon = lon,
                        temperature = hourly.temperature,
                        condition = openMeteoApi.weatherCodeToCondition(hourly.weatherCode),
                        source = "OPEN_METEO",
                        precipProbability = hourly.precipProbability,
                        fetchedAt = System.currentTimeMillis(),
                    )
                }
            saveHourlyEntities(entities, "saveHourlyForecasts")
        }

        private suspend fun saveNwsHourlyForecasts(
            hourlyForecasts: List<NwsApi.HourlyForecastPeriod>,
            lat: Double,
            lon: Double,
        ) {
            val entities =
                hourlyForecasts.mapNotNull { hourly ->
                    val dateTime =
                        try {
                            val zonedDateTime = ZonedDateTime.parse(hourly.startTime)
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
                        precipProbability = hourly.precipProbability,
                        fetchedAt = System.currentTimeMillis(),
                    )
                }
            saveHourlyEntities(entities, "saveNwsHourlyForecasts")
        }

        private suspend fun saveWeatherApiHourlyForecasts(
            hourlyForecasts: List<WeatherApi.HourlyForecast>,
            lat: Double,
            lon: Double,
        ) {
            val entities =
                hourlyForecasts.map { hourly ->
                    HourlyForecastEntity(
                        dateTime = hourly.dateTime,
                        locationLat = lat,
                        locationLon = lon,
                        temperature = hourly.temperature,
                        condition = hourly.condition,
                        source = WeatherSource.WEATHER_API.id,
                        precipProbability = hourly.precipProbability,
                        fetchedAt = System.currentTimeMillis(),
                    )
                }
            saveHourlyEntities(entities, "saveWeatherApiHourlyForecasts")
        }

        /**
         * Gets the interpolated current temperature based on hourly forecast data.
         * Returns null if no hourly data is available.
         */
        suspend fun getInterpolatedTemperature(
            lat: Double,
            lon: Double,
            currentTime: LocalDateTime = LocalDateTime.now(),
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
            currentTime: LocalDateTime = LocalDateTime.now(),
        ): LocalDateTime {
            val currentHour = currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val nextHour = currentTime.plusHours(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))

            val forecasts = hourlyForecastDao.getHourlyForecasts(currentHour, nextHour, lat, lon)

            val tempDiff =
                if (forecasts.size >= 2) {
                    (forecasts[1].temperature - forecasts[0].temperature).toInt()
                } else {
                    0
                }

            return temperatureInterpolator.getNextUpdateTime(currentTime, tempDiff)
        }

        suspend fun refreshCurrentTemperature(
            lat: Double,
            lon: Double,
            locationName: String,
            source: WeatherSource? = null,
            reason: String = "unspecified",
            force: Boolean = false,
        ): Result<Int> {
            return try {
                syncMutex.withLock {
                    val now = System.currentTimeMillis()
                    val timeSinceLastFetch = now - lastCurrentTempFetchTime
                    if (!force && timeSinceLastFetch < CURRENT_TEMP_FRESHNESS_MS) {
                        appLogDao.log(
                            "CURR_FETCH_SKIP",
                            "reason=$reason rate_limited=${timeSinceLastFetch}ms (<$CURRENT_TEMP_FRESHNESS_MS)",
                            "INFO",
                        )
                        return Result.success(0)
                    }

                    val targetSources =
                        (source?.let { listOf(it) } ?: widgetStateManager.getVisibleSourcesOrder())
                            .filter { it != WeatherSource.GENERIC_GAP }
                            .distinct()
                    val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

                    appLogDao.log(
                        "CURR_FETCH_START",
                        "reason=$reason location=$locationName sources=${targetSources.joinToString(",") { it.id }} force=$force",
                    )

                    var updatedCount = 0
                    targetSources.forEach { weatherSource ->
                        try {
                            val reading =
                                when (weatherSource) {
                                    WeatherSource.OPEN_METEO -> {
                                        val current = openMeteoApi.getCurrent(lat, lon) ?: return@forEach
                                        CurrentReadingPayload(
                                            source = weatherSource,
                                            temperature = current.temperature,
                                            condition = current.weatherCode?.let { openMeteoApi.weatherCodeToCondition(it) },
                                            observedAt = current.observedAt,
                                        )
                                    }
                                    WeatherSource.WEATHER_API -> {
                                        val current = weatherApi.getCurrent(lat, lon) ?: return@forEach
                                        CurrentReadingPayload(
                                            source = weatherSource,
                                            temperature = current.temperature,
                                            condition = current.condition,
                                            observedAt = current.observedAt,
                                        )
                                    }
                                    WeatherSource.NWS -> {
                                        appLogDao.log(
                                            "CURR_FETCH_SKIP",
                                            "reason=$reason source=${weatherSource.id} unsupported_current_endpoint",
                                            "INFO",
                                        )
                                        return@forEach
                                    }
                                    WeatherSource.GENERIC_GAP -> return@forEach
                                }

                            val interpolated =
                                getInterpolatedCurrentTempForSource(
                                    lat = lat,
                                    lon = lon,
                                    source = reading.source,
                                    currentTime = LocalDateTime.now(),
                                )
                            val delta = interpolated?.let { reading.temperature - it }

                            val existing = weatherDao.getWeatherForDateBySource(today, lat, lon, reading.source.id)
                            if (existing == null) {
                                appLogDao.log(
                                    "CURR_FETCH_SKIP",
                                    "reason=$reason source=${reading.source.id} missing_today_row date=$today",
                                    "INFO",
                                )
                                return@forEach
                            }

                            weatherDao.insertWeather(
                                existing.copy(
                                    currentTemp = reading.temperature,
                                    currentTempObservedAt = reading.observedAt ?: now,
                                    condition = reading.condition ?: existing.condition,
                                    fetchedAt = now,
                                ),
                            )

                            // Universal Human-Readable Logging for every source updated
                            val observedAtMs = reading.observedAt ?: now
                            val observedAgeMs = (now - observedAtMs).coerceAtLeast(0L)
                            val ageMins = observedAgeMs / 60000
                            val freshnessStr = if (ageMins == 0L) "Just now" else "${ageMins}m ago"
                            val deltaStr = delta?.let { " (delta ${if (it >= 0) "+" else ""}${String.format("%.1f", it)}°)" } ?: ""

                            val displayMessage = "[${reading.source.id}] ${String.format("%.1f", reading.temperature)}°$deltaStr | Observed $freshnessStr"
                            appLogDao.log("CURR_FETCH_DELTA", displayMessage)
                            Log.d(TAG, "CURR_FETCH_DELTA: $displayMessage")

                            updatedCount++
                        } catch (sourceError: Exception) {
                            appLogDao.log(
                                "CURR_FETCH_SKIP",
                                "reason=$reason source=${weatherSource.id} error=${sourceError.message}",
                                "INFO",
                            )
                        }
                    }

                    if (updatedCount > 0) {
                        lastCurrentTempFetchTime = now
                    }
                    appLogDao.log(
                        "CURR_FETCH_SUCCESS",
                        "reason=$reason updated=$updatedCount sources=${targetSources.joinToString(",") { it.id }} force=$force",
                    )
                    Result.success(updatedCount)
                }
            } catch (e: Exception) {
                appLogDao.log("CURR_FETCH_FAIL", "reason=$reason ${e.javaClass.simpleName}: ${e.message} force=$force", "ERROR")
                Result.failure(e)
            }
        }

        private suspend fun cleanOldData() {
            val now = System.currentTimeMillis()
            val cutoffStandard = now - MONTH_IN_MILLIS
            val cutoffMeteo = now - (7L * 24 * 60 * 60 * 1000) // 7 days for Open-Meteo

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

                // Standard 30-day cleanup for NWS and WeatherAPI
                weatherDao.deleteOldDataBySource(cutoffStandard, WeatherSource.NWS.id)
                weatherDao.deleteOldDataBySource(cutoffStandard, WeatherSource.WEATHER_API.id)
                weatherDao.deleteOldDataBySource(cutoffStandard, WeatherSource.GENERIC_GAP.id)

                forecastSnapshotDao.deleteOldSnapshotsBySource(cutoffStandard, WeatherSource.NWS.id)
                forecastSnapshotDao.deleteOldSnapshotsBySource(cutoffStandard, WeatherSource.WEATHER_API.id)
                forecastSnapshotDao.deleteOldSnapshotsBySource(cutoffStandard, WeatherSource.GENERIC_GAP.id)

                hourlyForecastDao.deleteOldForecastsBySource(cutoffStandard, WeatherSource.NWS.id)
                hourlyForecastDao.deleteOldForecastsBySource(cutoffStandard, WeatherSource.WEATHER_API.id)

                // Aggressive 7-day cleanup for Open-Meteo
                weatherDao.deleteOldDataBySource(cutoffMeteo, WeatherSource.OPEN_METEO.id)
                forecastSnapshotDao.deleteOldSnapshotsBySource(cutoffMeteo, WeatherSource.OPEN_METEO.id)
                hourlyForecastDao.deleteOldForecastsBySource(cutoffMeteo, WeatherSource.OPEN_METEO.id)

                // Maintain logs for 3 days (72 hours) to optimize space while keeping recent forensics
                val logCutoff = now - (3L * 24 * 60 * 60 * 1000)
                appLogDao.deleteOldLogs(logCutoff)

                if (oldWeather > 0) {
                    val cutoffDate = Instant.ofEpochMilli(cutoffStandard).atZone(ZoneId.systemDefault()).toLocalDate()
                    appLogDao.log("DB_CLEANUP", "Cleaned standard records older than $cutoffDate. Pruned Open-Meteo to 7 days.", "INFO")
                }
            } catch (e: Exception) {
                appLogDao.log("DB_CLEANUP_ERROR", "Cleanup failed: ${e.message}", "ERROR")
            }
        }

        suspend fun getLatestLocation(): Pair<Double, Double>? {
            val latest = weatherDao.getLatestWeather()
            return latest?.let { it.locationLat to it.locationLon }
        }

        private data class CurrentReadingPayload(
            val source: WeatherSource,
            val temperature: Float,
            val condition: String?,
            val observedAt: Long?,
        )

        private suspend fun getInterpolatedCurrentTempForSource(
            lat: Double,
            lon: Double,
            source: WeatherSource,
            currentTime: LocalDateTime,
        ): Float? {
            val startTime = currentTime.minusHours(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = currentTime.plusHours(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyForecastDao.getHourlyForecasts(startTime, endTime, lat, lon)
            if (hourlyForecasts.isEmpty()) return null
            return temperatureInterpolator.getInterpolatedTemperature(
                hourlyForecasts = hourlyForecasts,
                targetTime = currentTime,
                source = source,
            )
        }
    }
