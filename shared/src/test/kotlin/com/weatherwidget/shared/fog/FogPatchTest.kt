package com.weatherwidget.shared.fog

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The fog ellipse's geometry and opacity, and which fog a column shows
 * (plans/261111-daily-bar-morning-fog.md).
 */
@Category(ShortDuration::class)
class FogPatchTest {
    // 50..80 °F over y 300..0: 10 px per degree.
    private val yAt: (Float) -> Float = { t -> (80f - t) * 10f }
    private val fog = MorningFog(FogLevel.LIGHT, topTempF = 56.9f, visibilityMi = 3f)

    @Test
    fun `opacity follows visibility on a log scale with a faint hint`() {
        fun alpha(v: Float) = FogOpacity.alpha(MorningFog(FogLevel.FOG, 55f, v))
        assertEquals(0.15f, alpha(6f), 0.001f)
        assertEquals(0.15f, alpha(10f), 0.001f)
        assertEquals(0.240f, alpha(3f), 0.002f)
        assertEquals(0.381f, alpha(1f), 0.002f)
        assertEquals(0.419f, alpha(0.75f), 0.002f)
        assertEquals(0.560f, alpha(0.25f), 0.002f)
        assertEquals(0.65f, alpha(0.125f), 0.001f)
        assertEquals(0.65f, alpha(0f), 0.001f)
        // The hint runs on from 15 % at 6 SM down to 5 % at 10 SM: one continuous spectrum.
        assertEquals(0.125f, FogOpacity.alpha(MorningFog(FogLevel.HINT, 55f, 7f)), 0.001f)
        assertEquals(0.05f, FogOpacity.alpha(MorningFog(FogLevel.HINT, 55f, 10f)), 0.001f)
        assertEquals(0.15f, FogOpacity.alpha(MorningFog(FogLevel.HINT, 55f, 6f)), 0.001f)
        assertEquals(0f, FogOpacity.alpha(MorningFog.NONE), 0f)
    }

    @Test
    fun `ellipse sits on the low, reaches the burn-off, wider than the bar`() {
        val ovals = FogPatch.plan(fog, lowTempF = 52.8f, highTempF = 70.9f, yAt = yAt, centerX = 100f, columnWidth = 60f, inkRadius = 4.5f)
        println("ovals=$ovals")
        assertTrue(ovals.size in (FogPatch.LAYERS - 2)..FogPatch.LAYERS) // a layer rounding to 0 is skipped
        val outer = ovals.first()
        // The outermost layers' weighted alpha can round to 0 and be skipped: allow two layers' inset.
        fun nearWidth(w: Float, expected: Float) =
            assertTrue("width $w vs $expected", w <= expected + 0.01f && w >= expected * (1f - 4f * FogPatch.INSET_FRACTION))
        // A short fog (top 4° above the low) gets the larger minimum patch, at least 130 % of the column.
        assertTrue(outer.right - outer.left in 78f * (1f - 4f * FogPatch.INSET_FRACTION)..(78f * FogPatch.MIN_PATCH_SCALE))
        // A tall fog is exactly 130 % of the column: it spills into the neighbours.
        val tall = FogPatch.plan(fog.copy(topTempF = 66f), 52.8f, 70.9f, yAt, 100f, 60f, 4.5f).first()
        nearWidth(tall.right - tall.left, 78f)
        assertTrue(outer.right - outer.left > 9f) // much wider than the 9 px bar
        assertEquals(yAt(52.8f) + 4.5f, outer.bottom, (outer.bottom - outer.top) * FogPatch.BOTTOM_SHRINK * 2f / (FogPatch.LAYERS - 1))
        // Reaches past y(56.9) by the tip allowance.
        assertTrue(outer.top < yAt(56.9f))
        // Nested: each inside the last.
        ovals.zipWithNext().forEach { (a, b) ->
            assertTrue(b.left > a.left && b.right < a.right && b.top > a.top && b.bottom < a.bottom)
        }
        // The layers compound to the target opacity at the centre.
        val combined = 1f - ovals.fold(1f) { clear, o -> clear * (1f - (o.argb ushr 24) / 255f) }
        assertEquals(FogOpacity.alpha(fog), combined, 0.004f)
    }

    @Test
    fun `layers gather low in the ellipse, so the top fades`() {
        val tall = FogPatch.plan(fog.copy(topTempF = 62f, visibilityMi = 0.125f), 52.8f, 70.9f, yAt, 100f, 60f, 4.5f)
        val outer = tall.first()
        val inner = tall.last()
        val height = outer.bottom - outer.top
        println("outer=$outer inner=$inner")
        // The innermost (densest) layer sits in the bottom third; its centre well below the outer's.
        assertTrue(inner.top > outer.top + 0.6f * height)
        assertTrue(inner.bottom > outer.bottom - 0.15f * height)
        // Centre-line opacity a quarter of the way up is well over twice that three quarters up.
        fun alphaAt(y: Float) = 1f - tall.filter { y in it.top..it.bottom }
            .fold(1f) { clear, o -> clear * (1f - (o.argb ushr 24) / 255f) }
        val low = alphaAt(outer.bottom - 0.25f * height)
        val high = alphaAt(outer.bottom - 0.75f * height)
        println("alpha low=$low high=$high")
        assertTrue(low > 2.5f * high)
        assertTrue(high < 0.2f)
    }

    @Test
    fun `short patch is a circle, long fog stops at the bar top, none draws nothing`() {
        // Dense, so even the outermost layer's weighted alpha rounds above 0 and is drawn.
        val dense = fog.copy(visibilityMi = 0.125f)
        val atLow = FogPatch.plan(dense.copy(topTempF = 52.9f), 52.8f, 70.9f, yAt, 100f, 60f, 4.5f).first()
        assertEquals(atLow.right - atLow.left, atLow.bottom - atLow.top, 0.01f)
        assertEquals(78f * FogPatch.MIN_PATCH_SCALE, atLow.right - atLow.left, 0.01f) // 1.25 × the ellipse width

        val allDay = FogPatch.plan(dense.copy(topTempF = 90f), 52.8f, 70.9f, yAt, 100f, 60f, 4.5f).first()
        assertEquals(yAt(70.9f) - 4.5f, allDay.top, 0.01f)

        assertTrue(FogPatch.plan(MorningFog.NONE, 52.8f, 70.9f, yAt, 100f, 60f, 4.5f).isEmpty())
        assertTrue(FogPatch.plan(null, 52.8f, 70.9f, yAt, 100f, 60f, 4.5f).isEmpty())
        assertTrue(FogPatch.plan(fog, null, 70.9f, yAt, 100f, 60f, 4.5f).isEmpty())
    }

    @Test
    fun `column fog - past observed only, today observed plus ahead, future the row`() {
        val observed = MorningFog(FogLevel.LIGHT, 50f, 3f)
        val forecast = MorningFog(FogLevel.FOG, 56.9f, 0.5f)

        // Past: never the forecast.
        assertNull(DailyMorningFog.forColumn(true, false, null, forecast, null, false) { forecast }.fog)
        assertEquals(observed, DailyMorningFog.forColumn(true, false, observed, forecast, null, false) { null }.fog)

        // Today, NWS 2026-11-11: observed mist, no forecast fog ahead.
        val nws = DailyMorningFog.forColumn(false, true, observed, MorningFog.NONE, MorningFog.NONE, true) { null }
        assertEquals(observed, nws.fog)
        // Today with fog ahead too: stronger level, higher top, lower visibility.
        val both = DailyMorningFog.forColumn(false, true, observed, null, forecast, true) { null }.fog!!
        assertEquals(FogLevel.FOG, both.level)
        assertEquals(56.9f, both.topTempF!!, 0f)
        assertEquals(0.5f, both.visibilityMi!!, 0f)
        // Today after noon with no observation: the row's forecast.
        assertEquals(forecast, DailyMorningFog.forColumn(false, true, null, forecast, null, false) { null }.fog)

        // Future: the row, else the stored hours.
        assertEquals(forecast, DailyMorningFog.forColumn(false, false, observed, forecast, null, true) { null }.fog)
        assertEquals(forecast, DailyMorningFog.forColumn(false, false, null, null, null, true) { forecast }.fog)
    }
}
