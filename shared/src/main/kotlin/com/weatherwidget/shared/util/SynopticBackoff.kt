package com.weatherwidget.shared.util

/**
 * Exponential backoff for the Synoptic radius fetch after consecutive API-level rejections.
 *
 * 2026-09-08 on the Fold emulator: the token spent ~17 hours rejected (`RESPONSE_CODE != 1`, no
 * message) while the refresher still knocked roughly hourly, spending quota the API had already cut
 * off. Doubling from 30 minutes, capped at 6 hours, turns that into ~6 attempts over the same
 * window while still probing often enough to recover the moment the quota window frees up.
 */
object SynopticBackoff {
    const val BASE_BACKOFF_MS = 30 * 60 * 1000L
    const val MAX_BACKOFF_MS = 6 * 60 * 60 * 1000L

    /** Backoff to apply after [failStreak] consecutive failures; 0 when not failing. */
    fun backoffFor(failStreak: Int): Long {
        if (failStreak <= 0) return 0L
        val shift = (failStreak - 1).coerceAtMost(20)
        return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }
}
