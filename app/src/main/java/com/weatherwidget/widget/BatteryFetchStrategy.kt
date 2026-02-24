package com.weatherwidget.widget

/**
 * Pure decision functions for battery-aware fetch scheduling.
 * Extracted for testability — no Android dependencies.
 */
object BatteryFetchStrategy {

    const val STALE_DATA_THRESHOLD_MS = 4 * 60 * 60 * 1000L // 4 hours

    /**
     * Returns the fetch interval in minutes based on battery state,
     * or null if no scheduled fetch should occur (battery too low).
     */
    fun computeFetchInterval(isCharging: Boolean, batteryLevel: Int): Long? {
        if (isCharging) return 60

        return when {
            batteryLevel > 70 -> 120
            batteryLevel > 50 -> 240
            else -> null
        }
    }

    /**
     * Returns true if cached data is stale enough to warrant a refresh on user interaction.
     */
    fun shouldRefreshStaleData(fetchedAtMs: Long?, nowMs: Long): Boolean {
        val fetchedAt = fetchedAtMs ?: 0L
        return (nowMs - fetchedAt) > STALE_DATA_THRESHOLD_MS
    }
}
