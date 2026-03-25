package com.weatherwidget.widget.handlers

import com.weatherwidget.widget.DailyForecastGraphRenderer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DailyViewGraphCompactionTest {

    @Test
    fun `compactGraphDays reindexes sparse source columns to rendered positions`() {
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = LocalDate.of(2026, 3, 25),
                label = "Today",
                high = 75f,
                low = 49f,
                columnIndex = 0,
            ),
            DailyForecastGraphRenderer.DayData(
                date = LocalDate.of(2026, 4, 2),
                label = "Thu",
                high = 67f,
                low = 51f,
                columnIndex = 8,
            ),
        )

        val compacted = DailyViewHandler.compactGraphDays(days)

        assertEquals(0, compacted[0].columnIndex)
        assertEquals(1, compacted[1].columnIndex)
    }
}
