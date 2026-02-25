package com.weatherwidget.widget

/**
 * Centralized policy for deciding UI-only vs network-capable refresh behavior.
 */
object WidgetRefreshPolicy {

    /**
     * On screen unlock, prefer UI-only refresh unless the device is charging.
     */
    fun shouldUseUiOnlyOnScreenUnlock(isCharging: Boolean): Boolean = !isCharging

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
