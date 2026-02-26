package com.weatherwidget.widget

/**
 * Policy decisions for lightweight current-temperature network refresh.
 */
object CurrentTempFetchPolicy {
    const val CHARGING_INTERVAL_MINUTES = 10L

    /**
     * Fetch is allowed while charging only when screen is interactive.
     * On battery, fetch is only allowed for opportunistic contexts.
     */
    fun shouldFetchNow(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
        isOpportunisticContext: Boolean,
    ): Boolean {
        return if (isCharging) {
            isScreenInteractive
        } else {
            isOpportunisticContext
        }
    }

    /**
     * Background 10-minute loop should only run while charging and interactive.
     */
    fun shouldScheduleChargingLoop(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
    ): Boolean = isCharging && isScreenInteractive
}
