package com.weatherwidget.widget

/**
 * Pure decision functions for battery-aware fetch scheduling.
 * Extracted for testability — no Android dependencies.
 */
object BatteryFetchStrategy {

    const val STALE_DATA_THRESHOLD_MS = 4 * 60 * 60 * 1000L // 4 hours
    const val MIN_BATTERY_FOR_OPPORTUNISTIC_FETCH = 30
    private const val CHARGING_FETCH_INTERVAL_MINUTES = 30L

    /**
     * Returns the fetch interval in minutes based on battery state,
     * or null if no scheduled fetch should occur (battery too low).
     */
    fun computeFetchInterval(isCharging: Boolean, batteryLevel: Int): Long? {
        if (isCharging) return CHARGING_FETCH_INTERVAL_MINUTES

        return when {
            batteryLevel > 70 -> 240
            batteryLevel > 50 -> 480
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

    /**
     * Returns true when battery is healthy enough to allow a user-triggered opportunistic fetch.
     */
    fun shouldAllowOpportunisticFetchOnBattery(batteryLevel: Int): Boolean {
        return batteryLevel >= MIN_BATTERY_FOR_OPPORTUNISTIC_FETCH
    }
}
