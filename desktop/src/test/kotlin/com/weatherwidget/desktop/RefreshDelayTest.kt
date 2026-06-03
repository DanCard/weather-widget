package com.weatherwidget.desktop

import com.weatherwidget.data.model.HourlyForecast
import org.junit.Assert.*
import org.junit.Test

class RefreshDelayTest {

    @Test
    fun `returns default delay for null hourly`() {
        assertEquals(15 * 60 * 1000L, computeRefreshDelayMs(null))
    }

    @Test
    fun `returns default delay for empty hourly`() {
        assertEquals(15 * 60 * 1000L, computeRefreshDelayMs(emptyList()))
    }

    @Test
    fun `returns shorter delay for high temperature swing`() {
        val hourly = listOf(
            HourlyForecast(0, 60f, "Clear"),
            HourlyForecast(3600000, 72f, "Clear"), // 12 degree swing
        )
        val delay = computeRefreshDelayMs(hourly)
        // getUpdatesPerHour returns 4 for maxDiff >= 8, so 3600000/4 = 900000 (15 min)
        assertTrue(delay >= 10 * 60 * 1000L)
        assertTrue(delay <= 15 * 60 * 1000L)
    }

    @Test
    fun `returns default delay for low temperature swing`() {
        val hourly = listOf(
            HourlyForecast(0, 70f, "Clear"),
            HourlyForecast(3600000, 71f, "Clear"), // 1 degree swing
        )
        val delay = computeRefreshDelayMs(hourly)
        assertEquals(60 * 60 * 1000L, delay) // 1 update per hour
    }

    @Test
    fun `respects minimum delay floor`() {
        // Even with extreme swing, delay should be >= 10 min
        val hourly = listOf(
            HourlyForecast(0, 40f, "Clear"),
            HourlyForecast(3600000, 80f, "Clear"), // 40 degree swing
        )
        val delay = computeRefreshDelayMs(hourly)
        assertTrue(delay >= 10 * 60 * 1000L)
    }
}
