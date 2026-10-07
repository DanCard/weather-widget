package com.weatherwidget.data.repository

import com.weatherwidget.shared.util.WeatherSourceOrdering
import com.weatherwidget.data.remote.GoogleWeatherApi
import com.weatherwidget.shared.util.SourceCoverage
import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.shared.util.DailyPrecipPeriods
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.ApiKeyRedaction
import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.data.remote.NwsPointUnavailableException
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.OpenWeatherMapApi
import com.weatherwidget.data.remote.SilurianApi
import com.weatherwidget.data.remote.TomorrowIoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.widget.ForecastFetchContext
import com.weatherwidget.widget.ForecastFetchPolicy
import com.weatherwidget.widget.ForecastStalenessPolicy
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.util.NetworkRestrictionHelper
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
    private val googleWeatherApi: GoogleWeatherApi? = null,
    private val nwsForecastMapper: NwsForecastMapper,
    private val snapshotStore: ForecastSnapshotStore,
    private val hourlyStore: HourlyForecastStore,
    private val weatherApiHistoryBackfiller: WeatherApiHistoryBackfiller,
    private val nwsApiDailyActualsFetcher: NwsApiDailyActualsFetcher?,
    private val hourlyForecastHistoryDao: com.weatherwidget.data.local.HourlyForecastHistoryDao? = null,
    /** Snapshot-write clock; [ForecastRepository.clock] in production, pinned by tests. */
    private val clock: () -> Long = System::currentTimeMillis,
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

    private class SourceFetchEntry(
        source: WeatherSource,
        val fetch: suspend (Double, Double) -> List<ForecastEntity>?,
    ) {
        val tag: String = requireNotNull(SourceFetchLogTags.failureTag(source)) { "no failure tag for ${source.id}" }
    }

    /**
     * Per-source fetch configuration. Sources whose API client is null (debug-only providers not
     * provisioned in this build) are absent — the fetch loop skips them just as the old per-source
     * `if` guards did.
     */
    private fun buildFetchRegistry(): Map<WeatherSource, SourceFetchEntry> = buildMap {
        put(WeatherSource.NWS, SourceFetchEntry(WeatherSource.NWS) { lat, lon ->
            fetchFromNws(lat, lon)
        })
        openWeatherMapApi?.let { api ->
            put(WeatherSource.OPEN_WEATHER_MAP, SourceFetchEntry(WeatherSource.OPEN_WEATHER_MAP) { lat, lon ->
                fetchAndSaveSharedForecast(lat, lon, WeatherSource.OPEN_WEATHER_MAP) {
                    api.getForecast(lat, lon)
                }
            })
        }
        put(WeatherSource.OPEN_METEO, SourceFetchEntry(WeatherSource.OPEN_METEO) { lat, lon ->
            fetchAndSaveSharedForecast(lat, lon, WeatherSource.OPEN_METEO) {
                openMeteoApi.getForecast(lat, lon, historyDays = 7)
            }.also {
                // Only Open-Meteo has a previous-runs product, and this rides its fetch so
                // the call is never spent when Open-Meteo is not being fetched at all.
                fetchPriorDayCloudForecast(lat, lon)
            }
        })
        put(WeatherSource.WEATHER_API, SourceFetchEntry(WeatherSource.WEATHER_API) { lat, lon ->
            val forecasts = fetchAndSaveSharedForecast(lat, lon, WeatherSource.WEATHER_API) {
                weatherApi.getForecast(lat, lon)
            }
            weatherApiHistoryBackfiller.backfillIfNeeded(lat, lon)
            forecasts
        })
        put(WeatherSource.SILURIAN, SourceFetchEntry(WeatherSource.SILURIAN) { lat, lon ->
            fetchFromSilurian(lat, lon)
        })
        googleWeatherApi?.let { api ->
            put(WeatherSource.GOOGLE_WEATHER, SourceFetchEntry(WeatherSource.GOOGLE_WEATHER) { lat, lon ->
                val nowMs = clock()
                val storedHours = hourlyStore.storedHourlyForSite(
                    lat,
                    lon,
                    WeatherSource.GOOGLE_WEATHER.id,
                    startMs = nowMs - 3_600_000L,
                    endMs = nowMs + (GoogleWeatherApi.FORECAST_HOURS + 2) * 3_600_000L,
                )
                fetchAndSaveSharedForecast(lat, lon, WeatherSource.GOOGLE_WEATHER) {
                    api.getForecast(
                        lat,
                        lon,
                        includeHistory = googleNeedsHistory(lat, lon),
                        storedHours = storedHours,
                    ).also { appLogDao.log("GOOGLE_HOURS_PAGES", api.lastHoursPaging.orEmpty(), "INFO") }
                }
            })
        }
        tomorrowIoApi?.let { api ->
            put(WeatherSource.TOMORROW_IO, SourceFetchEntry(WeatherSource.TOMORROW_IO) { lat, lon ->
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
        // The network choke point. `visibleSources()` already drops sources that cannot serve the
        // *stored* active location; this re-checks against the coordinates actually being fetched,
        // so no caller can send NWS a point outside its coverage (a guaranteed 404 InvalidPoint).
        val servable = sourcesToFetch.filterTo(LinkedHashSet()) { SourceCoverage.supports(it.id, latitude, longitude) }

        val fetchedBySource = servable.mapNotNull { source ->
            val entry = registry[source] ?: return@mapNotNull null
            async {
                source to safeFetch(entry.tag, source, latitude, longitude) {
                    entry.fetch(latitude, longitude)
                }
            }
        }.awaitAll()

        fetchedBySource.forEach { (source, forecasts) ->
            forecasts?.let {
                val nowMs = clock()
                snapshotStore.saveForecastSnapshot(
                    withStoredPrecipPeriods(it, latitude, longitude, source.id),
                    latitude,
                    longitude,
                    source.id,
                    batchFetchedAt = nowMs,
                    nowMs = nowMs,
                )
            }
        }

        // NWS daily actuals from a dedicated /stations/{id}/observations pull. Idempotent: only
        // dates still missing a station-derived actual trigger a request.
        if (WeatherSource.NWS in servable) {
            runCatching { nwsApiDailyActualsFetcher?.fillMissingIfNeeded(latitude, longitude) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    /**
     * Per-product quota state for the views: a product the provider refused is recorded (the hourly
     * views or the daily view show it), one that answered is cleared. The fetch itself is a success
     * for the source — the other product was saved — so no source failure is counted (user,
     * 2026-10-07: an hours-only 429 put "quota used" on the daily view).
     */
    private suspend fun recordProductQuotas(source: WeatherSource, result: RawFetch) {
        for (product in ForecastProduct.entries) {
            val refusal = result.quotaRefused[product]
            if (refusal == null) {
                widgetStateManager.clearProductQuota(source, product)
            } else {
                widgetStateManager.recordProductQuota(
                    source,
                    product,
                    refusal.untilMs,
                    detail = ApiKeyRedaction.redact(refusal.detail),
                )
                appLogDao.log("FETCH_PRODUCT_QUOTA", "source=${source.id} product=$product until=${refusal.untilMs}", "WARN")
            }
        }
    }

    /**
     * Day/night precip for each daily row by the shared rule ([DailyPrecipPeriods], same as desktop):
     * the provider's own value carried on the row, else the max over the source's hourly rows as
     * stored now — every fetch saves its hourly rows before returning its daily ones, and a Google
     * one-page fetch leaves hours 25–72 in place.
     */
    private suspend fun withStoredPrecipPeriods(
        forecasts: List<ForecastEntity>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
    ): List<ForecastEntity> {
        if (forecasts.isEmpty()) return forecasts
        val dates = forecasts.map { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
        val stored = hourlyStore.storedHourlyForSite(
            latitude,
            longitude,
            sourceId,
            startMs = DailyPrecipPeriods.readStartMs(dates.min()),
            endMs = DailyPrecipPeriods.readEndMs(dates.max()),
        )
        return forecasts.zip(dates) { entity, date ->
            val periods = DailyPrecipPeriods.resolve(
                targetDate = date,
                storedHourly = stored,
                providerDay = entity.daytimePrecipProbability,
                providerNight = entity.nighttimePrecipProbability,
            )
            entity.copy(
                daytimePrecipProbability = periods.day,
                nighttimePrecipProbability = periods.night,
            )
        }
    }

    suspend fun fetchFromNws(
        latitude: Double,
        longitude: Double,
    ): List<ForecastEntity> {
        val (forecastEntities, hourlyEntities, elapsedHourly) =
            nwsForecastMapper.fetchFromNws(latitude, longitude)
        if (hourlyEntities.isNotEmpty()) {
            hourlyStore.saveHourlyEntities(hourlyEntities)
        }
        backfillElapsedHistory(elapsedHourly, latitude, longitude, WeatherSource.NWS)
        return forecastEntities
    }

    /**
     * Gives a site with no forecast history yet the elapsed hours this payload carries. Same
     * source only; never overwrites. INFO only when something was written — in steady state this
     * runs every fetch and stores nothing, which is VERBOSE.
     */
    private suspend fun backfillElapsedHistory(
        hourly: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        source: WeatherSource,
    ) {
        if (hourly.isEmpty()) return
        val summary = try {
            hourlyStore.backfillElapsedHistory(hourly, latitude, longitude, source.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogDao.log(
                "HOURLY_HISTORY_BACKFILL_FAIL",
                "source=${source.id} ${e.javaClass.simpleName}: ${e.message}",
                "WARN",
            )
            return
        }
        appLogDao.log(
            "HOURLY_HISTORY_BACKFILL",
            "source=${source.id} site=${LocationMatch.quantize(latitude)},${LocationMatch.quantize(longitude)} " +
                "offered=${summary.offered} covered=${summary.covered} stored=${summary.stored}",
            if (summary.stored > 0) "INFO" else "VERBOSE",
        )
    }

    /**
     * Google's `history/hours` has a small per-project daily quota (Cloud Console setting), so it is requested only for a site with
     * no Google history hours in the last day (`GoogleWeatherApi.needsHistory`) — read through the
     * same site match the elapsed backfill uses.
     */
    private suspend fun googleNeedsHistory(latitude: Double, longitude: Double): Boolean {
        val dao = hourlyForecastHistoryDao ?: return true
        val nowMs = clock()
        val window = GoogleWeatherApi.historyWindow(nowMs)
        val keyLat = LocationMatch.quantize(latitude)
        val keyLon = LocationMatch.quantize(longitude)
        val covered = dao.getHistoryInRangeForBucketWindow(
            startDateTime = window.first,
            endDateTime = window.last + 1,
            bucketStart = Long.MIN_VALUE,
            bucketEnd = Long.MAX_VALUE,
            lat = keyLat,
            lon = keyLon,
            source = WeatherSource.GOOGLE_WEATHER.id,
        ).filter { LocationMatch.sameSite(keyLat, keyLon, it.locationLat, it.locationLon) }
            .map { it.dateTime }
        return GoogleWeatherApi.needsHistory(covered, nowMs)
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
        recordProductQuotas(source, result)
        val actualsWrite = if (result.hourly.isNotEmpty()) {
            hourlyStore.saveHourlyEntitiesFromShared(
                result.hourly,
                latitude,
                longitude,
                source.id,
                historicalData = result.subHourly.ifEmpty {
                    if (source == WeatherSource.TOMORROW_IO) emptyList() else result.hourly
                },
            ).also {
                // The elapsed hours the live write just dropped, filed as history where the site
                // has none (Open-Meteo past_days, Silurian include_past, Tomorrow.io nowMinus23h).
                backfillElapsedHistory(result.hourly, latitude, longitude, source)
            }
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
            snapshotStore.mapDailyForecast(day, latitude, longitude, source.id)
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
            detail = ApiKeyRedaction.redact(exception.message ?: exception.javaClass.simpleName),
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
        // NWS 404 InvalidPoint: the site is outside its (US-only) coverage, not a transient
        // failure — the watermark should say so rather than "404 Not Found".
        is NwsPointUnavailableException -> "NO_COVERAGE"
        // A daily quota cannot recover before its reset; the watermark says when that is.
        is ApiAccessException ->
            if (GoogleQuota.isDailyQuotaExhausted(exception)) {
                GoogleQuota.ERROR_CODE_DAILY
            } else {
                exception.statusCode?.let { "HTTP_$it" } ?: "ACCESS_ERROR"
            }
        is ClientRequestException -> "HTTP_${exception.response.status.value}"
        else -> {
            val name = exception.javaClass.simpleName
            when {
                name.contains("UnknownHost") ||
                    name.contains("UnresolvedAddress") -> "DNS_ERROR"
                name.contains("ConnectException") ||
                    name.contains("ConnectionRefused") -> {
                    if (NetworkRestrictionHelper.isBackgroundDataRestricted(context)) "DATA_RESTRICTED" else "CONN_REFUSED"
                }
                name.contains("Timeout") ||
                    name.contains("SocketTimeout") -> "TIMEOUT"
                name.contains("SSL") || name.contains("TLS") -> "SSL_ERROR"
                name.contains("SocketException") -> {
                    if (NetworkRestrictionHelper.isBackgroundDataRestricted(context)) "DATA_RESTRICTED" else "SOCKET_ERROR"
                }
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

        /** Every user-selectable source; a hand-kept list here once left new sources never "stale". */
        private val SOURCES_TO_CHECK = WeatherSourceOrdering.ALL_CONFIGURABLE
    }
}
