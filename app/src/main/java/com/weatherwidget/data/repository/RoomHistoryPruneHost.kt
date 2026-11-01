package com.weatherwidget.data.repository

import android.content.SharedPreferences
import com.weatherwidget.data.local.HistoryPruneRun
import com.weatherwidget.data.local.HistoryPruneSchedule
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.HourlyActual
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.widget.handlers.GraphDataLoader

/**
 * Room side of [HistoryPruneRun]. [touchedSinceFetchedAt] and [startedAtMs] are this pass's
 * [HistorySnapshotPruner] scan.
 */
internal class RoomHistoryPruneHost(
    private val db: WeatherDatabase,
    private val prefs: SharedPreferences,
    private val touchedSinceFetchedAt: Long,
    private val startedAtMs: Long,
    private val personalStationWeight: () -> Double = { 1.0 },
) : HistoryPruneRun.Host {
    override suspend fun deleteOlderThan(cutoffMs: Long) = db.hourlyForecastHistoryDao().deleteOldHistory(cutoffMs)

    override suspend fun pruneSnapshots() {
        HistorySnapshotPruner(
            dao = db.hourlyForecastHistoryDao(),
            appLogDao = db.appLogDao(),
            freelistBytes = { freelistBytes() },
        ).prune(touchedSinceFetchedAt = touchedSinceFetchedAt, nowMs = startedAtMs)
    }

    override suspend fun deleteObservationsOlderThan(cutoffMs: Long) = db.observationDao().deleteOldObservations(cutoffMs)

    override suspend fun deleteHourlyActualsOlderThan(cutoffMs: Long) = db.hourlyActualDao().deleteOlderThan(cutoffMs)

    override suspend fun latestArchivedHour(): Long? = db.hourlyActualDao().latestHour()

    override suspend fun oldestObservation(): Long? = db.observationDao().oldestTimestamp()

    override suspend fun observationSites(startMs: Long, endMs: Long): Map<Pair<Double, Double>, Int> =
        db.observationDao().sitesInRange(startMs, endMs).associate { (it.locationLat to it.locationLon) to it.rowCount }

    // Unscoped: the archive covers every source in one pass, as the daily recompute does.
    override suspend fun observations(startMs: Long, endMs: Long, lat: Double, lon: Double): List<ObservationReading> =
        db.observationDao().getObservationsInRange(startMs, endMs, lat, lon, apis = null).map { it.toReading() }

    override suspend fun hourlyForecasts(startMs: Long, endMs: Long, lat: Double, lon: Double): List<HourlyForecast> =
        GraphDataLoader.unifyToNearestSite(db.hourlyForecastDao().getHourlyForecasts(startMs, endMs, lat, lon), lat, lon)
            .map { it.toHourlyForecast() }

    override fun personalStationWeight(): Double = personalStationWeight.invoke()

    override suspend fun insertHourlyActuals(rows: List<HourlyActual>) =
        db.hourlyActualDao().insertAll(rows.map { it.toEntity() })

    override suspend fun freelistBytes(): Long {
        val sql = db.openHelper.readableDatabase
        val pages = sql.query("PRAGMA freelist_count").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        val size = sql.query("PRAGMA page_size").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        return pages * size
    }

    override suspend fun vacuum() = db.openHelper.writableDatabase.execSQL("VACUUM")

    override suspend fun log(message: String) = db.appLogDao().log(HistoryPruneSchedule.LOG_TAG, message, "INFO")

    override suspend fun recordCompleted(startedAtMs: Long) {
        prefs.edit()
            .putLong(HistoryPruneWorker.KEY_LAST_COMPLETED_START_MS, startedAtMs)
            .putBoolean(HistoryPruneWorker.KEY_SETTLED_MIGRATION_V1, true)
            .apply()
    }
}
