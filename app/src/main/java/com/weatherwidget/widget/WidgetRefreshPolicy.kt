package com.weatherwidget.widget

/**
 * Centralized policy for deciding UI-only vs network-capable refresh behavior.
 */
object WidgetRefreshPolicy {

    /**
     * On screen unlock, prefer UI-only refresh when charging is unavailable and battery is low.
     * When on battery with enough charge, allow an opportunistic network-capable path; the
     * provider still gates actual network fetch on staleness.
     */
    fun shouldUseUiOnlyOnScreenUnlock(
        isCharging: Boolean,
        batteryLevel: Int,
    ): Boolean {
        if (isCharging) {
            return false
        }
        return !BatteryFetchStrategy.shouldAllowOpportunisticFetchOnBattery(batteryLevel)
    }

    /**
     * Network fetch after refresh should only happen when UI-only is not requested
     * and data is stale.
     */
    fun shouldTriggerNetworkFetchAfterRefresh(
        uiOnlyRequested: Boolean,
        isDataStale: Boolean,
    ): Boolean = !uiOnlyRequested && isDataStale

    /**
     * Worker-level network allowance based on refresh mode.
     */
    fun isNetworkAllowedForWorker(uiOnlyRefresh: Boolean): Boolean = !uiOnlyRefresh
}
