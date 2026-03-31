package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DailyForecastGraphRendererTest {

    @Test
    fun `resolveBottomStackLow prefers explicit bottom stack low over observed low`() {
        val day = DailyForecastGraphRenderer.DayData(
            date = LocalDate.of(2030, 6, 15),
            label = "Today",
            high = 74f,
            low = 67f,
            bottomStackLow = 65f,
            forecastLow = 65f,
            isToday = true,
        )

        assertEquals(65f, DailyForecastGraphRenderer.resolveBottomStackLow(day)!!, 0.1f)
    }

    @Test
    fun `resolveBottomStackLow falls back to observed low when explicit anchor is absent`() {
        val day = DailyForecastGraphRenderer.DayData(
            date = LocalDate.of(2030, 6, 16),
            label = "Sun",
            high = 76f,
            low = 58f,
        )

        assertEquals(58f, DailyForecastGraphRenderer.resolveBottomStackLow(day)!!, 0.1f)
    }
}
