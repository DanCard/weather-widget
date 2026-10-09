package com.weatherwidget.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.HourlyOnDemand
import com.weatherwidget.util.SharedPreferencesUtil

/**
 * Runs [HistorySnapshotPruner] in its own job — never inside a fetch. The first version ran from
 * `cleanOldData()` inside `getWeatherData` under `syncMutex`: on the Pixel (2026-09-30 00:06) the
 * first pass took 96 s, stretched a forced sync to 113 s, delayed that paint by ~96 s and blocked
 * every other sync behind the lock.
 */
class HistoryPruneWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val db = WeatherDatabase.getDatabase(applicationContext)
        val prefs = SharedPreferencesUtil.getPrefs(applicationContext, PREFS)
        val since = prefs.getLong(KEY_LAST_COMPLETED_START_MS, 0L)
        val startedAt = System.currentTimeMillis()
        HistorySnapshotPruner(
            dao = db.hourlyForecastHistoryDao(),
            appLogDao = db.appLogDao(),
            freelistBytes = {
                val sql = db.openHelper.readableDatabase
                val pages = sql.query("PRAGMA freelist_count").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                val size = sql.query("PRAGMA page_size").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                pages * size
            },
        ).prune(touchedSinceFetchedAt = since)
        pruneHourlyBeyondWindow(db, startedAt)
        // The start, not the end: rows fetched while this pass ran belong to the next one.
        prefs.edit().putLong(KEY_LAST_COMPLETED_START_MS, startedAt).apply()
        return Result.success()
    }

    /**
     * Google's on-demand hours (past its routine 72 h) that no fetch has refreshed for
     * [HourlyOnDemand.MAX_EXTENSION_AGE_MS]: routine fetches never refresh them. Other sources keep
     * their whole horizon.
     */
    private suspend fun pruneHourlyBeyondWindow(db: WeatherDatabase, nowMs: Long) {
        val deleted = WeatherSource.entries.sumOf { source ->
            val fromMs = HourlyOnDemand.extensionStartMs(source.id, nowMs) ?: return@sumOf 0
            db.hourlyForecastDao().deleteStaleBeyondWindow(source.id, fromMs, HourlyOnDemand.pruneFetchedBefore(nowMs))
        }
        db.appLogDao().log("HOURLY_WINDOW_PRUNE", "deleted=$deleted", "INFO")
    }

    companion object {
        private const val PREFS = "weather_prefs"
        private const val KEY_LAST_COMPLETED_START_MS = "history_prune_last_completed_start_ms"
        const val UNIQUE_WORK_NAME = "history_snapshot_prune"

        /** Unique (KEEP): a pending or running prune absorbs any further request. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<HistoryPruneWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
