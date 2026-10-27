package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** `plans/261027-shared-graph-label-top-limit.md`: one label area. */
@Category(ShortDuration::class)
class GraphLabelAreaTest {

    @Test
    fun `the label area starts at the canvas top, not the plot top`() {
        assertEquals(GraphRect(0f, 0f, 511f, 412f), GraphLabelArea.rect(511f, 412f))
    }
}
