package com.weatherwidget.widget

import kotlin.random.Random

/**
 * Pure decision functions for delaying automatic startup catch-up fetches so a freshly-started
 * process doesn't stampede every source's fetch/backfill work at once (the "thundering herd" that
 * competes with a user's tap for I/O and can push a click past the broadcast ANR deadline — see
 * [BroadcastAsyncRunner.WATCHDOG_MS]). Jitter spreads repeated app starts across the
 * window instead of every restart landing on the same offset. Explicit user-initiated refreshes
 * (manual tap, unlock-while-charging) bypass this policy entirely and stay immediate.
 */
object StartupFetchPolicy {
    /** Normal-staleness window for the displayed source's startup catch-up fetch. */
    const val NORMAL_DELAY_MIN_MS = 45_000L
    const val NORMAL_DELAY_MAX_MS = 90_000L

    /** Fast lane when data is very stale (e.g. phone was off overnight) — still jittered, but short. */
    const val VERY_STALE_DELAY_MIN_MS = 5_000L
    const val VERY_STALE_DELAY_MAX_MS = 15_000L
    const val VERY_STALE_THRESHOLD_MINUTES = 6 * 60L

    /**
     * History-repair work (observation backfill, coverage-gap refresh). These enqueue functions
     * are shared with the interactive "missing hourly data" day-tap flow (see
     * NoHourlyDayClickCoordinator / the two-phase pending->banner UX), which already tolerates a
     * short wait — NO_HOURLY_MESSAGE_DURATION_MS shows a banner for 8s while it waits. So this
     * window is deliberately short (not minutes): long enough to clear the first-second startup
     * scrum, short enough not to make that banner flow feel broken.
     */
    const val HISTORY_REPAIR_DELAY_MIN_MS = 10_000L
    const val HISTORY_REPAIR_DELAY_MAX_MS = 20_000L

    /** Null age (no data at all) counts as very stale — that's the "no_data" path, handled separately. */
    fun isVeryStale(dataAgeMinutes: Long?): Boolean =
        dataAgeMinutes == null || dataAgeMinutes >= VERY_STALE_THRESHOLD_MINUTES

    /** Jittered delay (ms) for the displayed-source startup catch-up fetch. */
    fun primaryFetchDelayMs(dataAgeMinutes: Long?, random: Random = Random.Default): Long =
        if (isVeryStale(dataAgeMinutes)) {
            jitter(VERY_STALE_DELAY_MIN_MS, VERY_STALE_DELAY_MAX_MS, random)
        } else {
            jitter(NORMAL_DELAY_MIN_MS, NORMAL_DELAY_MAX_MS, random)
        }

    /** Jittered delay (ms) for observation backfill / coverage-gap refresh enqueues. */
    fun historyRepairDelayMs(random: Random = Random.Default): Long =
        jitter(HISTORY_REPAIR_DELAY_MIN_MS, HISTORY_REPAIR_DELAY_MAX_MS, random)

    private fun jitter(minMs: Long, maxMs: Long, random: Random): Long =
        if (maxMs <= minMs) minMs else minMs + random.nextLong(maxMs - minMs)
}
