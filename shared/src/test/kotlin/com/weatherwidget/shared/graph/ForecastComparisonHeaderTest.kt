package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `arrow keys step one day and left stops at the history limit`() {
        val limit = today.minusDays(ForecastComparisonViewLogic.MAX_HISTORY_DAYS_BACK)
        assertEquals(today.plusDays(1), ForecastComparisonHeader.arrowKeyDay(today, left = false, today = today))
        assertEquals(today.minusDays(1), ForecastComparisonHeader.arrowKeyDay(today, left = true, today = today))
        assertEquals(limit, ForecastComparisonHeader.arrowKeyDay(limit.plusDays(1), left = true, today = today))
        assertNull(ForecastComparisonHeader.arrowKeyDay(limit, left = true, today = today))
        assertEquals(limit.plusDays(1), ForecastComparisonHeader.arrowKeyDay(limit, left = false, today = today))
    }
}
