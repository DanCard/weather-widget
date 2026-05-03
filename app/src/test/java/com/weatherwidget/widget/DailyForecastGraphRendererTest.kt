package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
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

    @Test
    fun `resolveRainAboveHighPlacement fits when rain label clears top margin`() {
        val placement = DailyForecastRainLabelRenderer.resolveRainAboveHighPlacement(
            highBaseline = 80f,
            highMetrics = DailyForecastGraphRenderer.TextMetrics(ascent = -24f, descent = 6f),
            rainMetrics = DailyForecastGraphRenderer.TextMetrics(ascent = -14f, descent = 4f),
            topMargin = 8f,
            gap = 3f,
        )

        assertEquals(true, placement.fits)
        assertEquals(49f, placement.baseline, 0.01f)
        assertEquals(53f, placement.bottom, 0.01f)
        assertEquals(56f, placement.highLabelTop, 0.01f)
    }

    @Test
    fun `resolveRainAboveHighPlacement rejects label when top space is insufficient`() {
        val placement = DailyForecastRainLabelRenderer.resolveRainAboveHighPlacement(
            highBaseline = 36f,
            highMetrics = DailyForecastGraphRenderer.TextMetrics(ascent = -24f, descent = 6f),
            rainMetrics = DailyForecastGraphRenderer.TextMetrics(ascent = -14f, descent = 4f),
            topMargin = 8f,
            gap = 3f,
        )

        assertEquals(false, placement.fits)
    }
}
