package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.remote.AviationWeatherStationFilter
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsMetarTextApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * The NWS current-observation web leg on both platforms: NWS's own latest-METAR text file
 * ([NwsMetarTextApi]), one request per station the tier policy reads web for
 * ([ObservationFallbackPolicy.shouldLogWebMetrics]), all started before any API answer so the two
 * transports overlap. plans/261015-nws-web-leg-from-nws-tgftp-metar-text.md.
 */
object NwsMetarTextWeb {
    const val TRANSPORT = "nws_metar_text"

    /**
     * Only official airport stations publish ICAO METAR reports to NOAA's METAR dissemination server
     * (`tgftp.nws.noaa.gov/data/observations/metar/stations/{ID}.TXT`). Personal weather stations (CWOP/MADIS)
     * and RAWS fire weather stations never have METAR files there; querying them causes hangs/timeouts.
     */
    fun isMetarStation(station: NwsApi.StationInfo): Boolean =
        station.type == com.weatherwidget.data.model.StationType.OFFICIAL ||
            (station.type == com.weatherwidget.data.model.StationType.UNKNOWN && station.id.length == 4 && (station.id.startsWith("K") || station.id.startsWith("P") || station.id.startsWith("T")))

    fun start(
        scope: CoroutineScope,
        api: NwsMetarTextApi,
        stations: List<NwsApi.StationInfo>,
        siteLat: Double,
        siteLon: Double,
        nowMs: () -> Long = System::currentTimeMillis,
    ): NwsStationLatestFetcher.WebSource {
        val elapsed = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val pending = stations.withIndex()
            .filter { ObservationFallbackPolicy.shouldLogWebMetrics(it.index) }
            .associate { (_, station) ->
                station.id to scope.async {
                    if (!isMetarStation(station)) {
                        FetchOutcome.NoData
                    } else {
                        val startMs = nowMs()
                        fetchReading(api, station, siteLat, siteLon).also { elapsed[station.id] = nowMs() - startMs }
                    }
                }
            }
        return NwsStationLatestFetcher.WebSource(
            transport = TRANSPORT,
            elapsedMs = { station -> elapsed[station.id] },
        ) { station, _, _ ->
            val outcome = pending[station.id]?.await() ?: FetchOutcome.NoData
            NwsStationLatestFetcher.batchRows(outcome, station.id, nowMs())
        }
    }

    /** One station's file as an NWS web row's input; also the history fallback's single row. */
    suspend fun fetchReading(
        api: NwsMetarTextApi,
        station: NwsApi.StationInfo,
        siteLat: Double,
        siteLon: Double,
    ): FetchOutcome<List<ObservationReading>> =
        if (!isMetarStation(station)) {
            FetchOutcome.NoData
        } else {
            when (val outcome = api.fetchLatest(station.id)) {
                is FetchOutcome.Success -> MetarObservationMapper.toReading(
                    outcome.value,
                    // Distance is re-measured from NWS's station coordinates by asNwsWebRow.
                    AviationWeatherStationFilter.RankedStation(station, distanceKm = 0.0, elevationMeters = null),
                    siteLat,
                    siteLon,
                )?.let { FetchOutcome.Success(listOf(it)) } ?: FetchOutcome.NoData
                is FetchOutcome.NoData -> FetchOutcome.NoData
                is FetchOutcome.Failed -> outcome
            }
        }
}
