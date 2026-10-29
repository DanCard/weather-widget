package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The graph-top solve: every column's labels stay below the ceiling, [DailyTopStack.HEADER_OVERLAP_FRACTION]
 * of the header's height inside the header. Expectations derive from that fraction so it can be tuned.
 */
@Category(ShortDuration::class)
class DailyTopStackTest {

    // Header 0..60 px: the ceiling sits HEADER_OVERLAP_FRACTION of 60 px above its bottom.
    private val stack = DailyTopStack(headerBottomPx = 60f, headerHeightPx = 60f)
    private val ceiling = 60f - DailyTopStack.HEADER_OVERLAP_FRACTION * 60f
    private fun col(label: String, f: Float, above: Float) = DailyTopStack.ColumnHeadroom(label, f, above)

    @Test
    fun `hottest column's labels end exactly at the ceiling`() {
        val s = stack.solve(graphBottom = 450f, columns = listOf(col("hot", 1f, 60f), col("cool", 0.5f, 60f)))
        // hot binds: its 60 px of labels end at the ceiling, so its bar top (the graph top) is 60 below it.
        assertEquals(ceiling + 60f, s.graphTop, 0.001f)
        assertEquals("hot", s.bindingColumn)
    }

    @Test
    fun `a cooler column with taller labels can set the height`() {
        val s = stack.solve(graphBottom = 400f, columns = listOf(col("hot", 1f, 20f), col("rainy", 0.9f, 80f)))
        // hot allows H <= (400 - ceiling - 20) / 1; rainy allows the smaller (400 - ceiling - 80) / 0.9.
        assertEquals(400f - (400f - ceiling - 80f) / 0.9f, s.graphTop, 0.001f)
        assertEquals("rainy", s.bindingColumn)
    }

    @Test
    fun `columns at the axis minimum never bind and the graph stops at the ceiling`() {
        val s = stack.solve(graphBottom = 400f, columns = listOf(col("flat", 0f, 500f)))
        assertEquals(stack.labelCeiling, s.graphTop, 0.001f)
        assertNull(s.bindingColumn)
    }

    @Test
    fun `ceiling sits the overlap fraction of the header's height inside the header`() {
        assertEquals(ceiling, stack.labelCeiling, 0.001f)
        // Desktop: the header row ends 10 px above the canvas.
        assertEquals(
            -10f - DailyTopStack.HEADER_OVERLAP_FRACTION * 100f,
            DailyTopStack(headerBottomPx = -10f, headerHeightPx = 100f).labelCeiling,
            0.001f,
        )
    }

    @Test
    fun `headroom is measured from the tallest bar top to the topmost label`() {
        val h = DailyTopStack.headroom("d", barTopTemp = 70f, minTemp = 50f, tempRange = 40f, barTopY = 200f, topmostLabelTop = 150f)
        assertEquals(0.5f, h.barTopFraction, 0.001f)
        assertEquals(50f, h.aboveBarPx, 0.001f)
    }
}
