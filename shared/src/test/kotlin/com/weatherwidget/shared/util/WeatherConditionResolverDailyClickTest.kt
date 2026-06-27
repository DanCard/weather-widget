package com.weatherwidget.shared.util

import com.weatherwidget.shared.util.WeatherConditionResolver.IC_CLOUDY
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_MOSTLY_CLOUDY
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_RAIN
import com.weatherwidget.shared.util.WeatherConditionResolver.IconHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shared daily-column-tap routing decision used by both Android
 * (`DayClickHelper.resolveDailyTargetViewMode`) and desktop (`dayClickConfig`):
 * the precipitation graph opens only when the day reads as rain AND its daily precip probability
 * clears [WeatherConditionResolver.DAILY_CLICK_PRECIP_THRESHOLD]; otherwise the hourly graph. A
 * daily tap never routes to cloud cover (unlike bottom-row taps via `resolveIconHome`).
 */
class WeatherConditionResolverDailyClickTest {

    @Test
    fun `rainy icon at threshold shows precipitation`() {
        assertTrue(WeatherConditionResolver.shouldDailyClickShowPrecip(isRainIndicator = true, precipProbability = 16))
        assertEquals(IconHome.PRECIPITATION, WeatherConditionResolver.resolveDailyClickHome(IC_RAIN, 16))
    }

    @Test
    fun `rainy icon below threshold falls back to hourly`() {
        assertFalse(WeatherConditionResolver.shouldDailyClickShowPrecip(isRainIndicator = true, precipProbability = 15))
        assertEquals(IconHome.HOURLY, WeatherConditionResolver.resolveDailyClickHome(IC_RAIN, 15))
    }

    @Test
    fun `rainy icon with null probability falls back to hourly`() {
        assertFalse(WeatherConditionResolver.shouldDailyClickShowPrecip(isRainIndicator = true, precipProbability = null))
        assertEquals(IconHome.HOURLY, WeatherConditionResolver.resolveDailyClickHome(IC_RAIN, null))
    }

    @Test
    fun `cloudy icon never routes to cloud cover on a daily tap`() {
        assertEquals(IconHome.HOURLY, WeatherConditionResolver.resolveDailyClickHome(IC_CLOUDY, 90))
        assertEquals(IconHome.HOURLY, WeatherConditionResolver.resolveDailyClickHome(IC_MOSTLY_CLOUDY, 50))
    }

    @Test
    fun `null icon falls back to hourly`() {
        assertEquals(IconHome.HOURLY, WeatherConditionResolver.resolveDailyClickHome(null, 90))
    }
}
