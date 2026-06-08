package com.weatherwidget.desktop

import com.weatherwidget.shared.util.BatteryTier

/**
 * Pure decision functions for battery-aware fetch scheduling on the desktop.
 * Uses shared [BatteryTier] thresholds.
 */
object DesktopFetchStrategy {

    private const val MS_PER_MINUTE = 60 * 1000L

    // AC Power Intervals
    private const val AC_OBSERVATION_MINUTES = 10L
    private const val AC_ACTIVE_FORECAST_MINUTES = 60L
    private const val AC_INACTIVE_FORECAST_MINUTES = 120L

    /**
     * Returns the delay in MS for the next observation fetch.
     * Returns null if fetches should be suspended.
     */
    fun getObservationRefreshDelayMs(isCharging: Boolean, batteryLevel: Int): Long? {
        if (isCharging) return AC_OBSERVATION_MINUTES * MS_PER_MINUTE

        return when {
            batteryLevel > BatteryTier.TIER_HIGH_THRESHOLD -> BatteryTier.INTERVAL_HIGH_MINUTES * MS_PER_MINUTE
            batteryLevel > BatteryTier.TIER_MEDIUM_THRESHOLD -> BatteryTier.INTERVAL_MEDIUM_MINUTES * MS_PER_MINUTE
            else -> null
        }
    }

    /**
     * Returns the delay in MS for the next forecast fetch.
     * Returns null if fetches should be suspended.
     */
    fun getForecastRefreshDelayMs(isCharging: Boolean, batteryLevel: Int, isActiveSource: Boolean): Long? {
        if (isCharging) {
            val minutes = if (isActiveSource) AC_ACTIVE_FORECAST_MINUTES else AC_INACTIVE_FORECAST_MINUTES
            return minutes * MS_PER_MINUTE
        }

        return when {
            batteryLevel > BatteryTier.TIER_HIGH_THRESHOLD -> BatteryTier.INTERVAL_HIGH_MINUTES * MS_PER_MINUTE
            batteryLevel > BatteryTier.TIER_MEDIUM_THRESHOLD -> BatteryTier.INTERVAL_MEDIUM_MINUTES * MS_PER_MINUTE
            else -> null
        }
    }
}
