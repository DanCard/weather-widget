package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.remote.NwsApi
import java.time.LocalDate

/**
 * The fetch surface [DesktopWeatherRepository] needs from the network layer. Extracted so the
 * repository depends on a seam it can mock directly (and so tests stop reaching into
 * [DesktopWeatherService] with reflection to substitute API clients).
 */
interface WeatherApiClient {
    /**
     * [recentObservationsOnly] narrows NWS's station-observation pull to the recent window (as in
     * [fetchObservationsOnly]) instead of the 7-day one, so a catch-up refresh can publish without
     * waiting on ~2 MB-per-station history bodies; the caller then fills the window with
     * [fetchObservationHistory]. Ignored by every other source.
     */
    suspend fun fetchForecast(recentObservationsOnly: Boolean = false): RawFetch

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

    suspend fun nearestStationsForDailyActuals(): List<NwsApi.StationInfo>

    suspend fun fetchApiObservationDay(
        station: NwsApi.StationInfo,
        startIso: String,
        endIso: String,
    ): List<ObservationReading>?

    suspend fun fetchHistoricalDailyTemps(startDate: String, endDate: String): List<DailyForecast>

    fun close()
}
