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
        // Unknown day % sizes blue by cloud; the 1 % cap is split 1 % gold / 99 % grey.
        assertEquals(WeatherColors.FORECAST_SUNNY, split.topColorArgb)
        assertTrue(split.topOverridesBar)
        assertEquals(0.99f, split.ratio, 1e-6f)
        assertEquals(0.01f * 0.01f, split.topFraction, 1e-6f)
        assertEquals(0.01f * 0.99f, split.midFraction, 1e-6f)

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
    fun highDaytimeRainIsRainyBarUnderNonRainIcon() {
        // Google Thu 10/22: "Mostly Cloudy", 85 % by day, 76 % noon cloud -> partly-cloudy icon. Was grey.
        val icon = WeatherConditionResolver.resolveIconName("Mostly Cloudy", isNight = false, cloudCover = 76, precipProbability = 85)
        assertEquals(false, WeatherConditionResolver.isPrecipitation(icon) || WeatherConditionResolver.isChanceOfRainIcon(icon))
        val google = WeatherColors.resolveMixedBarSplit(icon, 0.76f, dayPrecipProbability = 85)!!
        assertSegments(google, gold = 0.15f * 0.24f, grey = 0.15f * 0.76f, blue = 0.85f)

        // Below the gate it stays the ordinary cloud split; unknown % (snapshot bars) too.
        assertEquals(WeatherColors.FORECAST_CLOUDY, WeatherColors.resolveMixedBarSplit(icon, 0.76f, 59)!!.bottomColorArgb)
        assertEquals(WeatherColors.FORECAST_CLOUDY, WeatherColors.resolveMixedBarSplit(icon, 0.76f, null)!!.bottomColorArgb)
    }

    @Test
    fun segmentEdgesMeasureThePaintedInkIncludingRoundCaps() {
        // Thu 10/22 desktop: 85 % rain, 76 % cloud; endpoints 100..300 px, 20 px stroke (radius 10).
        // Ink is 90..310 (220 px): gold 3.6 % = 7.9 px, not 3.6 % of 200 plus a whole stroke width.
        val split = WeatherColors.rainDaySplit(0.76f, 85)!!
        val edges = WeatherColors.segmentEdges(split.topFraction, split.midFraction, 100f, 300f, 10f)
        assertEquals(90f + 220f * 0.15f * 0.24f, edges.topEndY, 1e-3f)
        assertEquals(90f + 220f * 0.15f, edges.midEndY, 1e-3f)

        // No stroke: the bare endpoints, as before.
        val bare = WeatherColors.segmentEdges(0.25f, 0f, 0f, 100f, 0f)
        assertEquals(25f, bare.topEndY, 1e-6f)
        assertEquals(25f, bare.midEndY, 1e-6f)
    }

    @Test
    fun rainyBarIsSkyCapOverBlueSizedByDaytimeRain() {
        // Silurian Wed 10/21: "Rain", 85 % by day, 99 % noon cloud -> full rain icon. Was a grey bar.
        val silurian = WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_RAIN, 0.99f, dayPrecipProbability = 85)!!
        assertSegments(silurian, gold = 0.15f * 0.01f, grey = 0.15f * 0.99f, blue = 0.85f)

        // Open-Meteo Wed: 61 %, 100 % cloud, cloudy-chance-rain icon. Was solid blue (blue = cloud share).
        val meteo = WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_CLOUDY_CHANCE_RAIN, 1.0f, 61)!!
        assertSegments(meteo, gold = 0f, grey = 0.39f, blue = 0.61f)

        // Google Wed: "Light Rain", 70 %, 67 % cloud, partly-cloudy-chance-rain icon. Was gold over blue.
        val google = WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_PARTLY_CLOUDY_CHANCE_RAIN, 0.67f, 70)!!
        assertSegments(google, gold = 0.30f * 0.33f, grey = 0.30f * 0.67f, blue = 0.70f)

        // Storm and snow are rainy bars too.
        assertEquals(WeatherColors.FORECAST_RAINY, WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_STORM, 0.5f, 70)!!.bottomColorArgb)
        assertEquals(WeatherColors.FORECAST_RAINY, WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_SNOW, 0.5f, 70)!!.bottomColorArgb)

        // A rain icon with no cloud figure: all-grey cap.
        val noCloud = WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_RAIN, null, 80)!!
        assertSegments(noCloud, gold = 0f, grey = 0.20f, blue = 0.80f)

        // Unknown day % (snapshot bars): blue falls back to the cloud ratio.
        val snapshot = WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_RAIN, 0.7f, dayPrecipProbability = null)!!
        assertEquals(0.7f, snapshot.ratio, 1e-6f)

        // Under 60 % by day: no blue, the ordinary two-segment cloud split.
        val dryDay = WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_RAIN, 0.99f, dayPrecipProbability = 15)!!
        assertEquals(false, dryDay.topOverridesBar)
        assertEquals(0f, dryDay.midFraction, 1e-6f)
        assertEquals(WeatherColors.FORECAST_CLOUDY, dryDay.bottomColorArgb)
        val dryChance = WeatherColors.resolveMixedBarSplit(WeatherConditionResolver.IC_CLOUDY_CHANCE_RAIN, 1.0f, 35)!!
        assertEquals(WeatherColors.FORECAST_CLOUDY, dryChance.bottomColorArgb)
        assertEquals(1.0f, dryChance.ratio, 1e-6f)
    }

    private fun assertSegments(split: WeatherColors.MixedBarSplit, gold: Float, grey: Float, blue: Float) {
        assertEquals(WeatherColors.FORECAST_SUNNY, split.topColorArgb)
        assertEquals(WeatherColors.FORECAST_CLOUDY, split.midColorArgb)
        assertEquals(WeatherColors.FORECAST_RAINY, split.bottomColorArgb)
        assertTrue(split.topOverridesBar)
        assertEquals(gold, split.topFraction, 1e-5f)
        assertEquals(grey, split.midFraction, 1e-5f)
        assertEquals(blue, split.ratio, 1e-5f)
        assertEquals(1f, split.topFraction + split.midFraction + split.ratio, 1e-5f)
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
