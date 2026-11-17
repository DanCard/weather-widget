package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.remote.AviationWeatherApi
import com.weatherwidget.data.remote.AviationWeatherStationFilter
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsMetarTextApi
import kotlinx.coroutines.CancellationException

/**
 * The NWS **history** web leg on both platforms: the station's own METAR reports over the backfill
 * window from Aviation Weather, so a station whose api.weather.gov history went stale gets back the
 * hours it is missing, not one report. NWS's latest-METAR text file ([NwsMetarTextWeb]) returned only
 * the newest: the emulator's KNUQ came back `rows=1` at 09:28 on 2026-11-17 while its 06:15–08:35
 * `10SM CLR` reports were missing, and KSJC (15.9 km, in fog) stood in for them.
 * plans/261117-today-fog-stale-forecast-and-nws-metar-history-fill.md
 *
 * The rows are the station's own reports, stamped as this NWS station's web rows by the caller
 * ([NwsStationLatestFetcher.asNwsWebRow]) exactly as the live fetch-both path stores them.
 */
object NwsMetarHistoryWeb {
    private const val HOUR_MS = 3_600_000L

    /** Widest window asked for: the gap fill's lookback ([NwsGapFillRun.BACKFILL_DAYS]). */
    const val MAX_HOURS = NwsGapFillRun.BACKFILL_DAYS * 24

    /** Hours back from [nowMs] that reach [startMs], rounded up, within 1..[MAX_HOURS]. */
    fun hoursFor(startMs: Long, nowMs: Long): Int =
        ((nowMs - startMs + HOUR_MS - 1) / HOUR_MS).toInt().coerceIn(1, MAX_HOURS)

    /**
     * [station]'s METAR reports from [startMs] to [nowMs]. A station that publishes no METAR is
     * [FetchOutcome.NoData] without a request. When the history answers nothing usable, the newest
     * report from [latestApi] stands in (what this leg returned before), so nothing is lost.
     */
    suspend fun fetchWindow(
        historyApi: AviationWeatherApi,
        latestApi: NwsMetarTextApi?,
        station: NwsApi.StationInfo,
        siteLat: Double,
        siteLon: Double,
        startMs: Long,
        nowMs: Long,
    ): FetchOutcome<List<ObservationReading>> {
        if (!NwsMetarTextWeb.isMetarStation(station)) return FetchOutcome.NoData
        val history = try {
            historyApi.fetchMetars(listOf(station.id), hoursFor(startMs, nowMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FetchOutcome.failed(e)
        }
        // Distance is re-measured from NWS's station coordinates by asNwsWebRow.
        val ranked = AviationWeatherStationFilter.RankedStation(station, distanceKm = 0.0, elevationMeters = null)
        val rows = history.valueOrNull().orEmpty()
            .filter { it.stationId.equals(station.id, ignoreCase = true) && it.observedAtMillis >= startMs }
            .mapNotNull { MetarObservationMapper.toReading(it, ranked, siteLat, siteLon) }
            .sortedBy { it.timestamp }
        if (rows.isNotEmpty()) return FetchOutcome.Success(rows)
        val latest = latestApi?.let { NwsMetarTextWeb.fetchReading(it, station, siteLat, siteLon) }
        return when {
            latest is FetchOutcome.Success -> latest
            history is FetchOutcome.Failed -> history
            else -> latest ?: FetchOutcome.NoData
        }
    }
}
