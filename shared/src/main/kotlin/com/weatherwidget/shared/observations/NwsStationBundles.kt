package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsMetarTextApi
import com.weatherwidget.data.remote.shouldTouchObservationFetchedAt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant

/**
 * One NWS observation pass over the nearest stations, both platforms: per station its `latest`
 * reading AND every reading in a window, so a station publishing faster than the cycle (KSJC ~5 min)
 * loses nothing between cycles (plans/260820-observation-loop-recent-window-not-latest-row.md).
 * Android's current-temp cycle fetched `latest` only and repaired gaps later; desktop the window —
 * one way now (user, 2026-10-23, plans/261023-nws-observation-cycle-latest-plus-recent-window-both-platforms.md).
 *
 * Moved from desktop's `fetchObservationBundles`. The platform supplies its fetchers (its HTTP, its
 * stall retry) and a [Host] for the attempt touch and the logs.
 */
object NwsStationBundles {
    /** The current-temp cycle's window: long enough for a slow station's last report (KPAO ~90 min). */
    const val RECENT_WINDOW_MINUTES = 90L

    const val MAX_STATIONS = 5

    /** One station's rows: [latest] the merged current reading, [historical] the window (or its web fallback). */
    data class Bundle(
        val station: NwsApi.StationInfo,
        val latest: ObservationReading?,
        val historical: List<ObservationReading>,
        /** The API observation stored beside a web-won latest ([NwsStationLatestFetcher.StationLatest.cloudCarrier]). */
        val cloudCarrier: ObservationReading? = null,
    ) {
        /** Everything to store: latest, the window, the cloud carrier. */
        val rows: List<ObservationReading> get() = listOfNotNull(latest) + historical + listOfNotNull(cloudCarrier)
    }

    interface Host {
        /** The attempt finished with nothing storable: stamp the station's newest row at this site. */
        suspend fun touchLatest(stationId: String)

        suspend fun log(tag: String, message: String, level: String)
    }

    /**
     * [recentOnly]: the current-temp cycle's window ([windowStart] ≈ now − [RECENT_WINDOW_MINUTES]),
     * where an empty window from a slow station still yields its latest reading. Otherwise a longer
     * history window (the forecast pull's days), where an empty window means a silent station.
     */
    suspend fun fetch(
        stations: List<NwsApi.StationInfo>,
        latitude: Double,
        longitude: Double,
        latestFetcher: NwsStationLatestFetcher,
        historyFetcher: NwsStationHistoryFetcher,
        metarTextApi: NwsMetarTextApi?,
        synopticApi: com.weatherwidget.data.remote.SynopticApi? = null,
        windowStart: Instant,
        windowEnd: Instant,
        recentOnly: Boolean,
        host: Host,
    ): List<Bundle> = coroutineScope {
        val nearest = stations.take(MAX_STATIONS)
        // METAR starts alongside the API jobs. Synoptic is consulted only if both remain stale.
        val primaryWeb = metarTextApi?.let { NwsMetarTextWeb.start(this, it, nearest, latitude, longitude) }
        val web = SynopticStaleStationFallback.wrap(
            primaryWeb, synopticApi, latitude, longitude,
            log = { tag, message, level -> host.log(tag, message, level) },
        )

        nearest.mapIndexed { index, station ->
            async {
                // One station throwing must not cancel the others' jobs (it would, inside coroutineScope).
                try {
                    stationPass(station, index, latitude, longitude, latestFetcher, historyFetcher, web, windowStart, windowEnd, recentOnly, host)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    NwsStationAttemptLog.threw(station.id, e).let { host.log(it.tag, it.message, it.level) }
                    null
                }
            }
        }.mapNotNull { it.await() }
    }

    private suspend fun stationPass(
        station: NwsApi.StationInfo,
        index: Int,
        latitude: Double,
        longitude: Double,
        latestFetcher: NwsStationLatestFetcher,
        historyFetcher: NwsStationHistoryFetcher,
        web: NwsStationLatestFetcher.WebSource?,
        windowStart: Instant,
        windowEnd: Instant,
        recentOnly: Boolean,
        host: Host,
    ): Bundle? {
        // Latest regardless of the window: it anchors the current temp and can be fresher than
        // the window's newest row.
        val latestResult = latestFetcher.fetch(station, index, latitude, longitude, web)
        val latest = latestResult.chosen
        latestResult.cloudCarrier?.let { carrier ->
            NwsStationAttemptLog.cloudCarrier(station.id, carrier.timestamp, carrier.cloudCoverLow)
                .let { host.log(it.tag, it.message, it.level) }
        }

        // The window with its web fallback (this station's web rows, "use" tier only). Tri-state
        // outcome: an empty window (station silent) and a failed request (nothing learned)
        // demand opposite fetchedAt handling below.
        val webRows = latestResult.webRows
        val history = historyFetcher.fetch(
            station, index, latitude, longitude, windowStart.toString(), windowEnd.toString(),
            web = if (ObservationFallbackPolicy.shouldFetchWeb(index)) ({ webRows }) else null,
        )
        val historical = history.rows

        return when {
            historical.isNotEmpty() && !history.usedWebFallback ->
                Bundle(station, latest, historical, latestResult.cloudCarrier)
            // NWS history empty or stale: the web readings stand in. QC-flagged stay in
            // historical for the stations UI; the chosen usable latest anchors current temp.
            history.usedWebFallback ->
                Bundle(station, latest, historical.filter { it != latest }, latestResult.cloudCarrier)
            // Recent window against a slow station: empty is legitimate, the latest still feeds
            // the blend. Only for recentOnly — on a long window empty means silent.
            recentOnly && latest != null -> Bundle(station, latest, emptyList(), latestResult.cloudCarrier)
            // Finished, nothing storable: record the attempt so a silent station shows a fresh
            // "Fetched" against an old "Reported".
            shouldTouchObservationFetchedAt(history.apiOutcome, latestResult.webOutcome) -> {
                host.touchLatest(station.id)
                NwsStationAttemptLog.touched(station.id).let { host.log(it.tag, it.message, it.level) }
                null
            }
            // Every upstream failed: fetchedAt stays frozen (a dead network is not a silent station).
            else -> {
                NwsStationAttemptLog.failed(
                    station.id, (history.apiOutcome as? FetchOutcome.Failed)?.reason, latestResult.webFailureReason,
                ).let { host.log(it.tag, it.message, it.level) }
                null
            }
        }
    }
}
