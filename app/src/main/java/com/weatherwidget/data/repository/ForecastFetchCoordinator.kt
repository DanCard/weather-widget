package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.OpenWeatherMapApi
import com.weatherwidget.data.remote.SilurianApi
import com.weatherwidget.data.remote.TomorrowIoApi
import com.weatherwidget.data.remote.VisualCrossingApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.shared.actuals.NwsApiActualsBackfill
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
    private val dailyActualsStore: DailyActualsStore,
) {
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
                            historyDays = WeatherConfig.ACTUALS_HISTORY_DAYS,
                        )
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

        // Backfill NWS api actuals from Open-Meteo ERA5 archive
        backfillNwsApiActualsIfNeeded(latitude, longitude)
    }

    private suspend fun backfillNwsApiActualsIfNeeded(latitude: Double, longitude: Double) {
        val today = LocalDate.now()
        val startMs = today.minusDays(90).toEpochDay() * 86_400_000L
        val endMs = today.toEpochDay() * 86_400_000L
        val missingDates = dailyActualsStore.findNwsDatesMissingApiActuals(latitude, longitude, startMs, endMs)
        if (missingDates.isEmpty()) return
        val archiveActuals = NwsApiActualsBackfill.backfill(
            fetchArchive = { start, end -> openMeteoApi.getHistoricalDailyTemps(latitude, longitude, start, end) },
            latitude = latitude,
            longitude = longitude,
            epochDayMillis = missingDates,
        )
        dailyActualsStore.backfillNwsApiActualsFromArchive(latitude, longitude, archiveActuals)
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
        fetch: suspend () -> ForecastResult?,
    ): List<ForecastEntity>? {
        val result = fetch() ?: return null
        if (result.hourly.isNotEmpty()) {
            hourlyStore.saveHourlyEntitiesFromShared(
                result.hourly,
                latitude,
                longitude,
                source.id,
            )
        }
        if (source == WeatherSource.OPEN_METEO) {
            dailyActualsStore.persistOpenMeteoPastDayActuals(latitude, longitude, result.daily)
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
