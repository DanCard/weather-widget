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

    /** Observed fog (or none observed) never reads the hours ahead. */
    private val noAhead: () -> MorningFog? = { error("ahead read") }

    @Test
    fun `opacity follows visibility on a log scale with a faint hint`() {
        fun alpha(v: Float) = FogOpacity.alpha(MorningFog(FogLevel.FOG, 55f, v))
        assertEquals(0.15f, alpha(6f), 0.001f)
        assertEquals(0.15f, alpha(10f), 0.001f)
        assertEquals(0.284f, alpha(3f), 0.002f)
        assertEquals(0.497f, alpha(1f), 0.002f)
        assertEquals(0.553f, alpha(0.75f), 0.002f)
        assertEquals(0.766f, alpha(0.25f), 0.002f)
        assertEquals(0.90f, alpha(0.125f), 0.001f)
        assertEquals(0.90f, alpha(0f), 0.001f)
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
        // Relative to the cap: the layers compound, so a higher cap lifts the top proportionally (0.28 of 0.90).
        assertTrue(high < 0.35f * FogOpacity.MAX_ALPHA)
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
    fun `column fog - observed combined with the whole-morning forecast, whatever the time`() {
        val observed = MorningFog(FogLevel.LIGHT, 50f, 3f)
        val forecast = MorningFog(FogLevel.FOG, 56.9f, 0.5f)

        // Open-Meteo, Fold 4 2026-11-11 11:00: observations say only "Light Fog" (3 mi stand-in) while
        // the 11:01 fetch's row holds the morning's 0.0 mi. The old rule read the hours still ahead
        // (one clear hour) and drew the faint observation; the row's dense fog wins now.
        val openMeteoRow = MorningFog(FogLevel.DENSE, 55.3f, 0f)
        val today = DailyMorningFog.forColumn(observed, openMeteoRow, aheadOfNow = noAhead) { null }
        assertEquals(FogLevel.DENSE, today.fog!!.level)
        assertEquals(55.3f, today.fog!!.topTempF!!, 0f)
        assertEquals(0f, today.fog!!.visibilityMi!!, 0f)
        assertEquals("observed+row", today.from)

        // Stronger level, higher top, lower visibility.
        val both = DailyMorningFog.forColumn(observed, forecast, aheadOfNow = noAhead) { null }.fog!!
        assertEquals(FogLevel.FOG, both.level)
        assertEquals(56.9f, both.topTempF!!, 0f)
        assertEquals(0.5f, both.visibilityMi!!, 0f)

        // NWS 2026-11-11: observed mist, the row clear: the observation stands.
        assertEquals(observed, DailyMorningFog.forColumn(observed, MorningFog.NONE, aheadOfNow = noAhead) { null }.fog)

        // No row (a past day fetched before the fog columns): the stored hours.
        val hourly = DailyMorningFog.forColumn(observed, null, aheadOfNow = noAhead) { forecast }
        assertEquals(FogLevel.FOG, hourly.fog!!.level)
        assertEquals("observed+hourly", hourly.from)
        // The row beats the stored hours; the hours are not even read.
        assertEquals(forecast, DailyMorningFog.forColumn(null, forecast, aheadOfNow = noAhead) { error("hours read") }.fog)

        // Future: no observation, the row else the hours.
        assertEquals(forecast, DailyMorningFog.forColumn(null, null, aheadOfNow = noAhead) { forecast }.fog)
        val nothing = DailyMorningFog.forColumn(null, null, aheadOfNow = noAhead) { null }
        assertNull(nothing.fog)
        assertEquals("none", nothing.from)
    }

    @Test
    fun `pre-dawn fog is a third the height and half the width, still on the low`() {
        // User 2026-11-12: "predawn fog [should be] petite. Not very tall. Maybe a third height? Not very wide either."
        val dense = fog.copy(visibilityMi = 0.125f) // dense so no outer layer rounds away
        for (top in listOf(52.9f, 56.9f, 66f)) {
            val day = FogPatch.plan(dense.copy(topTempF = top), 52.8f, 70.9f, yAt, 100f, 60f, 4.5f).first()
            val dawn = FogPatch.plan(dense.copy(topTempF = top, predawn = true), 52.8f, 70.9f, yAt, 100f, 60f, 4.5f).first()
            println("top=$top day=$day predawn=$dawn")
            assertEquals((day.bottom - day.top) / 3f, dawn.bottom - dawn.top, 0.01f)
            assertEquals((day.right - day.left) / 2f, dawn.right - dawn.left, 0.01f)
            assertEquals(day.bottom, dawn.bottom, 0.01f)
            assertEquals((day.left + day.right) / 2f, (dawn.left + dawn.right) / 2f, 0.01f)
        }
        // Same opacity: the size says when, the visibility says how thick.
        val dawnLayers = FogPatch.plan(dense.copy(predawn = true), 52.8f, 70.9f, yAt, 100f, 60f, 4.5f)
        val combined = 1f - dawnLayers.fold(1f) { clear, o -> clear * (1f - (o.argb ushr 24) / 255f) }
        assertEquals(FogOpacity.alpha(dense), combined, 0.004f)
    }

    @Test
    fun `combine is pre-dawn only when both sides are`() {
        val dawn = MorningFog(FogLevel.FOG, 53f, 0.5f, predawn = true)
        val daylight = MorningFog(FogLevel.LIGHT, 55f, 3f, predawn = false)
        assertTrue(MorningFog.combine(dawn, dawn.copy(level = FogLevel.DENSE))!!.predawn)
        assertEquals(false, MorningFog.combine(dawn, daylight)!!.predawn)
        assertEquals(false, MorningFog.combine(daylight, dawn)!!.predawn)
        // A clear side leaves the other as it is.
        assertTrue(MorningFog.combine(MorningFog.NONE, dawn)!!.predawn)
        // Stored columns: null predawn (rows before v88) reads as daylight; NONE stores null.
        assertEquals(false, MorningFog.fromColumns(FogLevel.FOG, 53f, 0.5f, null)!!.predawn)
        assertTrue(MorningFog.fromColumns(FogLevel.FOG, 53f, 0.5f, true)!!.predawn)
        assertNull(MorningFog.predawnColumn(MorningFog.NONE))
        assertEquals(true, MorningFog.predawnColumn(dawn))
        // No usable site (NaN before the first fix): no sunrise, never a crash.
        val day = java.time.LocalDate.of(2026, 11, 12)
        assertNull(MorningFogWindow.sunriseMs(day, Double.NaN, Double.NaN))
        assertNull(MorningFogWindow.sunriseMs(day, null, -122.0))
        assertNull(MorningFogWindow.sunriseMs(day, 120.0, -122.0))
    }

    @Test
    fun `column fog - stations that saw no fog cancel forecast fog for the hours gone`() {
        // NWS, Pixel 2026-11-12 07:28: the 00:14 fetch's row holds DENSE 0.24 mi (03-05 visibility only,
        // "Mostly Cloudy"); KNUQ read 10SM CLR all morning, so observed is NONE. The old combine drew it
        // at 77 %. User: "has fog indicator this morning, but there is no fog".
        val nwsRow = MorningFog(FogLevel.DENSE, 52f, 0.24f, predawn = true)
        val rowNeverRead: () -> MorningFog? = { error("hours read") }
        val gone = DailyMorningFog.forColumn(MorningFog.NONE, nwsRow, aheadOfNow = { MorningFog.NONE }, fromHourly = rowNeverRead)
        assertEquals(MorningFog.NONE, gone.fog)
        assertEquals("observed+ahead", gone.from)

        // 02:00, the same morning: nothing foggy observed yet, the forecast fog is still ahead.
        val stillAhead = MorningFog(FogLevel.DENSE, 52f, 0.24f, predawn = true)
        val early = DailyMorningFog.forColumn(MorningFog.NONE, nwsRow, aheadOfNow = { stillAhead }, fromHourly = rowNeverRead)
        assertEquals(stillAhead, early.fog)

        // A saturation hint is not fog seen: past-hour forecast fog stays out, and the hint draws as a hint.
        val hint = MorningFog(FogLevel.HINT, 52f, 8f)
        val hintOnly = DailyMorningFog.forColumn(hint, nwsRow, aheadOfNow = { MorningFog.NONE }, fromHourly = rowNeverRead)
        println("hint-only 07:30: ${hintOnly.fog} alpha=${FogOpacity.alpha(hintOnly.fog!!)}")
        assertEquals(hint, hintOnly.fog)
        assertTrue(FogOpacity.alpha(hintOnly.fog!!) < FogOpacity.MIN_ALPHA)
        // Before the forecast fog's hours, the fog ahead still shows.
        assertEquals(FogLevel.DENSE, DailyMorningFog.forColumn(hint, nwsRow, aheadOfNow = { stillAhead }, fromHourly = rowNeverRead).fog!!.level)

        // Hours ahead not covered (null): the stations' answer stands.
        assertEquals(MorningFog.NONE, DailyMorningFog.forColumn(MorningFog.NONE, nwsRow, aheadOfNow = { null }, fromHourly = rowNeverRead).fog)
    }
}
