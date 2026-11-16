package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class InkScanTest {
    // 6x4 raster, ink at (1,1) (2,1) (4,2): "#" = ink.
    private val raster = listOf("......", ".##...", "....#.", "......")
    private fun ink(x: Int, y: Int) = raster[y][x] == '#'

    @Test
    fun `bounds hold every ink pixel with exclusive right and bottom`() {
        assertEquals(DailyRainLabelPlanner.Box(1f, 1f, 5f, 3f), InkScan.opaqueBounds(4, 0, 6, ::ink))
    }

    @Test
    fun `a column range scans only those columns, as for the digits and the degree sign`() {
        assertEquals(DailyRainLabelPlanner.Box(1f, 1f, 3f, 2f), InkScan.opaqueBounds(4, 0, 3, ::ink))
        assertEquals(DailyRainLabelPlanner.Box(4f, 2f, 5f, 3f), InkScan.opaqueBounds(4, 3, 6, ::ink))
    }

    @Test
    fun `no ink gives null`() {
        assertNull(InkScan.opaqueBounds(4, 0, 6) { _, _ -> false })
    }

    @Test
    fun `row bands follow the glyph shape, leaving a 4's open corner free`() {
        // A "4": diagonal + crossbar on top, stem only below the crossbar.
        val four = listOf(
            "...#..",
            "..##..",
            ".#.#..",
            "######",
            "...#..",
            "...#..",
        )
        val bands = InkScan.rowBands(6, 0, 6, 2) { x, y -> four[y][x] == '#' }
        println("4 bands=$bands")
        assertEquals(
            listOf(
                DailyRainLabelPlanner.Box(2f, 0f, 4f, 2f),
                DailyRainLabelPlanner.Box(0f, 2f, 6f, 4f),
                DailyRainLabelPlanner.Box(3f, 4f, 4f, 6f),
            ),
            bands,
        )
    }
}
