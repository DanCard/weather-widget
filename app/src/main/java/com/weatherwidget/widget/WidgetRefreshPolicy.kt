package com.weatherwidget.widget

/**
 * Centralized policy for deciding UI-only vs network-capable refresh behavior.
 */
object WidgetRefreshPolicy {

    /**
     * Unlocking is not an explicit request for fresh weather. While unplugged, repaint from cache
     * at every battery level so unlocks cannot bypass the 4h/8h/no-fetch battery tiers.
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
