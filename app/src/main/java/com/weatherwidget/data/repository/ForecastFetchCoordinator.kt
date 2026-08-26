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
import com.weatherwidget.data.remote.VisualCrossingApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.widget.ForecastFetchContext
import com.weatherwidget.widget.ForecastFetchPolicy
import com.weatherwidget.widget.ForecastStalenessPolicy
import com.weatherwidget.widget.WidgetStateManager
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

/**
 * Selects, fetches, classifies, and persists provider results.
 */
internal class ForecastFetchCoordinator(
    private val context: Context,
    private val appLogDao: AppLogDao,
    private val openMeteoApi: OpenMeteoApi,
    private val visualCrossingApi: VisualCrossingApi,
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
            if (byHour.isEmpty()) return
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
                        // The previous-runs variable is cloud_cover_LOW_previous_day1, so the value
                        // is filed on the low-layer column where it belongs — everywhere else in the
                        // schema `cloudCover` means the total column. Readers prefer low, so rows
                        // written before this switch keep reading until the REPLACE-upsert rewrites.
                        cloudCover = null,
                        cloudCoverLow = cover,
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

    suspend fun fetchFromAllApis(
        latitude: Double,
        longitude: Double,
        sourcesToFetch: Set<WeatherSource>,
    ) = coroutineScope {
        val nwsDeferred = if (WeatherSource.NWS in sourcesToFetch) {
            async {
                safeFetch(
                    "FETCH_NWS_FAIL",
                    WeatherSource.NWS,
                    latitude,
                    longitude,
                ) {
                    fetchFromNws(latitude, longitude)
                }
            }
        } else {
            null
        }
        val openWeatherMapDeferred =
            if (
                openWeatherMapApi != null &&
                WeatherSource.OPEN_WEATHER_MAP in sourcesToFetch
            ) {
                async {
                    safeFetch(
                        "FETCH_OWM_FAIL",
                        WeatherSource.OPEN_WEATHER_MAP,
                        latitude,
                        longitude,
                    ) {
                        fetchAndSaveSharedForecast(
                            latitude,
                            longitude,
                            WeatherSource.OPEN_WEATHER_MAP,
                        ) {
                            openWeatherMapApi.getForecast(latitude, longitude)
                        }
                    }
                }
            } else {
                null
            }
        val visualCrossingDeferred =
            if (WeatherSource.VISUAL_CROSSING in sourcesToFetch) {
                async {
                    safeFetch(
                        "FETCH_VISUAL_CROSSING_FAIL",
                        WeatherSource.VISUAL_CROSSING,
                        latitude,
                        longitude,
                    ) {
                        fetchAndSaveSharedForecast(
                            latitude,
                            longitude,
                            WeatherSource.VISUAL_CROSSING,
                        ) {
                            visualCrossingApi.getForecast(latitude, longitude)
                        }
                    }
                }
            } else {
                null
            }
        val meteoDeferred = if (WeatherSource.OPEN_METEO in sourcesToFetch) {
            async {
                safeFetch(
                    "FETCH_METEO_FAIL",
                    WeatherSource.OPEN_METEO,
                    latitude,
                    longitude,
                ) {
                    fetchAndSaveSharedForecast(
                        latitude,
                        longitude,
                        WeatherSource.OPEN_METEO,
                    ) {
                        openMeteoApi.getForecast(
                            latitude,
                            longitude,
                            historyDays = 7,
                        )
                    }.also {
                        // Only Open-Meteo has a previous-runs product, and this rides its fetch so
                        // the call is never spent when Open-Meteo is not being fetched at all.
                        fetchPriorDayCloudForecast(latitude, longitude)
                    }
                }
            }
        } else {
            null
        }
        val weatherApiDeferred = if (WeatherSource.WEATHER_API in sourcesToFetch) {
            async {
                safeFetch(
                    "FETCH_WAPI_FAIL",
                    WeatherSource.WEATHER_API,
                    latitude,
                    longitude,
                ) {
                    val forecasts = fetchAndSaveSharedForecast(
                        latitude,
                        longitude,
                        WeatherSource.WEATHER_API,
                    ) {
                        weatherApi.getForecast(latitude, longitude)
                    }
                    weatherApiHistoryBackfiller.backfillIfNeeded(latitude, longitude)
                    forecasts
                }
            }
        } else {
            null
        }
        val silurianDeferred = if (WeatherSource.SILURIAN in sourcesToFetch) {
            async {
                safeFetch(
                    "FETCH_SILURIAN_FAIL",
                    WeatherSource.SILURIAN,
                    latitude,
                    longitude,
                ) {
                    val result = silurianApi.getForecast(latitude, longitude)
                    if (result.hourly.isNotEmpty()) {
                        hourlyStore.saveHourlyEntitiesFromShared(
                            result.hourly,
                            latitude,
                            longitude,
                            WeatherSource.SILURIAN.id,
                        )
                    }
                    result.daily.map { day ->
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
            }
        } else {
            null
        }
        val tomorrowIoDeferred =
            if (
                tomorrowIoApi != null &&
                WeatherSource.TOMORROW_IO in sourcesToFetch
            ) {
                async {
                    safeFetch(
                        "FETCH_TMRW_FAIL",
                        WeatherSource.TOMORROW_IO,
                        latitude,
                        longitude,
                    ) {
                        fetchAndSaveSharedForecast(
                            latitude,
                            longitude,
                            WeatherSource.TOMORROW_IO,
                        ) {
                            tomorrowIoApi.getForecast(latitude, longitude)
                        }
                    }
                }
            } else {
                null
            }

        val fetchedBySource = listOf(
            WeatherSource.NWS to nwsDeferred?.await(),
            WeatherSource.OPEN_WEATHER_MAP to openWeatherMapDeferred?.await(),
            WeatherSource.VISUAL_CROSSING to visualCrossingDeferred?.await(),
            WeatherSource.OPEN_METEO to meteoDeferred?.await(),
            WeatherSource.WEATHER_API to weatherApiDeferred?.await(),
            WeatherSource.SILURIAN to silurianDeferred?.await(),
            WeatherSource.TOMORROW_IO to tomorrowIoDeferred?.await(),
        )
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
        if (result.hourly.isNotEmpty()) {
            hourlyStore.saveHourlyEntitiesFromShared(
                result.hourly,
                latitude,
                longitude,
                source.id,
                historicalData = result.subHourly.ifEmpty { result.hourly },
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
            WeatherSource.VISUAL_CROSSING,
            WeatherSource.OPEN_WEATHER_MAP,
            WeatherSource.SILURIAN,
            WeatherSource.WEATHER_API,
            WeatherSource.OPEN_METEO,
            WeatherSource.TOMORROW_IO,
        )
    }
}
