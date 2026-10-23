package com.weatherwidget.data.local

/**
 * When `hourly_forecast_history` is pruned to what [HistorySnapshotRetention] keeps, on both
 * platforms: at most once a day (retention runs after every fetch), and never before the one-shot
 * repairs/backfills that read *every* snapshot have run — they would lose their input. Where the
 * prune then runs is the platform's (Android `HistoryPruneWorker` on charger + idle, desktop on AC
 * with the screen off).
 */
object HistoryPruneSchedule {
    const val MIN_INTERVAL_MS = 24L * 3_600_000L
    const val LOG_TAG = "HISTORY_PRUNE"

    /**
     * Reclaimable (freelist) bytes after the prune that make a VACUUM worth running: a DELETE leaves
     * the file its size. One value for both platforms (was 8 MB Android / 16 MB desktop; user
     * 2026-10-23) — it runs only on a charger with the screen off and took 0.16 s for 100 MB.
     */
    const val VACUUM_MIN_FREELIST_BYTES = 8L * 1024 * 1024

    /** Logged (as [LOG_TAG]) when the prune is due but a prerequisite pass has not run; counts as the day's attempt. */
    const val SKIPPED_PREREQUISITES = "skipped=prerequisites_pending"

    enum class Decision { NOT_DUE, PREREQUISITES_PENDING, RUN }

    /** [lastMs]: the last time the prune was due (run or skipped); 0 when never. */
    fun decide(nowMs: Long, lastMs: Long, prerequisitesDone: Boolean): Decision = when {
        nowMs - lastMs < MIN_INTERVAL_MS -> Decision.NOT_DUE
        !prerequisitesDone -> Decision.PREREQUISITES_PENDING
        else -> Decision.RUN
    }
}
