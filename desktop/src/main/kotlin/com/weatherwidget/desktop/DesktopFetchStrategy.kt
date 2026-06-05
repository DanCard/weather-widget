package com.weatherwidget.desktop

/**
 * Pure decision functions for battery-aware fetch scheduling on the desktop.
 * Replicates the Android BatteryFetchStrategy tiers.
 */
object DesktopFetchStrategy {

    private const val MS_PER_MINUTE = 60 * 1000L

    // AC Power Intervals (Matching Android's Screen On/Plugged In state)
    private const val AC_OBSERVATION_MINUTES = 10L
    private const val AC_ACTIVE_FORECAST_MINUTES = 60L
    private const val AC_INACTIVE_FORECAST_MINUTES = 120L

    // Battery Tiers (Matching Android's BatteryFetchStrategy)
    private const val BATTERY_HIGH_MINUTES = 240L // 4 hours
    private const val BATTERY_MEDIUM_MINUTES = 480L // 8 hours

    /**
     * Returns the delay in MS for the next observation fetch.
     * Returns null if fetches should be suspended.
     */
    fun getObservationRefreshDelayMs(isCharging: Boolean, batteryLevel: Int): Long? {
        if (isCharging) return AC_OBSERVATION_MINUTES * MS_PER_MINUTE

        return when {
            batteryLevel > 70 -> BATTERY_HIGH_MINUTES * MS_PER_MINUTE
            batteryLevel > 50 -> BATTERY_MEDIUM_MINUTES * MS_PER_MINUTE
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
            batteryLevel > 70 -> BATTERY_HIGH_MINUTES * MS_PER_MINUTE
            batteryLevel > 50 -> BATTERY_MEDIUM_MINUTES * MS_PER_MINUTE
            else -> null
        }
    }
}
