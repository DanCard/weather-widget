package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.toReading
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.actuals.NwsStationActualsRun

/**
 * Desktop SQLite side of [NwsStationActualsRun] at one site; the network calls are passed in. Pinned
 * to Android's Room Host by `NwsStationActualsHostContract` (`:shared` testFixtures).
 */
internal class DesktopNwsStationActualsHost(
    private val weatherDao: DesktopWeatherDao,
    override val lat: Double,
    override val lon: Double,
    override val personalStationWeight: Double,
    private val stations: suspend () -> List<NwsApi.StationInfo>,
    private val stationDay: suspend (NwsApi.StationInfo, String, String) -> List<ObservationReading>,
) : NwsStationActualsRun.Host {
    override suspend fun dailyHistory(startMs: Long, endMs: Long) = weatherDao.getExtremesInRange(startMs, endMs, lat, lon)

    override suspend fun stations() = stations.invoke()

    override suspend fun nwsLiveHourly(startMs: Long, endMs: Long) =
        weatherDao.getHourlyForecastsAllSources(lat, lon, startMs, endMs)
            .filter { it.source == WeatherSource.NWS.id && it.dateTime < endMs }

    override suspend fun nwsHourlyHistory(startMs: Long, endMs: Long) =
        weatherDao.getHourlyHistorySnapshots(lat, lon, WeatherSource.NWS.id, startMs, endMs)

    override suspend fun stationDay(station: NwsApi.StationInfo, startIso: String, endIso: String) =
        stationDay.invoke(station, startIso, endIso)

    override suspend fun storedObservations(startMs: Long, endMs: Long) =
        weatherDao.getObservationsInRange(startMs, endMs, lat, lon).map { it.toReading() }

    override suspend fun upsert(rows: List<DailyHistory>) = weatherDao.upsertDailyHistory(rows)

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)
}
