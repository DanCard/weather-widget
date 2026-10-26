package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.observations.NwsGapFillRun
import java.time.LocalDate

/**
 * The fetch surface [DesktopWeatherRepository] needs from the network layer. Extracted so the
 * repository depends on a seam it can mock directly (and so tests stop reaching into
 * [DesktopWeatherService] with reflection to substitute API clients).
 */
interface WeatherApiClient {
    /**
     * The displayed source's forecast. No forecast fetch pulls station observations since NWS's became
     * forecast-only (F6, plans/261025-shared-source-fetch-table.md); the refresh takes them through
     * [fetchObservationsOnly].
     */
    suspend fun fetchForecast(): RawFetch

    /**
     * [parts] ([ForecastParts]): which parts of the forecast to ask for — a source whose API can drop
     * one ([ForecastParts.splits]) does; the others make their one full request. An hourly-only fetch
     * also skips what keeps the daily cadence (Tomorrow.io's 5-minute history).
     */
    suspend fun fetchForecast(parts: ForecastParts): RawFetch =
        fetchForecast()

    /**
     * [refillDay]: a previous day the user refreshed on the Forecast History screen. Google then also
     * requests `history/hours` when that day's stored elapsed hours went stale
     * ([com.weatherwidget.data.remote.GoogleHistoryRefill]). Clients without such a product ignore it.
     */
    suspend fun fetchForecast(parts: ForecastParts, refillDay: LocalDate?): RawFetch =
        fetchForecast(parts)

    /**
     * [horizon]: LONG for a judged fetch, null when unjudged ([com.weatherwidget.data.remote.ForecastHorizonCadence]),
     * so what a billed source is asked for matches what the caller stores. Clients whose request does not follow the horizon ignore it.
     */
    suspend fun fetchForecast(
        parts: ForecastParts,
        refillDay: LocalDate?,
        horizon: com.weatherwidget.data.remote.ForecastHorizonCadence.Horizon?,
    ): RawFetch = fetchForecast(parts, refillDay)

    /**
     * Google's `history/hours` alone (one billed request), for a previous day's refill that must not
     * refetch the forecast — the desktop Observations Refresh. Empty for every other client.
     */
    suspend fun fetchGoogleHistory(): RawFetch = RawFetch()

    /**
     * The displayed source's forecast for a tapped or panned-to day
     * ([com.weatherwidget.data.remote.HourlyOnDemand]): its normal fetch, [hoursAhead] deep where the
     * source takes a horizon (Google), without the observation window.
     */
    suspend fun fetchForecastAhead(hoursAhead: Int): RawFetch = fetchForecast()

    /** Legacy Open-Meteo history backfill entry point; pinned as uncalled by a regression test. */
    suspend fun fetchHistory(historyDays: Int): RawFetch

    suspend fun fetchWeatherApiHistory(date: LocalDate): RawFetch

    suspend fun fetchObservationHistory(historyDays: Long): List<ObservationReading>

    /**
     * Day-ago cloud predictions for elapsed hours, backing the cloud graph's frozen forecast curve.
     * Empty for sources with no previous-runs product — the curve then falls back to the live value.
     */
    suspend fun fetchPriorDayCloudForecast(pastDays: Int): Map<Long, Int> = emptyMap()

    /**
     * Current-observations fetch. [recentOnly] narrows the station-history window to the last
     * [DesktopWeatherService.RECENT_OBSERVATION_WINDOW_MINUTES] rather than the multi-day one — the
     * current-temperature cycle needs every reading since the previous poll, not the whole series,
     * which [fetchForecast]'s full pull re-fetches anyway. It narrows the window; it never collapses
     * it to a single reading. See
     * plans/260820-observation-loop-recent-window-not-latest-row.md.
     */
    suspend fun fetchObservationsOnly(
        recentOnly: Boolean = false,
        /** The UI's picker-save refresh: may fetch Synoptic through a backoff (SynopticBackoff.shouldSkip). */
        userLocationChange: Boolean = false,
    ): RawFetch

    /**
     * One actuals feed's recent observations, whichever source this client was built for: the
     * per-feed fetch of the shared current-conditions run (Phase 3 of
     * plans/261013-shared-current-conditions-fetch.md). The feed's own provider current is kept.
     */
    suspend fun fetchFeedObservations(feed: WeatherSource, recentOnly: Boolean = true): RawFetch

    suspend fun nearestStationsForDailyActuals(): List<NwsApi.StationInfo>

    /**
     * This site's NWS observation stations for the shared [NwsGapFillRun]: [FetchOutcome.Failed] when
     * NWS never answered, so the fill can tell "unreachable" from "no stations here".
     */
    suspend fun nwsObservationStations(): FetchOutcome<List<NwsApi.StationInfo>> = FetchOutcome.NoData

    /** One station's [startIso, endIso) window for [NwsGapFillRun], with NWS's METAR-text web leg (as Android). */
    suspend fun fetchNwsStationWindow(
        station: NwsApi.StationInfo,
        index: Int,
        startIso: String,
        endIso: String,
        fallbackLogTag: String,
    ): NwsGapFillRun.StationRows = NwsGapFillRun.StationRows(emptyList(), usedWebFallback = false)

    suspend fun fetchApiObservationDay(
        station: NwsApi.StationInfo,
        startIso: String,
        endIso: String,
    ): List<ObservationReading>?

    suspend fun fetchHistoricalDailyTemps(startDate: String, endDate: String): List<DailyForecast>

    /**
     * Open-Meteo's 15-minute nowcast at this client's site (`:shared` NowcastFetcher), whatever
     * source the client was built for; null when the client keeps no nowcast store.
     */
    suspend fun refreshNowcast(): com.weatherwidget.shared.nowcast.NowcastFetcher.Outcome? = null

    fun close()
}
