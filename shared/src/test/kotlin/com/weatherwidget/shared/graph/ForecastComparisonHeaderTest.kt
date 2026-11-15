package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.util.Locale

@Category(ShortDuration::class)
class ForecastComparisonHeaderTest {
    private val today = LocalDate.of(2026, 10, 9)

    @Test
    fun `date label reads like the Android title`() {
        assertEquals("Fri, Oct 9", ForecastComparisonHeader.dateLabel(today, Locale.US))
    }

    @Test
    fun `back paging stops at the history limit`() {
        val limit = today.minusDays(ForecastComparisonViewLogic.MAX_HISTORY_DAYS_BACK)
        assertTrue(ForecastComparisonHeader.canGoBack(limit.plusDays(1), today))
        assertFalse(ForecastComparisonHeader.canGoBack(limit, today))
    }

    @Test
    fun `future days can always page back`() {
        assertTrue(ForecastComparisonHeader.canGoBack(today.plusDays(10), today))
    }
}
