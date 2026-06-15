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

        assertEquals(9, state.days.size) // NavigationUtils default for 9 columns

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
    fun `today thermostat tracks high-water mark via ghostHigh`() {
        // Evening case: the day already peaked above the current reading, so the solid mercury
        // sits at the current temp while the ghost preserves the day's high.
        val now = LocalDateTime.parse("2026-06-03T20:00:00")
        val state = DesktopDailyForecastModel.build(
            config = config,
            forecast = ForecastResult(
                currentTemp = 72.4f,
                currentCondition = "Sunny",
                daily = listOf(DailyForecast("2026-06-03", 97f, 60f, "Sunny")),
                dailyActuals = mapOf(
                    "2026-06-03" to extreme("2026-06-03", 97.7f, 60.3f, "Sunny"),
                ),
            ),
            dimensions = DesktopDailyForecastModel.dimensions(600, 400),
            now = now,
        )

        val today = state.days.find { it.date == LocalDate.parse("2026-06-03") }!!
        assertTrue(today.isToday)
        // Mercury sits at the current temp...
        assertEquals(72.4f, today.solidHigh)
        // ...while the ghost preserves the day's observed peak.
        assertEquals(97.7f, today.ghostHigh)
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

    // --- Scroll-wheel zoom (history-biased) ---------------------------------------------------

    private fun actualsRange(startDate: String, days: Int): Map<String, DailyExtreme> {
        val start = LocalDate.parse(startDate)
        return (0 until days).associate { i ->
            val d = start.plusDays(i.toLong()).toString()
            d to extreme(d, 70f, 50f, "Fair")
        }
    }

    private fun forecastRange(startDate: String, days: Int): List<DailyForecast> {
        val start = LocalDate.parse(startDate)
        return (0 until days).map { i -> DailyForecast(start.plusDays(i.toLong()).toString(), 80f, 60f, "Sunny") }
    }

    @Test
    fun `zoom-out prepends history days and anchors the right edge`() {
        val now = LocalDateTime.parse("2026-06-10T07:00:00")
        val forecast = ForecastResult(
            daily = forecastRange("2026-06-10", 8),       // today..+7
            dailyActuals = actualsRange("2026-06-01", 9), // 06-01..06-09
        )
        val cfg = config.copy(dateOffset = 0, dailyExtraHistory = 3)
        val state = DesktopDailyForecastModel.build(cfg, forecast, DesktopDailyForecastModel.dimensions(600, 400), now)

        // 9 base columns + 3 prepended history days.
        assertEquals(3, state.clampedExtraHistory)
        assertEquals(12, state.days.size)
        // Base left edge is yesterday (06-09); three more history days push it to 06-06.
        assertEquals(LocalDate.parse("2026-06-06"), state.days.first().date)
        // Right edge stays anchored at the base rightmost (06-17).
        assertEquals(LocalDate.parse("2026-06-17"), state.days.last().date)
        assertTrue(state.canZoomOut) // 3 < available history (8)
        assertTrue(state.canZoomIn)  // 3 > 0
    }

    @Test
    fun `zoom-out clamps to the earliest available history date`() {
        val now = LocalDateTime.parse("2026-06-10T07:00:00")
        val forecast = ForecastResult(
            daily = forecastRange("2026-06-10", 8),
            dailyActuals = actualsRange("2026-06-01", 9), // earliest = 06-01
        )
        val cfg = config.copy(dateOffset = 0, dailyExtraHistory = 20)
        val state = DesktopDailyForecastModel.build(cfg, forecast, DesktopDailyForecastModel.dimensions(600, 400), now)

        // Available history left of the base edge (06-09) is 8 days, so extra clamps to 8.
        assertEquals(8, state.clampedExtraHistory)
        assertEquals(LocalDate.parse("2026-06-01"), state.days.first().date)
        assertFalse(state.canZoomOut) // already at the earliest data
        assertTrue(state.canZoomIn)
    }

    @Test
    fun `zoom-out is capped even when more history data exists`() {
        val now = LocalDateTime.parse("2026-06-10T07:00:00")
        val forecast = ForecastResult(
            daily = forecastRange("2026-06-10", 8),
            dailyActuals = actualsRange("2026-05-20", 21), // 05-20..06-09, 20 days left of base edge
        )
        val cfg = config.copy(dateOffset = 0, dailyExtraHistory = 30)
        val state = DesktopDailyForecastModel.build(cfg, forecast, DesktopDailyForecastModel.dimensions(600, 400), now)

        // Capped at DAILY_MAX_EXTRA_HISTORY (14) despite 20 days of available history.
        assertEquals(14, state.clampedExtraHistory)
        assertEquals(9 + 14, state.days.size)
        assertEquals(LocalDate.parse("2026-05-26"), state.days.first().date) // 06-09 minus 14
        assertFalse(state.canZoomOut)
    }

    @Test
    fun `default view has no extra history but can still zoom out`() {
        val now = LocalDateTime.parse("2026-06-10T07:00:00")
        val forecast = ForecastResult(
            daily = forecastRange("2026-06-10", 8),
            dailyActuals = actualsRange("2026-06-01", 9),
        )
        val cfg = config.copy(dateOffset = 0, dailyExtraHistory = 0)
        val state = DesktopDailyForecastModel.build(cfg, forecast, DesktopDailyForecastModel.dimensions(600, 400), now)

        assertEquals(0, state.clampedExtraHistory)
        assertEquals(9, state.days.size)
        assertFalse(state.canZoomIn) // nothing to trim
        assertTrue(state.canZoomOut) // history available to reveal
    }

    @Test
    fun `zoom reveals history even when skip-yesterday drops it`() {
        // Narrow (5 col) widget after 8am: skip-yesterday normally hides history at offset 0.
        val now = LocalDateTime.parse("2026-06-10T09:00:00")
        val forecast = ForecastResult(
            daily = forecastRange("2026-06-10", 5),
            dailyActuals = actualsRange("2026-06-01", 9),
        )
        val cfg = config.copy(dateOffset = 0, dailyExtraHistory = 2)
        val state = DesktopDailyForecastModel.build(cfg, forecast, DesktopDailyForecastModel.dimensions(300, 400), now)

        assertTrue(state.skipYesterday)
        assertEquals(2, state.clampedExtraHistory)
        // Base window is today-first (06-10..); the 2 extra history days bring back 06-08 and 06-09.
        assertEquals(LocalDate.parse("2026-06-08"), state.days.first().date)
        assertTrue(state.days.any { it.date == LocalDate.parse("2026-06-10") && it.isToday })
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
