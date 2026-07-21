package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the cloud/rain forecast-bar split so Android and desktop can't silently diverge.
 * Both platforms render this split: bottom-segment height = cloud ratio, color = rain vs cloud.
 */
@Category(ShortDuration::class)
class MixedBarSplitTest {

    @Test
    fun nullRatioMeansSolidBar() {
        assertNull(WeatherColors.mixedBarSplit(null, isChanceOfRain = false))
        assertNull(WeatherColors.mixedBarSplit(null, isChanceOfRain = true))
    }

    @Test
    fun cloudyBottomIsGreyRainyBottomIsBlue() {
        val cloudy = WeatherColors.mixedBarSplit(0.4f, isChanceOfRain = false)!!
        assertEquals(WeatherColors.FORECAST_CLOUDY, cloudy.bottomColorArgb)

        val rainy = WeatherColors.mixedBarSplit(0.4f, isChanceOfRain = true)!!
        assertEquals(WeatherColors.FORECAST_RAINY, rainy.bottomColorArgb)
    }

    @Test
    fun topIsAlwaysSunnyAndFractionIsOneMinusRatio() {
        val split = WeatherColors.mixedBarSplit(0.35f, isChanceOfRain = false)!!
        assertEquals(WeatherColors.FORECAST_SUNNY, split.topColorArgb)
        assertEquals(0.35f, split.ratio, 1e-6f)
        assertEquals(0.65f, split.topFraction, 1e-6f)
    }

    @Test
    fun ratioIsClampedToUnitRange() {
        val high = WeatherColors.mixedBarSplit(1.5f, isChanceOfRain = false)!!
        assertEquals(1f, high.ratio, 1e-6f)
        assertEquals(0f, high.topFraction, 1e-6f)

        val low = WeatherColors.mixedBarSplit(-0.5f, isChanceOfRain = false)!!
        assertEquals(0f, low.ratio, 1e-6f)
        assertEquals(1f, low.topFraction, 1e-6f)
    }

    @Test
    fun chanceRainIconSetMatchesContract() {
        // Only "chance rain" mixed icons turn the bottom blue — "slight chance" stays grey.
        assertTrue(WeatherConditionResolver.isChanceOfRainIcon(WeatherConditionResolver.IC_PARTLY_CLOUDY_CHANCE_RAIN))
        assertTrue(WeatherConditionResolver.isChanceOfRainIcon(WeatherConditionResolver.IC_CLOUDY_CHANCE_RAIN))
        assertEquals(false, WeatherConditionResolver.isChanceOfRainIcon(WeatherConditionResolver.IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN))
        assertEquals(false, WeatherConditionResolver.isChanceOfRainIcon(WeatherConditionResolver.IC_RAIN))
    }
}
