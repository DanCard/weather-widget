package com.weatherwidget.widget

/**
 * Pure decision functions for UI update intervals.
 * Extracted for testability — no Android dependencies.
 */
object UIUpdateIntervalStrategy {
    const val PLUGGED_IN_MAX_DELAY_MS = 2 * 60 * 1000L // 2 minutes
    const val MINIMUM_DELAY_MS = 60 * 1000L // 1 minute

    /**
     * Computes the delay in milliseconds until the next UI update.
     */
    fun computeDelayMillis(
        nextUpdateTimeMillis: Long,
        nowMillis: Long,
        isCharging: Boolean,
        timeUntilSkipYesterdayMillis: Long
    ): Long {
        var delayMillis = nextUpdateTimeMillis - nowMillis

        // If plugged in (and screen is on, handled by receiver), update very frequently
        if (isCharging) {
            if (delayMillis > PLUGGED_IN_MAX_DELAY_MS) {
                delayMillis = PLUGGED_IN_MAX_DELAY_MS
            }
        }

        // Check if we need to schedule an update for the skip-yesterday window shift.
        if (timeUntilSkipYesterdayMillis in 1..delayMillis) {
            delayMillis = timeUntilSkipYesterdayMillis
        }

        return delayMillis.coerceAtLeast(MINIMUM_DELAY_MS)
    }
}