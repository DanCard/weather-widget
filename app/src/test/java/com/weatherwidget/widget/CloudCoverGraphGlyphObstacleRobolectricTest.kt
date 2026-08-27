package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.shared.graph.DominantStationLabel
import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * The wiring test for the actuals-source annotation versus the mid/high layer glyph trails.
 *
 * [CloudLayerGlyphLabelCollisionTest][com.weatherwidget.shared.graph.CloudLayerGlyphLabelCollisionTest]
 * covers the geometry, but it builds the obstacle list itself and so cannot notice the renderer
 * failing to *pass* one — which is exactly the defect that shipped (2026-08-27): the glyph pass and
 * the placement search were both correct in isolation and simply never introduced. This one drives
 * the real renderer and asserts the placement it produces clears the boxes the renderer itself fed
 * to the search.
 *
 * Robolectric has no font engine, so the label's own width is a 1px-per-character stub and the
 * scene is not to scale. The invariant asserted is scale-free on purpose — *the placement intersects
 * no glyph box* — and it is only meaningful because the glyph boxes are sized from dp
 * ([CloudLayerGlyphPlacer.GLYPH_BOX_WIDTH_RATIO][com.weatherwidget.shared.graph.CloudLayerGlyphPlacer.GLYPH_BOX_WIDTH_RATIO])
 * rather than measured; a measured box collapses to zero here and every assertion built on it
 * passes vacuously.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class CloudCoverGraphGlyphObstacleRobolectricTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * An afternoon of cirrus over a clear low deck: the low curve flat near zero, mid and high both
     * heavy across the whole window so the upper plot is a solid bank of glyphs.
     */
    private fun cirrusOverClearDeck(): List<CloudCoverGraphRenderer.CloudHourData> {
        val start = LocalDateTime.of(2026, 8, 27, 4, 0)
        return (0 until 18).map { index ->
            val dateTime = start.plusHours(index.toLong())
            CloudCoverGraphRenderer.CloudHourData(
                dateTime = dateTime,
                cloudCover = 3,
                midCover = 85,
                highCover = 100,
                label = "${index}h",
                showLabel = index % 4 == 0,
                isCurrentHour = index == 3,
            )
        }
    }

    private fun render(): Pair<DominantStationLabel.Placement?, List<GraphRect>> {
        var placement: DominantStationLabel.Placement? = null
        var glyphs: List<GraphRect> = emptyList()
        CloudCoverGraphRenderer.renderGraph(
            context = context,
            hours = cirrusOverClearDeck(),
            widthPx = 1460,
            heightPx = 900,
            currentTime = LocalDateTime.of(2026, 8, 27, 7, 0),
            dominantStationLabel = DominantStationLabel.plainLabelText(
                "Actual cloud cover data from Synoptic",
            ),
            onDominantStationPlaced = { placement = it },
            onLayerGlyphsPlaced = { glyphs = it },
        )
        return placement to glyphs
    }

    @Test
    fun `the renderer hands the glyph trails to the placement search`() {
        // Guards the assertion below from vacuity: with no obstacles reaching the search, "clears
        // every glyph" is true of any placement at all.
        val (_, glyphs) = render()
        assertTrue("expected the renderer to publish glyph obstacles for a heavy mid/high scene", glyphs.size > 20)
        assertTrue("glyph boxes must have area", glyphs.all { it.right > it.left && it.bottom > it.top })
    }

    @Test
    fun `the actuals source annotation is placed clear of every glyph`() {
        val (placement, glyphs) = render()

        assertNotNull("expected the annotation to be placed on a plot with room below the trails", placement)
        val hit = glyphs.filter { it.intersects(placement!!.box) }
        assertTrue(
            "annotation overlaps ${hit.size} glyph box(es): box=${placement!!.box} first=${hit.firstOrNull()}",
            hit.isEmpty(),
        )
    }
}
