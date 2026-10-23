package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.WeatherColors
import com.weatherwidget.shared.util.WeatherConditionResolver
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/** The one daily-bar stroke plan both Android and desktop draw. */
@Category(ShortDuration::class)
class AdaptiveBarStrokesTest {

    private val yellow = 0xFFFFFF00.toInt()
    private val grey = WeatherColors.FORECAST_CLOUDY
    private val gold = WeatherColors.FORECAST_SUNNY

    private fun plan(icon: String?, cloud: Float?, dayPct: Int?, bar: Int) =
        AdaptiveBarStrokes.plan(icon, cloud, dayPct, bar, highY = 100f, lowY = 300f, inkRadius = 5f)

    @Test
    fun cloudyDayTopIsGoldNotTheGreyBar() {
        // Google 2026-10-23: noon cloud 91 %, 20 % by day, cloud icon → grey bar. Its clear 9 % used
        // to be painted in the bar's grey over a grey bottom: a solid grey bar.
        val p = plan(WeatherConditionResolver.IC_CLOUDY, 0.91f, 20, grey)
        assertEquals(listOf(grey, gold), p.strokes.map { it.colorArgb })
        assertNull(p.strokes[0].clipBottomY)
        // 9 % of the 210 px of ink (95..305): the top ends at 95 + 18.9.
        assertEquals(113.9f, p.strokes[1].clipBottomY!!, 1e-3f)
    }

    @Test
    fun cloudyGreyTopKeepsTheBarsAlpha() {
        val translucentGrey = (grey and 0x00FFFFFF) or 0x80000000.toInt()
        val p = plan(WeatherConditionResolver.IC_CLOUDY, 0.5f, null, translucentGrey)
        assertEquals((gold and 0x00FFFFFF) or 0x80000000.toInt(), p.strokes.last().colorArgb)
    }

    @Test
    fun snapshotYellowKeepsItsOwnTop() {
        val p = plan(WeatherConditionResolver.IC_CLOUDY, 0.91f, null, yellow)
        assertEquals(listOf(grey, yellow), p.strokes.map { it.colorArgb })
    }

    @Test
    fun rainyBarIsBlueGreyGold() {
        val p = plan(WeatherConditionResolver.IC_RAIN, 0.5f, 80, WeatherColors.FORECAST_RAINY)
        assertEquals(listOf(WeatherColors.FORECAST_RAINY, grey, gold), p.strokes.map { it.colorArgb })
    }

    @Test
    fun solidWithoutAnIconOrACloudShare() {
        assertEquals(listOf(AdaptiveBarStrokes.Stroke(grey, null)), plan(null, 0.9f, null, grey).strokes)
        // Not a mixed icon and no cloud share: no split, whatever the colour.
        assertEquals(listOf(AdaptiveBarStrokes.Stroke(grey, null)), plan(WeatherConditionResolver.IC_CLOUDY, null, null, grey).strokes)
        assertEquals(listOf(AdaptiveBarStrokes.Stroke(grey, null)), plan(WeatherConditionResolver.IC_CLOUDY, 0f, null, grey).strokes)
    }

    @Test
    fun barTooShortToSplitIsSolid() {
        val p = AdaptiveBarStrokes.plan(WeatherConditionResolver.IC_CLOUDY, 0.5f, null, grey, 100f, 101f, 5f)
        assertNull(p.split)
        assertEquals(listOf(AdaptiveBarStrokes.Stroke(grey, null)), p.strokes)
    }

    @Test
    fun overcastHasNoTopStroke() {
        val p = plan(WeatherConditionResolver.IC_CLOUDY, 1f, null, grey)
        assertEquals(listOf(AdaptiveBarStrokes.Stroke(grey, null)), p.strokes)
    }
}
