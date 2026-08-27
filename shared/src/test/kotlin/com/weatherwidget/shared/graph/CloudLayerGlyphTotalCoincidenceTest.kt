package com.weatherwidget.shared.graph

import com.weatherwidget.shared.graph.CloudLayerGlyphPlacer.TotalCoincidence
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** What happens to a band glyph that lands on the main total curve. */
@Category(ShortDuration::class)
class CloudLayerGlyphTotalCoincidenceTest {

    @Test
    fun `a band clear of the total curve is drawn where it belongs`() {
        assertEquals(
            TotalCoincidence.DRAW,
            CloudLayerGlyphPlacer.coincidenceWithTotal(cover = 40f, totalCover = 90f, lowerBandCover = 10f),
        )
    }

    /**
     * The thin-cirrus day: overcast aloft, clear overhead. The low band is no longer drawn, so this
     * glyph is the only mark explaining why the curve reads 100 on a day that looks blue. Measured
     * 2026-08-27, this is 89 of the 90 hours where `high` coincides with the total.
     */
    @Test
    fun `a band that alone explains the total is nudged clear, never suppressed`() {
        assertEquals(
            TotalCoincidence.NUDGE,
            CloudLayerGlyphPlacer.coincidenceWithTotal(cover = 100f, totalCover = 100f, lowerBandCover = 3f),
        )
    }

    /** A low deck already accounts for the overcast, so the glyph is duplicate ink. */
    @Test
    fun `a band a lower one already explains is suppressed`() {
        assertEquals(
            TotalCoincidence.SUPPRESS,
            CloudLayerGlyphPlacer.coincidenceWithTotal(cover = 100f, totalCover = 100f, lowerBandCover = 100f),
        )
    }

    /** The rule is about coincidence, not about the number 100. */
    @Test
    fun `coincidence applies anywhere on the range, not only at the top`() {
        assertEquals(
            TotalCoincidence.NUDGE,
            CloudLayerGlyphPlacer.coincidenceWithTotal(cover = 60f, totalCover = 60f, lowerBandCover = 5f),
        )
    }

    @Test
    fun `the delta boundary is exclusive, matching the sibling-layer rule`() {
        val delta = CloudLayerGlyphPlacer.COINCIDENT_DELTA.toFloat()
        assertEquals(
            TotalCoincidence.DRAW,
            CloudLayerGlyphPlacer.coincidenceWithTotal(100f - delta, 100f, null),
        )
        assertEquals(
            TotalCoincidence.NUDGE,
            CloudLayerGlyphPlacer.coincidenceWithTotal(100f - delta + 1f, 100f, null),
        )
    }

    /** No total curve to collide with means the old behaviour, unchanged. */
    @Test
    fun `a null total always draws`() {
        assertEquals(
            TotalCoincidence.DRAW,
            CloudLayerGlyphPlacer.coincidenceWithTotal(cover = 100f, totalCover = null, lowerBandCover = 100f),
        )
    }

    @Test
    fun `a missing lower band cannot explain the total`() {
        assertEquals(
            TotalCoincidence.NUDGE,
            CloudLayerGlyphPlacer.coincidenceWithTotal(cover = 100f, totalCover = 100f, lowerBandCover = null),
        )
    }

    /**
     * The 0% half of the request needs no rule of its own: MIN_COVER already silences it. Measured
     * on the same 387 hours, all 203 with mid and total both 0 fall under that floor.
     */
    @Test
    fun `a band at zero under a zero total draws nothing already`() {
        val vertices = (0..10).map {
            LayerVertex(x = it * 10f, y = 100f, cover = 0, totalCover = 0, lowerBandCover = 0)
        }
        val glyphs = CloudLayerGlyphPlacer.place(
            vertices = vertices,
            glyph = CloudLayerGlyphPlacer.MID_GLYPH,
            stepPx = 20f,
        )
        assertTrue("MIN_COVER already suppresses a 0% band", glyphs.isEmpty())
    }

    @Test
    fun `place suppresses the redundant trail and keeps the explanatory one`() {
        fun trail(lowerBand: Int?) = CloudLayerGlyphPlacer.place(
            vertices = (0..10).map {
                LayerVertex(
                    x = it * 10f, y = 50f, cover = 100,
                    totalCover = 100, lowerBandCover = lowerBand,
                )
            },
            glyph = CloudLayerGlyphPlacer.HIGH_GLYPH,
            stepPx = 20f,
            nudgePx = 4f,
        )

        assertTrue("a low deck explains the overcast", trail(lowerBand = 100).isEmpty())
        val cirrus = trail(lowerBand = 3)
        assertTrue("cirrus is the only explanation and must survive", cirrus.isNotEmpty())
        assertTrue("and must be nudged off the curve", cirrus.all { it.y != 50f })
    }
}
