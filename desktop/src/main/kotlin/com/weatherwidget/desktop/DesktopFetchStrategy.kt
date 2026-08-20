package com.weatherwidget.desktop

import com.weatherwidget.shared.util.BatteryTier
import com.weatherwidget.shared.util.NonPrimaryObservationPolicy

/**
 * Pure decision functions for battery-aware fetch scheduling on the desktop.
 * Uses shared [BatteryTier] thresholds.
 */
object DesktopFetchStrategy {

    private const val MS_PER_MINUTE = 60 * 1000L

    // AC Power Intervals
    const val AC_OBSERVATION_SCREEN_ON_MINUTES = 10L
    const val AC_OBSERVATION_SCREEN_OFF_MINUTES = 30L
    const val AC_ACTIVE_FORECAST_MINUTES = 60L
    const val AC_INACTIVE_FORECAST_MINUTES = 120L

    const val CATCH_UP_STALENESS_THRESHOLD_MINUTES = 10L

    /**
     * Returns the delay in MS for the next observation fetch.
     * Returns null if fetches should be suspended.
     */
    fun getObservationRefreshDelayMs(isCharging: Boolean, batteryLevel: Int, screenOn: Boolean = true): Long? {
        if (isCharging) {
            val minutes = if (screenOn) AC_OBSERVATION_SCREEN_ON_MINUTES else AC_OBSERVATION_SCREEN_OFF_MINUTES
            return minutes * MS_PER_MINUTE
        }

        return when {
            batteryLevel > BatteryTier.TIER_HIGH_THRESHOLD -> BatteryTier.INTERVAL_HIGH_MINUTES * MS_PER_MINUTE
            batteryLevel > BatteryTier.TIER_MEDIUM_THRESHOLD -> BatteryTier.INTERVAL_MEDIUM_MINUTES * MS_PER_MINUTE
            else -> null
        }
    }

    /**
     * Returns true if an immediate catch-up observation fetch should run upon screen wake or user interaction.
     */
    fun shouldCatchUpObservations(
        lastFetchMs: Long?,
        nowMs: Long,
        stalenessThresholdMs: Long = CATCH_UP_STALENESS_THRESHOLD_MINUTES * MS_PER_MINUTE
    ): Boolean {
        if (lastFetchMs == null) return true
        return (nowMs - lastFetchMs) >= stalenessThresholdMs
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

    /**
     * Returns the delay in MS for the next non-primary (non-displayed) source actuals fetch, or
     * null to skip this cycle. Intentionally fires ONLY while charging AND the screen is on — the
     * non-primary actuals are a nicety (kept fresh for an instant source-toggle), not worth waking
     * the network on battery or while the user isn't looking at the display.
     *
     * [screenOn] is supplied by the caller (e.g. [ScreenStateDetector]) so this stays pure/testable.
     */
    fun getNonPrimaryObservationDelayMs(isCharging: Boolean, screenOn: Boolean): Long? =
        NonPrimaryObservationPolicy.intervalMinutes(isCharging, screenOn)?.let { it * MS_PER_MINUTE }
}
