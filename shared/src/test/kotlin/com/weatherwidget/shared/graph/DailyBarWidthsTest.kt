package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyBarWidthsTest {

    @Test
    fun `wide column uses the dp widths and past is 65 percent of today`() {
        val widths = DailyBarWidths.resolve(pxPerDp = 2f, dayWidthPx = 200f, todayWidened = false)

        assertEquals(18f, widths.forecastPx, 0.001f)
        assertEquals(18f, widths.todayPx, 0.001f)
        assertEquals(18f * 0.65f, widths.pastPx, 0.001f)
    }

    @Test
    fun `widened today column draws its triple bar at the widened width`() {
        val widths = DailyBarWidths.resolve(pxPerDp = 2f, dayWidthPx = 200f, todayWidened = true)

        assertEquals(16f, widths.todayPx, 0.001f)
        assertEquals(16f * 0.65f, widths.pastPx, 0.001f)
        assertEquals("future bars ignore today's widening", 18f, widths.forecastPx, 0.001f)
    }

    @Test
    fun `narrow column caps bars at 22 percent of the column and past still scales from today`() {
        val widths = DailyBarWidths.resolve(pxPerDp = 2f, dayWidthPx = 50f, todayWidened = false)

        assertEquals(11f, widths.forecastPx, 0.001f)
        assertEquals(11f, widths.todayPx, 0.001f)
        assertEquals(11f * 0.65f, widths.pastPx, 0.001f)
    }

    @Test
    fun `today is visibly heavier than history`() {
        val widths = DailyBarWidths.resolve(pxPerDp = 1f, dayWidthPx = 200f, todayWidened = false)

        assertTrue("today=${widths.todayPx} past=${widths.pastPx}", widths.todayPx - widths.pastPx >= 3f)
    }
}
