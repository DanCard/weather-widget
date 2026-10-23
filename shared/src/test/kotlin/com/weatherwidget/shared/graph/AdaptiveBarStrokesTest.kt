package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.WeatherColors
import com.weatherwidget.shared.util.WeatherConditionResolver
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** The one daily-bar sky both Android and desktop draw. */
@Category(ShortDuration::class)
class AdaptiveBarStrokesTest {

    private val gold = WeatherColors.FORECAST_SUNNY
    private val grey = WeatherColors.FORECAST_CLOUDY
    private val blue = WeatherColors.FORECAST_RAINY

    private fun assertSky(sky: AdaptiveBarStrokes.Sky, clear: Float, cloud: Float, rain: Float) {
        assertEquals("clear", clear, sky.clear, 1e-5f)
        assertEquals("cloud", cloud, sky.cloud, 1e-5f)
        assertEquals("rain", rain, sky.rain, 1e-5f)
    }

    @Test
    fun noonCloudAloneDecidesADryDay() {
        // The icon says "clear"; the noon cloud % wins.
        assertSky(AdaptiveBarStrokes.sky(0.91f, WeatherConditionResolver.IC_CLEAR, null), clear = 0.09f, cloud = 0.91f, rain = 0f)
        assertSky(AdaptiveBarStrokes.sky(0f, WeatherConditionResolver.IC_CLOUDY, null), clear = 1f, cloud = 0f, rain = 0f)
        assertSky(AdaptiveBarStrokes.sky(1f, WeatherConditionResolver.IC_CLEAR, null), clear = 0f, cloud = 1f, rain = 0f)
    }

    @Test
    fun rainComesFirstAndTheCapIsTheSky() {
        // 80 % rain, half cloud: blue 80, the 20 % cap split gold 10 over grey 10.
        assertSky(AdaptiveBarStrokes.sky(0.5f, null, 80), clear = 0.1f, cloud = 0.1f, rain = 0.8f)
        // No cloud figure and no icon: an all-grey cap.
        assertSky(AdaptiveBarStrokes.sky(null, null, 80), clear = 0f, cloud = 0.2f, rain = 0.8f)
        // Any chance is blue (user, 2026-10-23) — Google today: 20 % by day, noon cloud 91 %.
        assertSky(AdaptiveBarStrokes.sky(0.91f, WeatherConditionResolver.IC_CLEAR, 20), clear = 0.8f * 0.09f, cloud = 0.8f * 0.91f, rain = 0.2f)
        assertSky(AdaptiveBarStrokes.sky(0.5f, null, 0), clear = 0.5f, cloud = 0.5f, rain = 0f)
    }

    @Test
    fun noNoonCloudFallsBackToTheIconsSky() {
        // Google Sat 2026-10-31: past the 192 h download, condition "Cloudy".
        assertSky(AdaptiveBarStrokes.sky(null, WeatherConditionResolver.IC_CLOUDY, 15), clear = 0f, cloud = 0.85f, rain = 0.15f)
        assertSky(AdaptiveBarStrokes.sky(null, WeatherConditionResolver.IC_CLEAR, null), clear = 1f, cloud = 0f, rain = 0f)
        assertSky(AdaptiveBarStrokes.sky(null, WeatherConditionResolver.IC_PARTLY_CLOUDY, null), clear = 0.65f, cloud = 0.35f, rain = 0f)
        // Nothing known: all grey.
        assertSky(AdaptiveBarStrokes.sky(null, WeatherConditionResolver.IC_WIND, null), clear = 0f, cloud = 1f, rain = 0f)
    }

    @Test
    fun oneColourIsOneStroke() {
        val p = AdaptiveBarStrokes.plan(0f, null, null, 100f, 300f, 5f)
        assertEquals(listOf(AdaptiveBarStrokes.Stroke(gold, null)), p.strokes)
        val tiny = AdaptiveBarStrokes.plan(0.7f, null, null, 100f, 101f, 5f)
        assertEquals(listOf(AdaptiveBarStrokes.Stroke(grey, null)), tiny.strokes)
    }

    @Test
    fun edgeIsABlendCentredOnTheShare() {
        // Ink 95..305 (210 px); 9 % gold → the edge at 95 + 18.9 = 113.9, blended ± 5 px.
        val p = AdaptiveBarStrokes.plan(0.91f, null, null, 100f, 300f, 5f)
        assertEquals(2 + AdaptiveBarStrokes.BLEND_STEPS, p.strokes.size)
        assertEquals(AdaptiveBarStrokes.Stroke(grey, null), p.strokes.first())
        assertEquals(gold, p.strokes.last().colorArgb)
        assertEquals(113.9f - 5f, p.strokes.last().clipBottomY!!, 1e-3f)
        assertEquals(113.9f + 5f, p.strokes[1].clipBottomY!!, 1e-3f)
        // Clip bottoms rise strictly and the steps move from grey towards gold.
        val clips = p.strokes.drop(1).map { it.clipBottomY!! }
        assertTrue(clips.zipWithNext().all { (a, b) -> b < a })
        val red = p.strokes.drop(1).map { (it.colorArgb ushr 16) and 0xFF }
        assertTrue(red.zipWithNext().all { (a, b) -> b >= a }) // gold is redder than grey
    }

    @Test
    fun aThinShareGetsANarrowerBlendInsideIt() {
        // 2 % gold = 4.2 px of ink (95..99.2): the band narrows to ± 2.1 px, so it stays inside the bar.
        val p = AdaptiveBarStrokes.plan(0.98f, null, null, 100f, 300f, 5f)
        assertEquals(99.2f - 2.1f, p.strokes.last().clipBottomY!!, 1e-3f)
        assertEquals(99.2f + 2.1f, p.strokes[1].clipBottomY!!, 1e-3f)
    }

    @Test
    fun rainyBarIsBlueThenGreyThenGold() {
        val p = AdaptiveBarStrokes.plan(0.5f, null, 80, 100f, 300f, 5f)
        assertEquals(3 + 2 * AdaptiveBarStrokes.BLEND_STEPS, p.strokes.size)
        assertEquals(blue, p.strokes.first().colorArgb)
        assertNull(p.strokes.first().clipBottomY)
        assertEquals(grey, p.strokes[1 + AdaptiveBarStrokes.BLEND_STEPS].colorArgb)
        assertEquals(gold, p.strokes.last().colorArgb)
    }

    @Test
    fun snapshotBarKeepsItsOwnClearColour() {
        val yellow = 0xFFFFFF00.toInt()
        val p = AdaptiveBarStrokes.plan(0.5f, null, null, 100f, 300f, 5f, clearArgb = yellow)
        assertEquals(yellow, p.strokes.last().colorArgb)
    }

    @Test
    fun priorBarTakesItsOwnRowsRainToday() {
        // Today: the picked prior row's daytime chance, else its whole-day one — never the live day's.
        assertEquals(70, AdaptiveBarStrokes.priorBarRainPercent(isPast = false, 70, 40, dayRainPercent = 10))
        assertEquals(40, AdaptiveBarStrokes.priorBarRainPercent(isPast = false, null, 40, dayRainPercent = 10))
        assertNull(AdaptiveBarStrokes.priorBarRainPercent(isPast = false, null, null, dayRainPercent = 10))
        // A past day keeps no prior chance: the day's.
        assertEquals(10, AdaptiveBarStrokes.priorBarRainPercent(isPast = true, null, null, dayRainPercent = 10))
    }

    @Test
    fun lerpEnds() {
        assertEquals(grey, AdaptiveBarStrokes.lerpArgb(grey, gold, 0f))
        assertEquals(gold, AdaptiveBarStrokes.lerpArgb(grey, gold, 1f))
    }
}
