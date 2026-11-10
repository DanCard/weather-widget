package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** The daily column's left slot, both platforms (plans/261110-future-days-prior-forecast-bar.md). */
@Category(ShortDuration::class)
class PriorBarSlotTest {

    @Test
    fun `high label is outlined exactly when the column draws a left bar`() {
        // Fold 4, 2026-11-10: Friday's 75° prior bar ran through the plain 67° label.
        assertTrue(PriorBarSlot.highLabelOutlined(isToday = false, isPast = false, futurePriorBarDrawn = true))
        assertFalse("future column without one stays plain", PriorBarSlot.highLabelOutlined(false, false, false))
        assertTrue(PriorBarSlot.highLabelOutlined(isToday = true, isPast = false, futurePriorBarDrawn = false))
        assertTrue(PriorBarSlot.highLabelOutlined(isToday = false, isPast = true, futurePriorBarDrawn = false))
    }

    @Test
    fun `future left bar is the past width and touches the forecast bar`() {
        val widths = DailyBarWidths.resolve(pxPerDp = 2f, dayWidthPx = 200f, todayWidened = false)
        assertEquals(widths.pastPx, PriorBarSlot.futureWidthPx(widths), 0f)
        assertEquals((widths.forecastPx + widths.pastPx) / 2f, PriorBarSlot.futureOffsetPx(widths, 200f, 4f), 0.001f)
    }
}
