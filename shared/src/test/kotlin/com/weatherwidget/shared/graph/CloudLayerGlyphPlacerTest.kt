package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pure geometry, so these assert pixel positions directly — no font engine involved (see the
 * Robolectric-has-no-font-engine constraint that shapes the rest of the graph tests).
 */
@Category(ShortDuration::class)
class CloudLayerGlyphPlacerTest {

    /** A flat horizontal run at constant coverage, one vertex every 100px. */
    private fun flat(cover: Int?, other: Int? = null, total: Int? = null, n: Int = 5) =
        (0 until n).map {
            LayerVertex(x = it * 100f, y = 50f, cover = cover, otherCover = other, totalCover = total)
        }

    @Test
    fun `glyphs land one step apart along a flat curve`() {
        val glyphs = CloudLayerGlyphPlacer.place(flat(80), 'm', stepPx = 100f)

        // 400px of curve, first glyph deferred a full step: 100, 200, 300, 400.
        assertEquals(listOf(100f, 200f, 300f, 400f), glyphs.map { it.x })
        assertTrue(glyphs.all { it.y == 50f })
        assertTrue(glyphs.all { it.glyph == 'm' })
    }

    @Test
    fun `coverage below the floor draws nothing`() {
        val glyphs = CloudLayerGlyphPlacer.place(flat(CloudLayerGlyphPlacer.MIN_COVER - 1), 'm', 50f)
        assertEquals(emptyList<LayerGlyph>(), glyphs)
    }

    @Test
    fun `coverage at the floor still draws`() {
        val glyphs = CloudLayerGlyphPlacer.place(flat(CloudLayerGlyphPlacer.MIN_COVER), 'm', 50f)
        assertTrue(glyphs.isNotEmpty())
    }

    @Test
    fun `actual trail suppresses exact one hundred when total is also one hundred`() {
        val glyphs = CloudLayerGlyphPlacer.place(
            flat(cover = 100, total = 100),
            'h',
            stepPx = 50f,
            suppressMatchingTotal = true,
        )

        assertEquals(emptyList<LayerGlyph>(), glyphs)
    }

    @Test
    fun `actual trail keeps one hundred when total differs`() {
        val glyphs = CloudLayerGlyphPlacer.place(
            flat(cover = 100, total = 99),
            'h',
            stepPx = 50f,
            suppressMatchingTotal = true,
        )

        assertTrue(glyphs.isNotEmpty())
    }

    @Test
    fun `actual trail suppresses matching non-extreme values`() {
        val glyphs = CloudLayerGlyphPlacer.place(
            flat(cover = 99, total = 99),
            'h',
            stepPx = 50f,
            suppressMatchingTotal = true,
        )

        assertTrue(glyphs.isEmpty())
    }

    @Test
    fun `actual trail suppresses exact zero when zero is admitted by the caller`() {
        val glyphs = CloudLayerGlyphPlacer.place(
            flat(cover = 0, total = 0),
            'm',
            stepPx = 50f,
            minCover = 0,
            suppressMatchingTotal = true,
        )

        assertEquals(emptyList<LayerGlyph>(), glyphs)
    }

    @Test
    fun `total-match suppression preserves the adjacent distinct climb`() {
        val vertices = listOf(
            LayerVertex(x = 0f, y = 0f, cover = 100, totalCover = 100),
            LayerVertex(x = 100f, y = 100f, cover = 80, totalCover = 100),
        )
        val glyphs = CloudLayerGlyphPlacer.place(
            vertices,
            'm',
            stepPx = 25f,
            suppressMatchingTotal = true,
        )

        assertTrue("the non-extreme interior of the segment must remain visible", glyphs.isNotEmpty())
        assertTrue(glyphs.all { it.x > 0f })
    }

    @Test
    fun `actual trail keeps below-five cover when it differs from total`() {
        val glyphs = CloudLayerGlyphPlacer.place(
            flat(cover = 3, total = 80),
            CloudLayerGlyphPlacer.LOW_GLYPH,
            stepPx = 50f,
            minCover = 0,
            suppressMatchingTotal = true,
        )

        assertTrue(glyphs.isNotEmpty())
    }

    @Test
    fun `a zero stretch between two cloudy stretches leaves a gap`() {
        // 0% in the middle is an ABSENT layer, not a line along the bottom of the plot.
        val vertices = listOf(80, 80, 0, 0, 80, 80).mapIndexed { i, c ->
            LayerVertex(x = i * 100f, y = 50f, cover = c)
        }
        val xs = CloudLayerGlyphPlacer.place(vertices, 'm', stepPx = 100f).map { it.x }

        // Nothing may land inside the 0% stretch (x 200..300).
        assertTrue("expected a gap over the zero run, got $xs", xs.none { it in 200f..300f })
        assertTrue("expected glyphs on both cloudy sides, got $xs", xs.any { it < 200f } && xs.any { it > 300f })
    }

    @Test
    fun `a null endpoint suppresses its segment instead of interpolating toward zero`() {
        val vertices = listOf(
            LayerVertex(0f, 50f, 90),
            LayerVertex(100f, 50f, null),
            LayerVertex(200f, 50f, 90),
        )
        val xs = CloudLayerGlyphPlacer.place(vertices, 'h', stepPx = 25f).map { it.x }

        assertTrue("no glyph may fall in the null segment, got $xs", xs.none { it in 0f..100f && it > 0f && it < 100f })
    }

    @Test
    fun `phase offset keeps the two layers off the same x`() {
        val mid = CloudLayerGlyphPlacer.place(
            flat(100, other = 100), 'm', stepPx = 100f, phaseFraction = CloudLayerGlyphPlacer.MID_PHASE,
        )
        val high = CloudLayerGlyphPlacer.place(
            flat(100, other = 100), 'h', stepPx = 100f, phaseFraction = CloudLayerGlyphPlacer.HIGH_PHASE,
        )

        assertTrue(mid.isNotEmpty() && high.isNotEmpty())
        assertTrue(
            "mid ${mid.map { it.x }} and high ${high.map { it.x }} must not share an x",
            mid.map { it.x }.intersect(high.map { it.x }.toSet()).isEmpty(),
        )
    }

    @Test
    fun `coincident layers are nudged apart`() {
        // Both layers pinned at 100%: without the nudge they map to the same y and overprint.
        val nudged = CloudLayerGlyphPlacer.place(flat(100, other = 100), 'h', 100f, nudgePx = -8f)
        assertTrue(nudged.isNotEmpty())
        assertTrue("expected an upward nudge", nudged.all { it.y == 42f })
    }

    @Test
    fun `layers far apart are not nudged`() {
        val plain = CloudLayerGlyphPlacer.place(flat(100, other = 10), 'h', 100f, nudgePx = -8f)
        assertTrue(plain.isNotEmpty())
        assertTrue("values are far apart; y must be untouched", plain.all { it.y == 50f })
    }

    @Test
    fun `a missing sibling value never nudges`() {
        val plain = CloudLayerGlyphPlacer.place(flat(100, other = null), 'h', 100f, nudgePx = -8f)
        assertTrue(plain.isNotEmpty())
        assertTrue(plain.all { it.y == 50f })
    }

    @Test
    fun `glyphs follow a sloped curve`() {
        val vertices = listOf(
            LayerVertex(0f, 0f, 90),
            LayerVertex(300f, 400f, 90),
        )
        val glyphs = CloudLayerGlyphPlacer.place(vertices, 'm', stepPx = 100f)

        // 3-4-5 triangle: 500px of arc length at 100px steps, so glyphs at 100..500 along it.
        assertEquals(5, glyphs.size)
        glyphs.forEach { g ->
            assertEquals("must stay on the line y = 4x/3", 4f * g.x / 3f, g.y, 0.01f)
        }
    }

    @Test
    fun `degenerate inputs are safe`() {
        assertEquals(emptyList<LayerGlyph>(), CloudLayerGlyphPlacer.place(emptyList(), 'm', 10f))
        assertEquals(emptyList<LayerGlyph>(), CloudLayerGlyphPlacer.place(flat(80, n = 1), 'm', 10f))
        assertEquals(emptyList<LayerGlyph>(), CloudLayerGlyphPlacer.place(flat(80), 'm', 0f))
        assertEquals(emptyList<LayerGlyph>(), CloudLayerGlyphPlacer.place(flat(80), 'm', Float.NaN))
    }

    @Test
    fun `hasVisibleCover gates the whole pass`() {
        assertTrue(CloudLayerGlyphPlacer.hasVisibleCover(listOf(null, 0, 40)))
        assertEquals(false, CloudLayerGlyphPlacer.hasVisibleCover(listOf(null, 0, 4)))
        assertEquals(false, CloudLayerGlyphPlacer.hasVisibleCover(emptyList()))
    }

    @Test
    fun `a near-vertical transition carries dashes up the climb`() {
        // One hour going 12% -> 100% spans the plot vertically. Because spacing follows the curve,
        // that climb carries its own run of glyphs rather than being thinned to one or two — the
        // trail stays continuous where the layer moves fastest.
        val vertices = listOf(
            LayerVertex(x = 0f, y = 300f, cover = 12),
            LayerVertex(x = 30f, y = 0f, cover = 100),
            LayerVertex(x = 60f, y = 0f, cover = 100),
        )
        val glyphs = CloudLayerGlyphPlacer.place(vertices, 'm', stepPx = 20f)

        val onTheClimb = glyphs.count { it.y > 0f && it.y < 300f }
        assertTrue("expected glyphs spread up the climb, got ${glyphs.map { it.x to it.y }}", onTheClimb >= 3)
    }

    @Test
    fun `forecast and actual glyph styles stay small and sparse`() {
        // Guards the visually reviewed treatment: equal small type, with both former step sizes
        // increased by 30%. Actuals still use the sparser of the two trails.
        assertEquals(4.5f, CloudLayerGlyphPlacer.GLYPH_SIZE_DP, 0.001f)
        assertEquals(
            CloudLayerGlyphPlacer.GLYPH_SIZE_DP,
            CloudLayerGlyphPlacer.ACTUAL_GLYPH_SIZE_DP,
            0.001f,
        )
        assertEquals(13f * 1.3f, CloudLayerGlyphPlacer.GLYPH_STEP_DP, 0.001f)
        assertEquals(20f * 1.3f, CloudLayerGlyphPlacer.ACTUAL_GLYPH_STEP_DP, 0.001f)
        assertTrue(
            "actual glyphs must be spaced more sparsely than forecast glyphs",
            CloudLayerGlyphPlacer.ACTUAL_GLYPH_STEP_DP > CloudLayerGlyphPlacer.GLYPH_STEP_DP,
        )
    }
}
