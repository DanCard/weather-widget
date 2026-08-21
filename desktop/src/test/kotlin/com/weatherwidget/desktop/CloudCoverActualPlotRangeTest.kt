package com.weatherwidget.desktop

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CloudCoverActualPlotRangeTest {
    private val hour = 3_600_000L

    @Test
    fun `actual range starts at visible pre-roll point rather than minute-bearing nominal window`() {
        val nine = 1_800_000_000_000L
        val points = (0..4).map { index ->
            HourlyForecast(
                dateTime = nine + index * hour,
                temperature = 60f,
                condition = "Cloudy",
                cloudCoverLow = 100,
            )
        }
        val now = nine + 3 * hour + 45 * 60_000L

        assertEquals(nine..now, cloudActualPlotRange(points, now))
    }
}
