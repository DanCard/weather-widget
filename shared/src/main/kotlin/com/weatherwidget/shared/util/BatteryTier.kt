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
     * Battery level at/above which an unplugged device may still use the aggressive "charging"
     * fetch cadence. Distinct from "is effectively charging": this is a *cadence* decision ("battery
     * is high enough to afford frequent fetches"), not a statement that the device is physically
     * plugged in or full.
     */
    const val TREAT_AS_CHARGING_THRESHOLD = 80

    /** A full battery is treated as effectively charging even when unplugged. */
    const val FULL_BATTERY_LEVEL = 100

    /** Minimum battery percent for opportunistic (piggyback) network work. */
    const val OPPORTUNISTIC_MIN_BATTERY_PERCENT = 65

    /**
     * Whether an unplugged device's battery is high enough to be scheduled as if it were charging.
     * Used by forecast-fetch cadence decisions, never by the "is it physically charging" checks that
     * gate the current-temperature/non-primary loops.
     */
    fun treatAsCharging(isCharging: Boolean, batteryLevel: Int): Boolean =
        isCharging || batteryLevel >= TREAT_AS_CHARGING_THRESHOLD

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
