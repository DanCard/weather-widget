package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.shouldTouchObservationFetchedAt

/**
 * One NWS station's latest observation: the API's latest report, the station's web (METAR) rows,
 * the prefer-newest merge ([NwsObservationPlanner.mergeLatest]) and the `OBS_WEB_API_DELTA` line.
 * Android's `NwsCurrentObservationUpdater` and desktop's `fetchObservationBundles` each wrapped the
 * merge in their own copy of this (Phase 3d of plans/260909-nws-fetch-unification.md, deferred then;
 * Phase 2 of plans/261013-shared-current-conditions-fetch.md). Each platform still stores the result.
 *
 * Works on [ObservationReading] throughout: desktop used to turn METAR rows into `NwsApi.Observation`
 * for its bundle and back, a round trip that lost the METAR 24 h extremes and re-rounded °F via °C.
 */
class NwsStationLatestFetcher(
    private val latestObservation: suspend (stationId: String) -> FetchOutcome<NwsApi.Observation>,
    private val log: suspend (tag: String, message: String, level: String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * A station's web rows. [rows] receives the station's index and the API's newest timestamp (null
     * when none), which a windowed transport (Synoptic) sizes its window from; the Aviation Weather
     * batch ignores both.
     */
    class WebSource(
        val transport: String,
        val rows: suspend (station: NwsApi.StationInfo, index: Int, apiNewestMs: Long?) -> FetchOutcome<List<ObservationReading>>,
    )

    data class StationLatest(
        val station: NwsApi.StationInfo,
        val index: Int,
        /** The row to store as the station's current reading, stamped NWS; web rows carry `isWebFallback`. */
        val chosen: ObservationReading?,
        /** The API row kept beside a web-won [chosen] when it carries low cloud (see [NwsObservationPlanner]). */
        val cloudCarrier: ObservationReading?,
        /** This station's web rows, every one, stamped as NWS web rows (desktop's history fallback reads them). */
        val webRows: List<ObservationReading>,
        /** Null when the station's tier fetches no web rows. */
        val webOutcome: FetchOutcome<List<ObservationReading>>?,
        /** The API answered, or said "nothing", but no usable row resulted: record the attempt. */
        val shouldTouchFetchedAt: Boolean,
        val nwsFailureReason: String?,
        val webFailureReason: String?,
    )

    suspend fun fetch(
        station: NwsApi.StationInfo,
        index: Int,
        latitude: Double,
        longitude: Double,
        web: WebSource?,
    ): StationLatest {
        val apiOutcome = latestObservation(station.id)
        val apiReading = apiOutcome.valueOrNull()?.let {
            NwsObservationMapper.toReading(it, station, latitude, longitude, isWebFallback = false)
        }
        val apiNewestMs = apiReading?.timestamp
        val fetchWebForUse = ObservationFallbackPolicy.shouldFetchWeb(index)
        val logWebMetrics = ObservationFallbackPolicy.shouldLogWebMetrics(index)

        if (web == null || !(fetchWebForUse || logWebMetrics)) {
            return StationLatest(
                station, index,
                chosen = apiReading,
                cloudCarrier = null,
                webRows = emptyList(),
                webOutcome = null,
                shouldTouchFetchedAt = apiReading == null && shouldTouchObservationFetchedAt(apiOutcome, null),
                nwsFailureReason = (apiOutcome as? FetchOutcome.Failed)?.reason,
                webFailureReason = null,
            )
        }

        val webOutcome = web.rows(station, index, apiNewestMs)
        val webRows = webOutcome.valueOrNull().orEmpty().map { asNwsWebRow(it, station, latitude, longitude) }
        val merge = NwsObservationPlanner.mergeLatest(
            apiLatest = apiReading,
            apiNewestMs = apiNewestMs,
            webReadings = webRows,
            useWebForLatest = fetchWebForUse,
            isQcFailed = { it.qcFailed },
            observedAtMillis = { it.timestamp },
            hasLowCloud = { it.cloudCoverLow != null },
        )
        val deltaMinutes = merge.apiNewestMs?.let { api -> merge.webNewestMs?.let { (it - api) / 60_000L } }
        val outcomeLabel = when (webOutcome) {
            is FetchOutcome.Success -> "success"
            is FetchOutcome.NoData -> "no_data"
            is FetchOutcome.Failed -> "failed:${webOutcome.reason}"
        }
        log(
            "OBS_WEB_API_DELTA",
            "station=${station.id} index=$index tier=${if (fetchWebForUse) "use" else "metrics"} " +
                "transport=${web.transport} outcome=$outcomeLabel apiNewestMs=${merge.apiNewestMs} " +
                "webNewestMs=${merge.webNewestMs} deltaMin=$deltaMinutes " +
                "apiTempF=${apiReading?.temperature} webTempF=${webRows.lastOrNull { !it.qcFailed }?.temperature} " +
                "webQcFailed=${webRows.any { it.qcFailed }} chosen=${if (merge.chosenIsWeb) "web" else "api"}",
            "INFO",
        )
        val chosen = merge.chosen
        return StationLatest(
            station, index,
            chosen = chosen,
            cloudCarrier = merge.cloudCarrier,
            webRows = webRows,
            webOutcome = webOutcome,
            shouldTouchFetchedAt = chosen == null && shouldTouchObservationFetchedAt(apiOutcome, webOutcome),
            nwsFailureReason = (apiOutcome as? FetchOutcome.Failed)?.reason,
            webFailureReason = (webOutcome as? FetchOutcome.Failed)?.reason,
        )
    }

    companion object {
        /**
         * A web (METAR / Synoptic) row filed as this NWS station's, at the same site and distance as
         * the station's API rows ([NwsObservationMapper.toReading]). The Aviation Weather feed puts
         * stations at its own coordinates: KPAO 5.0 km from Mountain View against NWS's 6.0 km, so the
         * blend weighted one station two ways depending on which feed won the hour
         * (memory idw_weight_window_dependent_distance; replay 2026-10-13).
         */
        fun asNwsWebRow(
            row: ObservationReading,
            station: NwsApi.StationInfo,
            siteLat: Double,
            siteLon: Double,
        ): ObservationReading =
            row.copy(
                api = WeatherSource.NWS.id,
                isWebFallback = true,
                stationName = station.name,
                stationType = station.type,
                locationLat = siteLat,
                locationLon = siteLon,
                distanceKm = NwsObservationMapper.distanceKm(siteLat, siteLon, station.lat, station.lon).toFloat(),
            )

        /**
         * One station's rows out of the Aviation Weather batch both platforms start before the
         * station jobs, minus any stamped further ahead than [ObservationFallbackPolicy.MAX_WEB_FUTURE_SKEW_MS].
         */
        fun batchRows(
            batch: FetchOutcome<List<ObservationReading>>,
            stationId: String,
            nowMs: Long,
        ): FetchOutcome<List<ObservationReading>> =
            when (batch) {
                is FetchOutcome.Success -> batch.value
                    .filter { it.stationId == stationId && it.timestamp <= nowMs + ObservationFallbackPolicy.MAX_WEB_FUTURE_SKEW_MS }
                    .let { if (it.isEmpty()) FetchOutcome.NoData else FetchOutcome.Success(it) }
                is FetchOutcome.NoData -> FetchOutcome.NoData
                is FetchOutcome.Failed -> batch
            }
    }
}
