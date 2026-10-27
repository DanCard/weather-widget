package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** `plans/261027-shared-graph-label-top-limit.md`: one label area, header items as obstacles. */
@Category(ShortDuration::class)
class GraphLabelAreaTest {

    @Test
    fun `the label area starts at the canvas top, not the plot top`() {
        assertEquals(GraphRect(0f, 0f, 511f, 412f), GraphLabelArea.rect(511f, 412f))
    }

    @Test
    fun `header spans map into graph px, clipped to the graph and dropped when off it`() {
        val rects = GraphLabelArea.headerObstacles(
            spans = listOf(0f to 100f, 300f to 400f, 900f to 1000f),
            originX = 30f,
            scale = 0.5f,
            bandTop = 0f,
            bandBottom = 20f,
            graphWidth = 400f,
        )
        assertEquals(
            listOf(
                GraphRect(0f, 0f, 35f, 20f), // starts left of the bitmap: clipped at 0
                GraphRect(135f, 0f, 185f, 20f),
                // 900..1000 → 435..485: right of a 400px graph, dropped
            ),
            rects,
        )
    }

    @Test
    fun `an empty band yields no obstacles`() {
        assertEquals(
            emptyList<GraphRect>(),
            GraphLabelArea.headerObstacles(listOf(0f to 100f), 0f, 1f, bandTop = 10f, bandBottom = 10f, graphWidth = 400f),
        )
    }
}
