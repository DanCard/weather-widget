package com.weatherwidget.data.repository

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.ClimateNormalEntity
import com.weatherwidget.data.local.DailyExtremeDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
     import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.OpenWeatherMapApi
import com.weatherwidget.data.remote.VisualCrossingApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.data.remote.SilurianApi
import com.weatherwidget.data.remote.TomorrowIoApi
import com.weatherwidget.widget.ForecastFetchContext
import com.weatherwidget.widget.ForecastFetchPolicy
import com.weatherwidget.widget.ForecastStalenessPolicy
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val TAG = "ForecastRepository"

@Singleton
class ForecastRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val forecastDao: ForecastDao,
        private val hourlyForecastDao: HourlyForecastDao,
        private val hourlyForecastHistoryDao: HourlyForecastHistoryDao,
        private val appLogDao: AppLogDao,
        private val nwsApi: NwsApi,
        private val openMeteoApi: OpenMeteoApi,
        private val visualCrossingApi: VisualCrossingApi,
        private val weatherApi: WeatherApi,
        private val silurianApi: SilurianApi,
        private val widgetStateManager: WidgetStateManager,
        private val climateNormalDao: ClimateNormalDao,
        private val observationDao: ObservationDao,
        private val dailyExtremeDao: DailyExtremeDao,
        private val observationRepository: ObservationRepository,
        private val tomorrowIoApi: TomorrowIoApi? = null,
        private val openWeatherMapApi: OpenWeatherMapApi? = null,
        private val nwsForecastMapper: NwsForecastMapper,
    ) {
        

        private val syncMutex = Mutex()
        private val prefs by lazy { com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, "weather_prefs") }

        companion object {
            private const val MIN_NETWORK_INTERVAL_MS = 600_000L // 10 minutes
            private const val MAX_RETRIES = 5
            private const val CACHE_LOOKBACK_DAYS = 7L
            private const val CACHE_FORECAST_DAYS = 30L

            @androidx.annotation.VisibleForTesting
            internal fun hasMeaningfulHourlyChange(
                existing: HourlyForecastEntity?,
                newlyFetched: HourlyForecastEntity,
            ): Boolean {
                if (existing == null) return true
                return existing.temperature != newlyFetched.temperature ||
                    existing.condition != newlyFetched.condition ||
                    existing.precipProbability != newlyFetched.precipProbability ||
                    existing.precipAmountMm != newlyFetched.precipAmountMm ||
                    existing.cloudCover != newlyFetched.cloudCover ||
                    newlyFetched.fetchedAt - existing.fetchedAt > 60 * 60 * 1000L
            }

            /**
             * Merge a newly fetched hourly entity with the existing DB row, preserving any
             * non-null nullable fields from the existing row when the new fetch returned null.
             * Prevents a single bad fetch (e.g., NWS gridpoints failure that drops the skyCover
             * field) from wiping previously good cloudCover / precip data.
             */
            @androidx.annotation.VisibleForTesting
            internal fun mergePreservingNullableFields(
                existing: HourlyForecastEntity?,
                newlyFetched: HourlyForecastEntity,
            ): HourlyForecastEntity {
                if (existing == null) return newlyFetched
                return newlyFetched.copy(
                    cloudCover = newlyFetched.cloudCover ?: existing.cloudCover,
                    precipProbability = newlyFetched.precipProbability ?: existing.precipProbability,
                    precipAmountMm = newlyFetched.precipAmountMm ?: existing.precipAmountMm,
                )
            }

        }

        private var lastFetchTime: Long
            get() = FetchMetadata.getLastFullFetchTime(context)
            set(value) = FetchMetadata.setLastFullFetchTime(context, value)

        suspend fun getWeatherData(
            latitude: Double,
            longitude: Double,
            locationName: String,
            forceRefresh: Boolean = false,
            networkAllowed: Boolean = true,
            targetSourceId: String? = null,
            fetchContext: ForecastFetchContext? = null,
        ): Result<List<ForecastEntity>> {
            val fetchStartTime = System.currentTimeMillis()
            try {
                // Initial check without locking
                var cachedForecasts = getCachedData(latitude, longitude)
                if (!forceRefresh && !requiresNetworkFetch(latitude, longitude, cachedForecasts, fetchContext)) {
                    return Result.success(cachedForecasts)
                }

                if (!networkAllowed) return Result.success(cachedForecasts)

                syncMutex.withLock {
                    // Re-read data after acquiring lock to ensure another thread didn't just update it
                    cachedForecasts = getCachedData(latitude, longitude)
                    if (!forceRefresh && !requiresNetworkFetch(latitude, longitude, cachedForecasts, fetchContext)) {
                        return Result.success(cachedForecasts)
                    }

                    // Enforce a hard throttle on full network fetches unless forced
                    val timeSinceLastFetch = System.currentTimeMillis() - lastFetchTime
                    if (!forceRefresh && timeSinceLastFetch < MIN_NETWORK_INTERVAL_MS && cachedForecasts.isNotEmpty()) {
                        return Result.success(cachedForecasts)
                    }

                    appLogDao.log("NET_FETCH_START", "force=$forceRefresh target=$targetSourceId ctx=${fetchContext?.let { "charging=${it.isCharging},screen=${it.isScreenInteractive},batt=${it.batteryLevel},active=${it.activeSourceIds}" } ?: "none"}")
                    val enabledSources = widgetStateManager.getVisibleSourcesOrder().toSet()
                    if (forceRefresh && targetSourceId != null && enabledSources.none { it.id == targetSourceId }) {
                        appLogDao.log("NET_FETCH_SKIP_DISABLED", "target=$targetSourceId")
                        return Result.success(cachedForecasts)
                    }

                    fun shouldForceSource(source: WeatherSource): Boolean {
                        if (!forceRefresh) return false
                        if (targetSourceId == null) return true // Force all if no target specified
                        return source.id == targetSourceId
                    }

                   // Perform parallel fetches from all APIs
                    val sourcesToFetch = enabledSources.filter { source ->
                        shouldForceSource(source) || isStale(source, cachedForecasts, fetchContext)
                    }.toSet() - WeatherSource.GENERIC_GAP

                    val (nwsForecasts, owmForecasts, visualCrossingForecasts, meteoForecasts, wapiForecasts, silurianForecasts, tomorrowIoForecasts) = fetchFromAllApis(
                        latitude, longitude, locationName, sourcesToFetch,
                        openWeatherMapApi != null,
                    )

                    // Determine the end of our real forecast coverage to fill in the rest with climate normals
                    val maxCoverageDates = mutableListOf<LocalDate>()
                    nwsForecasts?.maxOfOrNull { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }?.let { maxCoverageDates.add(it) }
                    owmForecasts?.maxOfOrNull { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }?.let { maxCoverageDates.add(it) }
                    visualCrossingForecasts?.maxOfOrNull { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }?.let { maxCoverageDates.add(it) }
                    meteoForecasts?.maxOfOrNull { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }?.let { maxCoverageDates.add(it) }
                    wapiForecasts?.maxOfOrNull { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }?.let { maxCoverageDates.add(it) }
                    silurianForecasts?.maxOfOrNull { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }?.let { maxCoverageDates.add(it) }
                    tomorrowIoForecasts?.maxOfOrNull { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }?.let { maxCoverageDates.add(it) }

                    // If any expected source is missing from both the fresh fetch AND the cache,
                    // we need to fill from today onwards
                    if (maxCoverageDates.isEmpty() && cachedForecasts.isEmpty()) {
                        maxCoverageDates.add(LocalDate.now().minusDays(1))
                    } else if (maxCoverageDates.isEmpty()) {
                        // Use the max date from cache if no fresh fetch succeeded
                        cachedForecasts.maxOfOrNull { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }?.let { maxCoverageDates.add(it) }
                    }
                    
                    val minMaxDate = maxCoverageDates.minOrNull() ?: LocalDate.now()
                    val climateGaps = fetchClimateNormalsGap(latitude, longitude, locationName, minMaxDate, 30)
                    if (climateGaps.isNotEmpty()) {
                        forecastDao.insertAll(climateGaps)
                    }

                    cleanOldData()
                    val totalFetchTime = System.currentTimeMillis() - fetchStartTime
                    lastFetchTime = System.currentTimeMillis()
                    
                    appLogDao.log("NET_FETCH_COMPLETE", "durationMs=$totalFetchTime sources=${sourcesToFetch.joinToString(",") { it.id }}")
                    
                    return Result.success(getCachedData(latitude, longitude))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (exception: Exception) {
                lastFetchTime = 0L // Allow immediate retry on error
                appLogDao.logException("NET_FETCH_ERROR", "Network fetch failed", exception)
                val fallbackData = getCachedData(latitude, longitude)
                return if (fallbackData.isNotEmpty()) Result.success(fallbackData) else Result.failure(exception)
            }
        }

        private fun requiresNetworkFetch(
            latitude: Double,
            longitude: Double,
            forecasts: List<ForecastEntity>,
            fetchContext: ForecastFetchContext? = null,
        ): Boolean {
            val sourcesToCheck = listOf(
                WeatherSource.NWS,
                WeatherSource.VISUAL_CROSSING,
                WeatherSource.OPEN_WEATHER_MAP,
                WeatherSource.SILURIAN,
                WeatherSource.WEATHER_API,
                WeatherSource.OPEN_METEO,
                WeatherSource.TOMORROW_IO,
            )
            return sourcesToCheck.any { source ->
                val isNeeded = widgetStateManager.isSourceVisible(source)
                isNeeded && isStale(source, forecasts, fetchContext)
            }
        }

        private fun isStale(
            source: WeatherSource,
            forecasts: List<ForecastEntity>,
            fetchContext: ForecastFetchContext? = null,
        ): Boolean {
            val lastSourceFetchTime = forecasts.filter { it.source == source.id }.maxOfOrNull { it.batchFetchedAt } ?: 0L
            val now = System.currentTimeMillis()

            if (fetchContext != null) {
                val intervalMinutes = ForecastFetchPolicy.intervalMinutes(
                    isCharging = fetchContext.isCharging,
                    isScreenInteractive = fetchContext.isScreenInteractive,
                    isActiveSource = source.id in fetchContext.activeSourceIds,
                    batteryLevel = fetchContext.batteryLevel,
                ) ?: return false
                return ForecastFetchPolicy.isDue(lastSourceFetchTime, intervalMinutes, now)
            }

            val visibleSources = widgetStateManager.getVisibleSourcesOrder()
            val position = visibleSources.indexOf(source)
            val threshold = ForecastStalenessPolicy.getStalenessThresholdMs(position)
            return now - lastSourceFetchTime >= threshold
        }

        private data class FetchResult(
            val nws: List<ForecastEntity>?,
            val openWeatherMap: List<ForecastEntity>?,
            val visualCrossing: List<ForecastEntity>?,
            val meteo: List<ForecastEntity>?,
            val wapi: List<ForecastEntity>?,
            val silurian: List<ForecastEntity>?,
            val tomorrowIo: List<ForecastEntity>?,
        )

       private suspend fun fetchFromAllApis(
            latitude: Double,
            longitude: Double,
            locationName: String,
            sourcesToFetch: Set<WeatherSource>,
            hasOpenWeatherMapApi: Boolean,
        ): FetchResult = coroutineScope {
            val nwsDeferred = if (WeatherSource.NWS in sourcesToFetch) async {
                safeFetch("FETCH_NWS_FAIL", WeatherSource.NWS) {
                    fetchFromNws(latitude, longitude, locationName)
                }
            } else null

            val openWeatherMapDeferred = if (hasOpenWeatherMapApi && WeatherSource.OPEN_WEATHER_MAP in sourcesToFetch) async {
                safeFetch("FETCH_OWM_FAIL", WeatherSource.OPEN_WEATHER_MAP) {
                    val api = openWeatherMapApi ?: return@safeFetch null
                    val result = api.getForecast(latitude, longitude)
                    if (result.hourly.isNotEmpty()) {
                        saveHourlyEntitiesFromShared(result.hourly, latitude, longitude, WeatherSource.OPEN_WEATHER_MAP.id)
                    }
                    result.daily.map { day ->
                        mapDailyForecast(day, latitude, longitude, locationName, WeatherSource.OPEN_WEATHER_MAP.id, result.hourly)
                    }
                }
            } else null

            val visualCrossingDeferred = if (WeatherSource.VISUAL_CROSSING in sourcesToFetch) async {
                safeFetch("FETCH_VISUAL_CROSSING_FAIL", WeatherSource.VISUAL_CROSSING) {
                    val result = visualCrossingApi.getForecast(latitude, longitude)
                    if (result.hourly.isNotEmpty()) {
                        saveHourlyEntitiesFromShared(result.hourly, latitude, longitude, WeatherSource.VISUAL_CROSSING.id)
                    }
                    result.daily.map { day ->
                        mapDailyForecast(day, latitude, longitude, locationName, WeatherSource.VISUAL_CROSSING.id, result.hourly)
                    }
                }
            } else null
            
            val meteoDeferred = if (WeatherSource.OPEN_METEO in sourcesToFetch) async {
                safeFetch("FETCH_METEO_FAIL", WeatherSource.OPEN_METEO) {
                    val result = openMeteoApi.getForecast(
                        latitude,
                        longitude,
                        7,
                        historyDays = WeatherConfig.ACTUALS_HISTORY_DAYS
                    )
                    if (result.hourly.isNotEmpty()) {
                        saveHourlyEntitiesFromShared(result.hourly, latitude, longitude, WeatherSource.OPEN_METEO.id)
                    }
                    result.daily.map { day ->
                        mapDailyForecast(day, latitude, longitude, locationName, WeatherSource.OPEN_METEO.id, result.hourly)
                    }
                }
            } else null
            
            val wapiDeferred = if (WeatherSource.WEATHER_API in sourcesToFetch) async {
                safeFetch("FETCH_WAPI_FAIL", WeatherSource.WEATHER_API) {
                    val result = weatherApi.getForecast(latitude, longitude)
                    if (result.hourly.isNotEmpty()) {
                        saveHourlyEntitiesFromShared(result.hourly, latitude, longitude, WeatherSource.WEATHER_API.id)
                    }
                    result.daily.map { day ->
                        mapDailyForecast(day, latitude, longitude, locationName, WeatherSource.WEATHER_API.id, result.hourly)
                    }
                }
            } else null

            val silurianDeferred = if (WeatherSource.SILURIAN in sourcesToFetch) async {
                safeFetch("FETCH_SILURIAN_FAIL", WeatherSource.SILURIAN) {
                    val result = silurianApi.getForecast(latitude, longitude)
                    if (result.hourly.isNotEmpty()) {
                        saveHourlyEntitiesFromShared(result.hourly, latitude, longitude, WeatherSource.SILURIAN.id)
                    }
                    result.daily.map { day ->
                    mapDailyForecast(
                        DailyForecast(
                            day.date,
                            day.highTemp,
                            day.lowTemp,
                            day.condition,
                            day.condition,
                            day.precipProbability,
                            day.precipAmountMm,
                        ),

                        latitude,
                        longitude,
                        locationName,
                        WeatherSource.SILURIAN.id,
                        result.hourly,
                    )
                    }

                }
            } else null

            val tomorrowIoDeferred = if (WeatherSource.TOMORROW_IO in sourcesToFetch) async {
                safeFetch("FETCH_TMRW_FAIL", WeatherSource.TOMORROW_IO) {
                    val api = tomorrowIoApi ?: return@safeFetch null
                    val result = api.getForecast(latitude, longitude)
                    if (result.hourly.isNotEmpty()) {
                        saveHourlyEntitiesFromShared(result.hourly, latitude, longitude, WeatherSource.TOMORROW_IO.id)
                    }
                    result.daily.map { day ->
                        mapDailyForecast(day, latitude, longitude, locationName, WeatherSource.TOMORROW_IO.id, result.hourly)
                    }
                }
            } else null

            val nwsForecasts = nwsDeferred?.await()
            val owmForecasts = openWeatherMapDeferred?.await()
            val visualCrossingForecasts = visualCrossingDeferred?.await()
            val meteoForecasts = meteoDeferred?.await()
            val wapiForecasts = wapiDeferred?.await()
            val silurianForecasts = silurianDeferred?.await()
            val tomorrowIoForecasts = tomorrowIoDeferred?.await()

            // Save each provider fetch as a coherent batch with a shared batchFetchedAt.
            nwsForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.NWS.id, System.currentTimeMillis()) }
            owmForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.OPEN_WEATHER_MAP.id, System.currentTimeMillis()) }
            visualCrossingForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.VISUAL_CROSSING.id, System.currentTimeMillis()) }
            meteoForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.OPEN_METEO.id, System.currentTimeMillis()) }
            wapiForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.WEATHER_API.id, System.currentTimeMillis()) }
            silurianForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.SILURIAN.id, System.currentTimeMillis()) }
            tomorrowIoForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.TOMORROW_IO.id, System.currentTimeMillis()) }

            FetchResult(nwsForecasts, owmForecasts, visualCrossingForecasts, meteoForecasts, wapiForecasts, silurianForecasts, tomorrowIoForecasts)
        }

        internal suspend fun fetchFromNws(latitude: Double, longitude: Double, locationName: String): List<ForecastEntity> {
            val (forecastEntities, hourlyEntities) = nwsForecastMapper.fetchFromNws(latitude, longitude, locationName)
            if (hourlyEntities.isNotEmpty()) {
                saveHourlyEntities(hourlyEntities)
            }
            return forecastEntities
        }

        private suspend fun logFetchFailure(
            tag: String,
            source: WeatherSource,
            exception: Exception,
        ) {
            when (exception) {
                is ApiAccessException -> {
                    val code = exception.statusCode?.let { "HTTP_$it" } ?: "ACCESS_ERROR"
                    appLogDao.log(tag, "source=${source.id} code=$code detail=${exception.detail}", "WARN")
                }
                is ClientRequestException -> {
                    val statusCode = exception.response.status.value
                    val detail = extractHttpErrorDetail(
                        runCatching { exception.response.bodyAsText() }.getOrNull(),
                        exception.message,
                    )
                    appLogDao.log(tag, "source=${source.id} code=HTTP_$statusCode detail=$detail", "WARN")
                }
                else -> appLogDao.log(tag, "source=${source.id} error=${exception.message}", "WARN")
            }
        }

        private fun extractErrorCode(exception: Exception): String = when (exception) {
            is ApiAccessException -> exception.statusCode?.let { "HTTP_$it" } ?: "ACCESS_ERROR"
            is ClientRequestException -> "HTTP_${exception.response.status.value}"
            else -> {
                val name = exception.javaClass.simpleName
                when {
                    name.contains("UnknownHost") || name.contains("UnresolvedAddress") -> "DNS_ERROR"
                    name.contains("ConnectException") || name.contains("ConnectionRefused") -> "CONN_REFUSED"
                    name.contains("Timeout") || name.contains("SocketTimeout") -> "TIMEOUT"
                    name.contains("SSL") || name.contains("TLS") -> "SSL_ERROR"
                    name.contains("SocketException") -> "SOCKET_ERROR"
                    else -> name.take(20).ifBlank { "ERROR" }
                }
            }
        }

        private fun extractHttpErrorDetail(body: String?, fallbackMessage: String?): String {
            val bodyText = body?.trim().orEmpty()
            val messageMatch = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(bodyText)?.groupValues?.getOrNull(1)
            val errorMatch = Regex("\"error\"\\s*:\\s*\\{[^}]*\"message\"\\s*:\\s*\"([^\"]+)\"").find(bodyText)?.groupValues?.getOrNull(1)
            return messageMatch ?: errorMatch ?: fallbackMessage ?: "Request failed"
        }

        private suspend fun <T> safeFetch(
            tag: String,
            source: WeatherSource,
            block: suspend () -> T,
        ): T? {
            return try {
                val result = block()
                if (result != null) {
                    widgetStateManager.recordSourceFetchSuccess(source)
                }
                result
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (exception: Exception) {
                widgetStateManager.recordSourceFetchFailure(source, errorCode = extractErrorCode(exception))
                logFetchFailure(tag, source, exception)
                null
            }
        }

        @androidx.annotation.VisibleForTesting
        internal fun mapDailyForecast(
            day: DailyForecast,
            latitude: Double,
            longitude: Double,
            locationName: String,
            sourceId: String,
            hourlyForecasts: List<HourlyForecast> = emptyList(),
        ): ForecastEntity {
            val targetDate = LocalDate.parse(day.date)
            val zone = ZoneId.systemDefault()

            // Daytime: 8:00 AM to 8:00 PM (on the target date)
            val dayStart = targetDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
            val dayEnd = targetDate.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()

            // Nighttime: 8:00 PM on target date to 8:00 AM next day
            val nightStart = dayEnd
            val nightEnd = targetDate.plusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()

            val calcDaytime = hourlyForecasts
                .filter { it.dateTime >= dayStart && it.dateTime < dayEnd }
                .mapNotNull { it.precipProbability }
                .maxOrNull()

            val calcNighttime = hourlyForecasts
                .filter { it.dateTime >= nightStart && it.dateTime < nightEnd }
                .mapNotNull { it.precipProbability }
                .maxOrNull()

            return ForecastEntity(
                targetDate = targetDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                forecastDate = LocalDate.now().toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                locationLat = latitude,
                locationLon = longitude,
                locationName = locationName,
                highTemp = day.highTemp,
                lowTemp = day.lowTemp,
                condition = day.condition,
                nativeDailyIconToken = day.iconToken,
                isClimateNormal = false,
                source = sourceId,
                precipProbability = day.precipProbability,
                daytimePrecipProbability = calcDaytime,
                nighttimePrecipProbability = calcNighttime,
                precipAmountMm = day.precipAmountMm,
            )
        }

        @androidx.annotation.VisibleForTesting
        internal suspend fun saveForecastSnapshot(
            weatherForecasts: List<ForecastEntity>, 
            latitude: Double, 
            longitude: Double, 
            sourceId: String,
            batchFetchedAt: Long = System.currentTimeMillis(),
        ) {
            val todayDate = LocalDate.now()
            val todayEpoch = todayDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val now = ZonedDateTime.now()
            val forecastsToSave = weatherForecasts.filter { forecast ->
                val date = LocalDate.ofEpochDay(forecast.targetDate / WidgetConstants.MS_IN_A_DAY)
                if (date.isBefore(todayDate) || forecast.isClimateNormal) return@filter false
                // Exclude entries whose daytime period has already ended — NWS overwrites elapsed
                // periods with observed reality, so snapshotting them would corrupt accuracy tracking
                // by making tomorrow's "forecast vs actual" comparison observed-vs-observed.
                val periodEnd = forecast.periodEndTime?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
                if (periodEnd != null && periodEnd.isBefore(now)) {
                    appLogDao.log("SNAPSHOT_SKIP_ELAPSED", "date=${LocalDate.ofEpochDay(forecast.targetDate / WidgetConstants.MS_IN_A_DAY)} source=${forecast.source} periodEnd=$periodEnd")
                    return@filter false
                }
                true
            }.mapNotNull { forecast ->
                if (forecast.highTemp == null && forecast.lowTemp == null) return@mapNotNull null

                // Preserve full decimal precision for Today's forecast to improve accuracy tracking.
                // Continue rounding future days to integers for UI consistency and storage.
                val isToday = forecast.targetDate == todayEpoch
                val highTempSaved = if (isToday) forecast.highTemp else forecast.highTemp?.roundToInt()?.toFloat()
                val lowTempSaved = if (isToday) forecast.lowTemp else forecast.lowTemp?.roundToInt()?.toFloat()

                ForecastEntity(
                    targetDate = forecast.targetDate,
                    forecastDate = todayEpoch,
                    locationLat = latitude,
                    locationLon = longitude,
                    locationName = forecast.locationName,
                    highTemp = highTempSaved,
                    lowTemp = lowTempSaved,
                    condition = forecast.condition,
                    nativeDailyIconToken = forecast.nativeDailyIconToken,
                    isClimateNormal = forecast.isClimateNormal,
                    source = sourceId,
                    precipProbability = forecast.precipProbability,
                    daytimePrecipProbability = forecast.daytimePrecipProbability,
                    nighttimePrecipProbability = forecast.nighttimePrecipProbability,
                    precipAmountMm = forecast.precipAmountMm,
                    batchFetchedAt = batchFetchedAt,
                    fetchedAt = System.currentTimeMillis()
                )
            }

            if (forecastsToSave.isNotEmpty()) {
                // Use the optimized DAO query to get existing records for comparison
                val existingForecasts = forecastDao.getForecastsInRangeBySource(
                    startDate = todayEpoch,
                    endDate = todayDate.plusDays(14).toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                    lat = latitude,
                    lon = longitude,
                    source = sourceId
                )
                
                // associateBy picks the first occurrence.
                // Since DAO orders by batchFetchedAt DESC, then fetchedAt DESC, this is the latest row for each targetDate.
                val latestByDate = existingForecasts.distinctBy { it.targetDate }.associateBy { it.targetDate }
                
                val changedForecasts = forecastsToSave.filter { newlyFetched ->
                    val existing = latestByDate[newlyFetched.targetDate]
                    val fieldsMatch = existing != null &&
                        existing.highTemp == newlyFetched.highTemp &&
                        existing.lowTemp == newlyFetched.lowTemp &&
                        existing.condition == newlyFetched.condition &&
                        existing.nativeDailyIconToken == newlyFetched.nativeDailyIconToken &&
                        existing.precipProbability == newlyFetched.precipProbability &&
                        existing.daytimePrecipProbability == newlyFetched.daytimePrecipProbability &&
                        existing.nighttimePrecipProbability == newlyFetched.nighttimePrecipProbability &&
                        existing.precipAmountMm == newlyFetched.precipAmountMm
                    val newDataIsStrictlyBetter = existing != null &&
                        ((existing.highTemp == null && newlyFetched.highTemp != null) ||
                        (existing.lowTemp == null && newlyFetched.lowTemp != null))
                    if (fieldsMatch && !newDataIsStrictlyBetter) {
                        appLogDao.log("SNAPSHOT_SKIP", "date=${newlyFetched.targetDate} source=$sourceId existing_high=${existing.highTemp} new_high=${newlyFetched.highTemp} existing_low=${existing.lowTemp} new_low=${newlyFetched.lowTemp} existing_cond=${existing.condition} new_cond=${newlyFetched.condition} existing_precip=${existing.precipProbability} new_precip=${newlyFetched.precipProbability}")
                        false
                    } else {
                        if (existing != null && newDataIsStrictlyBetter) {
                            appLogDao.log("SNAPSHOT_UPGRADE", "date=${newlyFetched.targetDate} source=$sourceId existing_high=${existing.highTemp} new_high=${newlyFetched.highTemp} existing_low=${existing.lowTemp} new_low=${newlyFetched.lowTemp}")
                        }
                        appLogDao.log("SNAPSHOT_SAVE", "date=${newlyFetched.targetDate} source=$sourceId")
                        true
                    }
                }

                if (sourceId == WeatherSource.NWS.id) {
                    appLogDao.log(
                        "NWS_BATCH_SAVE_SUMMARY",
                        buildNwsBatchSaveSummary(
                            batchFetchedAt = batchFetchedAt,
                            rawForecasts = weatherForecasts,
                            forecastsToSave = forecastsToSave,
                            changedForecasts = changedForecasts,
                        ),
                    )
                }
                
                if (changedForecasts.isNotEmpty()) {
                    // History cadence cap: collapse any earlier snapshot from this same bucket so the
                    // daily forecast timeline keeps at most one row per 4h (primary) / 8h (non-primary)
                    // window. The inserted rows carry the real fetchedAt, so current display and
                    // last-updated stay fresh; only intra-bucket duplicates are removed. See
                    // ForecastHistoryPolicy and ForecastEvolutionRenderer's SNAPSHOT_BUCKET_HOURS.
                    val primarySourceId = widgetStateManager.getPrimarySource().id
                    val bucketStart = ForecastHistoryPolicy.snapshotBucket(System.currentTimeMillis(), sourceId, primarySourceId)
                    val bucketEnd = bucketStart + ForecastHistoryPolicy.bucketMs(sourceId, primarySourceId)
                    forecastDao.deleteForecastsInBucket(
                        source = sourceId,
                        lat = latitude,
                        lon = longitude,
                        targetDates = changedForecasts.map { it.targetDate },
                        bucketStart = bucketStart,
                        bucketEnd = bucketEnd,
                    )
                    forecastDao.insertAll(changedForecasts)
                }
            }
        }

        private fun buildNwsBatchSaveSummary(
            batchFetchedAt: Long,
            rawForecasts: List<ForecastEntity>,
            forecastsToSave: List<ForecastEntity>,
            changedForecasts: List<ForecastEntity>,
        ): String {
            val rawMaxDate = rawForecasts.maxOfOrNull { it.targetDate }
            val filteredMaxDate = forecastsToSave.maxOfOrNull { it.targetDate }
            val savedMaxDate = changedForecasts.maxOfOrNull { it.targetDate }
            val terminalRow = forecastsToSave.maxByOrNull { it.targetDate }
            return "batch=$batchFetchedAt " +
                "rawCount=${rawForecasts.size} rawMaxDate=${formatEpochDate(rawMaxDate)} " +
                "filteredCount=${forecastsToSave.size} filteredMaxDate=${formatEpochDate(filteredMaxDate)} " +
                "savedCount=${changedForecasts.size} savedMaxDate=${formatEpochDate(savedMaxDate)} " +
                "terminal=${formatTerminalRow(terminalRow)}"
        }

        private fun formatEpochDate(epochMs: Long?): String =
            epochMs?.let { LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY).toString() } ?: "null"

        private fun formatTerminalRow(row: ForecastEntity?): String {
            if (row == null) return "null"
            return "${formatEpochDate(row.targetDate)} high=${row.highTemp} low=${row.lowTemp}"
        }

        private suspend fun fetchClimateNormalsGap(
            latitude: Double, 
            longitude: Double, 
            locationName: String, 
            lastCoveredDate: LocalDate, 
            targetDays: Int
        ): List<ForecastEntity> {
            val targetEndDate = LocalDate.now().plusDays(targetDays.toLong())
            var cursorDate = lastCoveredDate.plusDays(1)
            val normalsMap = getHistoricalNormalsByMonthDay(latitude, longitude)
            val gapEntities = mutableListOf<ForecastEntity>()
            
            while (!cursorDate.isAfter(targetEndDate)) {
                normalsMap[MonthDay.from(cursorDate)]?.let { (highTemp, lowTemp) ->
                    val dateEpoch = cursorDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY
                    gapEntities.add(
                        ForecastEntity(
                            targetDate = dateEpoch,
                            forecastDate = dateEpoch,
                            locationLat = latitude,
                            locationLon = longitude,
                            locationName = locationName,
                            highTemp = highTemp,
                            lowTemp = lowTemp,
                            condition = "Historical Avg",
                            isClimateNormal = true,
                            source = WeatherSource.GENERIC_GAP.id,
                        ),
                    )
                }
                cursorDate = cursorDate.plusDays(1)
            }
            return gapEntities
        }

        /**
         * Returns climate normals (a rough seasonal average high/low) for every calendar
         * day at this location, used as the future-day fallback when no real forecast
         * exists. Normals are derived by averaging several years of observed daily highs/lows
         * from the Open-Meteo historical archive into 12 monthly means, cached as 12 rows,
         * and expanded back to per-day values by interpolating between month midpoints.
         */
        suspend fun getHistoricalNormalsByMonthDay(latitude: Double, longitude: Double): Map<MonthDay, Pair<Float, Float>> {
            val locationKey = "${(latitude * 10).roundToInt() / 10.0}_${(longitude * 10).roundToInt() / 10.0}"
            val cachedNormals = climateNormalDao.getNormalsForLocation(locationKey)

            if (cachedNormals.isNotEmpty()) {
                val monthlyHigh = cachedNormals.associate { it.monthDay.take(2).toInt() to it.highTemp }
                val monthlyLow = cachedNormals.associate { it.monthDay.take(2).toInt() to it.lowTemp }
                return expandMonthlyToDaily(monthlyHigh, monthlyLow)
            }

            if (!widgetStateManager.isSourceVisible(WeatherSource.OPEN_METEO)) {
                appLogDao.log("CLIMATE_SKIP_DISABLED", "source=${WeatherSource.OPEN_METEO.id}")
                return emptyMap()
            }

            // Average a rolling 20-year window ending at the most recent complete year into 12
            // monthly means. Rolling (vs a fixed reference period) so it includes the latest
            // years and reflects the current climate, advancing automatically each year. Runs a
            // touch warmer than published 1991-2020 normals because of climate warming.
            val endYear = LocalDate.now().year - 1
            val startYear = endYear - 19
            val dailyTemps = openMeteoApi.getHistoricalDailyTemps(latitude, longitude, "$startYear-01-01", "$endYear-12-31")
            val byMonth = dailyTemps.groupBy { LocalDate.parse(it.date).monthValue }
            val monthlyHigh = mutableMapOf<Int, Float>()
            val monthlyLow = mutableMapOf<Int, Float>()
            for ((month, rows) in byMonth) {
                val highs = rows.map { it.highTemp }.filter { !it.isNaN() }
                val lows = rows.map { it.lowTemp }.filter { !it.isNaN() }
                if (highs.isNotEmpty()) monthlyHigh[month] = roundToTenth(highs.average())
                if (lows.isNotEmpty()) monthlyLow[month] = roundToTenth(lows.average())
            }

            if (monthlyHigh.isEmpty() || monthlyLow.isEmpty()) {
                appLogDao.log("CLIMATE_FETCH_EMPTY", "lat=$latitude lon=$longitude rows=${dailyTemps.size}")
                return emptyMap()
            }

            climateNormalDao.deleteOtherLocations(locationKey)
            climateNormalDao.insertAll(
                (1..12).mapNotNull { month ->
                    val high = monthlyHigh[month] ?: return@mapNotNull null
                    val low = monthlyLow[month] ?: return@mapNotNull null
                    ClimateNormalEntity(
                        monthDay = "${month.toString().padStart(2, '0')}-15",
                        locationKey = locationKey,
                        highTemp = high,
                        lowTemp = low,
                    )
                },
            )

            return expandMonthlyToDaily(monthlyHigh, monthlyLow)
        }

        private fun roundToTenth(value: Double): Float = (value * 10).roundToInt() / 10f

        /**
         * Expands 12 monthly means (keyed by month 1..12) into a value for every calendar
         * day by linear interpolation. Each month's mean is anchored at the 15th; days
         * between anchors are interpolated, wrapping across the Dec↔Jan boundary. Iterates a
         * leap year so Feb 29 is covered.
         */
        @VisibleForTesting
        internal fun expandMonthlyToDaily(
            monthlyHigh: Map<Int, Float>,
            monthlyLow: Map<Int, Float>,
        ): Map<MonthDay, Pair<Float, Float>> {
            if (monthlyHigh.isEmpty() || monthlyLow.isEmpty()) return emptyMap()

            val baseYear = 2020 // leap year so Feb 29 is covered
            val avgHigh = monthlyHigh.values.average().toFloat()
            val avgLow = monthlyLow.values.average().toFloat()

            data class Anchor(val date: LocalDate, val high: Float, val low: Float)
            fun anchorFor(year: Int, month: Int) =
                Anchor(LocalDate.of(year, month, 15), monthlyHigh[month] ?: avgHigh, monthlyLow[month] ?: avgLow)

            // Wrap-around neighbors ensure every day of baseYear sits between two anchors.
            val anchors = buildList {
                add(anchorFor(baseYear - 1, 12))
                for (m in 1..12) add(anchorFor(baseYear, m))
                add(anchorFor(baseYear + 1, 1))
            }.sortedBy { it.date }

            val result = mutableMapOf<MonthDay, Pair<Float, Float>>()
            var date = LocalDate.of(baseYear, 1, 1)
            val end = LocalDate.of(baseYear, 12, 31)
            while (!date.isAfter(end)) {
                val prev = anchors.last { !it.date.isAfter(date) }
                val next = anchors.first { !it.date.isBefore(date) }
                if (prev.date == next.date) {
                    result[MonthDay.from(date)] = prev.high to prev.low
                } else {
                    val span = ChronoUnit.DAYS.between(prev.date, next.date).toFloat()
                    val pos = ChronoUnit.DAYS.between(prev.date, date).toFloat() / span
                    result[MonthDay.from(date)] =
                        (prev.high + (next.high - prev.high) * pos) to (prev.low + (next.low - prev.low) * pos)
                }
                date = date.plusDays(1)
            }
            return result
        }

        private suspend fun saveHourlyEntities(entities: List<HourlyForecastEntity>) {
            if (entities.isEmpty()) return

            val minDateTime = entities.minOf { it.dateTime }
            val maxDateTime = entities.maxOf { it.dateTime }
            val sample = entities.first()
            val existingByDateTime = hourlyForecastDao.getHourlyForecastsBySource(
                minDateTime, maxDateTime, sample.locationLat, sample.locationLon, sample.source
            ).associateBy { it.dateTime }

            val mergedEntities = entities.map { newlyFetched ->
                mergePreservingNullableFields(existingByDateTime[newlyFetched.dateTime], newlyFetched)
            }

            val changedEntities = mergedEntities.filter { merged ->
                val existing = existingByDateTime[merged.dateTime]
                hasMeaningfulHourlyChange(existing, merged)
            }

            if (changedEntities.isNotEmpty()) {
                hourlyForecastDao.insertAll(changedEntities)
            }

            // Forecast-history snapshot: preserve the full predicted hourly curve as fetched, keyed by
            // its snapshot bucket. Within a bucket the PK (incl snapshotBucket) makes later fetches
            // REPLACE earlier ones, capping cadence at 4h (primary) / 8h (non-primary). The live table
            // above stays latest-only; this is the historical record. See ForecastHistoryPolicy.
            val primarySourceId = widgetStateManager.getPrimarySource().id
            val historyRows = mergedEntities.map { e ->
                HourlyForecastHistoryEntity(
                    dateTime = e.dateTime,
                    locationLat = e.locationLat,
                    locationLon = e.locationLon,
                    temperature = e.temperature,
                    condition = e.condition,
                    source = e.source,
                    snapshotBucket = ForecastHistoryPolicy.snapshotBucket(e.fetchedAt, e.source, primarySourceId),
                    precipProbability = e.precipProbability,
                    cloudCover = e.cloudCover,
                    precipAmountMm = e.precipAmountMm,
                    fetchedAt = e.fetchedAt,
                )
            }
            if (historyRows.isNotEmpty()) {
                hourlyForecastHistoryDao.insertAll(historyRows)
            }
        }
        private suspend fun saveHourlyEntitiesFromShared(
            hourlyData: List<HourlyForecast>,
            latitude: Double,
            longitude: Double,
            sourceId: String
        ) {
            val now = System.currentTimeMillis()
            val fetchedAt = now

            // 1. Save future data as forecasts (predictions). We filter out the past so we don't 
            // retroactively overwrite older predictions with newly fetched re-analysis history,
            // preserving the difference between what was forecast vs actuals.
            val futureData = hourlyData.filter { it.dateTime >= now - 3600_000L }
            saveHourlyEntities(futureData.map {
                HourlyForecastEntity(
                    it.dateTime, latitude, longitude, it.temperature, it.condition,
                    sourceId, it.precipProbability, it.cloudCover, it.precipAmountMm, fetchedAt
                )
            })

            // 2. Backfill past hours as observations to drive the "actuals" line for non-NWS
            // APIs on fresh installs or emulators. NOTE: this is the past slice of the source's
            // hourly data, not a station measurement — see precip handling below.
            saveHistoricalActuals(hourlyData, latitude, longitude, sourceId)
        }

        private suspend fun saveHistoricalActuals(
            hourlyData: List<HourlyForecast>,
            latitude: Double,
            longitude: Double,
            sourceId: String
        ) {
            val now = System.currentTimeMillis()
            val fetchedAt = System.currentTimeMillis()
            // Only persist past-day precip as a measured "actual" for sources whose backfilled
            // past hours are genuine actuals — a real history product (obs / history endpoint /
            // reanalysis archive) or, for Tomorrow.io, a fetch window capped at <24h so its past
            // hours are recent nowcast/analysis rather than stale forecast. Forecast-only sources
            // (Visual Crossing, OpenWeatherMap) would otherwise present their own past forecast as
            // a measurement — the same provenance bug we removed for NWS. Temperature backfill is
            // still kept; only measured precip is gated.
            val keepMeasuredPrecip = WeatherSource.fromId(sourceId).providesHistoricalActuals

            val historicalObs = hourlyData
                .filter { it.dateTime <= now }
                .map { hour ->
                    ObservationEntity(
                        stationId = "${sourceId}_MAIN",
                        stationName = "$sourceId: History Backfill",
                        timestamp = hour.dateTime,
                        temperature = hour.temperature,
                        condition = hour.condition,
                        locationLat = latitude,
                        locationLon = longitude,
                        distanceKm = 0.0f,
                        stationType = "OFFICIAL",
                        fetchedAt = fetchedAt,
                        api = sourceId,
                        precipAmountMm = if (keepMeasuredPrecip) hour.precipAmountMm else null
                    )
                }

            if (historicalObs.isNotEmpty()) {
                observationDao.insertAll(historicalObs)
            }
        }
        private suspend fun saveNwsHourlyForecasts(hourlyPeriods: List<NwsApi.HourlyForecastPeriod>, latitude: Double, longitude: Double) =
            saveHourlyEntities(hourlyPeriods.map { period ->
                HourlyForecastEntity(
                    period.startTime, latitude, longitude, period.temperature,
                    period.shortForecast, WeatherSource.NWS.id, period.precipProbability, period.cloudCover, period.precipAmountMm, System.currentTimeMillis()
                )
            })

        suspend fun getObservationsInRange(
            startTimestamp: Long,
            endTimestamp: Long,
            latitude: Double,
            longitude: Double,
        ): List<ObservationEntity> = observationDao.getObservationsInRange(startTimestamp, endTimestamp, latitude, longitude)

        suspend fun getCachedData(latitude: Double, longitude: Double) =
            forecastDao.getLatestForecastsInRange(LocalDate.now().minusDays(CACHE_LOOKBACK_DAYS).toEpochDay() * WidgetConstants.MS_IN_A_DAY, LocalDate.now().plusDays(CACHE_FORECAST_DAYS).toEpochDay() * WidgetConstants.MS_IN_A_DAY, latitude, longitude)

        suspend fun getCachedDataBySource(latitude: Double, longitude: Double, source: WeatherSource): List<ForecastEntity> {
            val startDate = LocalDate.now().minusDays(CACHE_LOOKBACK_DAYS).toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val endDate = LocalDate.now().plusDays(CACHE_FORECAST_DAYS).toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val gapData = forecastDao.getForecastsInRangeBySource(startDate, endDate, latitude, longitude, WeatherSource.GENERIC_GAP.id)
            val sourceData = forecastDao.getForecastsInRangeBySource(startDate, endDate, latitude, longitude, source.id)
            
            val latestBatchFetchedAt = sourceData.maxOfOrNull { it.batchFetchedAt }
            val liveSourceData = if (latestBatchFetchedAt != null) {
                sourceData.filter { it.batchFetchedAt == latestBatchFetchedAt }
            } else {
                emptyList()
            }

            if (source == WeatherSource.NWS) {
                appLogDao.log(
                    "NWS_BATCH_RENDER_SUMMARY",
                    "batch=$latestBatchFetchedAt liveCount=${liveSourceData.size} " +
                        "liveMinDate=${formatEpochDate(liveSourceData.minOfOrNull { it.targetDate })} " +
                        "liveMaxDate=${formatEpochDate(liveSourceData.maxOfOrNull { it.targetDate })}",
                )
            }

            val latestSourceByDate = liveSourceData.groupBy { it.targetDate }.mapValues { (_, rows) -> rows.first() }
            val latestGapByDate = gapData.groupBy { it.targetDate }.mapValues { (_, rows) -> rows.first() }

            return (latestGapByDate.keys + latestSourceByDate.keys)
                .sorted()
                .mapNotNull { date -> latestSourceByDate[date] ?: latestGapByDate[date] }
        }

        suspend fun getForecastForDate(date: Long, latitude: Double, longitude: Double) =
            forecastDao.getForecastForDate(date, latitude, longitude)

        suspend fun getForecastForDateBySource(date: Long, latitude: Double, longitude: Double, source: WeatherSource): ForecastEntity? =
            forecastDao.getForecastsInRangeBySource(date, date, latitude, longitude, source.id).firstOrNull()

        suspend fun getForecastsInRange(startDate: Long, endDate: Long, latitude: Double, longitude: Double) =
            forecastDao.getForecastsInRange(startDate, endDate, latitude, longitude)

        suspend fun getAllForecastsInRange(startDate: Long, endDate: Long, latitude: Double, longitude: Double) =
            forecastDao.getAllForecastsInRange(startDate, endDate, latitude, longitude)

        suspend fun getAllForecastsInRangeForSources(startDate: Long, endDate: Long, latitude: Double, longitude: Double, sources: List<String>) =
            forecastDao.getAllForecastsInRangeForSources(startDate, endDate, latitude, longitude, sources)

        suspend fun getLatestForecastsInRange(startDate: Long, endDate: Long, latitude: Double, longitude: Double) =
            forecastDao.getLatestForecastsInRange(startDate, endDate, latitude, longitude)

        suspend fun getLatestForecastsInRangeForSources(startDate: Long, endDate: Long, latitude: Double, longitude: Double, sources: List<String>) =
            forecastDao.getLatestForecastsInRangeForSources(startDate, endDate, latitude, longitude, sources)

        suspend fun cleanOldData() {
            val oneMonthAgoTimestamp = System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 30 // 30 days
            val sixDaysAgoTimestamp = System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 6 // 6 days
            val logsCutoffTimestamp = System.currentTimeMillis() - 1000L * 60 * 60 * 72 // 72 hours
            forecastDao.deleteOldForecasts(oneMonthAgoTimestamp)
            hourlyForecastDao.deleteOldForecasts(oneMonthAgoTimestamp)
            hourlyForecastHistoryDao.deleteOldHistory(oneMonthAgoTimestamp)
            observationDao.deleteOldObservations(sixDaysAgoTimestamp)
            dailyExtremeDao.deleteOldExtremes(oneMonthAgoTimestamp)
            appLogDao.deleteOldLogs(logsCutoffTimestamp)
        }
    }
