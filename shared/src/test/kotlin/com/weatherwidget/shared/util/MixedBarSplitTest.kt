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

    @Test
    fun resolveMixedBarSplitResolvesBlueForChanceOfRain() {
        // With explicit cloud ratio override
        val split = WeatherColors.resolveMixedBarSplit(
            iconName = WeatherConditionResolver.IC_CLOUDY_CHANCE_RAIN,
            cloudCoverRatio = 0.99f,
        )!!
        assertEquals(WeatherColors.FORECAST_RAINY, split.bottomColorArgb)
        assertEquals(WeatherColors.FORECAST_SUNNY, split.topColorArgb)
        assertEquals(0.99f, split.ratio, 1e-6f)
        assertEquals(0.01f, split.topFraction, 1e-6f)

        // Without override, falls back to canonical icon cloud ratio (0.65 for cloudy_chance_rain)
        val fallbackSplit = WeatherColors.resolveMixedBarSplit(
            iconName = WeatherConditionResolver.IC_CLOUDY_CHANCE_RAIN,
            cloudCoverRatio = null,
        )!!
        assertEquals(WeatherColors.FORECAST_RAINY, fallbackSplit.bottomColorArgb)
        assertEquals(0.65f, fallbackSplit.ratio, 1e-6f)

        // Non-chance-rain icon resolves grey
        val greySplit = WeatherColors.resolveMixedBarSplit(
            iconName = WeatherConditionResolver.IC_CLOUDY,
            cloudCoverRatio = 0.85f,
        )!!
        assertEquals(WeatherColors.FORECAST_CLOUDY, greySplit.bottomColorArgb)
    }

    @Test
    fun overnightOnlyRainKeepsDailyBarGrey() {
        // Google Tue 10/20: "Light Rain", 24h 65% (overnight) -> chance-rain icon, but 15% by day.
        val icon = WeatherConditionResolver.resolveIconName("Light Rain", isNight = false, cloudCover = 49, precipProbability = 65)
        assertEquals(WeatherConditionResolver.IC_PARTLY_CLOUDY_CHANCE_RAIN, icon)
        val dayDry = WeatherColors.resolveMixedBarSplit(icon, 0.49f, dayPrecipProbability = 15)!!
        assertEquals(WeatherColors.FORECAST_CLOUDY, dayDry.bottomColorArgb)

        val dayWet = WeatherColors.resolveMixedBarSplit(icon, 0.49f, dayPrecipProbability = 60)!!
        assertEquals(WeatherColors.FORECAST_RAINY, dayWet.bottomColorArgb)

        // Unknown day precip (snapshot bars) leaves the icon's verdict alone.
        val unknown = WeatherColors.resolveMixedBarSplit(icon, 0.49f, dayPrecipProbability = null)!!
        assertEquals(WeatherColors.FORECAST_RAINY, unknown.bottomColorArgb)
    }

    @Test
    fun rainDayIsGreyCapOverBlueSizedByDaytimeRain() {
        // Silurian Wed 10/21: "Rain", 85 % by day, 99 % noon cloud -> full rain icon. Was a grey bar.
        val wed = WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_RAIN, 0.99f, dayPrecipProbability = 85)!!
        assertEquals(WeatherColors.FORECAST_CLOUDY, wed.topColorArgb)
        assertEquals(WeatherColors.FORECAST_RAINY, wed.bottomColorArgb)
        assertTrue(wed.topOverridesBar)
        assertEquals(0.85f, wed.ratio, 1e-6f)
        assertEquals(0.15f, wed.topFraction, 1e-6f)

        // Storm and snow are rain days too.
        assertTrue(WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_STORM, 0.5f, 70)!!.topOverridesBar)
        assertTrue(WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_SNOW, 0.5f, 70)!!.topOverridesBar)

        // Unknown day % (snapshot bars): the blue height falls back to the cloud ratio.
        val snapshot = WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_RAIN, 0.7f, dayPrecipProbability = null)!!
        assertEquals(0.7f, snapshot.ratio, 1e-6f)
        assertEquals(WeatherColors.FORECAST_CLOUDY, snapshot.topColorArgb)

        // Rain overnight only (day % under 60): not a rain day — the ordinary grey split.
        val dryDay = WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_RAIN, 0.99f, dayPrecipProbability = 15)!!
        assertEquals(false, dryDay.topOverridesBar)
        assertEquals(WeatherColors.FORECAST_CLOUDY, dryDay.bottomColorArgb)

        // Chance-rain icons keep their gold top in the bar's own colour.
        val chance = WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_CLOUDY_CHANCE_RAIN, 0.99f, 70)!!
        assertEquals(false, chance.topOverridesBar)
        assertEquals(WeatherColors.FORECAST_SUNNY, chance.topColorArgb)
    }

    @Test
    fun isDaytimeRainThreshold() {
        assertTrue(WeatherColors.isDaytimeRain(null))
        assertTrue(WeatherColors.isDaytimeRain(WeatherConditionResolver.CHANCE_RAIN_MIN_PRECIP))
        assertEquals(false, WeatherColors.isDaytimeRain(WeatherConditionResolver.CHANCE_RAIN_MIN_PRECIP - 1))
    }

    @Test
    fun shouldDrawAdaptiveBarMatchesContract() {
        assertTrue(WeatherColors.shouldDrawAdaptiveBar(isMixed = true, cloudCoverRatio = null))
        assertTrue(WeatherColors.shouldDrawAdaptiveBar(isMixed = false, cloudCoverRatio = 0.5f))
        assertEquals(false, WeatherColors.shouldDrawAdaptiveBar(isMixed = false, cloudCoverRatio = 0f))
        assertEquals(false, WeatherColors.shouldDrawAdaptiveBar(isMixed = false, cloudCoverRatio = null))
    }
}
