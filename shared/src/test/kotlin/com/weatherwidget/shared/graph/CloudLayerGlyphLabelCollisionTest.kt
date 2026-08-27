package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The actuals-source annotation ("Actual cloud cover data from Synoptic") versus the mid/high cloud
 * layer glyph trails, across [CloudLayerGlyphPlacer], [GraphEmptySpaceFinder] and
 * [DominantStationLabel].
 *
 * The bug these pin down (desktop, 2026-08-27): the trails are ink the placement search could not
 * see. `curveYsAt` carried the low forecast curve and the observed curve only, and the glyphs were
 * in no `drawnBounds`, so a plot whose whole upper half was a bank of `h`s read as open air and the
 * annotation was drawn straight through it.
 *
 * Pure geometry with metrics passed in, so no font engine is involved.
 */
@Category(ShortDuration::class)
class CloudLayerGlyphLabelCollisionTest {

    // Roughly the desktop popup at 1x: a wide plot with the footer already excluded.
    private val plot = GraphRect(0f, 150f, 1460f, 890f)
    private val graphBottom = 890f
    private val graphHeight = 740f

    /** The annotation as desktop measures it: top-left anchored, so descent is 0. */
    private val metrics = GraphEmptySpaceFinder.Metrics(width = 370f, ascent = -17f, descent = 0f)
    private val padPx = 2f
    private val glyphSizePx = 9f

    private fun yAt(cover: Float, bottom: Float = graphBottom, height: Float = graphHeight) =
        bottom - height * (cover / 100f)

    /** 25 hourly vertices, one per ~60px of plot. */
    private fun vertices(covers: List<Int?>, other: List<Int?>, bottom: Float, height: Float) =
        covers.mapIndexed { i, c ->
            LayerVertex(
                x = i * 60f,
                y = yAt((c ?: 0).toFloat(), bottom, height),
                cover = c,
                otherCover = other.getOrNull(i),
            )
        }

    private fun trailBounds(
        mid: List<Int?>,
        high: List<Int?>,
        bottom: Float = graphBottom,
        height: Float = graphHeight,
    ): List<GraphRect> {
        val glyphs =
            CloudLayerGlyphPlacer.place(
                vertices(mid, high, bottom, height),
                CloudLayerGlyphPlacer.MID_GLYPH,
                stepPx = 18f,
            ) +
                CloudLayerGlyphPlacer.place(
                    vertices(high, mid, bottom, height),
                    CloudLayerGlyphPlacer.HIGH_GLYPH,
                    stepPx = 18f,
                    phaseFraction = CloudLayerGlyphPlacer.HIGH_PHASE,
                )
        return CloudLayerGlyphPlacer.glyphBounds(glyphs, glyphSizePx)
    }

    /**
     * The observed scene: the low forecast curve flat along the bottom (3%), and mid/high both high
     * across the right two-thirds of the window — an afternoon of cirrus over a clear low deck.
     */
    private val midCovers: List<Int?> = List(25) { if (it >= 8) 85 else 0 }
    private val highCovers: List<Int?> = List(25) { if (it >= 8) 100 else 0 }

    private val lowForecastCurve: (Float) -> List<Float> = { listOf(yAt(3f)) }

    /**
     * The NOW hairline, a fifth of the way across. Load-bearing for the reproduction, not scenery:
     * a 370px label spanning it is vetoed, which is what rules out the left-hand anchors
     * ([DominantStationLabel.X_FRACTIONS] 0.08 and 0.22) and sends the annotation into the right of
     * the plot where the trails are.
     */
    private val nowVeto = listOf(GraphRect(326f, 150f, 334f, 890f))

    private fun place(drawnBounds: List<GraphRect>) =
        DominantStationLabel.place(
            text = "Actual cloud cover data from Synoptic",
            spanHours = 18L,
            plot = plot,
            drawnBounds = drawnBounds,
            curveYsAt = lowForecastCurve,
            metrics = metrics,
            padPx = padPx,
            vetoBounds = nowVeto,
        )

    @Test
    fun `without the trails as obstacles the annotation lands on the glyphs`() {
        // Guards the fix from below: if this ever stops overlapping, the scene has drifted and the
        // test opposite it proves nothing.
        val placement = place(drawnBounds = emptyList())
        assertNotNull("scene should still place a label with no obstacles at all", placement)

        val trails = trailBounds(midCovers, highCovers)
        assertTrue("trails should be non-empty for this scene", trails.isNotEmpty())
        assertTrue(
            "expected the unaware placement to collide with the glyph trails, box=${placement!!.box}",
            trails.any { it.intersects(placement.box) },
        )
    }

    @Test
    fun `with the trails as obstacles the annotation clears every glyph`() {
        val trails = trailBounds(midCovers, highCovers)
        val placement = place(drawnBounds = trails)

        assertNotNull("the left third of this plot is empty; the label should still be drawn", placement)
        assertTrue(
            "placement overlaps a glyph box: box=${placement!!.box}",
            trails.none { it.intersects(placement.box) },
        )
    }

    @Test
    fun `a plot the trails fill leaves the annotation undrawn`() {
        // A short plot (a 2-row widget) with volatile layers sawtoothing its full height every
        // hour: every 60px of x carries ink at every height, so no 370x17 band anywhere is clear.
        // This is the half of the requirement that is not "move it" — when there is nowhere to put
        // it, the annotation is dropped rather than drawn over the data.
        val shortPlot = GraphRect(0f, 150f, 1460f, 320f)
        val mid: List<Int?> = List(25) { if (it % 2 == 0) 5 else 100 }
        val high: List<Int?> = List(25) { if (it % 2 == 0) 100 else 5 }
        val trails = trailBounds(mid, high, bottom = shortPlot.bottom, height = shortPlot.bottom - shortPlot.top)

        assertNull(
            "expected no placement on a plot with no clear band",
            DominantStationLabel.place(
                text = "Actual cloud cover data from Synoptic",
                spanHours = 18L,
                plot = shortPlot,
                drawnBounds = trails,
                curveYsAt = { listOf(yAt(3f, shortPlot.bottom, shortPlot.bottom - shortPlot.top)) },
                metrics = metrics,
                padPx = padPx,
                vetoBounds = nowVeto,
            ),
        )
    }

    @Test
    fun `glyph boxes are centred on the glyph and sized from its type size`() {
        val bounds = CloudLayerGlyphPlacer.glyphBounds(listOf(LayerGlyph(100f, 50f, 'h')), 10f)
        assertTrue(bounds.size == 1)
        val r = bounds.single()
        val halfW = 10f * CloudLayerGlyphPlacer.GLYPH_BOX_WIDTH_RATIO / 2f
        val halfH = 10f * CloudLayerGlyphPlacer.GLYPH_BOX_HEIGHT_RATIO / 2f
        assertTrue("$r", r.left == 100f - halfW && r.right == 100f + halfW)
        assertTrue("$r", r.top == 50f - halfH && r.bottom == 50f + halfH)
    }

    @Test
    fun `a glyph with no type size contributes no obstacle`() {
        // Robolectric's font engine returns zeroes; a renderer that passed one through must fence
        // off nothing rather than a degenerate rect at the origin.
        assertTrue(CloudLayerGlyphPlacer.glyphBounds(listOf(LayerGlyph(100f, 50f, 'h')), 0f).isEmpty())
    }
}
