package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenUnlockBatteryPolicyInstrumentedTest {

    @Test
    fun unpluggedUnlockRemainsCacheOnlyEvenWhenForecastDataIsStale() {
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
    fun chargingUnlockCanFetchWhenForecastDataIsStale() {
        val uiOnly = WidgetRefreshPolicy.shouldUseUiOnlyOnScreenUnlock(isCharging = true)

        assertFalse(uiOnly)
        assertTrue(
            WidgetRefreshPolicy.shouldTriggerNetworkFetchAfterRefresh(
                uiOnlyRequested = uiOnly,
                isDataStale = true,
            ),
        )
    }
}
