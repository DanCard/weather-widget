package com.weatherwidget.widget

/**
 * Policy decisions for lightweight current-temperature network refresh.
 */
object CurrentTempFetchPolicy {
    const val CHARGING_INTERVAL_MINUTES = 10L
    const val CHARGING_SCREEN_OFF_INTERVAL_MINUTES = 16L

    /**
     * Returns the appropriate charging loop interval based on screen state.
     */
    fun chargingIntervalMinutes(isScreenInteractive: Boolean): Long =
        if (isScreenInteractive) CHARGING_INTERVAL_MINUTES else CHARGING_SCREEN_OFF_INTERVAL_MINUTES

    /**
     * Fetch is allowed while charging (regardless of screen state) or on battery
     * in opportunistic contexts. Manual triggers always bypass these checks.
     */
    fun shouldFetchNow(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
        isOpportunisticContext: Boolean,
        isManual: Boolean = false,
    ): Boolean {
        if (isManual) return true

        return if (isCharging) {
            true
        } else {
            isOpportunisticContext
        }
    }

    /**
     * Background charging loop should run whenever the device is charging,
     * regardless of screen state.
     */
    fun shouldScheduleChargingLoop(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
    ): Boolean = isCharging
}
