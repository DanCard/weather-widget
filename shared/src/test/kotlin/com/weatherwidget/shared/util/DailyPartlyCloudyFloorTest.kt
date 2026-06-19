package com.weatherwidget.shared.util

import com.weatherwidget.shared.util.WeatherConditionResolver.IC_CLOUDY
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_MOSTLY_CLEAR
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_MOSTLY_CLOUDY
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_NIGHT
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_PARTLY_CLOUDY
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_PARTLY_CLOUDY_NIGHT
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the daily partly-cloudy AND-gate: a provider's worded "partly cloudy" only stands when the
 * measured noon cloud cover is ≥ [WeatherConditionResolver.PARTLY_CLOUDY_MIN_CLOUD_COVER]%.
 */
class DailyPartlyCloudyFloorTest {

    private fun floor(icon: String, pct: Int?, night: Boolean = false) =
        WeatherConditionResolver.applyDailyPartlyCloudyFloor(icon, pct, night)

    @Test
    fun partlyCloudyBelow25DowngradesToMostlyClear() {
        assertEquals(IC_MOSTLY_CLEAR, floor(IC_PARTLY_CLOUDY, 20))
        assertEquals(IC_MOSTLY_CLEAR, floor(IC_PARTLY_CLOUDY, 0))
    }

    @Test
    fun partlyCloudyAtOrAbove25StaysPartlyCloudy() {
        assertEquals(IC_PARTLY_CLOUDY, floor(IC_PARTLY_CLOUDY, 25))
        assertEquals(IC_PARTLY_CLOUDY, floor(IC_PARTLY_CLOUDY, 60))
    }

    @Test
    fun nullCloudCoverLeavesPartlyCloudyUnchanged() {
        // No measurement → trust the provider's wording (e.g. sources without hourly cloud data).
        assertEquals(IC_PARTLY_CLOUDY, floor(IC_PARTLY_CLOUDY, null))
    }

    @Test
    fun partlyCloudyNightBelow25DowngradesToClearNight() {
        // getCloudCoverIcon maps the 0–25 night band to the clear-night icon.
        assertEquals(IC_NIGHT, floor(IC_PARTLY_CLOUDY_NIGHT, 10, night = true))
    }

    @Test
    fun nonPartlyIconsAreNeverTouched() {
        assertEquals(IC_CLOUDY, floor(IC_CLOUDY, 10))
        assertEquals(IC_MOSTLY_CLOUDY, floor(IC_MOSTLY_CLOUDY, 5))
        assertEquals(IC_MOSTLY_CLEAR, floor(IC_MOSTLY_CLEAR, 5))
    }
}
