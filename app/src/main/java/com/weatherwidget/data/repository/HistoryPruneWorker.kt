package com.weatherwidget.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.weatherwidget.data.local.RetentionPolicy
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
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
        val startedAt = System.currentTimeMillis()
        val migrationDone = prefs.getBoolean(KEY_SETTLED_MIGRATION_V1, false)
        val activeScanWindowMs = (RetentionPolicy.DEFAULT_DAYS + 2) * 24L * 3_600_000L
        val lastCompleted = prefs.getLong(KEY_LAST_COMPLETED_START_MS, 0L)
        val since = if (!migrationDone) {
            0L
        } else {
            minOf(lastCompleted, startedAt - activeScanWindowMs)
        }

        HistorySnapshotPruner(
            dao = db.hourlyForecastHistoryDao(),
            appLogDao = db.appLogDao(),
            freelistBytes = {
                val sql = db.openHelper.readableDatabase
                val pages = sql.query("PRAGMA freelist_count").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                val size = sql.query("PRAGMA page_size").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                pages * size
            },
        ).prune(touchedSinceFetchedAt = since, nowMs = startedAt)

        val editor = prefs.edit().putLong(KEY_LAST_COMPLETED_START_MS, startedAt)
        if (!migrationDone) {
            editor.putBoolean(KEY_SETTLED_MIGRATION_V1, true)
        }
        editor.apply()

        // Automated file compaction: if freelist >= 8 MB, run VACUUM while charging + idle
        try {
            val freelistBytes = run {
                val sql = db.openHelper.readableDatabase
                val pages = sql.query("PRAGMA freelist_count").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                val size = sql.query("PRAGMA page_size").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                pages * size
            }
            if (freelistBytes >= HISTORY_VACUUM_MIN_FREE_BYTES) {
                val startNs = System.nanoTime()
                db.openHelper.writableDatabase.execSQL("VACUUM")
                val durationMs = (System.nanoTime() - startNs) / 1_000_000L
                db.appLogDao().log(
                    "DB_VACUUM",
                    "freelistBytes=$freelistBytes durationMs=$durationMs",
                    "INFO",
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("HistoryPruneWorker", "VACUUM failed", e)
        }

        return Result.success()
    }

    companion object {
        private const val PREFS = "weather_prefs"
        private const val KEY_LAST_COMPLETED_START_MS = "history_prune_last_completed_start_ms"
        private const val KEY_SETTLED_MIGRATION_V1 = "hourly_settled_v1_done"
        private const val HISTORY_VACUUM_MIN_FREE_BYTES = 8L * 1024 * 1024
        const val UNIQUE_WORK_NAME = "history_snapshot_prune"

        /** Unique (KEEP): a pending or running prune absorbs any further request. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request())
        }

        /**
         * Only while charging with the device idle (screen off and unused for a while) — user,
         * 2026-10-09: pruning is housekeeping, never worth battery or a busy phone. The first pass
         * took 96 s on the Pixel.
         */
        internal fun request() = OneTimeWorkRequestBuilder<HistoryPruneWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build())
            .build()
    }
}
