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
        timeUntilDayRolloverMillis: Long
    ): Long {
        var delayMillis = nextUpdateTimeMillis - nowMillis

        // If plugged in (and screen is on, handled by receiver), update very frequently
        if (isCharging) {
            if (delayMillis > PLUGGED_IN_MAX_DELAY_MS) {
                delayMillis = PLUGGED_IN_MAX_DELAY_MS
            }
        }

        // Repaint at the date rollover so the daily window shifts on time.
        if (timeUntilDayRolloverMillis in 1..delayMillis) {
            delayMillis = timeUntilDayRolloverMillis
        }

        return delayMillis.coerceAtLeast(MINIMUM_DELAY_MS)
    }

    /**
     * Milliseconds until the next local midnight — the one moment the daily window changes (every
     * date shifts one column left so the today column keeps its slot). Without this clamp the
     * rollover waited for the next opportunistic update, up to an hour late. Zone-aware so a DST
     * transition at midnight is measured in real elapsed time.
     */
    fun millisUntilNextMidnight(now: java.time.ZonedDateTime): Long {
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        return java.time.Duration.between(now, midnight).toMillis()
    }
}