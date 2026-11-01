package com.weatherwidget.data.local

/**
 * When `hourly_forecasts` is pruned to what [HistorySnapshotRetention] keeps, on both platforms: at
 * most once a day, never inside a fetch, and never before the one-shot repairs/backfills that read
 * *every* snapshot have run — they would lose their input.
 *
 * A prune waits for a moment the device is not in use, but that wait is bounded on desktop: a gate
 * that can only be met by chance starves. Desktop's prune ran at the end of a refresh and needed the
 * screen off, while refreshes stop an hour into being away, so it barely ran after 2026-10-09 and 61%
 * of the table was deletable by 2026-11-01. See plans/261101-reliable-hourly-prune-scheduling.md.
 *
 * Android leaves both conditions to `HistoryPruneWorker`'s charging + idle constraints and has no
 * override: housekeeping never costs battery or interrupts a busy phone (user, 2026-10-09).
 */
object HistoryPruneSchedule {
    const val MIN_INTERVAL_MS = 24L * 3_600_000L

    /** Past this, a prune stops waiting for the device to be out of use. Power is still required. */
    const val OVERDUE_MS = 3L * 24L * 3_600_000L

    /** How often desktop's housekeeping loop asks [decide]. */
    const val CHECK_INTERVAL_MS = 15L * 60_000L

    /** Lets the launch refresh go first, without a restart ever pushing the first check back 15 min. */
    const val FIRST_CHECK_DELAY_MS = 2L * 60_000L

    const val LOG_TAG = "HISTORY_PRUNE"

    /** Desktop's one-row "last completed prune" marker; exempt from the 72 h log window. */
    const val LAST_RUN_TAG = "HISTORY_PRUNE_LAST"

    /**
     * Reclaimable (freelist) bytes after the prune that make a VACUUM worth running: a DELETE leaves
     * the file its size. One value for both platforms (was 8 MB Android / 16 MB desktop; user
     * 2026-10-23). It took 0.16 s for 100 MB.
     */
    const val VACUUM_MIN_FREELIST_BYTES = 8L * 1024 * 1024

    /** Logged (as [LOG_TAG]) when the prune is due but a prerequisite pass has not run. */
    const val SKIPPED_PREREQUISITES = "skipped=prerequisites_pending"

    /** Logged (as [LOG_TAG]) when the prune is due but the device is in use or off power. */
    const val SKIPPED_WAITING_FOR_IDLE = "skipped=waiting_for_idle"

    enum class Decision { NOT_DUE, PREREQUISITES_PENDING, WAITING_FOR_IDLE, RUN }

    /** A prune that has never run counts as overdue ([lastMs] 0). */
    fun isOverdue(nowMs: Long, lastMs: Long): Boolean = nowMs - lastMs >= OVERDUE_MS

    /**
     * [lastMs]: the last completed prune on desktop, the last enqueue on Android; 0 when never.
     * [onPower] and [notInUse] are probed only when the answer depends on them.
     */
    fun decide(
        nowMs: Long,
        lastMs: Long,
        prerequisitesDone: Boolean,
        onPower: () -> Boolean = { true },
        notInUse: () -> Boolean = { true },
    ): Decision = when {
        nowMs - lastMs < MIN_INTERVAL_MS -> Decision.NOT_DUE
        !prerequisitesDone -> Decision.PREREQUISITES_PENDING
        !onPower() -> Decision.WAITING_FOR_IDLE
        isOverdue(nowMs, lastMs) || notInUse() -> Decision.RUN
        else -> Decision.WAITING_FOR_IDLE
    }
}
