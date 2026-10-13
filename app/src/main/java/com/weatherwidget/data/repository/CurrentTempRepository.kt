package com.weatherwidget.data.repository

import com.weatherwidget.data.remote.FetchErrorCode
import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.withQuantizedLocation
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.ApiKeyRedaction
import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.data.remote.OpenWeatherMapApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.data.remote.TomorrowIoApi
import com.weatherwidget.shared.observations.CurrentConditionsFetcher
import com.weatherwidget.shared.util.ActualsFeedPolicy
import com.weatherwidget.shared.util.SpatialInterpolator
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.util.NetworkRestrictionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val TAG = "CurrentTempRepository"

@Singleton
class CurrentTempRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val observationDao: ObservationDao,
        private val hourlyForecastDao: HourlyForecastDao,
        private val appLogDao: AppLogDao,
        private val nwsApi: NwsApi,
        private val openMeteoApi: OpenMeteoApi,
        private val weatherApi: WeatherApi,
        private val widgetStateManager: WidgetStateManager,
        private val dailyHistoryDao: DailyHistoryDao,
        private val observationRepository: ObservationRepository,
        private val tomorrowIoApi: TomorrowIoApi? = null,
        private val openWeatherMapApi: OpenWeatherMapApi? = null,
    ) {
        private val syncMutex = Mutex()

        /** The model feeds' current conditions, shared with desktop (plans/261013-shared-current-conditions-fetch.md). */
        private val currentConditions = CurrentConditionsFetcher.of(openMeteoApi, weatherApi, openWeatherMapApi, tomorrowIoApi)
        companion object {
            private val logTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
            private fun formatLogTime(ms: Long?): String = if (ms == null) "none" else logTimeFormatter.format(Instant.ofEpochMilli(ms))

            private const val CURRENT_TEMP_FRESHNESS_MS = 300_000L // 5 minutes
            private const val MAX_RETRIES = 5

            // Sources ranked 4th or lower in the visible order are "low priority": their current
            // temp is network-fetched at most once an hour instead of on every charging-loop cycle
            // (~10 min). This conserves tight free-plan API quotas (e.g. Tomorrow.io ~25 req/hr).
            // Index is 0-based, so rank >= 3 means the 4th source onward. A source currently
            // displayed on a widget is always exempt from this throttle regardless of its rank, so
            // the source the user is viewing stays fresh even if it sits low in the global order.
            private const val LOW_PRIORITY_RANK_THRESHOLD = 3
            private const val LOW_PRIORITY_CURRENT_TEMP_INTERVAL_MS = 3_600_000L // 60 minutes
        }

        suspend fun refreshCurrentTemperature(
            latitude: Double, 
            longitude: Double, 
            source: WeatherSource? = null, 
            reason: String = "unspecified", 
            forceRefresh: Boolean = false
        ): Result<Int> {
            val mutexRequestMs = SystemClock.elapsedRealtime()
            val startMs = System.currentTimeMillis()
            return try {
                syncMutex.withLock {
                    val mutexAcquiredMs = SystemClock.elapsedRealtime()
                    val mutexWaitMs = mutexAcquiredMs - mutexRequestMs
                    if (mutexWaitMs > 100) {
                        appLogDao.log("CURR_FETCH_MUTEX", "reason=$reason waitMs=$mutexWaitMs", "WARN")
                    }

                    val currentTime = System.currentTimeMillis()
                    // Location-scoped: a location handoff must not inherit the previous site's
                    // cooldown. The same source fetched 2 minutes ago at an old site is still stale
                    // for the new one, so gate on this location's last fetch, not the global one.
                    val lastFetchTime = FetchMetadata.getLastCurrentTempFetchTime(context, latitude, longitude)
                    if (!forceRefresh && currentTime - lastFetchTime < CURRENT_TEMP_FRESHNESS_MS) {
                        appLogDao.log(
                            "CURR_FETCH_FRESH_SKIP",
                            "reason=$reason ageMs=${currentTime - lastFetchTime} freshnessMs=$CURRENT_TEMP_FRESHNESS_MS lastFetchMs=$lastFetchTime",
                            "INFO",
                        )
                        return Result.success(0)
                    }
                    
                    val enabledSources = widgetStateManager.getVisibleSourcesOrder()
                    // Rank = position in the visible order; used to throttle low-priority sources.
                    val rankBySource = enabledSources.withIndex().associate { (index, src) -> src to index }
                    // An explicit single-source request (user toggled to it) or a forced refresh
                    // bypasses the low-priority throttle so the displayed source stays fresh.
                    val bypassThrottle = forceRefresh || source != null
                    val requestedSources = source?.let { requested ->
                        if (requested in enabledSources) listOf(requested) else emptyList()
                    } ?: enabledSources
                    // Which observation feeds to fetch is shared with desktop (ActualsFeedPolicy).
                    // A forecast-only source is never fetched as its own feed: it must never file
                    // its model current as an observation, the circular-actuals problem borrowing
                    // exists to replace (guarded by WeatherRepositoryTest). What it gets instead is
                    // its provider's feed, here even when that feed is not itself visible: Google
                    // Weather borrowing NWS used to fetch nothing here.
                    val feedConsumers = ActualsFeedPolicy.currentTempFeeds(requestedSources, latitude, longitude)
                    val candidateSources = feedConsumers.keys.toList()

                    // Sources currently displayed on a widget are never throttled — "priority" means
                    // the source the user is actually viewing, not just the global first-in-order one.
                    // A feed inherits the priority of the best-placed source it serves.
                    val activeSourceIds = widgetStateManager.getActiveDisplaySourceIds()
                    val targetSources = candidateSources.filter { src ->
                        val consumers = feedConsumers.getValue(src)
                        val rank = consumers.minOf { rankBySource[it] ?: 0 }
                        val throttled = !bypassThrottle &&
                            consumers.none { it.id in activeSourceIds } &&
                            rank >= LOW_PRIORITY_RANK_THRESHOLD &&
                            !widgetStateManager.shouldFetchCurrentTempForSource(src.id, LOW_PRIORITY_CURRENT_TEMP_INTERVAL_MS)
                        !throttled
                    }
                    val skipped = candidateSources - targetSources.toSet()
                    if (skipped.isNotEmpty()) {
                        appLogDao.log(
                            "CURR_FETCH_THROTTLE_SKIP",
                            "reason=$reason throttled=${skipped.joinToString { "${it.id}@rank${feedConsumers.getValue(it).minOf { c -> rankBySource[c] ?: 0 }}" }} intervalMs=$LOW_PRIORITY_CURRENT_TEMP_INTERVAL_MS",
                            "INFO",
                        )
                    }

                    appLogDao.log("CURR_FETCH_START", "reason=$reason targets=${targetSources.joinToString { it.id }} thread=${Thread.currentThread().name}")
                    
                    var successfulSourceCount = 0
                    targetSources.forEach { targetSource ->
                        val sourceStartMs = SystemClock.elapsedRealtime()
                        // Record the attempt up front so the low-priority throttle window starts
                        // even when the fetch fails (e.g. a 429) — this prevents rapid retries
                        // from burning a tight quota. Only consulted for rank >= 4 sources.
                        widgetStateManager.markCurrentTempFetched(targetSource.id)
                        try {
                            val reading = fetchFromSource(targetSource, latitude, longitude)
                            val sourceDurationMs = SystemClock.elapsedRealtime() - sourceStartMs
                            if (reading != null) {
                                successfulSourceCount += 1
                                widgetStateManager.recordSourceFetchSuccess(targetSource)
                            }
                            logCurrentSourceResult(reason, targetSource, reading, durationMs = sourceDurationMs)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (exception: ApiAccessException) {
                            val sourceDurationMs = SystemClock.elapsedRealtime() - sourceStartMs
                            widgetStateManager.recordSourceFetchFailure(
                                targetSource,
                                extractCurrentErrorCode(exception),
                                ApiKeyRedaction.redact(exception.message),
                            )
                            logCurrentFetchFailure(targetSource, exception)
                            logCurrentSourceResult(reason, targetSource, null, exception, durationMs = sourceDurationMs)
                        } catch (exception: Exception) {
                            val sourceDurationMs = SystemClock.elapsedRealtime() - sourceStartMs
                            val errorCode = extractCurrentErrorCode(exception)
                            widgetStateManager.recordSourceFetchFailure(
                                targetSource,
                                errorCode,
                                ApiKeyRedaction.redact(exception.message ?: exception.javaClass.simpleName),
                            )
                            logCurrentFetchFailure(targetSource, exception)
                            logCurrentSourceResult(reason, targetSource, null, exception, durationMs = sourceDurationMs)
                        }
                    }
                    
                    FetchMetadata.setLastCurrentTempFetchTime(context, latitude, longitude, System.currentTimeMillis())
                    val totalDurationMs = System.currentTimeMillis() - startMs
                    val targetIds = targetSources.joinToString(",") { it.id }
                    appLogDao.log("CURR_FETCH_COMPLETE", "reason=$reason successCount=$successfulSourceCount totalMs=$totalDurationMs targets=$targetIds attempted=${targetSources.size}")
                    Result.success(targetSources.size)
                }
            } catch (exception: Exception) {
                val totalDurationMs = System.currentTimeMillis() - startMs
                appLogDao.logException("CURR_FETCH_ERROR", "Current temp fetch orchestrator failed (reason=$reason, totalMs=$totalDurationMs)", exception)
                Result.failure(exception)
            }
        }

        private suspend fun fetchFromSource(source: WeatherSource, latitude: Double, longitude: Double): CurrentReadingPayload? =
            when (source) {
                WeatherSource.NWS -> observationRepository.fetchNwsCurrent(latitude, longitude)
                in CurrentConditionsFetcher.FEEDS -> fetchCurrentConditions(source, latitude, longitude)
                else -> null
            }

        /**
         * A model feed through the shared [CurrentConditionsFetcher]; this side stores the rows and logs.
         * A failed request the provider did not refuse is "no reading", not a source failure, as before
         * the move: only an [ApiAccessException] reaches the failure record (Phase 3 sets one rule).
         */
        private suspend fun fetchCurrentConditions(feed: WeatherSource, latitude: Double, longitude: Double): CurrentReadingPayload? {
            val result = try {
                currentConditions.fetch(feed, latitude, longitude)
            } catch (e: ApiAccessException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return null
            val entities = result.readings.map { it.toEntity() }
            if (feed == WeatherSource.TOMORROW_IO) {
                storeTomorrowIoHistory(entities, latitude, longitude)
            } else {
                entities.forEach { insertCurrentObservation(it) }
            }
            return result.current?.let { CurrentReadingPayload(it.source, it.temperature, it.condition, it.observedAt) }
        }

        /** Tomorrow.io's five-minute window: one batch, with how many rows it replaced, then the retired-product cleanup. */
        private suspend fun storeTomorrowIoHistory(entities: List<ObservationEntity>, latitude: Double, longitude: Double) {
            val replacementCount = if (entities.isEmpty()) {
                0
            } else {
                val sample = entities.first()
                val existingKeys = observationDao.getObservationsInRange(
                    entities.minOf { it.timestamp },
                    entities.maxOf { it.timestamp },
                    sample.locationLat,
                    sample.locationLon,
                    listOf(WeatherSource.TOMORROW_IO.id),
                ).asSequence()
                    .filter {
                        it.locationLat == sample.locationLat &&
                            it.locationLon == sample.locationLon
                    }
                    .map { Triple(it.stationId, it.timestamp, it.api) }
                    .toSet()
                entities.count { Triple(it.stationId, it.timestamp, it.api) in existingKeys }
            }
            if (entities.isNotEmpty()) observationDao.insertAll(entities)
            appLogDao.log(
                "TMRW_5M_FETCH",
                "windowHours=${TomorrowIoApi.INCREMENTAL_ACTUALS_LOOKBACK_HOURS} " +
                    "rows=${entities.size} earliest=${entities.minOfOrNull { it.timestamp }} " +
                    "latest=${entities.maxOfOrNull { it.timestamp }} replacements=$replacementCount",
                "INFO",
            )
            RetiredProductCleanupRunner.runFor(
                api = WeatherSource.TOMORROW_IO.id,
                latitude = latitude,
                longitude = longitude,
                observationDao = observationDao,
                dailyHistoryDao = dailyHistoryDao,
                appLogDao = appLogDao,
            )
        }

        private fun extractCurrentErrorCode(exception: Exception): String =
            FetchErrorCode.of(exception) { NetworkRestrictionHelper.isBackgroundDataRestricted(context) }

        private suspend fun logCurrentFetchFailure(source: WeatherSource, exception: Exception) {
            when (exception) {
                is ApiAccessException -> {
                    val code = exception.statusCode?.let { "HTTP_$it" } ?: "ACCESS_ERROR"
                    appLogDao.log("CURR_FETCH_ERROR", "source=${source.id} code=$code detail=${exception.detail}", "WARN")
                }
                is ClientRequestException -> {
                    val statusCode = exception.response.status.value
                    val detail = extractHttpErrorDetail(
                        runCatching { exception.response.bodyAsText() }.getOrNull(),
                        exception.message,
                    )
                    appLogDao.log("CURR_FETCH_ERROR", "source=${source.id} code=HTTP_$statusCode detail=$detail", "WARN")
                }
                else -> appLogDao.log("CURR_FETCH_ERROR", "source=${source.id} error=${exception.message}", "WARN")
            }
        }

        private suspend fun logCurrentSourceResult(
            reason: String,
            source: WeatherSource,
            reading: CurrentReadingPayload?,
            exception: Exception? = null,
            durationMs: Long = 0,
        ) {
            val nowMs = System.currentTimeMillis()
            val observedAgeMin = reading?.observedAt?.let { (nowMs - it) / 60_000L }
            val errorSummary = exception?.let { " error=${it.javaClass.simpleName}:${it.message ?: "no_message"}" }.orEmpty()
            val observedTimeStr = formatLogTime(reading?.observedAt)
            val fetchTimeStr = formatLogTime(nowMs)
            appLogDao.log(
                "CURR_FETCH_SOURCE_RESULT",
                "reason=$reason source=${source.id} success=${reading != null} " +
                    "temp=${reading?.temperature ?: "none"} observedAt=$observedTimeStr " +
                    "fetchedAt=$fetchTimeStr " +
                    "observedAgeMin=${observedAgeMin ?: "none"} condition=${reading?.condition ?: "none"} " +
                    "durationMs=$durationMs$errorSummary",
                if (reading != null) "INFO" else "WARN",
            )

            if (reading != null) {
                appLogDao.log(
                    "CURRENT_TEMP_STATUS",
                    "source=${source.id} ok=true",
                    "INFO",
                )
            } else {
                appLogDao.log(
                    "CURRENT_TEMP_STATUS",
                    "source=${source.id} ok=false class=${exception?.javaClass?.simpleName ?: "Unknown"} detail=${exception?.message ?: "unknown"}",
                    "WARN",
                )
            }
        }

        private suspend fun insertCurrentObservation(obsEntity: ObservationEntity) {
            // Snap the device location to the shared write-key grid so Open-Meteo/Tomorrow current
            // obs key the same site as NWS instead of a raw-double fragment (plan 260721, Fix A).
            val quantized = obsEntity.withQuantizedLocation()
            observationDao.insertAll(listOf(quantized))
            logCurrentObservationInsert(quantized)
        }

        private suspend fun logCurrentObservationInsert(obsEntity: ObservationEntity) {
            val nowMs = System.currentTimeMillis()
            val observedTimeStr = formatLogTime(obsEntity.timestamp)
            val fetchTimeStr = formatLogTime(obsEntity.fetchedAt)
            appLogDao.log(
                "OBS_CURRENT_INSERT",
                "source=${obsEntity.api} station=${obsEntity.stationId} timestamp=$observedTimeStr " +
                    "fetchedAt=$fetchTimeStr temp=${obsEntity.temperature} " +
                    "timestampAgeMin=${(nowMs - obsEntity.timestamp) / 60_000L} " +
                    "fetchAgeMin=${(nowMs - obsEntity.fetchedAt) / 60_000L}",
                "INFO",
            )
        }

        private fun extractHttpErrorDetail(body: String?, fallbackMessage: String?): String {
            val bodyText = body?.trim().orEmpty()
            val messageMatch = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(bodyText)?.groupValues?.getOrNull(1)
            val errorMatch = Regex("\"error\"\\s*:\\s*\\{[^}]*\"message\"\\s*:\\s*\"([^\"]+)\"").find(bodyText)?.groupValues?.getOrNull(1)
            return messageMatch ?: errorMatch ?: fallbackMessage ?: "Request failed"
        }

        suspend fun getInterpolatedTemperature(
            latitude: Double,
            longitude: Double,
            time: LocalDateTime = LocalDateTime.now(),
        ): Float? {
            val zoneId = ZoneId.systemDefault()
            val startMs = time.minusHours(3).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
            val endMs = time.plusHours(3).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
            val hourlyForecasts = hourlyForecastDao.getHourlyForecasts(startMs, endMs, latitude, longitude)

            return if (hourlyForecasts.isEmpty()) null
            else TemperatureInterpolator.getInterpolatedTemperature(
                hourlyForecasts.map { it.toHourlyForecast() },
                time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            )
        }

        suspend fun getNextInterpolationUpdateTime(
            latitude: Double,
            longitude: Double,
            time: LocalDateTime = LocalDateTime.now(),
        ): LocalDateTime {
            val zoneId = ZoneId.systemDefault()
            val currentHourMs = time.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .atZone(zoneId).toInstant().toEpochMilli()
            val nextHourMs = time.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1)
                .atZone(zoneId).toInstant().toEpochMilli()
            val forecasts = hourlyForecastDao.getHourlyForecasts(currentHourMs, nextHourMs, latitude, longitude)
            
            val tempDiff = if (forecasts.size >= 2) (forecasts[1].temperature - forecasts[0].temperature).toInt() else 0
            val nextEpochMs = TemperatureInterpolator.getNextUpdateTime(time.atZone(zoneId).toInstant().toEpochMilli(), tempDiff)
            return Instant.ofEpochMilli(nextEpochMs).atZone(zoneId).toLocalDateTime()
        }

        fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val results = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            return results[0]
        }
    }

internal data class CurrentReadingPayload(
    val source: WeatherSource, 
    val temperature: Float, 
    val condition: String?, 
    val observedAt: Long?
)
