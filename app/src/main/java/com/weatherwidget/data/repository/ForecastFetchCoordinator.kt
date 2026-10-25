package com.weatherwidget.data.repository

import com.weatherwidget.shared.graph.PriorDayCloudRun
import com.weatherwidget.data.remote.GoogleWeatherApi
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.remote.ForecastFetchPlan
import com.weatherwidget.data.remote.ForecastHorizonCadence
import com.weatherwidget.data.remote.HourlyOnDemand
import com.weatherwidget.data.remote.ForecastHorizonMarkers
import com.weatherwidget.data.remote.GoogleHistoryRun
import com.weatherwidget.data.remote.QuotaState
import com.weatherwidget.data.remote.SourceQuotaBlocks
import com.weatherwidget.shared.util.SourceCoverage
import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.shared.util.ForecastPersistRun
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.BuiltInApiKeys
import com.weatherwidget.data.remote.FetchErrorCode
import com.weatherwidget.data.remote.ApiKeyRedaction
import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.data.remote.NwsPointUnavailableException
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.OpenWeatherMapApi
import com.weatherwidget.data.remote.SilurianApi
import com.weatherwidget.data.remote.TomorrowIoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.widget.ForecastFetchContext
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
    /** The cloud graph's frozen forecast curve: `:shared` [PriorDayCloudRun] owns the rule; this stores rows. */
    private suspend fun fetchPriorDayCloudForecast(latitude: Double, longitude: Double) {
        val dao = hourlyForecastHistoryDao ?: return
        PriorDayCloudRun.run(
            RoomPriorDayCloudHost(dao, appLogDao, latitude, longitude, lastPriorCloudFetch) { pastDays, nowMs ->
                openMeteoApi.getPriorDayCloudForecast(latitude, longitude, pastDays, nowMs)
            },
            sourceId = WeatherSource.OPEN_METEO.id,
            nowMs = System.currentTimeMillis(),
        )
    }

    private val lastPriorCloudFetch = PriorDayCloudRun.LastAttempt()

    /**
     * Whether an unforced sync has anything to fetch: a source whose fetch is due
     * ([horizonDecision]). [backgroundSources] is [com.weatherwidget.shared.sourceview.SourceFetchGate]'s
     * forecast set; null (gate unavailable) means every visible source, as before the gate.
     */
    fun requiresNetworkFetch(
        forecasts: List<ForecastEntity>,
        fetchContext: ForecastFetchContext? = null,
        backgroundSources: Set<WeatherSource>? = null,
        latitude: Double? = null,
        longitude: Double? = null,
    ): Boolean =
        planSync(forecasts, forceRefresh = false, targetSourceId = null, fetchContext, backgroundSources, latitude, longitude)
            .due.isNotEmpty()

    /** [planFetch]'s sources, for callers that only need the set. */
    fun visibleSourcesToFetch(
        cachedForecasts: List<ForecastEntity>,
        forceRefresh: Boolean,
        targetSourceId: String?,
        fetchContext: ForecastFetchContext?,
        backgroundSources: Set<WeatherSource>? = null,
    ): Set<WeatherSource> =
        planFetch(cachedForecasts, forceRefresh, targetSourceId, fetchContext, backgroundSources).keys

    /**
     * Which sources this sync fetches, and which parts of each ([ForecastParts]).
     *
     * A targeted force ([targetSourceId]: a source switch, an on-demand day) always fetches its
     * target. Everything else — sources due riding along, an untargeted force (Refresh, location
     * change: user, 2026-10-10) — is limited to [backgroundSources]; a source not viewed in 8 days is
     * fetched on demand only (performance/261010-fetch-only-sources-likely-to-be-viewed.md).
     *
     * A force takes both parts; an unforced sync takes a source whose fetch is due
     * ([ForecastHorizonCadence]), less any quota-refused product ([ForecastParts.unblocked]).
     */
    fun planFetch(
        cachedForecasts: List<ForecastEntity>,
        forceRefresh: Boolean,
        targetSourceId: String?,
        fetchContext: ForecastFetchContext?,
        backgroundSources: Set<WeatherSource>? = null,
        latitude: Double? = null,
        longitude: Double? = null,
    ): Map<WeatherSource, ForecastParts> =
        planSync(cachedForecasts, forceRefresh, targetSourceId, fetchContext, backgroundSources, latitude, longitude)
            .due.mapKeys { (id, _) -> WeatherSource.entries.first { it.id == id } }

    /**
     * This sync's `:shared` [ForecastFetchPlan] (the step desktop's forecast loop runs): candidates are
     * the visible sources [backgroundSources] lets through, plus a forced target. A forced fetch takes
     * both parts; a scheduled one what its horizon decision makes due, less a refused product; without
     * a context or site the old rank thresholds ([isStale]) decide.
     */
    fun planSync(
        cachedForecasts: List<ForecastEntity>,
        forceRefresh: Boolean,
        targetSourceId: String?,
        fetchContext: ForecastFetchContext?,
        backgroundSources: Set<WeatherSource>? = null,
        latitude: Double? = null,
        longitude: Double? = null,
    ): ForecastFetchPlan.Plan {
        val candidates = widgetStateManager.getVisibleSourcesOrder()
            .filter { it != WeatherSource.GENERIC_GAP }
            .filter { source ->
                val targeted = forceRefresh && source.id == targetSourceId
                targeted || backgroundSources == null || source in backgroundSources
            }
        val byId = candidates.associateBy { it.id }
        return ForecastFetchPlan.plan(
            candidates = candidates.map { it.id },
            nowMs = clock(),
            decide = { id -> horizonDecision(byId.getValue(id), fetchContext, latitude, longitude) },
            forced = { id -> ForecastParts.BOTH.takeIf { forceRefresh && (id == targetSourceId || targetSourceId == null) } },
            dueWithoutDecision = { id -> isStale(byId.getValue(id), cachedForecasts) },
        )
    }

    /** [ForecastHorizonCadence] for [source] at the site, or null without a context or site. */
    fun horizonDecision(
        source: WeatherSource,
        fetchContext: ForecastFetchContext?,
        latitude: Double?,
        longitude: Double?,
    ): ForecastHorizonCadence.Decision? {
        if (fetchContext == null || latitude == null || longitude == null) return null
        return ForecastHorizonCadence.decide(
            isCharging = fetchContext.isCharging,
            batteryLevel = fetchContext.batteryLevel,
            isScreenOn = fetchContext.isScreenInteractive,
            lastFetchMs = horizonMarkers(source, latitude, longitude).lastLongMs(),
            nowMs = clock(),
            wakeAtMs = fetchContext.wakeAtMs,
        )
    }

    fun isTargetSourceDisabled(targetSourceId: String?): Boolean =
        targetSourceId != null &&
            widgetStateManager.getVisibleSourcesOrder().none { it.id == targetSourceId }

    /** [fetch]: the network call and its own side effects; [afterPersist]: what needs the answer stored first. */
    private class SourceFetchEntry(
        source: WeatherSource,
        val afterPersist: suspend (Double, Double) -> Unit = { _, _ -> },
        val fetch: suspend (Double, Double) -> RawFetch?,
    ) {
        val tag: String = requireNotNull(SourceFetchLogTags.failureTag(source)) { "no failure tag for ${source.id}" }
    }

    /**
     * Per-source fetch configuration. Sources whose API client is null (debug-only providers not
     * provisioned in this build) are absent — the fetch loop skips them just as the old per-source
     * `if` guards did.
     */
    /**
     * Last instants (exclusive) of hourly a fetch of [source] stores, live and snapshot: a judged
     * (LONG) fetch's 8 days live and 72 h snapshotted, 72 h when unjudged, deeper for the on-demand day it was asked for
     * ([HourlyOnDemand.keep]). Past them, only the daily row's summaries are kept
     * ([DailyHourlySummaries]).
     */
    private fun hourlyKeepUntilMs(
        source: WeatherSource,
        fetchContext: ForecastFetchContext?,
        horizon: ForecastHorizonCadence.Horizon? = null,
    ): HourlyOnDemand.KeepUntil =
        HourlyOnDemand.keep(source.id, fetchContext?.hourlyAhead, horizon).untilMs(clock())

    /**
     * Every fetch is LONG ([ForecastHorizonCadence]). Null when the context is unknown or the fetch
     * is an on-demand day, which has its own horizon.
     */
    private fun fetchHorizon(fetchContext: ForecastFetchContext?): ForecastHorizonCadence.Horizon? =
        ForecastHorizonCadence.Horizon.LONG.takeIf { fetchContext != null && fetchContext.hourlyAhead == null }

    /** A manual Refresh of a displayed source: an unchanged daily row is written anyway ([com.weatherwidget.shared.util.DailyForecastWrite]). */
    private fun isUserRefreshOf(source: WeatherSource, fetchContext: ForecastFetchContext?): Boolean =
        fetchContext?.userRefresh == true && source.id in fetchContext.activeSourceIds

    /** A judged fetch that answered restarts the cadence ([ForecastHorizonCadence]). Logged `FORECAST_HORIZON`. */
    private suspend fun markHorizon(
        horizon: ForecastHorizonCadence.Horizon?,
        source: WeatherSource,
        latitude: Double,
        longitude: Double,
    ) = ForecastHorizonMarkers.mark(horizonMarkers(source, latitude, longitude), source.id, horizon, clock()) { tag, message, level ->
        appLogDao.log(tag, message, level)
    }

    /** [source]'s horizon markers at the site, in the widget's prefs. */
    private fun horizonMarkers(source: WeatherSource, latitude: Double, longitude: Double) = object : ForecastHorizonMarkers {
        override fun lastLongMs() = widgetStateManager.getLastLongFetch(source.id, latitude, longitude)
        override suspend fun markLong(nowMs: Long) = widgetStateManager.markLongFetch(source.id, latitude, longitude, nowMs)
    }

    private fun buildFetchRegistry(
        fetchContext: ForecastFetchContext?,
        horizons: Map<WeatherSource, ForecastHorizonCadence.Horizon?> = emptyMap(),
        plan: Map<WeatherSource, ForecastParts> = emptyMap(),
    ): Map<WeatherSource, SourceFetchEntry> = buildMap {
        // What each request asks for: a non-splitting source always asks for everything.
        fun parts(source: WeatherSource) = ForecastParts.requested(source, plan[source] ?: ForecastParts.BOTH)
        put(WeatherSource.NWS, SourceFetchEntry(WeatherSource.NWS) { lat, lon ->
            nwsForecastMapper.fetchFromNws(lat, lon, parts(WeatherSource.NWS))
        })
        openWeatherMapApi?.let { api ->
            put(WeatherSource.OPEN_WEATHER_MAP, SourceFetchEntry(WeatherSource.OPEN_WEATHER_MAP) { lat, lon ->
                api.getForecast(lat, lon)
            })
        }
        put(
            WeatherSource.OPEN_METEO,
            SourceFetchEntry(
                WeatherSource.OPEN_METEO,
                // Only Open-Meteo has a previous-runs product, and this rides its fetch so
                // the call is never spent when Open-Meteo is not being fetched at all.
                afterPersist = { lat, lon -> fetchPriorDayCloudForecast(lat, lon) },
            ) { lat, lon ->
                openMeteoApi.getForecast(lat, lon, historyDays = 7, parts = parts(WeatherSource.OPEN_METEO)).also { raw ->
                    // The same reply's next 3 h of 15-minute precipitation: the nowcast, for free.
                    raw.nowcast?.let { com.weatherwidget.widget.AndroidNowcast.store(context).saveSeries(lat, lon, it) }
                }
            },
        )
        put(
            WeatherSource.WEATHER_API,
            SourceFetchEntry(
                WeatherSource.WEATHER_API,
                // Actuals: on the daily cadence, not the hourly one.
                afterPersist = { lat, lon ->
                    if (plan[WeatherSource.WEATHER_API]?.daily != false) weatherApiHistoryBackfiller.backfillIfNeeded(lat, lon)
                },
            ) { lat, lon -> weatherApi.getForecast(lat, lon) },
        )
        put(WeatherSource.SILURIAN, SourceFetchEntry(WeatherSource.SILURIAN) { lat, lon ->
            silurianApi.getForecast(lat, lon, parts(WeatherSource.SILURIAN))
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
                run {
                    api.getForecast(
                        lat,
                        lon,
                        // An on-demand day needs no elapsed hours; history/hours has a 20/day quota.
                        includeHistory = fetchContext?.hourlyAhead == null &&
                            googleNeedsHistory(lat, lon, fetchContext?.historyRefillDay),
                        storedHours = storedHours,
                        parts = parts(WeatherSource.GOOGLE_WEATHER),
                        hoursAhead = HourlyOnDemand.askHours(WeatherSource.GOOGLE_WEATHER.id, fetchContext?.hourlyAhead, horizons[WeatherSource.GOOGLE_WEATHER]),
                    ).also {
                        appLogDao.log(GoogleWeatherApi.HOURS_PAGES_TAG, api.hoursPagesLine(onDemandDay = fetchContext?.hourlyAhead != null), "INFO")
                        fetchContext?.historyRefillDay?.let { day -> refillStaleGoogleHours(it, lat, lon, day) }
                    }
                }
            })
        }
        tomorrowIoApi?.let { api ->
            put(WeatherSource.TOMORROW_IO, SourceFetchEntry(WeatherSource.TOMORROW_IO) { lat, lon ->
                run {
                    coroutineScope {
                        val forecastDeferred = async { api.getForecast(lat, lon, parts(WeatherSource.TOMORROW_IO)) }
                        val historyDeferred = async {
                            // Actuals: on the daily cadence, not the hourly one.
                            if (plan[WeatherSource.TOMORROW_IO]?.daily == false) return@async null
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

    /** Every one of [sourcesToFetch], both parts. */
    suspend fun fetchFromAllApis(
        latitude: Double,
        longitude: Double,
        sourcesToFetch: Set<WeatherSource>,
        fetchContext: ForecastFetchContext? = null,
    ) = fetchFromAllApis(latitude, longitude, sourcesToFetch.associateWith { ForecastParts.BOTH }, fetchContext)

    /**
     * Each source in [plan] with its [ForecastParts]. Daily rows are saved only when the plan asked
     * for them — an hourly-only fetch of a source that cannot split (WeatherAPI, OpenWeatherMap) gets
     * daily rows back and drops them, so its daily stays on the daily cadence.
     */
    suspend fun fetchFromAllApis(
        latitude: Double,
        longitude: Double,
        plan: Map<WeatherSource, ForecastParts>,
        fetchContext: ForecastFetchContext? = null,
    ) = coroutineScope {
        val sourcesToFetch = plan.keys
        val horizons = sourcesToFetch.associateWith { fetchHorizon(fetchContext) }
        val registry = buildFetchRegistry(fetchContext, horizons, plan)
        // The network choke point. `visibleSources()` already drops sources that cannot serve the
        // *stored* active location; this re-checks against the coordinates actually being fetched,
        // so no caller can send NWS a point outside its coverage (a guaranteed 404 InvalidPoint).
        val servable = sourcesToFetch.filterTo(LinkedHashSet()) { SourceCoverage.supports(it.id, latitude, longitude) }

        val fetchedBySource = servable.mapNotNull { source ->
            val entry = registry[source] ?: return@mapNotNull null
            async {
                source to safeFetch(entry.tag, source, latitude, longitude) {
                    entry.fetch(latitude, longitude)?.also {
                        persistFetch(source, it, latitude, longitude, fetchContext, horizons[source], plan.getValue(source))
                        entry.afterPersist(latitude, longitude)
                    }
                }
            }
        }.awaitAll()

        fetchedBySource.forEach { (source, fetched) ->
            if (fetched != null) markHorizon(horizons[source], source, latitude, longitude)
        }

        // NWS daily actuals from a dedicated /stations/{id}/observations pull. Idempotent: only
        // dates still missing a station-derived actual trigger a request. It writes NWS's own
        // daily_history rows only — borrowers read the provider's stored observations — so it rides
        // the NWS forecast fetch, gated with it (SourceFetchGate).
        if (WeatherSource.NWS in servable && plan[WeatherSource.NWS]?.daily == true) {
            runCatching { nwsApiDailyActualsFetcher?.fillMissingIfNeeded(latitude, longitude) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    /**
     * One source only: a tapped or panned-to day's on-demand hourly
     * (`plans/261009-on-demand-hourly-shared-single-source-fetch.md`). The same per-source
     * fetch-and-save the full sync runs — [fetchContext]'s `hourlyAhead` deepens Google's hours; every
     * other source's single call returns its whole horizon anyway — and nothing else: no other
     * source, actuals recompute, repairs or widget paint. True when the source answered.
     */
    suspend fun fetchSingleSource(
        latitude: Double,
        longitude: Double,
        source: WeatherSource,
        fetchContext: ForecastFetchContext,
    ): Boolean {
        if (!SourceCoverage.supports(source.id, latitude, longitude)) return false
        val horizon = fetchHorizon(fetchContext)
        val entry = buildFetchRegistry(fetchContext, mapOf(source to horizon))[source] ?: return false
        safeFetch(entry.tag, source, latitude, longitude) {
            entry.fetch(latitude, longitude)?.also {
                persistFetch(source, it, latitude, longitude, fetchContext, horizon, ForecastParts.BOTH)
                entry.afterPersist(latitude, longitude)
            }
        } ?: return false
        markHorizon(horizon, source, latitude, longitude)
        return true
    }

    /**
     * One source's answer stored: quota banners, provider actuals, then the `:shared`
     * [ForecastPersistRun] (same as desktop) — live hours, the fetch's snapshot and elapsed-hour
     * backfill, the daily rows. Daily rows only when [parts] asked for them: a source that cannot
     * split (WeatherAPI, OpenWeatherMap) sends them anyway, and its daily stays on the daily cadence.
     */
    private suspend fun persistFetch(
        source: WeatherSource,
        result: RawFetch,
        latitude: Double,
        longitude: Double,
        fetchContext: ForecastFetchContext?,
        horizon: ForecastHorizonCadence.Horizon?,
        parts: ForecastParts,
    ) {
        recordProductQuotas(source, result)
        if (source != WeatherSource.NWS) saveProviderActuals(source, result, latitude, longitude)
        ForecastPersistRun(
            hourlyStore.forecastPersistHost(snapshotStore.forecastDao, appLogDao, source.id, latitude, longitude),
        ).persist(
            ForecastPersistRun.Fetch(
                hourly = result.hourly,
                elapsedHourly = result.elapsedHourly,
                daily = result.daily.takeIf { parts.daily },
                keep = hourlyKeepUntilMs(source, fetchContext, horizon),
                userRefresh = isUserRefreshOf(source, fetchContext),
            ),
            clock(),
        )
    }

    /**
     * Providers with an actuals-capable historical product pass it as [RawFetch.subHourly]; else the
     * hourly series is offered ([HistoricalActualsBackfill] decides). Tomorrow.io offers only its
     * five-minute Timeline result — its elapsed hourly forecast rows are never observations.
     */
    private suspend fun saveProviderActuals(source: WeatherSource, result: RawFetch, latitude: Double, longitude: Double) {
        if (result.hourly.isEmpty()) return
        hourlyStore.saveHistoricalActuals(
            result.subHourly.ifEmpty { if (source == WeatherSource.TOMORROW_IO) emptyList() else result.hourly },
            latitude,
            longitude,
            source.id,
            tomorrowIoWindowHours = TomorrowIoApi.FULL_ACTUALS_LOOKBACK_HOURS.takeIf { source == WeatherSource.TOMORROW_IO },
        )
    }

    /** The views' quota banners: the shared [QuotaState.record] rule over the widget's prefs. */
    private suspend fun recordProductQuotas(source: WeatherSource, result: RawFetch) =
        QuotaState.record(quotaState, source.id, result, clock())

    private val quotaState by lazy { com.weatherwidget.widget.AndroidQuotaState(widgetStateManager, appLogDao) }

    /** NWS fetched and stored as a full sync would (both parts, no fetch context) — a test hook. */
    suspend fun fetchFromNws(latitude: Double, longitude: Double): RawFetch =
        nwsForecastMapper.fetchFromNws(latitude, longitude).also {
            persistFetch(WeatherSource.NWS, it, latitude, longitude, null, null, ForecastParts.BOTH)
        }

    /** [GoogleHistoryRun.needsHistory] at this site; without a history DAO, always. */
    private suspend fun googleNeedsHistory(latitude: Double, longitude: Double, refillDay: java.time.LocalDate?): Boolean {
        val run = googleHistory(latitude, longitude) ?: return true
        return run.needsHistory(refillDay, clock())
    }

    /** A past day's History refresh: [GoogleHistoryRun.refill]. */
    private suspend fun refillStaleGoogleHours(result: RawFetch, latitude: Double, longitude: Double, day: java.time.LocalDate) {
        googleHistory(latitude, longitude)?.refill(result.providerHistoryHourly, day, clock())
    }

    private fun googleHistory(latitude: Double, longitude: Double): GoogleHistoryRun? {
        hourlyForecastHistoryDao ?: return null
        return GoogleHistoryRun(hourlyStore.googleHistoryHost(latitude, longitude, appLogDao))
    }

    /** Without a cadence context (no device state or site): the old rank thresholds. */
    private fun isStale(
        source: WeatherSource,
        forecasts: List<ForecastEntity>,
    ): Boolean {
        val lastSourceFetchTime = forecasts
            .filter { it.source == source.id }
            .maxOfOrNull { it.lastConfirmedAt } ?: 0L
        val position = widgetStateManager.getVisibleSourcesOrder().indexOf(source)
        val threshold = ForecastStalenessPolicy.getStalenessThresholdMs(position)
        return System.currentTimeMillis() - lastSourceFetchTime >= threshold
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
            if (if (result is RawFetch) result.daily.isNotEmpty() else result !is Collection<*> || result.isNotEmpty()) {
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
                "source=${source.id} error=${exception.message}" +
                    // Which half of the key resolution came back empty: `bakedLen=0` is a build made
                    // without local.properties (2026-10-25: a worktree's APK on the emulator).
                    if (exception.message?.contains("API_KEY is missing") == true) {
                        " keys=${BuiltInApiKeys.describe(source, widgetStateManager)}"
                    } else {
                        ""
                    },
                "WARN",
            )
        }
    }

    private fun extractErrorCode(exception: Exception): String =
        FetchErrorCode.of(exception) { NetworkRestrictionHelper.isBackgroundDataRestricted(context) }

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
}
