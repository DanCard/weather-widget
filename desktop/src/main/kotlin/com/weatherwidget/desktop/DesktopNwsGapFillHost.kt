package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.observations.NwsGapFillRun
import java.time.LocalDate

/**
 * Desktop side of [NwsGapFillRun]: the client's NWS station calls, the desktop SQLite reads, and the
 * repository's own observation write and extremes recompute (passed in, so the fill stores exactly as
 * a refresh does). Android's is `NwsObservationBackfiller`'s Room host.
 */
internal class DesktopNwsGapFillHost(
    private val weatherService: WeatherApiClient,
    private val weatherDao: DesktopWeatherDao,
    private val store: suspend (List<ObservationReading>) -> Unit,
    private val recompute: () -> Unit,
) : NwsGapFillRun.Host {
    override suspend fun stations(latitude: Double, longitude: Double) = weatherService.nwsObservationStations()

    override suspend fun fetchStation(
        station: NwsApi.StationInfo,
        index: Int,
        latitude: Double,
        longitude: Double,
        startIso: String,
        endIso: String,
        fallbackLogTag: String,
    ) = weatherService.fetchNwsStationWindow(station, index, startIso, endIso, fallbackLogTag)

    override suspend fun store(rows: List<ObservationReading>, latitude: Double, longitude: Double) = store.invoke(rows)

    override suspend fun dailyHistoryRows(startDayMs: Long, endDayMs: Long, latitude: Double, longitude: Double) =
        weatherDao.getExtremesInRange(startDayMs, endDayMs, latitude, longitude)
            .map { NwsGapFillRun.DayRow(it.date, it.source) }

    override suspend fun observations(startMs: Long, endMs: Long, latitude: Double, longitude: Double) =
        weatherDao.getObservationsInRange(startMs, endMs, latitude, longitude)
            .map { NwsGapFillRun.ObsStamp(it.timestamp, it.api, it.stationId) }

    /** Desktop recomputes its whole observation window (no unchanged-input skip to bypass), so [days] is covered. */
    override suspend fun recomputeExtremes(latitude: Double, longitude: Double, days: Set<LocalDate>) = recompute()

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)
}
