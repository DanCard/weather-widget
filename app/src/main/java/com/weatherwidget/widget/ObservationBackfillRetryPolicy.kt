package com.weatherwidget.widget

import com.weatherwidget.data.repository.RecentBackfillResult

/**
 * Retry schedule for an hourly-observation backfill that never reached NWS.
 *
 * The 30-minute render cooldown (`HOURLY_BACKFILL_COOLDOWN_MS`) exists so repaints do not enqueue a
 * 72 h x 5-station pull on every draw. It is the wrong wait after a transient timeout: NWS did no
 * work, and the graph stays single-station for half an hour (emulator-5554, 2026-10-03). So the
 * worker retries those itself, on this schedule, and the cooldown keeps governing repaints.
 *
 * 10 s -> 1 m -> 4 m is not expressible with WorkManager backoff (linear or doubling only), hence
 * an explicit table. See plans/261003-obs-backfill-retry-when-nws-unreachable.md.
 */
internal object ObservationBackfillRetryPolicy {
    val RETRY_DELAYS_MS = longArrayOf(10_000L, 60_000L, 240_000L)

    /** Delay before retry number [attempt] + 1, or null when the schedule is exhausted. */
    fun delayMs(attempt: Int): Long? = RETRY_DELAYS_MS.getOrNull(attempt)

    /**
     * Delay before the next attempt for a run that produced [result] on [attempt] (0 = first run),
     * or null when no retry is due: NWS answered (rows or a real empty), or the schedule is spent.
     */
    fun nextRetryDelayMs(result: RecentBackfillResult, attempt: Int): Long? =
        if (result.unreachable) delayMs(attempt) else null
}
