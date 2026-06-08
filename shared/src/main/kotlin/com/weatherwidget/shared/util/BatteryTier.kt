package com.weatherwidget.shared.util

/**
 * Shared battery-aware fetch scheduling thresholds.
 * Both Android and desktop use these tiers; each platform layers its own
 * charging intervals and extras on top.
 */
object BatteryTier {
    const val TIER_HIGH_THRESHOLD = 70
    const val TIER_MEDIUM_THRESHOLD = 50

    /** Minutes between fetches when battery > 70% (4 hours). */
    const val INTERVAL_HIGH_MINUTES = 240L

    /** Minutes between fetches when battery > 50% (8 hours). */
    const val INTERVAL_MEDIUM_MINUTES = 480L

    /**
     * Returns the fetch interval in minutes based on battery state,
     * or null if no scheduled fetch should occur (battery too low).
     */
    fun computeFetchInterval(isCharging: Boolean, batteryLevel: Int, chargingIntervalMinutes: Long): Long? {
        if (isCharging) return chargingIntervalMinutes
        return when {
            batteryLevel > TIER_HIGH_THRESHOLD -> INTERVAL_HIGH_MINUTES
            batteryLevel > TIER_MEDIUM_THRESHOLD -> INTERVAL_MEDIUM_MINUTES
            else -> null
        }
    }
}
