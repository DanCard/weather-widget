package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyBarWidthsTest {

    @Test
    fun `wide column uses the dp widths and past scales from today`() {
        val widths = DailyBarWidths.resolve(pxPerDp = 2f, dayWidthPx = 200f, todayWidened = false)

        assertEquals(DailyBarWidths.FORECAST_BAR_WIDTH_DP * 2f, widths.forecastPx, 0.001f)
        assertEquals(DailyBarWidths.TODAY_BAR_WIDTH_DP * 2f, widths.todayPx, 0.001f)
        assertEquals(widths.todayPx * DailyBarWidths.PAST_WIDTH_SCALE, widths.pastPx, 0.001f)
    }

    @Test
    fun `widened today column draws its triple bar at the widened width`() {
        val widths = DailyBarWidths.resolve(pxPerDp = 2f, dayWidthPx = 200f, todayWidened = true)

        assertEquals(DailyBarWidths.TODAY_BAR_WIDTH_DP_WIDENED * 2f, widths.todayPx, 0.001f)
        assertEquals(widths.todayPx * DailyBarWidths.PAST_WIDTH_SCALE, widths.pastPx, 0.001f)
        assertEquals("future bars ignore today's widening", DailyBarWidths.FORECAST_BAR_WIDTH_DP * 2f, widths.forecastPx, 0.001f)
    }

    @Test
    fun `narrow column caps bars at max column fraction and past still scales from today`() {
        val widths = DailyBarWidths.resolve(pxPerDp = 2f, dayWidthPx = 50f, todayWidened = false)
        val cap = 50f * DailyBarWidths.MAX_COLUMN_FRACTION

        assertEquals(cap, widths.forecastPx, 0.001f)
        assertEquals(cap, widths.todayPx, 0.001f)
        assertEquals(cap * DailyBarWidths.PAST_WIDTH_SCALE, widths.pastPx, 0.001f)
    }

    @Test
    fun `today is visibly heavier than history`() {
        val widths = DailyBarWidths.resolve(pxPerDp = 1f, dayWidthPx = 200f, todayWidened = false)

        assertTrue("past=${widths.pastPx} must stay positive", widths.pastPx > 0f)
        assertTrue("today=${widths.todayPx} past=${widths.pastPx}", widths.todayPx - widths.pastPx >= 3f)
    }
}
