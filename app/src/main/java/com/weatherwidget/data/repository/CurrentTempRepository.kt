package com.weatherwidget.data.repository

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
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.OpenWeatherMapApi
import com.weatherwidget.data.remote.VisualCrossingApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.data.remote.SilurianApi
import com.weatherwidget.data.remote.TomorrowIoApi
import com.weatherwidget.shared.util.SpatialInterpolator
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.shared.actuals.TomorrowIoActuals
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        private val visualCrossingApi: VisualCrossingApi,
        private val weatherApi: WeatherApi,
        private val silurianApi: SilurianApi,
        private val widgetStateManager: WidgetStateManager,
        private val dailyHistoryDao: DailyHistoryDao,
        private val observationRepository: ObservationRepository,
        private val tomorrowIoApi: TomorrowIoApi? = null,
        private val openWeatherMapApi: OpenWeatherMapApi? = null,
    ) {
        private val syncMutex = Mutex()
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

            // Spatial-interpolation sample grid around the user (see getPointsOfInterest). ~0.072°
            // lat ≈ 8 km and ~0.09° lon ≈ 9-10 km at mid-latitudes.
            private const val POI_LAT_OFFSET_DEGREES = 0.072
            private const val POI_LON_OFFSET_DEGREES = 0.09

            fun appendHistoricalPoi(poiString: String, latitude: Double, longitude: Double, name: String): String {
                val poiStrings = poiString.split(";").filter { it.isNotEmpty() }.toMutableList()
                poiStrings.removeIf { it.contains("|$latitude|$longitude") }
                poiStrings.add(0, "$name|$latitude|$longitude")
                return poiStrings.take(3).joinToString(";")
            }

            fun parseHistoricalPois(poiString: String): List<Triple<Double, Double, String>> =
                poiString
                    .split(";")
                    .filter { it.isNotEmpty() }
                    .mapNotNull {
                        runCatching {
                            val parts = it.split("|")
                            Triple(parts[1].toDouble(), parts[2].toDouble(), parts[0])
                        }.getOrNull()
                    }
        }
        
        private val prefs by lazy { com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, "weather_prefs") }

        suspend fun refreshCurrentTemperature(
            latitude: Double, 
            longitude: Double, 
            locationName: String, 
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

                    TomorrowIoLegacyActualsCleanup.runIfNeeded(
                        context = context,
                        observationDao = observationDao,
                        dailyHistoryDao = dailyHistoryDao,
                        appLogDao = appLogDao,
                    )

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
                    
                    recordHistoricalPoi(latitude, longitude, locationName)
                    val enabledSources = widgetStateManager.getVisibleSourcesOrder()
                    // Rank = position in the visible order; used to throttle low-priority sources.
                    val rankBySource = enabledSources.withIndex().associate { (index, src) -> src to index }
                    // An explicit single-source request (user toggled to it) or a forced refresh
                    // bypasses the low-priority throttle so the displayed source stays fresh.
                    val bypassThrottle = forceRefresh || source != null
                    val candidateSources = (source?.let { requested ->
                        if (requested in enabledSources) listOf(requested) else emptyList()
                    } ?: enabledSources)
                        // This loop is specifically for observations that may correct the header.
                        // Forecast-only providers (Open-Meteo and Silurian) are refreshed by the
                        // normal forecast cycle and must never drive an observation correction.
                        // NOT relaxed for borrowing sources. This gate governs which sources may
                        // WRITE observations, and a forecast-only source must never file its own
                        // model current as one — that is the circular actuals problem borrowing
                        // exists to replace, guarded by WeatherRepositoryTest. Borrowing happens
                        // purely on the READ side, via ObservationSourceMatcher.matchesActualSource.
                        .filter { it.supportsTemperatureActuals }
                        .distinct()

                    // Sources currently displayed on a widget are never throttled — "priority" means
                    // the source the user is actually viewing, not just the global first-in-order one.
                    val activeSourceIds = widgetStateManager.getActiveDisplaySourceIds()
                    val targetSources = candidateSources.filter { src ->
                        val rank = rankBySource[src] ?: 0
                        val throttled = !bypassThrottle &&
                            src.id !in activeSourceIds &&
                            rank >= LOW_PRIORITY_RANK_THRESHOLD &&
                            !widgetStateManager.shouldFetchCurrentTempForSource(src.id, LOW_PRIORITY_CURRENT_TEMP_INTERVAL_MS)
                        !throttled
                    }
                    val skipped = candidateSources - targetSources.toSet()
                    if (skipped.isNotEmpty()) {
                        appLogDao.log(
                            "CURR_FETCH_THROTTLE_SKIP",
                            "reason=$reason throttled=${skipped.joinToString { "${it.id}@rank${rankBySource[it]}" }} intervalMs=$LOW_PRIORITY_CURRENT_TEMP_INTERVAL_MS",
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
                            widgetStateManager.recordSourceFetchFailure(targetSource, extractCurrentErrorCode(exception))
                            logCurrentFetchFailure(targetSource, exception)
                            logCurrentSourceResult(reason, targetSource, null, exception, durationMs = sourceDurationMs)
                        } catch (exception: Exception) {
                            val sourceDurationMs = SystemClock.elapsedRealtime() - sourceStartMs
                            val errorCode = extractCurrentErrorCode(exception)
                            widgetStateManager.recordSourceFetchFailure(targetSource, errorCode)
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
                WeatherSource.OPEN_WEATHER_MAP -> fetchOpenWeatherMapCurrent(latitude, longitude)
                WeatherSource.VISUAL_CROSSING -> fetchVisualCrossingCurrent(latitude, longitude)
                WeatherSource.OPEN_METEO -> fetchOpenMeteoCurrent(latitude, longitude)
                WeatherSource.WEATHER_API -> fetchWeatherApiCurrent(latitude, longitude)
                WeatherSource.NWS -> observationRepository.fetchNwsCurrent(latitude, longitude)
                WeatherSource.SILURIAN -> fetchSilurianCurrent(latitude, longitude)
                WeatherSource.TOMORROW_IO -> fetchTomorrowIoCurrent(latitude, longitude)
                else -> null
            }

        private suspend fun fetchOpenWeatherMapCurrent(latitude: Double, longitude: Double): CurrentReadingPayload? = coroutineScope {
            val api = openWeatherMapApi ?: return@coroutineScope null
            fetchForecastCurrent(
                source = WeatherSource.OPEN_WEATHER_MAP,
                stationPrefix = "OPEN_WEATHER_MAP",
                stationLabelPrefix = "OWM",
                latitude = latitude,
                longitude = longitude,
            ) { lat, lon -> api.getForecast(lat, lon) }
        }

        private suspend fun fetchForecastCurrent(
            source: WeatherSource,
            stationPrefix: String,
            stationLabelPrefix: String,
            latitude: Double,
            longitude: Double,
            fetch: suspend (Double, Double) -> RawFetch,
        ): CurrentReadingPayload? = coroutineScope {
            val pointsOfInterest = getPointsOfInterest(latitude, longitude)
            val deferredResults = pointsOfInterest.mapIndexed { index, point ->
                async {
                    val result = try {
                        fetch(point.first, point.second)
                    } catch (e: ApiAccessException) {
                        throw e
                    } catch (e: ClientRequestException) {
                        checkAndRethrowFailure(source, e)
                        null
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    val currentTemp = result?.providerCurrentTemp
                    if (result != null && currentTemp != null) {
                        val stationId = if (point.third == "Current") "${stationPrefix}_MAIN" else "${stationPrefix}_$index"
                        val condition = result.providerCurrentCondition ?: "Unknown"
                        val obsEntity = ObservationEntity(
                            stationId,
                            "$stationLabelPrefix: ${point.third}",
                            result.providerCurrentObservedAt ?: System.currentTimeMillis(),
                            currentTemp,
                            condition,
                            point.first,
                            point.second,
                            calculateDistance(latitude, longitude, point.first, point.second) / 1000f,
                            "OFFICIAL",
                            api = source.id,
                        )
                        insertCurrentObservation(obsEntity)
                    }
                    result
                }
            }.map { it.await() }

            deferredResults.firstNotNullOfOrNull { result ->
                val forecastResult = result ?: return@firstNotNullOfOrNull null
                val currentTemp = forecastResult.providerCurrentTemp
                if (currentTemp != null) {
                    CurrentReadingPayload(source, currentTemp, forecastResult.providerCurrentCondition, forecastResult.providerCurrentObservedAt)
                } else {
                    null
                }
            }
        }

        private suspend fun fetchVisualCrossingCurrent(latitude: Double, longitude: Double): CurrentReadingPayload? = coroutineScope {
            fetchForecastCurrent(
                source = WeatherSource.VISUAL_CROSSING,
                stationPrefix = "VISUAL_CROSSING",
                stationLabelPrefix = "VisCr",
                latitude = latitude,
                longitude = longitude,
            ) { lat, lon -> visualCrossingApi.getForecast(lat, lon) }
        }

        private suspend fun fetchOpenMeteoCurrent(latitude: Double, longitude: Double): CurrentReadingPayload? = coroutineScope {
            val pointsOfInterest = getPointsOfInterest(latitude, longitude)
            val deferredReadings = pointsOfInterest.mapIndexed { index, point ->
                async {
                    val reading = try {
                        openMeteoApi.getCurrent(point.first, point.second)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (reading != null) {
                        val condition = reading.weatherCode?.let { openMeteoApi.weatherCodeToCondition(it) } ?: "Unknown"
                        val stationId = if (point.third == "Current") "OPEN_METEO_MAIN" else "OPEN_METEO_$index"
                        val obsEntity = ObservationEntity(
                            stationId = stationId,
                            stationName = "Meteo: ${point.third}",
                            timestamp = reading.observedAt ?: System.currentTimeMillis(),
                            temperature = reading.temperature,
                            condition = condition,
                            locationLat = point.first,
                            locationLon = point.second,
                            distanceKm = calculateDistance(latitude, longitude, point.first, point.second) / 1000f,
                            stationType = "OFFICIAL",
                            api = WeatherSource.OPEN_METEO.id,
                            cloudCover = reading.cloudCover,
                            cloudCoverLow = reading.cloudCoverLow,
                        )
                        insertCurrentObservation(obsEntity)
                    }
                    reading
                }
            }.map { it.await() }

            deferredReadings.firstNotNullOfOrNull { it }?.let { reading ->
                CurrentReadingPayload(
                    WeatherSource.OPEN_METEO,
                    reading.temperature,
                    reading.weatherCode?.let { openMeteoApi.weatherCodeToCondition(it) },
                    reading.observedAt,
                )
            }
        }

        private suspend fun fetchSilurianCurrent(latitude: Double, longitude: Double): CurrentReadingPayload? = coroutineScope {
            fetchForecastCurrent(
                source = WeatherSource.SILURIAN,
                stationPrefix = "SILURIAN",
                stationLabelPrefix = "Silurian",
                latitude = latitude,
                longitude = longitude,
            ) { lat, lon -> silurianApi.getForecast(lat, lon) }
        }
        private suspend fun fetchWeatherApiCurrent(latitude: Double, longitude: Double): CurrentReadingPayload? = coroutineScope {
            fetchForecastCurrent(
                source = WeatherSource.WEATHER_API,
                stationPrefix = "WEATHER_API",
                stationLabelPrefix = "WAPI",
                latitude = latitude,
                longitude = longitude,
            ) { lat, lon -> weatherApi.getForecast(lat, lon) }
        }

        private suspend fun fetchTomorrowIoCurrent(latitude: Double, longitude: Double): CurrentReadingPayload? {
            val api = tomorrowIoApi ?: return null
            val reading = try {
                api.getRealtime(latitude, longitude)
            } catch (e: ApiAccessException) {
                throw e
            } catch (e: ClientRequestException) {
                checkAndRethrowFailure(WeatherSource.TOMORROW_IO, e)
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return null

            insertCurrentObservation(
                ObservationEntity(
                    stationId = TomorrowIoActuals.REALTIME_STATION_ID,
                    stationName = TomorrowIoActuals.REALTIME_STATION_NAME,
                    timestamp = reading.observedAt,
                    temperature = reading.temperature,
                    condition = reading.condition,
                    locationLat = latitude,
                    locationLon = longitude,
                    distanceKm = 0f,
                    stationType = "OFFICIAL",
                    api = WeatherSource.TOMORROW_IO.id,
                    cloudCover = reading.cloudCover,
                ),
            )
            return CurrentReadingPayload(
                source = WeatherSource.TOMORROW_IO,
                temperature = reading.temperature,
                condition = reading.condition,
                observedAt = reading.observedAt,
            )
        }

        /**
         * Five fetch points for the current temperature: the user's site plus four offset samples.
         * Each is filed as its own stationId — the centre as `<SOURCE>_MAIN` (the synthetic backfill
         * ID), the offsets as `<SOURCE>_<index>` — so the IDW blend can spatially interpolate. The
         * offsets are deliberately NOT flagged synthetic (see
         * ObservationSourceMatcher.isSyntheticBackfillStation): they rank as real "sites" in the
         * blend and surface as POIs in the stations list, while the centre row is the synthetic
         * backfill and is deprioritised against them.
         */
        private fun getPointsOfInterest(latitude: Double, longitude: Double): List<Triple<Double, Double, String>> =
            listOf(
                Triple(latitude, longitude, "Current"),
                Triple(latitude + POI_LAT_OFFSET_DEGREES, longitude, "North"),
                Triple(latitude - POI_LAT_OFFSET_DEGREES, longitude, "South"),
                Triple(latitude, longitude + POI_LON_OFFSET_DEGREES, "East"),
                Triple(latitude, longitude - POI_LON_OFFSET_DEGREES, "West"),
            )

        private fun extractCurrentErrorCode(exception: Exception): String = when (exception) {
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

        private suspend fun checkAndRethrowFailure(source: WeatherSource, exception: ClientRequestException) {
            val status = exception.response.status.value
            if (status == 429) {
                val detail = extractHttpErrorDetail(
                    runCatching { exception.response.bodyAsText() }.getOrNull(),
                    exception.message,
                )
                throw ApiAccessException(
                    source = source,
                    statusCode = 429,
                    detail = detail,
                    message = "${source.displayName} 429 rate limit. $detail",
                )
            }
            if (status == 401) {
                val detail = extractHttpErrorDetail(
                    runCatching { exception.response.bodyAsText() }.getOrNull(),
                    exception.message,
                )
                throw ApiAccessException(
                    source = source,
                    statusCode = 401,
                    detail = detail,
                    message = "${source.displayName} 401 error. $detail",
                )
            }
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

        @androidx.annotation.VisibleForTesting
        internal fun recordHistoricalPoi(latitude: Double, longitude: Double, name: String) {
            val current = prefs.getString("historical_pois", "") ?: ""
            val updated = appendHistoricalPoi(current, latitude, longitude, name)
            prefs.edit().putString("historical_pois", updated).apply()
        }

        @androidx.annotation.VisibleForTesting
        internal fun getHistoricalPois(): List<Triple<Double, Double, String>> =
            parseHistoricalPois(prefs.getString("historical_pois", "") ?: "")

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
