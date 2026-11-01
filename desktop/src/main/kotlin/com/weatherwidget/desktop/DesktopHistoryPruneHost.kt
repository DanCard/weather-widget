package com.weatherwidget.desktop

import com.weatherwidget.data.local.HistoryPruneRun
import com.weatherwidget.data.local.HistoryPruneSchedule
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.toReading
import com.weatherwidget.data.model.HourlyActual
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading

/** Desktop SQLite side of [HistoryPruneRun]. */
class DesktopHistoryPruneHost(
    private val dao: DesktopWeatherDao,
    private val personalStationWeight: Double = 1.0,
) : HistoryPruneRun.Host {
    override suspend fun deleteOlderThan(cutoffMs: Long) = dao.deleteOldHourlyForecasts(cutoffMs)

    override suspend fun pruneSnapshots() {
        dao.pruneHourlyHistorySnapshots()
    }

    override suspend fun deleteObservationsOlderThan(cutoffMs: Long) = dao.deleteOldObservations(cutoffMs)

    override suspend fun deleteHourlyActualsOlderThan(cutoffMs: Long) = dao.deleteOldHourlyActuals(cutoffMs)

    override suspend fun latestArchivedHour(): Long? = dao.latestArchivedHour()

    override suspend fun oldestObservation(): Long? = dao.oldestObservation()

    override suspend fun observationSites(startMs: Long, endMs: Long) = dao.observationSites(startMs, endMs)

    override suspend fun observations(startMs: Long, endMs: Long, lat: Double, lon: Double): List<ObservationReading> =
        dao.getObservationsInRange(startMs, endMs, lat, lon).map { it.toReading() }

    override suspend fun hourlyForecasts(startMs: Long, endMs: Long, lat: Double, lon: Double): List<HourlyForecast> =
        dao.getHourlyForecastsAllSources(lat, lon, startMs, endMs)

    override fun personalStationWeight(): Double = personalStationWeight

    override suspend fun insertHourlyActuals(rows: List<HourlyActual>) = dao.insertHourlyActuals(rows)

    override suspend fun freelistBytes(): Long = dao.freelistBytes()

    override suspend fun vacuum() = dao.vacuum()

    override suspend fun log(message: String) = dao.log(HistoryPruneSchedule.LOG_TAG, message)

    override suspend fun recordCompleted(startedAtMs: Long) =
        dao.replaceLogMarker(HistoryPruneSchedule.LAST_RUN_TAG, "completed", startedAtMs)
}
