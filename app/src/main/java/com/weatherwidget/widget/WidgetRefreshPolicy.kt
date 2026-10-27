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
     * A Refresh tap on fresh data still repairs station history: the stale branch's sync runs the
     * daily NWS gap fill itself, so only the fresh branch needs it. Before this, a fill the wake had
     * judged unneeded (desktop 2026-10-27, cloud actuals stopped at 01:00) had no manual way out.
     */
    fun shouldRunHistoryGapFillAfterRefresh(
        uiOnlyRequested: Boolean,
        isDataStale: Boolean,
    ): Boolean = !uiOnlyRequested && !isDataStale

    /**
     * Worker-level network allowance based on refresh mode.
     */
    fun isNetworkAllowedForWorker(uiOnlyRefresh: Boolean): Boolean = !uiOnlyRefresh
}
