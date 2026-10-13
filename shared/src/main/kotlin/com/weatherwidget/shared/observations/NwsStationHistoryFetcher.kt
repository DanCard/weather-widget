package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import kotlinx.coroutines.CancellationException

/**
 * One NWS station's history window, with the web fallback when the API's history is stale or empty
 * ([ObservationFallbackPolicy.shouldUseWebFallback]). Android's `NwsObservationSource.fetchHistorical`
 * and the history half of desktop's `fetchObservationBundles` each wrapped the shared rules in their
 * own copy of this (Phase 4 of plans/261013-shared-current-conditions-fetch.md; "3e" of
 * plans/260909-nws-fetch-unification.md).
 *
 * The web transport stays the platform's ([fetch]'s `web`): Android asks Synoptic for the backfill
 * window, desktop reuses the Aviation Weather rows its latest step already holds.
 */
class NwsStationHistoryFetcher(
    /** `api.weather.gov/stations/{id}/observations?start=&end=` — desktop wraps it in its stall retry. */
    private val observations: suspend (station: NwsApi.StationInfo, startIso: String, endIso: String) -> List<NwsApi.Observation>,
    private val log: suspend (tag: String, message: String, level: String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class StationHistory(
        /** The rows to store: the API window, or the web rows stamped as this station's. */
        val rows: List<ObservationReading>,
        val usedWebFallback: Boolean,
        /** What the API said: [FetchOutcome.NoData] is an answered empty window, distinct from a failure. */
        val apiOutcome: FetchOutcome<List<NwsApi.Observation>>,
    ) {
        /** Simple name of the exception the API call threw, or null when it answered. */
        val apiFailure: String? get() = (apiOutcome as? FetchOutcome.Failed)?.reason?.substringBefore(":")
    }

    /**
     * @param web the station's web rows for the fallback, or null when this platform has none for
     *   it. Asked only when the fallback applies. Null or empty keeps the API rows: replacing stale
     *   history with nothing would only lose rows (Android did; desktop never did).
     * @param fallbackLogTag tag for the `station=… reason=…` line, or null for none.
     */
    suspend fun fetch(
        station: NwsApi.StationInfo,
        index: Int,
        latitude: Double,
        longitude: Double,
        startIso: String,
        endIso: String,
        web: (suspend () -> List<ObservationReading>?)?,
        fallbackLogTag: String? = null,
    ): StationHistory {
        val apiOutcome: FetchOutcome<List<NwsApi.Observation>> = try {
            val window = observations(station, startIso, endIso)
            if (window.isEmpty()) FetchOutcome.NoData else FetchOutcome.Success(window)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("NWS_HISTORY_FETCH_FAIL", "station=${station.id} error=${e::class.simpleName}:${e.message}", "WARN")
            FetchOutcome.failed(e)
        }
        val apiRows = apiOutcome.valueOrNull().orEmpty()
            .map { NwsObservationMapper.toReading(it, station, latitude, longitude) }
        val apiOnly = StationHistory(apiRows, usedWebFallback = false, apiOutcome)

        if (web == null || !ObservationFallbackPolicy.shouldUseWebFallback(index, apiRows.maxOfOrNull { it.timestamp }, nowMs())) {
            return apiOnly
        }
        val apiFailure = apiOnly.apiFailure
        if (fallbackLogTag != null) {
            val reason = ObservationFallbackPolicy.fallbackReason(apiRows.size, apiFetchFailed = apiFailure != null)
            val detail = apiFailure?.let { "$reason:$it" } ?: reason
            log(fallbackLogTag, "station=${station.id} reason=$detail", "INFO")
        }
        val webRows = web().orEmpty().map { NwsStationLatestFetcher.asNwsWebRow(it, station, latitude, longitude) }
        return if (webRows.isEmpty()) apiOnly else StationHistory(webRows, usedWebFallback = true, apiOutcome)
    }
}
