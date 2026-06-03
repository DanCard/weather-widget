package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyActual
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyForecastSnapshot
import com.weatherwidget.data.model.ForecastResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DesktopDailyForecastModelTest {
    private val config = DesktopConfig(
        lat = 37.4220,
        lon = -122.0841,
        label = "Mountain View",
        source = "Manual",
    )

    @Test
    fun `dimensions use Android column and graph thresholds`() {
        val narrow = DesktopDailyForecastModel.dimensions(widthDp = 92, heightDp = 80)
        assertEquals(2, narrow.cols)
        assertEquals(1, narrow.rows)
        assertFalse(narrow.useGraph)

        val wide = DesktopDailyForecastModel.dimensions(widthDp = 520, heightDp = 220)
        assertEquals(8, wide.cols)
        assertTrue(wide.useGraph)
    }

    @Test
    fun `build uses responsive visible days and snapshot backed history navigation`() {
        val now = LocalDateTime.parse("2026-06-03T07:00:00")
        val state = DesktopDailyForecastModel.build(
            config = config,
            forecast = ForecastResult(
                currentTemp = 72.4f,
                currentCondition = "Sunny",
                daily = listOf(
                    DailyForecast("2026-06-03", 80f, 60f, "Sunny"),
                    DailyForecast("2026-06-04", 82f, 61f, "Partly Cloudy"),
                    DailyForecast("2026-06-05", 83f, 62f, "Rain", precipProbability = 40),
                ),
                dailyActuals = mapOf(
                    "2026-06-01" to DailyActual("2026-06-01", 77f, 56f, "Fair"),
                    "2026-06-02" to DailyActual("2026-06-02", 78f, 57f, "Fair"),
                ),
                dailySnapshots = mapOf(
                    "2026-06-01" to listOf(
                        DailyForecastSnapshot("2026-06-01", 75f, 57f, "Cloudy", fetchedAt = 1L),
                    ),
                    "2026-06-02" to listOf(
                        DailyForecastSnapshot("2026-06-02", 76f, 58f, "Cloudy", fetchedAt = 1L),
                    ),
                ),
            ),
            dimensions = DesktopDailyForecastModel.dimensions(widthDp = 180, heightDp = 180),
            now = now,
        )

        assertEquals(listOf("2026-06-02", "2026-06-03", "2026-06-04"), state.days.map { it.date.toString() })
        assertTrue(state.canNavigateLeft)
        assertTrue(state.canNavigateRight)
        assertEquals(76f, state.days.first().forecastHigh)
    }

    @Test
    fun `today day uses current temperature with forecast and snapshot side bars`() {
        val now = LocalDateTime.parse("2026-06-03T09:00:00")
        val state = DesktopDailyForecastModel.build(
            config = config,
            forecast = ForecastResult(
                currentTemp = 73.2f,
                currentCondition = "Sunny",
                daily = listOf(DailyForecast("2026-06-03", 80f, 60f, "Sunny")),
                dailySnapshots = mapOf(
                    "2026-06-03" to listOf(
                        DailyForecastSnapshot("2026-06-03", 78f, 59f, "Partly Cloudy", fetchedAt = 100L),
                    ),
                ),
            ),
            dimensions = DesktopDailyForecastModel.dimensions(widthDp = 240, heightDp = 180),
            now = now,
        )

        val today = state.days.first { it.isToday }
        assertEquals(73.2f, today.solidHigh)
        assertEquals(80f, today.forecastHigh)
        assertEquals(78f, today.snapshotHigh)
        assertEquals("73.2°", state.header.currentTempText)
    }
}
