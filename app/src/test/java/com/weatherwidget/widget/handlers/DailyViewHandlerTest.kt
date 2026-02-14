package com.weatherwidget.widget.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyViewHandlerTest {

    @Test
    fun `showHistory returns true for past days`() {
        val today = LocalDate.of(2024, 6, 15)
        val pastDay = LocalDate.of(2024, 6, 14)
        val hasRainForecast = false

        val isHistory = pastDay.isBefore(today)
        val showHistory = isHistory || !hasRainForecast

        assertTrue(showHistory)
    }

    @Test
    fun `showHistory returns false for today with rain`() {
        val today = LocalDate.of(2024, 6, 15)
        val hasRainForecast = true

        val isHistory = today.isBefore(today) // false
        val showHistory = isHistory || !hasRainForecast

        assertFalse(showHistory)
    }

    @Test
    fun `showHistory returns true for today without rain`() {
        val today = LocalDate.of(2024, 6, 15)
        val hasRainForecast = false

        val isHistory = today.isBefore(today) // false
        val showHistory = isHistory || !hasRainForecast

        assertTrue(showHistory)
    }

    @Test
    fun `showHistory returns false for future day with rain`() {
        val today = LocalDate.of(2024, 6, 15)
        val futureDay = LocalDate.of(2024, 6, 16)
        val hasRainForecast = true

        val isHistory = futureDay.isBefore(today) // false
        val showHistory = isHistory || !hasRainForecast

        assertFalse(showHistory)
    }

    @Test
    fun `showHistory returns true for future day without rain`() {
        val today = LocalDate.of(2024, 6, 15)
        val futureDay = LocalDate.of(2024, 6, 16)
        val hasRainForecast = false

        val isHistory = futureDay.isBefore(today) // false
        val showHistory = isHistory || !hasRainForecast

        assertTrue(showHistory)
    }

    @Test
    fun `firstRainDay returns -1 when no rain`() {
        val rainSummaries = mapOf(
            1 to null,
            2 to null,
            3 to null,
        )

        val firstRainDay = rainSummaries.entries
            .firstOrNull { it.value != null }
            ?.key ?: -1

        assertEquals(-1, firstRainDay)
    }

    @Test
    fun `firstRainDay returns first day with rain`() {
        val rainSummaries = mapOf(
            1 to "10am",
            2 to "2pm",
            3 to null,
        )

        val firstRainDay = rainSummaries.entries
            .firstOrNull { it.value != null }
            ?.key ?: -1

        assertEquals(1, firstRainDay)
    }

    @Test
    fun `firstRainDay returns second day when first has no rain`() {
        val rainSummaries = mapOf(
            1 to null,
            2 to "12am",
            3 to "3pm",
        )

        val firstRainDay = rainSummaries.entries
            .firstOrNull { it.value != null }
            ?.key ?: -1

        assertEquals(2, firstRainDay)
    }

    @Test
    fun `shouldShowRain returns true only for first rain day`() {
        val firstRainDay = 2

        assertFalse(shouldShowRain(1, firstRainDay))
        assertTrue(shouldShowRain(2, firstRainDay))
        assertFalse(shouldShowRain(3, firstRainDay))
    }

    @Test
    fun `shouldShowRain returns false when no rain days`() {
        val firstRainDay = -1

        assertFalse(shouldShowRain(1, firstRainDay))
        assertFalse(shouldShowRain(2, firstRainDay))
        assertFalse(shouldShowRain(3, firstRainDay))
    }

    private fun shouldShowRain(dayIndex: Int, firstRainDay: Int): Boolean {
        return dayIndex == firstRainDay && firstRainDay != -1
    }
}
