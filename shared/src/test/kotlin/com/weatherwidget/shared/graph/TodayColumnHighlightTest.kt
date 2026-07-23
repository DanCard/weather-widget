package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TodayColumnHighlightTest {

    @Test
    fun `equal-width bars touch when spacing equals one bar width`() {
        // Android case: all three bars share a width. Factor 1.0 must place flanks exactly one bar
        // width from centre so their edges meet the thermostat's edges.
        val offset = TodayColumnHighlight.tripleBarSpacing(
            centerBarWidthPx = 8f,
            flankBarWidthPx = 8f,
            dayWidthPx = 200f,
            spacingFactor = 1.0f,
            columnEdgeMarginPx = 2f,
        )
        assertEquals(8f, offset, 0.001f)
    }

    @Test
    fun `unequal-width bars touch at the average of the two widths`() {
        // Desktop case: thinner flanking bars. Touching offset = (center + flank) / 2.
        val offset = TodayColumnHighlight.tripleBarSpacing(
            centerBarWidthPx = 7f,
            flankBarWidthPx = 7f * 0.65f,
            dayWidthPx = 200f,
            spacingFactor = 1.0f,
            columnEdgeMarginPx = 2f,
        )
        assertEquals((7f + 4.55f) / 2f, offset, 0.001f)
    }

    @Test
    fun `spacing factor below one overlaps and above one gaps`() {
        val touching = TodayColumnHighlight.tripleBarSpacing(8f, 8f, 200f, 1.0f, 2f)
        val overlap = TodayColumnHighlight.tripleBarSpacing(8f, 8f, 200f, 0.9f, 2f)
        val gap = TodayColumnHighlight.tripleBarSpacing(8f, 8f, 200f, 1.1f, 2f)
        assertTrue("overlap should pull flanks closer", overlap < touching)
        assertTrue("gap should push flanks apart", gap > touching)
    }

    @Test
    fun `spacing is clamped so a flank bar stays inside the column`() {
        // Narrow column: requested touching offset (8) would push the flank's outer edge past the
        // half-column. Clamp keeps it within dayWidth/2 - flank/2 - margin.
        val dayWidth = 22f
        val offset = TodayColumnHighlight.tripleBarSpacing(
            centerBarWidthPx = 8f,
            flankBarWidthPx = 8f,
            dayWidthPx = dayWidth,
            spacingFactor = 1.0f,
            columnEdgeMarginPx = 2f,
        )
        val maxAllowed = dayWidth / 2f - 8f / 2f - 2f // 11 - 4 - 2 = 5
        assertEquals(maxAllowed, offset, 0.001f)
        // Outer edge of the flank bar sits inside the half-column.
        assertTrue(offset + 8f / 2f <= dayWidth / 2f)
    }

    @Test
    fun `panel spans the bars horizontally and stops above the day-label band`() {
        val panel = TodayColumnHighlight.panelBounds(
            centerXPx = 100f,
            tripleBarOffsetPx = 8f,
            flankBarWidthPx = 8f,
            dayWidthPx = 60f,
            graphTopPx = 50f,
            canvasHeightPx = 400f,
            dayLabelBandPx = 30f,
            horizontalPaddingPx = 9f,
            topMarginPx = 4f,
        )
        // halfWidth = offset(8) + flank/2(4) + padding(9) = 21, clamped to dayWidth/2 = 30 -> 21.
        assertEquals(79f, panel.left, 0.001f)
        assertEquals(121f, panel.right, 0.001f)
        // Top lifted by margin; bottom above the day-label band.
        assertEquals(46f, panel.top, 0.001f)
        assertEquals(370f, panel.bottom, 0.001f)
    }

    @Test
    fun `panel half-width never exceeds the half-column`() {
        val dayWidth = 30f
        val panel = TodayColumnHighlight.panelBounds(
            centerXPx = 100f,
            tripleBarOffsetPx = 20f, // large, would overflow
            flankBarWidthPx = 8f,
            dayWidthPx = dayWidth,
            graphTopPx = 10f,
            canvasHeightPx = 400f,
            dayLabelBandPx = 30f,
            horizontalPaddingPx = 9f,
            topMarginPx = 4f,
        )
        assertEquals(dayWidth / 2f, (panel.right - panel.left) / 2f, 0.001f)
    }
}
