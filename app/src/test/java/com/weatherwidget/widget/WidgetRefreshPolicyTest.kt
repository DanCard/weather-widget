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
    fun `worker network allowance mirrors ui-only mode`() {
        assertFalse(WidgetRefreshPolicy.isNetworkAllowedForWorker(uiOnlyRefresh = true))
        assertTrue(WidgetRefreshPolicy.isNetworkAllowedForWorker(uiOnlyRefresh = false))
    }
}
