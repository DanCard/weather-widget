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
                    val startMs = nowMs()
                    fetchReading(api, station, siteLat, siteLon).also { elapsed[station.id] = nowMs() - startMs }
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
