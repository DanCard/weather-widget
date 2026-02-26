package com.weatherwidget.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRefreshPolicyTest {

    @Test
    fun `screen unlock is network-capable while charging`() {
        assertFalse(
            WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(
                isCharging = true,
                batteryLevel = 5,
            ),
        )
    }

    @Test
    fun `screen unlock on battery is ui-only when battery is below opportunistic threshold`() {
        assertTrue(
            WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(
                isCharging = false,
                batteryLevel = BatteryFetchStrategy.MIN_BATTERY_FOR_OPPORTUNISTIC_FETCH - 1,
            ),
        )
    }

    @Test
    fun `screen unlock on battery allows opportunistic network at threshold and above`() {
        assertFalse(
            WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(
                isCharging = false,
                batteryLevel = BatteryFetchStrategy.MIN_BATTERY_FOR_OPPORTUNISTIC_FETCH,
            ),
        )
        assertFalse(
            WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(
                isCharging = false,
                batteryLevel = 80,
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
