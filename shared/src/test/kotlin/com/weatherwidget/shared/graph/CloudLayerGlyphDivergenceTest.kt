package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** The suppression rule that decides whether an observed band trail is drawn at all. */
@Category(ShortDuration::class)
class CloudLayerGlyphDivergenceTest {

    private fun frozen(count: Int) = List(count) { true }

    @Test
    fun `an actual that agrees with the forecast draws nothing`() {
        val result = CloudLayerGlyphPlacer.divergentActuals(
            forecast = listOf(50),
            actual = listOf(50 + CloudLayerGlyphPlacer.ACTUAL_MIN_DIVERGENCE - 1),
            frozen = frozen(1),
        )
        assertEquals(listOf(null), result)
    }

    @Test
    fun `an actual exactly at the divergence floor is drawn`() {
        val actual = 50 + CloudLayerGlyphPlacer.ACTUAL_MIN_DIVERGENCE
        val result = CloudLayerGlyphPlacer.divergentActuals(
            forecast = listOf(50),
            actual = listOf(actual),
            frozen = frozen(1),
        )
        assertEquals(listOf(actual), result)
    }

    @Test
    fun `divergence is symmetric`() {
        val below = 50 - CloudLayerGlyphPlacer.ACTUAL_MIN_DIVERGENCE
        val result = CloudLayerGlyphPlacer.divergentActuals(
            forecast = listOf(50),
            actual = listOf(below),
            frozen = frozen(1),
        )
        assertEquals(listOf(below), result)
    }

    /**
     * Without a frozen snapshot the forecast list is carrying the retro-corrected live row — the
     * actual itself — so "divergence" would be measured against a copy of the thing being measured.
     */
    @Test
    fun `an unfrozen hour never draws an observed glyph`() {
        val result = CloudLayerGlyphPlacer.divergentActuals(
            forecast = listOf(10),
            actual = listOf(90),
            frozen = listOf(false),
        )
        assertEquals(listOf(null), result)
    }

    @Test
    fun `a missing forecast or actual draws nothing`() {
        assertEquals(
            listOf(null, null),
            CloudLayerGlyphPlacer.divergentActuals(
                forecast = listOf(null, 40),
                actual = listOf(80, null),
                frozen = frozen(2),
            ),
        )
    }

    @Test
    fun `each hour is judged on its own`() {
        val result = CloudLayerGlyphPlacer.divergentActuals(
            forecast = listOf(10, 10, 10),
            actual = listOf(12, 90, 11),
            frozen = frozen(3),
        )
        assertEquals(listOf(null, 90, null), result)
    }

    /** The four trails must not share an x, or a 100/100 pair overprints. */
    @Test
    fun `the four phases are all distinct`() {
        val phases = setOf(
            CloudLayerGlyphPlacer.MID_PHASE,
            CloudLayerGlyphPlacer.HIGH_PHASE,
            CloudLayerGlyphPlacer.MID_ACTUAL_PHASE,
            CloudLayerGlyphPlacer.HIGH_ACTUAL_PHASE,
        )
        assertEquals(4, phases.size)
    }

    @Test
    fun `the actual phases place glyphs off the forecast phases' x positions`() {
        val vertices = (0..10).map {
            LayerVertex(x = it * 10f, y = 50f, cover = 80, otherCover = null)
        }
        fun xs(phase: Float) = CloudLayerGlyphPlacer.place(
            vertices = vertices,
            glyph = CloudLayerGlyphPlacer.MID_GLYPH,
            stepPx = 20f,
            phaseFraction = phase,
        ).map { it.x }

        val forecastXs = xs(CloudLayerGlyphPlacer.MID_PHASE).toSet()
        val actualXs = xs(CloudLayerGlyphPlacer.MID_ACTUAL_PHASE)
        assertEquals(emptyList<Float>(), actualXs.filter { it in forecastXs })
    }
}
