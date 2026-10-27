package com.weatherwidget.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
class WidgetRefreshPolicyTest {

    @Test
    fun `screen unlock is network-capable while charging`() {
        assertFalse(
            WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(
                isCharging = true,
            ),
        )
    }

    @Test
    fun `screen unlock on battery is always ui-only`() {
        assertTrue(
            WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(
                isCharging = false,
            ),
        )
    }

    @Test
    fun `stale data cannot turn an unplugged unlock into a network fetch`() {
        val uiOnly = WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(isCharging = false)

        assertTrue(uiOnly)
        assertFalse(
            WidgetRefreshPolicy.shouldTriggerNetworkFetchAfterRefresh(
                uiOnlyRequested = uiOnly,
                isDataStale = true,
            ),
        )
    }

    @Test
    fun `network fetch after refresh requires non-ui-only and stale data`() {
        assertFalse(
            WidgetRefreshPolicy.shouldTriggerNetworkFetchAfterRefresh(
                uiOnlyRequested = true,
                isDataStale = true,
            ),
        )
        assertFalse(
            WidgetRefreshPolicy.shouldTriggerNetworkFetchAfterRefresh(
                uiOnlyRequested = false,
                isDataStale = false,
            ),
        )
        assertTrue(
            WidgetRefreshPolicy.shouldTriggerNetworkFetchAfterRefresh(
                uiOnlyRequested = false,
                isDataStale = true,
            ),
        )
    }

    @Test
    fun `a refresh on fresh data runs the history gap fill, and only then`() {
        assertTrue(WidgetRefreshPolicy.shouldRunHistoryGapFillAfterRefresh(uiOnlyRequested = false, isDataStale = false))
        // Stale: the sync it enqueues runs the daily pass itself.
        assertFalse(WidgetRefreshPolicy.shouldRunHistoryGapFillAfterRefresh(uiOnlyRequested = false, isDataStale = true))
        // A screen's repaint on close is not a request for data.
        assertFalse(WidgetRefreshPolicy.shouldRunHistoryGapFillAfterRefresh(uiOnlyRequested = true, isDataStale = false))
        assertFalse(WidgetRefreshPolicy.shouldRunHistoryGapFillAfterRefresh(uiOnlyRequested = true, isDataStale = true))
    }

    @Test
    fun `worker network allowance mirrors ui-only mode`() {
        assertFalse(WidgetRefreshPolicy.isNetworkAllowedForWorker(uiOnlyRefresh = true))
        assertTrue(WidgetRefreshPolicy.isNetworkAllowedForWorker(uiOnlyRefresh = false))
    }
}
