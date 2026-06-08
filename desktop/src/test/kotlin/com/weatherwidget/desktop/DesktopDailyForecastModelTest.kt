package com.weatherwidget.desktop

import com.weatherwidget.data.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DesktopDailyForecastModelTest {

    private val config = DesktopConfig(
        lat = 37.4220,
        lon = -122.0841,
        label = "Test",
        source = "NWS",
        weatherSource = "NWS",
        dateOffset = -1
    )

    private fun extreme(date: String, high: Float, low: Float, condition: String) = DailyExtreme(
        date = LocalDate.parse(date).toEpochDay() * 86_400_000L,
        source = "NWS",
        locationLat = 37.4220,
        locationLon = -122.0841,
        highTemp = high,
        lowTemp = low,
        condition = condition,
        updatedAt = System.currentTimeMillis()
    )

    @Test
    fun `build properly groups and maps daily data`() {
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
                    "2026-06-01" to extreme("2026-06-01", 77f, 56f, "Fair"),
                    "2026-06-02" to extreme("2026-06-02", 78f, 57f, "Fair"),
                ),
                dailySnapshots = mapOf(
                    "2026-06-01" to listOf(
                        DailyForecastSnapshot("2026-06-01", 75f, 57f, "Cloudy", fetchedAt = 1L),
                        DailyForecastSnapshot("2026-06-01", 51f, 51f, "Cloudy", fetchedAt = 2L),
                    ),
                    "2026-06-02" to listOf(
                        DailyForecastSnapshot("2026-06-02", 76f, 58f, "Cloudy", fetchedAt = 1L),
                    )
                )
            ),
            dimensions = DesktopDailyForecastModel.dimensions(600, 400),
            now = now
        )

        assertEquals(8, state.days.size) // NavigationUtils default for 8 columns

        val jun1 = state.days.find { it.date == LocalDate.parse("2026-06-01") }!!
        assertEquals(77f, jun1.solidHigh)
        assertEquals(56f, jun1.solidLow)
        assertEquals(75f, jun1.forecastHigh)
        assertEquals(57f, jun1.forecastLow)

        val jun3 = state.days.find { it.date == LocalDate.parse("2026-06-03") }!!
        assertTrue(jun3.isToday)
        assertEquals(80f, jun3.forecastHigh)
        assertEquals(60f, jun3.forecastLow)
        // Today's solid high is max(actual.high, current)
        assertEquals(72.4f, jun3.solidHigh) 
    }

    @Test
    fun `isIconWidth detection works`() {
        val narrow = DesktopDailyForecastModel.dimensions(120, 100)
        assertTrue(narrow.isIconWidth)
        assertFalse(narrow.useGraph)

        val wide = DesktopDailyForecastModel.dimensions(600, 400)
        assertFalse(wide.isIconWidth)
        assertTrue(wide.useGraph)
    }

    @Test
    fun `detects available dates correctly for navigation`() {
        val now = LocalDateTime.parse("2026-06-03T07:00:00")
        val forecast = ForecastResult(
            daily = listOf(DailyForecast("2026-06-03", 80f, 60f, "Sunny")),
            dailyActuals = mapOf("2026-06-01" to extreme("2026-06-01", 77f, 56f, "Fair"))
        )
        val state = DesktopDailyForecastModel.build(config, forecast, DesktopDailyForecastModel.dimensions(600, 400), now)
        
        assertFalse(state.canNavigateLeft) // Already at the earliest date (June 1st)
        assertFalse(state.canNavigateRight) // No data past today
    }
}
