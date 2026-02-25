package com.weatherwidget.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRefreshPolicyTest {

    @Test
    fun `screen unlock is ui-only when not charging`() {
        assertTrue(WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(isCharging = false))
        assertFalse(WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(isCharging = true))
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
    fun `worker network allowance mirrors ui-only mode`() {
        assertFalse(WidgetRefreshPolicy.isNetworkAllowedForWorker(uiOnlyRefresh = true))
        assertTrue(WidgetRefreshPolicy.isNetworkAllowedForWorker(uiOnlyRefresh = false))
    }
}
