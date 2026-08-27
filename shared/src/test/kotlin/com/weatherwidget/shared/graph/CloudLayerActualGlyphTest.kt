package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** Phase contracts that keep faint actual trails legible beside forecast trails. */
@Category(ShortDuration::class)
class CloudLayerActualGlyphTest {

    /** The five trails must not share a phase, or coincident layer values overprint. */
    @Test
    fun `the five phases are all distinct`() {
        val phases = setOf(
            CloudLayerGlyphPlacer.MID_PHASE,
            CloudLayerGlyphPlacer.HIGH_PHASE,
            CloudLayerGlyphPlacer.LOW_ACTUAL_PHASE,
            CloudLayerGlyphPlacer.MID_ACTUAL_PHASE,
            CloudLayerGlyphPlacer.HIGH_ACTUAL_PHASE,
        )
        assertEquals(5, phases.size)
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
        listOf(
            CloudLayerGlyphPlacer.LOW_ACTUAL_PHASE,
            CloudLayerGlyphPlacer.MID_ACTUAL_PHASE,
            CloudLayerGlyphPlacer.HIGH_ACTUAL_PHASE,
        ).forEach { phase ->
            val actualXs = xs(phase)
            assertEquals(emptyList<Float>(), actualXs.filter { it in forecastXs })
        }
    }
}
