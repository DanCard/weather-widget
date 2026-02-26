package com.weatherwidget.widget

/**
 * Policy decisions for lightweight current-temperature network refresh.
 */
object CurrentTempFetchPolicy {
    const val CHARGING_INTERVAL_MINUTES = 10L

    /**
     * Fetch is allowed while charging if the screen is interactive OR if it's an
     * opportunistic background sync (e.g. 30-minute system job).
     * On battery, fetch is only allowed for opportunistic contexts.
     * Manual triggers always bypass these checks.
     */
    fun shouldFetchNow(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
        isOpportunisticContext: Boolean,
        isManual: Boolean = false,
    ): Boolean {
        if (isManual) return true

        return if (isCharging) {
            isScreenInteractive || isOpportunisticContext
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
