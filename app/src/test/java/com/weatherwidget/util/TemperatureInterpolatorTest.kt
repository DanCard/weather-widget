package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

class TemperatureInterpolatorTest {

    private lateinit var interpolator: TemperatureInterpolator

    @Before
    fun setup() {
        interpolator = TemperatureInterpolator()
    }

    // Helper to create hourly forecast entities
    private fun createHourlyForecast(dateTime: String, temp: Int): HourlyForecastEntity {
        return HourlyForecastEntity(
            dateTime = dateTime,
            locationLat = 37.42,
            locationLon = -122.08,
            temperature = temp.toFloat(),
            source = "OPEN_METEO",
            fetchedAt = System.currentTimeMillis()
        )
    }

    @Test
    fun `getInterpolatedTemperature returns null for empty list`() {
        val result = interpolator.getInterpolatedTemperature(
            emptyList(),
            LocalDateTime.of(2026, 1, 15, 14, 30)
        )
        assertNull(result)
    }

    @Test
    fun `getInterpolatedTemperature returns current hour temp when no next hour`() {
        val forecasts = listOf(
            createHourlyForecast("2026-01-15T14:00", 70)
        )

        val result = interpolator.getInterpolatedTemperature(
            forecasts,
            LocalDateTime.of(2026, 1, 15, 14, 30)
        )

        assertEquals(70.0f, result)
    }

    @Test
    fun `getInterpolatedTemperature returns next hour temp when no current hour`() {
        val forecasts = listOf(
            createHourlyForecast("2026-01-15T15:00", 72)
        )

        val result = interpolator.getInterpolatedTemperature(
            forecasts,
            LocalDateTime.of(2026, 1, 15, 14, 30)
        )

        assertEquals(72.0f, result)
    }

    @Test
    fun `getInterpolatedTemperature interpolates at half hour with 4 degree difference`() {
        val forecasts = listOf(
            createHourlyForecast("2026-01-15T14:00", 70),
            createHourlyForecast("2026-01-15T15:00", 74)
        )

        // At 14:30 (halfway), should be 72 (70 + 4*0.5 = 72)
        val result = interpolator.getInterpolatedTemperature(
            forecasts,
            LocalDateTime.of(2026, 1, 15, 14, 30)
        )

        assertEquals(72.0f, result)
    }

    @Test
    fun `getInterpolatedTemperature interpolates at quarter hour`() {
        val forecasts = listOf(
            createHourlyForecast("2026-01-15T14:00", 70),
            createHourlyForecast("2026-01-15T15:00", 74)
        )

        // At 14:15 (25%), should be 71 (70 + 4*0.25 = 71)
        val result = interpolator.getInterpolatedTemperature(
            forecasts,
            LocalDateTime.of(2026, 1, 15, 14, 15)
        )

        assertEquals(71.0f, result)
    }

    @Test
    fun `getInterpolatedTemperature interpolates at three quarter hour`() {
        val forecasts = listOf(
            createHourlyForecast("2026-01-15T14:00", 70),
            createHourlyForecast("2026-01-15T15:00", 74)
        )

        // At 14:45 (75%), should be 73 (70 + 4*0.75 = 73)
        val result = interpolator.getInterpolatedTemperature(
            forecasts,
            LocalDateTime.of(2026, 1, 15, 14, 45)
        )

        assertEquals(73.0f, result)
    }

    @Test
    fun `getInterpolatedTemperature handles decreasing temperature`() {
        val forecasts = listOf(
            createHourlyForecast("2026-01-15T14:00", 75),
            createHourlyForecast("2026-01-15T15:00", 71)
        )

        // At 14:30 (halfway), should be 73 (75 + (-4)*0.5 = 73)
        val result = interpolator.getInterpolatedTemperature(
            forecasts,
            LocalDateTime.of(2026, 1, 15, 14, 30)
        )

        assertEquals(73.0f, result)
    }

    @Test
    fun `getInterpolatedTemperature returns current temp when difference below threshold`() {
        val forecasts = listOf(
            createHourlyForecast("2026-01-15T14:00", 70),
            createHourlyForecast("2026-01-15T15:00", 70)  // No difference
        )

        // When temps are the same, return current hour temp
        val result = interpolator.getInterpolatedTemperature(
            forecasts,
            LocalDateTime.of(2026, 1, 15, 14, 30)
        )

        assertEquals(70.0f, result)
    }

    @Test
    fun `getInterpolatedTemperature at exact hour returns that hour temp`() {
        val forecasts = listOf(
            createHourlyForecast("2026-01-15T14:00", 70),
            createHourlyForecast("2026-01-15T15:00", 74)
        )

        // At exactly 14:00, should be 70
        val result = interpolator.getInterpolatedTemperature(
            forecasts,
            LocalDateTime.of(2026, 1, 15, 14, 0)
        )

        assertEquals(70.0f, result)
    }

    @Test
    fun `getInterpolatedTemperature finds closest when target not in range`() {
        val forecasts = listOf(
            createHourlyForecast("2026-01-15T10:00", 65),
            createHourlyForecast("2026-01-15T11:00", 68)
        )

        // At 14:00, should find closest (11:00 -> 68)
        val result = interpolator.getInterpolatedTemperature(
            forecasts,
            LocalDateTime.of(2026, 1, 15, 14, 0)
        )

        assertEquals(68.0f, result)
    }

    // Tests for getUpdatesPerHour

    @Test
    fun `getUpdatesPerHour returns 1 for small temp difference`() {
        assertEquals(1, interpolator.getUpdatesPerHour(0))
        assertEquals(1, interpolator.getUpdatesPerHour(1))
    }

    @Test
    fun `getUpdatesPerHour returns 2 for 2-3 degree difference`() {
        assertEquals(2, interpolator.getUpdatesPerHour(2))
        assertEquals(2, interpolator.getUpdatesPerHour(3))
    }

    @Test
    fun `getUpdatesPerHour returns 3 for 4-5 degree difference`() {
        assertEquals(3, interpolator.getUpdatesPerHour(4))
        assertEquals(3, interpolator.getUpdatesPerHour(5))
    }

    @Test
    fun `getUpdatesPerHour returns 4 for 6+ degree difference`() {
        assertEquals(4, interpolator.getUpdatesPerHour(6))
        assertEquals(4, interpolator.getUpdatesPerHour(10))
    }

    @Test
    fun `getUpdatesPerHour handles negative differences`() {
        assertEquals(2, interpolator.getUpdatesPerHour(-2))
        assertEquals(4, interpolator.getUpdatesPerHour(-8))
    }

    // Tests for getNextUpdateTime

    @Test
    fun `getNextUpdateTime with 1 update per hour returns next hour`() {
        val currentTime = LocalDateTime.of(2026, 1, 15, 14, 25)
        val result = interpolator.getNextUpdateTime(currentTime, 1)

        assertEquals(LocalDateTime.of(2026, 1, 15, 15, 0), result)
    }

    @Test
    fun `getNextUpdateTime with 2 updates per hour returns next 30 min mark`() {
        val currentTime = LocalDateTime.of(2026, 1, 15, 14, 10)
        val result = interpolator.getNextUpdateTime(currentTime, 3)  // 2 updates/hour

        assertEquals(LocalDateTime.of(2026, 1, 15, 14, 30), result)
    }

    @Test
    fun `getNextUpdateTime with 3 updates per hour returns next 20 min mark`() {
        val currentTime = LocalDateTime.of(2026, 1, 15, 14, 5)
        val result = interpolator.getNextUpdateTime(currentTime, 5)  // 3 updates/hour

        assertEquals(LocalDateTime.of(2026, 1, 15, 14, 20), result)
    }

    @Test
    fun `getNextUpdateTime with 4 updates per hour returns next 15 min mark`() {
        val currentTime = LocalDateTime.of(2026, 1, 15, 14, 10)
        val result = interpolator.getNextUpdateTime(currentTime, 8)  // 4 updates/hour

        assertEquals(LocalDateTime.of(2026, 1, 15, 14, 15), result)
    }

    @Test
    fun `getNextUpdateTime rolls over to next hour when needed`() {
        val currentTime = LocalDateTime.of(2026, 1, 15, 14, 50)
        val result = interpolator.getNextUpdateTime(currentTime, 3)  // 2 updates/hour, 30 min intervals

        assertEquals(LocalDateTime.of(2026, 1, 15, 15, 0), result)
    }
}
