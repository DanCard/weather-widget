package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** [HourlyEmptyNoticeRenderer.layoutLines] with a fixed-advance measure (10 px per char per 15 px of size). */
@Category(ShortDuration::class)
class HourlyEmptyNoticeLayoutTest {
    private val density = 1f
    private val measure: (String, Float) -> Float = { s, size -> s.length * 10f * size / 15f }
    private val text = "No hourly forecast for Sun Nov 22 — data ends Sun Nov 22 at 12 AM"

    @Test
    fun `fits on one line at full size`() {
        val lines = HourlyEmptyNoticeRenderer.layoutLines(text, maxWidth = 1_000f, density = density, measure = measure)
        println("oneLine=$lines")
        assertEquals(listOf(text), lines.lines)
        assertEquals(15f, lines.textSize)
    }

    @Test
    fun `splits at the dash when too wide, capitalising the second line`() {
        val lines = HourlyEmptyNoticeRenderer.layoutLines(text, maxWidth = 400f, density = density, measure = measure)
        println("split=$lines")
        assertEquals(listOf("No hourly forecast for Sun Nov 22", "Data ends Sun Nov 22 at 12 AM"), lines.lines)
        assertEquals(15f, lines.textSize)
    }

    @Test
    fun `shrinks the widest line to fit but not below the minimum`() {
        val narrow = HourlyEmptyNoticeRenderer.layoutLines(text, maxWidth = 250f, density = density, measure = measure)
        println("narrow=$narrow")
        assertEquals(15f * 250f / 330f, narrow.textSize, 0.01f)
        val tiny = HourlyEmptyNoticeRenderer.layoutLines(text, maxWidth = 50f, density = density, measure = measure)
        assertEquals(10f, tiny.textSize)
    }
}
