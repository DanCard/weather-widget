package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.OpenWeatherMapApi
import com.weatherwidget.data.remote.SilurianApi
import com.weatherwidget.data.remote.TomorrowIoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.widget.ForecastFetchContext
import com.weatherwidget.widget.ForecastFetchPolicy
import com.weatherwidget.widget.ForecastStalenessPolicy
import com.weatherwidget.widget.WidgetStateManager
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

/**
 * Selects, fetches, classifies, and persists provider results.
 */
internal class ForecastFetchCoordinator(
    private val context: Context,
    private val appLogDao: AppLogDao,
    private val openMeteoApi: OpenMeteoApi,
    private val weatherApi: WeatherApi,
    private val silurianApi: SilurianApi,
    private val widgetStateManager: WidgetStateManager,
    private val tomorrowIoApi: TomorrowIoApi?,
    private val openWeatherMapApi: OpenWeatherMapApi?,
    private val nwsForecastMapper: NwsForecastMapper,
    private val snapshotStore: ForecastSnapshotStore,
    private val hourlyStore: HourlyForecastStore,
    private val weatherApiHistoryBackfiller: WeatherApiHistoryBackfiller,
    private val nwsApiDailyActualsFetcher: NwsApiDailyActualsFetcher?,
    private val hourlyForecastHistoryDao: com.weatherwidget.data.local.HourlyForecastHistoryDao? = null,
) {
    /**
     * Fetches the day-ago cloud predictions backing the cloud graph's frozen forecast curve.
     *
     * Throttled to once an hour: the prediction made for an already-elapsed hour never changes, so
     * refetching it every cycle would spend a call to rewrite identical rows. Best-effort — any
     * failure leaves the graph drawing the live value on both curves, which is honest (it marks
     * itself unfrozen) rather than broken.
     */
    private suspend fun fetchPriorDayCloudForecast(latitude: Double, longitude: Double) {
        val dao = hourlyForecastHistoryDao ?: return
        val now = System.currentTimeMillis()
        if (now - lastPriorCloudFetchMs < PRIOR_CLOUD_FETCH_INTERVAL_MS) return
        lastPriorCloudFetchMs = now
        try {
            val byHour = openMeteoApi.getPriorDayCloudForecast(
                latitude, longitude, PRIOR_CLOUD_PAST_DAYS, now,
            )
            if (byHour.isEmpty()) {
                // Loud on purpose. An empty result is indistinguishable at the render from a
                // healthy graph: nulls are omitted rather than zeroed (correctly), so
                // CloudSeriesBuilder falls back to the live row with isFrozen = false and still
                // draws a continuous, plausible curve — a hindcast wearing the forecast's clothes.
                // Measured 2026-08-27: the Previous Runs API serves all-nulls for
                // cloud_cover_low_previous_day1 at every location probed, and has never served it —
                // the newest stored OPEN_METEO_PRIOR24 row is from 90 minutes BEFORE the commit
                // that started requesting it. Seven days of nothing, and nothing anywhere said so.
                appLogDao.log(
                    "PRIOR_CLOUD_EMPTY",
                    "no usable hours from ${OpenMeteoApi.PREVIOUS_RUNS_VARIABLE}; " +
                        "frozen forecast curve falls back to live values",
                )
                return
            }
            dao.insertAll(
                byHour.map { (hourMs, cover) ->
                    com.weatherwidget.data.local.HourlyForecastHistoryEntity(
                        dateTime = hourMs,
                        // Quantized so GPS jitter overwrites instead of fragmenting the site.
                        locationLat = com.weatherwidget.data.local.LocationMatch.quantize(latitude),
                        locationLon = com.weatherwidget.data.local.LocationMatch.quantize(longitude),
                        // NOT NULL in the schema but meaningless here; only cloud is read back.
                        temperature = 0f,
                        condition = "prior-run cloud",
                        source = com.weatherwidget.shared.graph.PriorDayCloudForecast.SOURCE_ID,
                        timestampToGroupPredictions =
                            com.weatherwidget.shared.graph.PriorDayCloudForecast.predictionBucketFor(hourMs),
                        // The previous-runs variable is cloud_cover_previous_day1 — the total
                        // column — so it is filed where `cloudCover` means what it means everywhere
                        // else in the schema. Rows written while it was the LOW variable still carry
                        // their value on cloudCoverLow and are still read through
                        // VisibleCloudCover's band fallback; nothing needs migrating.
                        cloudCover = cover,
                        cloudCoverLow = null,
                        fetchedAt = now,
                    )
                },
            )
            appLogDao.log("PRIOR_CLOUD", "stored hours=${byHour.size}")
        } catch (e: Exception) {
            appLogDao.log("PRIOR_CLOUD_FAIL", "cloud graph falls back to live values: ${e.javaClass.simpleName}")
        }
    }

    private var lastPriorCloudFetchMs = 0L

    fun requiresNetworkFetch(
        forecasts: List<ForecastEntity>,
        fetchContext: ForecastFetchContext? = null,
    ): Boolean = SOURCES_TO_CHECK.any { source ->
        widgetStateManager.isSourceVisible(source) &&
            isStale(source, forecasts, fetchContext)
    }

    fun visibleSourcesToFetch(
        cachedForecasts: List<ForecastEntity>,
        forceRefresh: Boolean,
        targetSourceId: String?,
        fetchContext: ForecastFetchContext?,
    ): Set<WeatherSource> {
        val enabledSources = widgetStateManager.getVisibleSourcesOrder().toSet()
        return enabledSources.filter { source ->
            val forced = forceRefresh &&
                (targetSourceId == null || source.id == targetSourceId)
            forced || isStale(source, cachedForecasts, fetchContext)
        }.toSet() - WeatherSource.GENERIC_GAP
    }

    fun isTargetSourceDisabled(targetSourceId: String?): Boolean =
        targetSourceId != null &&
            widgetStateManager.getVisibleSourcesOrder().none { it.id == targetSourceId }

    private data class SourceFetchEntry(
        val tag: String,
        val fetch: suspend (Double, Double) -> List<ForecastEntity>?,
    )

    /**
     * Per-source fetch configuration. Sources whose API client is null (debug-only providers not
     * provisioned in this build) are absent — the fetch loop skips them just as the old per-source
     * `if` guards did.
     */
    private fun buildFetchRegistry(): Map<WeatherSource, SourceFetchEntry> = buildMap {
        put(WeatherSource.NWS, SourceFetchEntry("FETCH_NWS_FAIL") { lat, lon ->
            fetchFromNws(lat, lon)
        })
        openWeatherMapApi?.let { api ->
            put(WeatherSource.OPEN_WEATHER_MAP, SourceFetchEntry("FETCH_OWM_FAIL") { lat, lon ->
                fetchAndSaveSharedForecast(lat, lon, WeatherSource.OPEN_WEATHER_MAP) {
                    api.getForecast(lat, lon)
                }
            })
        }
        put(WeatherSource.OPEN_METEO, SourceFetchEntry("FETCH_METEO_FAIL") { lat, lon ->
            fetchAndSaveSharedForecast(lat, lon, WeatherSource.OPEN_METEO) {
                openMeteoApi.getForecast(lat, lon, historyDays = 7)
            }.also {
                // Only Open-Meteo has a previous-runs product, and this rides its fetch so
                // the call is never spent when Open-Meteo is not being fetched at all.
                fetchPriorDayCloudForecast(lat, lon)
            }
        })
        put(WeatherSource.WEATHER_API, SourceFetchEntry("FETCH_WAPI_FAIL") { lat, lon ->
            val forecasts = fetchAndSaveSharedForecast(lat, lon, WeatherSource.WEATHER_API) {
                weatherApi.getForecast(lat, lon)
            }
            weatherApiHistoryBackfiller.backfillIfNeeded(lat, lon)
            forecasts
        })
        put(WeatherSource.SILURIAN, SourceFetchEntry("FETCH_SILURIAN_FAIL") { lat, lon ->
            fetchFromSilurian(lat, lon)
        })
        tomorrowIoApi?.let { api ->
            put(WeatherSource.TOMORROW_IO, SourceFetchEntry("FETCH_TMRW_FAIL") { lat, lon ->
                fetchAndSaveSharedForecast(lat, lon, WeatherSource.TOMORROW_IO) {
                    coroutineScope {
                        val forecastDeferred = async { api.getForecast(lat, lon) }
                        val historyDeferred = async {
                            runCatching {
                                api.getFiveMinuteHistory(
                                    lat,
                                    lon,
                                    TomorrowIoApi.FULL_ACTUALS_LOOKBACK_HOURS,
                                )
                            }.onFailure { error ->
                                if (error is CancellationException) throw error
                                appLogDao.log(
                                    "FETCH_TMRW_5M_FAIL",
                                    "full history unavailable; retaining cached actuals: ${error.javaClass.simpleName}",
                                    "WARN",
                                )
                            }.getOrNull()
                        }
                        val forecast = forecastDeferred.await()
                        val history = historyDeferred.await()
                        forecast.copy(
                            subHourly = history?.subHourly.orEmpty(),
                            providerCurrentTemp = history?.providerCurrentTemp,
                            providerCurrentCondition = history?.providerCurrentCondition,
                            providerCurrentObservedAt = history?.providerCurrentObservedAt,
                            providerCurrentCloudCover = history?.providerCurrentCloudCover,
                        )
                    }
                }
            })
        }
    }

    suspend fun fetchFromAllApis(
        latitude: Double,
        longitude: Double,
        sourcesToFetch: Set<WeatherSource>,
    ) = coroutineScope {
        val registry = buildFetchRegistry()

        val fetchedBySource = sourcesToFetch.mapNotNull { source ->
            val entry = registry[source] ?: return@mapNotNull null
            async {
                source to safeFetch(entry.tag, source, latitude, longitude) {
                    entry.fetch(latitude, longitude)
                }
            }
        }.awaitAll()

        fetchedBySource.forEach { (source, forecasts) ->
            forecasts?.let {
                snapshotStore.saveForecastSnapshot(
                    it,
                    latitude,
                    longitude,
                    source.id,
                    System.currentTimeMillis(),
                )
            }
        }

        // NWS daily actuals from a dedicated /stations/{id}/observations pull. Idempotent: only
        // dates still missing a station-derived actual trigger a request.
        if (WeatherSource.NWS in sourcesToFetch) {
            runCatching { nwsApiDailyActualsFetcher?.fillMissingIfNeeded(latitude, longitude) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    suspend fun fetchFromNws(
        latitude: Double,
        longitude: Double,
    ): List<ForecastEntity> {
        val (forecastEntities, hourlyEntities) =
            nwsForecastMapper.fetchFromNws(latitude, longitude)
        if (hourlyEntities.isNotEmpty()) {
            hourlyStore.saveHourlyEntities(hourlyEntities)
        }
        return forecastEntities
    }

    private suspend fun fetchFromSilurian(
        latitude: Double,
        longitude: Double,
    ): List<ForecastEntity> {
        val result = silurianApi.getForecast(latitude, longitude)
        if (result.hourly.isNotEmpty()) {
            hourlyStore.saveHourlyEntitiesFromShared(
                result.hourly,
                latitude,
                longitude,
                WeatherSource.SILURIAN.id,
            )
        }
        return result.daily.map { day ->
            snapshotStore.mapDailyForecast(
                DailyForecast(
                    date = day.date,
                    highTemp = day.highTemp,
                    lowTemp = day.lowTemp,
                    condition = day.condition,
                    iconToken = day.condition,
                    precipProbability = day.precipProbability,
                    precipAmountMm = day.precipAmountMm,
                ),
                latitude,
                longitude,
                WeatherSource.SILURIAN.id,
                result.hourly,
            )
        }
    }

    private fun isStale(
        source: WeatherSource,
        forecasts: List<ForecastEntity>,
        fetchContext: ForecastFetchContext?,
    ): Boolean {
        val lastSourceFetchTime = forecasts
            .filter { it.source == source.id }
            .maxOfOrNull { it.batchFetchedAt } ?: 0L
        val now = System.currentTimeMillis()
        if (fetchContext != null) {
            val intervalMinutes = ForecastFetchPolicy.intervalMinutes(
                isCharging = fetchContext.isCharging,
                isScreenInteractive = fetchContext.isScreenInteractive,
                isActiveSource = source.id in fetchContext.activeSourceIds,
                batteryLevel = fetchContext.batteryLevel,
            ) ?: return false
            return ForecastFetchPolicy.isDue(
                lastSourceFetchTime,
                intervalMinutes,
                now,
            )
        }
        val position = widgetStateManager.getVisibleSourcesOrder().indexOf(source)
        val threshold = ForecastStalenessPolicy.getStalenessThresholdMs(position)
        return now - lastSourceFetchTime >= threshold
    }

    private suspend fun fetchAndSaveSharedForecast(
        latitude: Double,
        longitude: Double,
        source: WeatherSource,
        fetch: suspend () -> RawFetch?,
    ): List<ForecastEntity>? {
        val result = fetch() ?: return null
        val actualsWrite = if (result.hourly.isNotEmpty()) {
            hourlyStore.saveHourlyEntitiesFromShared(
                result.hourly,
                latitude,
                longitude,
                source.id,
                historicalData = result.subHourly.ifEmpty {
                    if (source == WeatherSource.TOMORROW_IO) emptyList() else result.hourly
                },
            )
        } else {
            HourlyForecastStore.HistoricalActualsWriteSummary(0, 0)
        }
        if (source == WeatherSource.TOMORROW_IO) {
            appLogDao.log(
                "TMRW_5M_FETCH",
                "windowHours=${TomorrowIoApi.FULL_ACTUALS_LOOKBACK_HOURS} " +
                    "rows=${actualsWrite.rowCount} " +
                    "earliest=${result.subHourly.minOfOrNull { it.dateTime }} " +
                    "latest=${result.subHourly.maxOfOrNull { it.dateTime }} " +
                    "replacements=${actualsWrite.replacementCount}",
                "INFO",
            )
        }
        return result.daily.map { day ->
            snapshotStore.mapDailyForecast(
                day,
                latitude,
                longitude,
                source.id,
                result.hourly,
            )
        }
    }

    private suspend fun <T> safeFetch(
        tag: String,
        source: WeatherSource,
        latitude: Double,
        longitude: Double,
        block: suspend () -> T,
    ): T? = try {
        val result = block()
        if (result != null) {
            widgetStateManager.recordSourceFetchSuccess(source)
            if (result !is Collection<*> || result.isNotEmpty()) {
                FetchMetadata.setLastForecastSourceSuccessTime(
                    context = context,
                    sourceId = source.id,
                    latitude = latitude,
                    longitude = longitude,
                    time = System.currentTimeMillis(),
                )
            }
        }
        result
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (exception: Exception) {
        widgetStateManager.recordSourceFetchFailure(
            source,
            errorCode = extractErrorCode(exception),
        )
        logFetchFailure(tag, source, exception)
        null
    }

    private suspend fun logFetchFailure(
        tag: String,
        source: WeatherSource,
        exception: Exception,
    ) {
        when (exception) {
            is ApiAccessException -> {
                val code = exception.statusCode?.let { "HTTP_$it" } ?: "ACCESS_ERROR"
                appLogDao.log(
                    tag,
                    "source=${source.id} code=$code detail=${exception.detail}",
                    "WARN",
                )
            }
            is ClientRequestException -> {
                val statusCode = exception.response.status.value
                val responseBody = try {
                    exception.response.bodyAsText()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
                appLogDao.log(
                    tag,
                    "source=${source.id} code=HTTP_$statusCode " +
                        "detail=${extractHttpErrorDetail(responseBody, exception.message)}",
                    "WARN",
                )
            }
            else -> appLogDao.log(
                tag,
                "source=${source.id} error=${exception.message}",
                "WARN",
            )
        }
    }

    private fun extractErrorCode(exception: Exception): String = when (exception) {
        is ApiAccessException ->
            exception.statusCode?.let { "HTTP_$it" } ?: "ACCESS_ERROR"
        is ClientRequestException -> "HTTP_${exception.response.status.value}"
        else -> {
            val name = exception.javaClass.simpleName
            when {
                name.contains("UnknownHost") ||
                    name.contains("UnresolvedAddress") -> "DNS_ERROR"
                name.contains("ConnectException") ||
                    name.contains("ConnectionRefused") -> "CONN_REFUSED"
                name.contains("Timeout") ||
                    name.contains("SocketTimeout") -> "TIMEOUT"
                name.contains("SSL") || name.contains("TLS") -> "SSL_ERROR"
                name.contains("SocketException") -> "SOCKET_ERROR"
                else -> name.take(20).ifBlank { "ERROR" }
            }
        }
    }

    private fun extractHttpErrorDetail(
        body: String?,
        fallbackMessage: String?,
    ): String {
        val bodyText = body?.trim().orEmpty()
        val messageMatch = Regex(
            "\"message\"\\s*:\\s*\"([^\"]+)\"",
        ).find(bodyText)?.groupValues?.getOrNull(1)
        val errorMatch = Regex(
            "\"error\"\\s*:\\s*\\{[^}]*\"message\"\\s*:\\s*\"([^\"]+)\"",
        ).find(bodyText)?.groupValues?.getOrNull(1)
        return messageMatch ?: errorMatch ?: fallbackMessage ?: "Request failed"
    }

    companion object {
        private const val PRIOR_CLOUD_FETCH_INTERVAL_MS = 60 * 60 * 1000L
        /** Covers the widget's 30-day pan; `_previous_day1` is populated across the whole span. */
        private const val PRIOR_CLOUD_PAST_DAYS = 31

        private val SOURCES_TO_CHECK = listOf(
            WeatherSource.NWS,
            WeatherSource.OPEN_WEATHER_MAP,
            WeatherSource.SILURIAN,
            WeatherSource.WEATHER_API,
            WeatherSource.OPEN_METEO,
            WeatherSource.TOMORROW_IO,
        )
    }
}
