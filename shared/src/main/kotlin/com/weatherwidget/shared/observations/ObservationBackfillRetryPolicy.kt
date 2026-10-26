package com.weatherwidget.shared.observations

/**
 * Retry schedule for an NWS observation fill that never reached NWS ([NwsGapFillRun] `unreachable`).
 *
 * Android's 30-minute render cooldown (`HOURLY_BACKFILL_COOLDOWN_MS`) exists so repaints do not enqueue a
 * 72 h x 5-station pull on every draw. It is the wrong wait after a transient timeout: NWS did no
 * work, and the graph stays single-station for half an hour (emulator-5554, 2026-10-03). So the
 * Android worker re-enqueues itself on this schedule, and desktop's wake fill retries on it too.
 *
 * 10 s -> 1 m -> 4 m is not expressible with WorkManager backoff (linear or doubling only), hence
 * an explicit table. See plans/261003-obs-backfill-retry-when-nws-unreachable.md and
 * plans/261025-shared-nws-gap-fill.md.
 */
object ObservationBackfillRetryPolicy {
    val RETRY_DELAYS_MS = longArrayOf(10_000L, 60_000L, 240_000L)

    /** The retry's log event, one format on both platforms. */
    const val LOG_TAG = "OBS_HOURLY_BACKFILL_RETRY"

    fun logMessage(reason: String, attempt: Int, delayMs: Long): String = "reason=$reason attempt=$attempt delayMs=$delayMs"

    /** Delay before retry number [attempt] + 1, or null when the schedule is exhausted. */
    fun delayMs(attempt: Int): Long? = RETRY_DELAYS_MS.getOrNull(attempt)

    /**
     * Delay before the next attempt after a run on [attempt] (0 = first run), or null when no retry is
     * due: NWS answered (rows or a real empty), or the schedule is spent.
     */
    fun nextRetryDelayMs(unreachable: Boolean, attempt: Int): Long? =
        if (unreachable) delayMs(attempt) else null
}
