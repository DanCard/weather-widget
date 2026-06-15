package com.weatherwidget.shared.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyDayValueResolverTest {

    @Test
    fun todayHeadlineExcludesSnapshot() {
        // observed 71, live forecast 80, ghost (peak so far) 72 → headline is the live forecast 80.
        // The 24h-prior snapshot (84) is NOT a parameter here, so it can never inflate the headline.
        val high = DailyDayValueResolver.effectiveHighForLabel(
            isToday = true, solidHigh = 71f, forecastHigh = 80f, ghostHigh = 72f,
        )
        assertEquals(80f, high)
    }

    @Test
    fun todayHeadlineUsesGhostWhenPeakExceededForecast() {
        // If the day already peaked above the live forecast, the ghost high wins.
        val high = DailyDayValueResolver.effectiveHighForLabel(
            isToday = true, solidHigh = 71f, forecastHigh = 80f, ghostHigh = 83f,
        )
        assertEquals(83f, high)
    }

    @Test
    fun nonTodayReturnsObservedHighOnly() {
        val high = DailyDayValueResolver.effectiveHighForLabel(
            isToday = false, solidHigh = 75f, forecastHigh = 99f, ghostHigh = 88f,
        )
        assertEquals(75f, high)
    }

    @Test
    fun todayAllNullReturnsNull() {
        assertNull(
            DailyDayValueResolver.effectiveHighForLabel(
                isToday = true, solidHigh = null, forecastHigh = null, ghostHigh = null,
            )
        )
    }
}
