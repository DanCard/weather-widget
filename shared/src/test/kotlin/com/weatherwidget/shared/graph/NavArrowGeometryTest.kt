package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The plot-local rectangles standing in for the nav arrows, which no renderer can see because they
 * are composited over the graph rather than drawn into it.
 *
 * These are pure-geometry unit tests. Whether the constants still match the real
 * `widget_weather.xml` is a separate question, guarded by `NavTouchZoneRoboTest` — passing here only
 * proves the arithmetic, not that the rectangle lands on the chevron.
 */
@Category(ShortDuration::class)
class NavArrowGeometryTest {

    private val plot = GraphRect(0f, 100f, 1000f, 700f) // 1000 x 600, offset top like a real graph
    private val density = 3f

    /** 40dp button minus graph_view's 4dp inset, at density 3 => 108px of arrow over the plot. */
    private val expectedWidth = 108f

    /** 80dp minHeight at density 3. */
    private val expectedHeight = 240f

    private fun bounds(
        left: Boolean = true,
        right: Boolean = true,
        plot: GraphRect = this.plot,
    ) = NavArrowGeometry.arrowBounds(
        plot = plot,
        density = density,
        visibility = NavArrowGeometry.Visibility(left = left, right = right),
    )

    @Test
    fun `left arrow hugs the plot's left edge`() {
        val rect = bounds(right = false).single()
        assertEquals("left edge", plot.left, rect.left, 0.01f)
        assertEquals("width is 36dp of overlap", expectedWidth, rect.width, 0.01f)
    }

    @Test
    fun `right arrow hugs the plot's right edge`() {
        val rect = bounds(left = false).single()
        assertEquals("right edge", plot.right, rect.right, 0.01f)
        assertEquals("width is 36dp of overlap", expectedWidth, rect.width, 0.01f)
    }

    @Test
    fun `arrow band is the button height, centred vertically in the plot`() {
        val rect = bounds(right = false).single()
        assertEquals("height", expectedHeight, rect.height, 0.01f)
        assertEquals(
            "centred: equal air above and below",
            rect.top - plot.top,
            plot.bottom - rect.bottom,
            0.01f,
        )
    }

    /**
     * The whole point of a centred band rather than a full-height column: a label in the top or
     * bottom third clears the arrow at any x, so the edge anchors stay usable.
     */
    @Test
    fun `arrow leaves the top and bottom bands clear`() {
        val rect = bounds(right = false).single()
        assertTrue(
            "expected clear air above the arrow, got top=${rect.top} plotTop=${plot.top}",
            rect.top > plot.top + 100f,
        )
        assertTrue(
            "expected clear air below the arrow, got bottom=${rect.bottom} plotBottom=${plot.bottom}",
            rect.bottom < plot.bottom - 100f,
        )
    }

    @Test
    fun `both arrows produce two disjoint rects`() {
        val rects = bounds()
        assertEquals(2, rects.size)
        assertTrue(
            "left and right must not meet on a realistic plot: $rects",
            rects[0].right < rects[1].left,
        )
    }

    @Test
    fun `hidden arrows reserve nothing`() {
        assertEquals(emptyList<GraphRect>(), bounds(left = false, right = false))
        assertEquals("only the visible one", 1, bounds(left = true, right = false).size)
    }

    /**
     * A 1x-height widget's plot can be shorter than the arrow's 80dp minimum. Letting the band hang
     * outside the plot would veto every candidate box and silently suppress the label entirely —
     * which reads as "the annotation is broken", not "the arrow is crowded".
     */
    @Test
    fun `band never exceeds a short plot`() {
        val shortPlot = GraphRect(0f, 0f, 1000f, 90f)
        val rect = NavArrowGeometry.arrowBounds(
            plot = shortPlot,
            density = density,
            visibility = NavArrowGeometry.Visibility.BOTH,
        ).first()
        assertEquals("clamped to the plot", shortPlot.height, rect.height, 0.01f)
        assertEquals(shortPlot.top, rect.top, 0.01f)
        assertEquals(shortPlot.bottom, rect.bottom, 0.01f)
    }

    @Test
    fun `explicit dimensions override the Android dp defaults for desktop`() {
        val rect = NavArrowGeometry.arrowBounds(
            plot = plot,
            density = 1f,
            visibility = NavArrowGeometry.Visibility(left = true, right = false),
            widthPx = 28f,
            heightPx = 24f,
        ).single()
        assertEquals(28f, rect.width, 0.01f)
        assertEquals(24f, rect.height, 0.01f)
    }
}
