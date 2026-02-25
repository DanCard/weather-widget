package com.weatherwidget.widget.handlers

import com.weatherwidget.widget.WeatherWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetIntentRouterRobolectricTest {

    @Test
    fun `router action constants match provider action constants`() {
        assertEquals(WeatherWidgetProvider.ACTION_NAV_LEFT, WidgetIntentRouter.ACTION_NAV_LEFT)
        assertEquals(WeatherWidgetProvider.ACTION_NAV_RIGHT, WidgetIntentRouter.ACTION_NAV_RIGHT)
        assertEquals(WeatherWidgetProvider.ACTION_TOGGLE_API, WidgetIntentRouter.ACTION_TOGGLE_API)
        assertEquals(WeatherWidgetProvider.ACTION_TOGGLE_VIEW, WidgetIntentRouter.ACTION_TOGGLE_VIEW)
        assertEquals(WeatherWidgetProvider.ACTION_TOGGLE_PRECIP, WidgetIntentRouter.ACTION_TOGGLE_PRECIP)
        assertEquals(WeatherWidgetProvider.ACTION_CYCLE_ZOOM, WidgetIntentRouter.ACTION_CYCLE_ZOOM)
        assertEquals(WeatherWidgetProvider.ACTION_SET_VIEW, WidgetIntentRouter.ACTION_SET_VIEW)
    }

    @Test
    fun `router set-view extra key matches provider contract`() {
        assertEquals(WeatherWidgetProvider.EXTRA_TARGET_VIEW, WidgetIntentRouter.EXTRA_TARGET_VIEW)
    }
}
