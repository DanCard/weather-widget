package com.weatherwidget.data.repository

import com.weatherwidget.shared.actuals.TomorrowIoActuals
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
import com.weatherwidget.shared.util.DailyHourlyConsistency
import com.weatherwidget.shared.util.DailyHourlySummaries
import com.weatherwidget.shared.util.DailyPrecipPeriods
import com.weatherwidget.data.local.hourlySummary
import com.weatherwidget.data.local.withHourlySummary
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.remote.ApiAccessException
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
     * Whether an unforced sync has anything to fetch: a source whose LONG or SHORT horizon is due
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
     * A force takes both parts; an unforced sync takes a source whose horizon is due
     * ([ForecastHorizonCadence]), less any quota-refused product ([ForecastParts.unblocked]). Which
     * horizon each fetch is ([fetchHorizon]) is judged again at fetch time from the same markers.
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
            lastLongMs = horizonMarkers(source, latitude, longitude).lastLongMs(),
            lastShortMs = horizonMarkers(source, latitude, longitude).lastShortMs(),
            nowMs = clock(),
            wakeAtMs = fetchContext.wakeAtMs,
        )
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
    /**
     * Last instants (exclusive) of hourly a fetch of [source] stores, live and snapshot: 72 h
     * routinely, a LONG fetch's 8 days live, deeper for the on-demand day it was asked for
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
     * LONG or SHORT for a fetch of [source] at the site ([ForecastHorizonCadence.forced]: a user's
     * Refresh of the displayed source is LONG; anything else takes what is due, at least SHORT). Null
     * when the context is unknown or the fetch is an on-demand day, which have their own horizon.
     */
    private fun fetchHorizon(
        source: WeatherSource,
        latitude: Double,
        longitude: Double,
        fetchContext: ForecastFetchContext?,
    ): ForecastHorizonCadence.Horizon? {
        if (fetchContext == null || fetchContext.hourlyAhead != null) return null
        return ForecastHorizonCadence.forced(
            horizonDecision(source, fetchContext, latitude, longitude),
            userRefresh = isUserRefreshOf(source, fetchContext),
        )
    }

    /** A manual Refresh is LONG for the displayed source only; others keep the routine rule. */
    private fun isUserRefreshOf(source: WeatherSource, fetchContext: ForecastFetchContext?): Boolean =
        fetchContext?.userRefresh == true && source.id in fetchContext.activeSourceIds

    /**
     * A fetch that answered restarts the SHORT cadence; a LONG one also restarts the LONG cadence
     * ([ForecastHorizonCadence]). Logged `FORECAST_HORIZON`.
     */
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
        override fun lastShortMs() = widgetStateManager.getLastShortFetch(source.id, latitude, longitude)
        override suspend fun markLong(nowMs: Long) = widgetStateManager.markLongFetch(source.id, latitude, longitude, nowMs)
        override suspend fun markShort(nowMs: Long) = widgetStateManager.markShortFetch(source.id, latitude, longitude, nowMs)
    }

    private fun buildFetchRegistry(
        fetchContext: ForecastFetchContext?,
        horizons: Map<WeatherSource, ForecastHorizonCadence.Horizon?> = emptyMap(),
        plan: Map<WeatherSource, ForecastParts> = emptyMap(),
    ): Map<WeatherSource, SourceFetchEntry> = buildMap {
        // What each request asks for: a non-splitting source always asks for everything.
        fun parts(source: WeatherSource) = ForecastParts.requested(source, plan[source] ?: ForecastParts.BOTH)
        put(WeatherSource.NWS, SourceFetchEntry(WeatherSource.NWS) { lat, lon ->
            fetchFromNws(lat, lon, hourlyKeepUntilMs(WeatherSource.NWS, fetchContext, horizons[WeatherSource.NWS]), parts(WeatherSource.NWS))
        })
        openWeatherMapApi?.let { api ->
            put(WeatherSource.OPEN_WEATHER_MAP, SourceFetchEntry(WeatherSource.OPEN_WEATHER_MAP) { lat, lon ->
                fetchAndSaveSharedForecast(lat, lon, WeatherSource.OPEN_WEATHER_MAP, hourlyKeepUntilMs(WeatherSource.OPEN_WEATHER_MAP, fetchContext, horizons[WeatherSource.OPEN_WEATHER_MAP])) {
                    api.getForecast(lat, lon)
                }
            })
        }
        put(WeatherSource.OPEN_METEO, SourceFetchEntry(WeatherSource.OPEN_METEO) { lat, lon ->
            fetchAndSaveSharedForecast(lat, lon, WeatherSource.OPEN_METEO, hourlyKeepUntilMs(WeatherSource.OPEN_METEO, fetchContext, horizons[WeatherSource.OPEN_METEO])) {
                openMeteoApi.getForecast(lat, lon, historyDays = 7, parts = parts(WeatherSource.OPEN_METEO)).also { raw ->
                    // The same reply's next 3 h of 15-minute precipitation: the nowcast, for free.
                    raw.nowcast?.let { com.weatherwidget.widget.AndroidNowcast.store(context).saveSeries(lat, lon, it) }
                }
            }.also {
                // Only Open-Meteo has a previous-runs product, and this rides its fetch so
                // the call is never spent when Open-Meteo is not being fetched at all.
                fetchPriorDayCloudForecast(lat, lon)
            }
        })
        put(WeatherSource.WEATHER_API, SourceFetchEntry(WeatherSource.WEATHER_API) { lat, lon ->
            val forecasts = fetchAndSaveSharedForecast(lat, lon, WeatherSource.WEATHER_API, hourlyKeepUntilMs(WeatherSource.WEATHER_API, fetchContext, horizons[WeatherSource.WEATHER_API])) {
                weatherApi.getForecast(lat, lon)
            }
            // Actuals: on the daily cadence, not the hourly one.
            if (plan[WeatherSource.WEATHER_API]?.daily != false) weatherApiHistoryBackfiller.backfillIfNeeded(lat, lon)
            forecasts
        })
        put(WeatherSource.SILURIAN, SourceFetchEntry(WeatherSource.SILURIAN) { lat, lon ->
            fetchFromSilurian(lat, lon, hourlyKeepUntilMs(WeatherSource.SILURIAN, fetchContext, horizons[WeatherSource.SILURIAN]), parts(WeatherSource.SILURIAN))
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
                fetchAndSaveSharedForecast(lat, lon, WeatherSource.GOOGLE_WEATHER, hourlyKeepUntilMs(WeatherSource.GOOGLE_WEATHER, fetchContext, horizons[WeatherSource.GOOGLE_WEATHER])) {
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
                fetchAndSaveSharedForecast(lat, lon, WeatherSource.TOMORROW_IO, hourlyKeepUntilMs(WeatherSource.TOMORROW_IO, fetchContext, horizons[WeatherSource.TOMORROW_IO])) {
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
        val horizons = sourcesToFetch.associateWith { fetchHorizon(it, latitude, longitude, fetchContext) }
        val registry = buildFetchRegistry(fetchContext, horizons, plan)
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
                val parts = plan.getValue(source)
                if (parts.daily) saveDailyBatch(it, latitude, longitude, source, horizons[source], userRefresh = isUserRefreshOf(source, fetchContext))
                markHorizon(horizons[source], source, latitude, longitude)
            }
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
        val horizon = fetchHorizon(source, latitude, longitude, fetchContext)
        val entry = buildFetchRegistry(fetchContext, mapOf(source to horizon))[source] ?: return false
        val forecasts = safeFetch(entry.tag, source, latitude, longitude) { entry.fetch(latitude, longitude) }
            ?: return false
        saveDailyBatch(forecasts, latitude, longitude, source, horizon, userRefresh = isUserRefreshOf(source, fetchContext))
        markHorizon(horizon, source, latitude, longitude)
        return true
    }

    /**
     * The fetch's daily rows; a SHORT fetch keeps only the next [ForecastHorizonCadence.SHORT_DAYS]
     * days, so days past them stay as the last LONG fetch left them.
     */
    private suspend fun saveDailyBatch(
        forecasts: List<ForecastEntity>,
        latitude: Double,
        longitude: Double,
        source: WeatherSource,
        horizon: ForecastHorizonCadence.Horizon?,
        userRefresh: Boolean = false,
    ) {
        val nowMs = clock()
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val kept = forecasts.filter {
            ForecastHorizonCadence.storesDailyDate(horizon, LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY), today)
        }
        snapshotStore.saveForecastSnapshot(
            withStoredHourlySummaries(kept, latitude, longitude, source.id),
            latitude,
            longitude,
            source.id,
            batchFetchedAt = nowMs,
            nowMs = nowMs,
            userRefresh = userRefresh,
        )
    }

    /** The views' quota banners: the shared [QuotaState.record] rule over the widget's prefs. */
    private suspend fun recordProductQuotas(source: WeatherSource, result: RawFetch) =
        QuotaState.record(quotaState, source.id, result, clock())

    private val quotaState by lazy { com.weatherwidget.widget.AndroidQuotaState(widgetStateManager, appLogDao) }

    /**
     * The daily view's hourly-derived values for each row ([DailyHourlySummaries], shared with
     * desktop): the fetch's own download (set by [withPayloadSummaries]) per field, else the site's
     * stored hours — every fetch saves its hourly before its daily rows, and a Google one-page fetch
     * leaves hours 25–72 in place. A field neither covers stays null here and is carried from the
     * previous row by [ForecastSnapshotStore.saveForecastSnapshot], which also resolves the stored
     * day/night chance from it ([DailyPrecipPeriods]).
     */
    private suspend fun withStoredHourlySummaries(
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
            entity.withHourlySummary(
                DailyHourlySummaries.carryForward(
                    incoming = entity.hourlySummary,
                    prior = if (stored.isEmpty()) null else DailyHourlySummaries.forDate(stored, date, sourceId),
                ),
            )
        }
    }

    /** `DAILY_HOURLY_MISMATCH` ([DailyHourlyConsistency]): this fetch's daily extremes vs its own hourly. */
    private suspend fun logDailyHourlyMismatches(forecasts: List<ForecastEntity>, payload: List<HourlyForecast>, sourceId: String) {
        val days = forecasts.map {
            DailyHourlyConsistency.Day(LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY), it.highTemp, it.lowTemp)
        }
        val mismatches = DailyHourlyConsistency.check(days, payload, LocalDate.now())
        if (mismatches.isNotEmpty()) {
            appLogDao.log(DailyHourlyConsistency.LOG_TAG, DailyHourlyConsistency.logMessage(sourceId, mismatches), "WARN")
        }
    }

    /** [forecasts] with each day's summary from this fetch's whole hourly [payload], before any trim. */
    private suspend fun withPayloadSummaries(
        forecasts: List<ForecastEntity>,
        payload: List<HourlyForecast>,
        latitude: Double,
        longitude: Double,
        sourceId: String,
    ): List<ForecastEntity> {
        if (forecasts.isEmpty() || payload.isEmpty()) return forecasts
        logDailyHourlyMismatches(forecasts, payload, sourceId)
        val summarized = forecasts.map { entity ->
            val date = LocalDate.ofEpochDay(entity.targetDate / WidgetConstants.MS_IN_A_DAY)
            entity.withHourlySummary(DailyHourlySummaries.forDate(payload, date, sourceId))
        }
        appLogDao.log(
            "DAILY_SUMMARY",
            "source=$sourceId site=${LocationMatch.quantize(latitude)},${LocationMatch.quantize(longitude)} " +
                "days=${summarized.size} noon=${summarized.count { it.noonCloudPercent != null }} " +
                "day=${summarized.count { it.hourlyDayPrecipMax != null }} " +
                "night=${summarized.count { it.hourlyNightPrecipMax != null }}",
            "VERBOSE",
        )
        return summarized
    }

    suspend fun fetchFromNws(
        latitude: Double,
        longitude: Double,
        keepUntilMs: HourlyOnDemand.KeepUntil = hourlyKeepUntilMs(WeatherSource.NWS, null),
        parts: ForecastParts = ForecastParts.BOTH,
    ): List<ForecastEntity> {
        val (forecastEntities, hourlyEntities, elapsedHourly) =
            nwsForecastMapper.fetchFromNws(latitude, longitude, parts)
        if (hourlyEntities.isNotEmpty()) {
            hourlyStore.saveHourlyEntities(hourlyEntities, keepUntilMs)
        }
        backfillElapsedHistory(elapsedHourly, latitude, longitude, WeatherSource.NWS)
        return withPayloadSummaries(
            forecastEntities,
            elapsedHourly + hourlyEntities.map { it.toHourlyForecast() },
            latitude,
            longitude,
            WeatherSource.NWS.id,
        )
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
        summary ?: return
        appLogDao.log(
            ElapsedForecastBackfill.LOG_TAG,
            ElapsedForecastBackfill.logMessage(source.id, latitude, longitude, summary),
            ElapsedForecastBackfill.logLevel(summary),
        )
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
        val dao = hourlyForecastHistoryDao ?: return null
        val keyLat = LocationMatch.quantize(latitude)
        val keyLon = LocationMatch.quantize(longitude)
        val google = WeatherSource.GOOGLE_WEATHER.id
        return GoogleHistoryRun(object : GoogleHistoryRun.Host {
            override suspend fun coveredHistoryHours(startMs: Long, endMs: Long) =
                dao.getHistoryInRangeForBucketWindow(
                    startDateTime = startMs,
                    endDateTime = endMs,
                    bucketStart = Long.MIN_VALUE,
                    bucketEnd = Long.MAX_VALUE,
                    lat = keyLat,
                    lon = keyLon,
                    source = google,
                ).filter { LocationMatch.sameSite(keyLat, keyLon, it.locationLat, it.locationLon) }
                    .map { it.dateTime }

            override suspend fun liveHours(startMs: Long, endMs: Long) =
                hourlyStore.storedHourlyForSite(latitude, longitude, google, startMs, endMs)

            override suspend fun saveRefill(rows: List<HourlyForecast>) =
                hourlyStore.saveRefillHours(rows, latitude, longitude, google)

            override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)
        })
    }

    private suspend fun fetchFromSilurian(
        latitude: Double,
        longitude: Double,
        keepUntilMs: HourlyOnDemand.KeepUntil,
        parts: ForecastParts = ForecastParts.BOTH,
    ): List<ForecastEntity> {
        val result = silurianApi.getForecast(latitude, longitude, parts)
        if (result.hourly.isNotEmpty()) {
            hourlyStore.saveHourlyEntitiesFromShared(
                result.hourly,
                latitude,
                longitude,
                WeatherSource.SILURIAN.id,
                keepUntilMs = keepUntilMs,
            )
        }
        val daily = result.daily.map { day ->
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
        return withPayloadSummaries(daily, result.hourly, latitude, longitude, WeatherSource.SILURIAN.id)
    }

    /** Without a cadence context (no device state or site): the old rank thresholds. */
    private fun isStale(
        source: WeatherSource,
        forecasts: List<ForecastEntity>,
    ): Boolean {
        val lastSourceFetchTime = forecasts
            .filter { it.source == source.id }
            .maxOfOrNull { it.batchFetchedAt } ?: 0L
        val position = widgetStateManager.getVisibleSourcesOrder().indexOf(source)
        val threshold = ForecastStalenessPolicy.getStalenessThresholdMs(position)
        return System.currentTimeMillis() - lastSourceFetchTime >= threshold
    }

    private suspend fun fetchAndSaveSharedForecast(
        latitude: Double,
        longitude: Double,
        source: WeatherSource,
        keepUntilMs: HourlyOnDemand.KeepUntil,
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
                keepUntilMs = keepUntilMs,
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
                TomorrowIoActuals.FETCH_TAG,
                TomorrowIoActuals.fetchLine(
                    TomorrowIoApi.FULL_ACTUALS_LOOKBACK_HOURS,
                    actualsWrite.rowCount,
                    result.subHourly.minOfOrNull { it.dateTime },
                    result.subHourly.maxOfOrNull { it.dateTime },
                    actualsWrite.replacementCount,
                ),
                "INFO",
            )
        }
        return withPayloadSummaries(
            result.daily.map { day -> snapshotStore.mapDailyForecast(day, latitude, longitude, source.id) },
            result.hourly,
            latitude,
            longitude,
            source.id,
        )
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
