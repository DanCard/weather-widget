package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.actuals.NwsStationActualsRun

/**
 * Room side of [NwsStationActualsRun] at one site; the network calls are passed in. Its reads and
 * writes are pinned to desktop's SQLite Host by `NwsStationActualsHostContract` (`:shared` testFixtures).
 */
internal class RoomNwsStationActualsHost(
    override val lat: Double,
    override val lon: Double,
    override val personalStationWeight: Double,
    private val store: NwsStationActualsStore,
    private val appLogDao: AppLogDao,
    private val stations: suspend () -> List<NwsApi.StationInfo>,
    private val stationDay: suspend (NwsApi.StationInfo, String, String) -> List<ObservationReading>,
) : NwsStationActualsRun.Host {
    override suspend fun dailyHistory(startMs: Long, endMs: Long) = store.dailyHistoryInRange(lat, lon, startMs, endMs)

    override suspend fun stations() = stations.invoke()

    override suspend fun nwsLiveHourly(startMs: Long, endMs: Long) = store.nwsLiveHourly(lat, lon, startMs, endMs)

    override suspend fun nwsHourlyHistory(startMs: Long, endMs: Long) = store.nwsHourlyHistory(lat, lon, startMs, endMs)

    override suspend fun stationDay(station: NwsApi.StationInfo, startIso: String, endIso: String) =
        stationDay.invoke(station, startIso, endIso)

    override suspend fun storedObservations(startMs: Long, endMs: Long) = store.storedObservations(lat, lon, startMs, endMs)

    override suspend fun upsert(rows: List<DailyHistory>) = store.upsertDailyHistory(rows)

    override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)
}
