package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class RainAnalyzerTest {

    private fun createForecast(
        dateTime: String,
        condition: String = "Clear",
        precipProb: Int? = 0,
        source: String = "NWS",
    ): HourlyForecastEntity {
        return HourlyForecastEntity(
            dateTime = dateTime,
            locationLat = 37.7749,
            locationLon = -122.4194,
            temperature = 70f,
            condition = condition,
            source = source,
            precipProbability = precipProb,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    // Use a "now" time that is before all test data (2024 dates) so future rain filtering doesn't break tests
    private val testNow = LocalDateTime.of(2024, 1, 1, 0, 0)

    @Test
    fun `analyzeDay returns no rain for clear day`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T08:00", "Sunny", 0),
            createForecast("2024-06-15T12:00", "Clear", 0),
            createForecast("2024-06-15T18:00", "Clear", 0),
        )

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertFalse(result.hasRain)
        assertNull(result.summary)
        assertTrue(result.windows.isEmpty())
    }

    @Test
    fun `analyzeDay detects rain from high precipitation probability`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T08:00", "Cloudy", 0),
            createForecast("2024-06-15T14:00", "Cloudy", 60),
            createForecast("2024-06-15T15:00", "Cloudy", 70),
            createForecast("2024-06-15T16:00", "Cloudy", 50),
            createForecast("2024-06-15T20:00", "Clear", 0),
        )

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertTrue(result.hasRain)
        assertNotNull(result.summary)
        assertEquals("2pm", result.summary)
        assertEquals(1, result.windows.size)
    }

    @Test
    fun `analyzeDay detects rain from condition text when probability is null`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T08:00", "Sunny", null),
            createForecast("2024-06-15T14:00", "Rain", null),
            createForecast("2024-06-15T15:00", "Heavy Rain", null),
        )

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertTrue(result.hasRain)
        assertNotNull(result.summary)
    }

    @Test
    fun `analyzeDay ignores rain condition text when probability is low`() {
        // NWS often reports "Slight Chance Rain Showers" with low probability (18%)
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T08:00", "Mostly Cloudy", 0),
            createForecast("2024-06-15T16:00", "Slight Chance Rain Showers", 18),
            createForecast("2024-06-15T17:00", "Slight Chance Rain Showers", 18),
            createForecast("2024-06-15T18:00", "Slight Chance Rain Showers", 18),
            createForecast("2024-06-15T20:00", "Clear", 0),
        )

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertFalse(result.hasRain)
        assertNull(result.summary)
    }

    @Test
    fun `analyzeDay detects multiple rain windows`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T08:00", "Clear", 0),
            createForecast("2024-06-15T10:00", "Rain", 50),  // Morning rain
            createForecast("2024-06-15T11:00", "Rain", 60),
            createForecast("2024-06-15T14:00", "Clear", 0),  // Break
            createForecast("2024-06-15T18:00", "Showers", 40), // Evening rain
            createForecast("2024-06-15T19:00", "Showers", 45),
        )

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertTrue(result.hasRain)
        assertEquals("10am, 6pm", result.summary)
        assertEquals(2, result.windows.size)
    }

    @Test
    fun `analyzeDay returns All day for extended rain`() {
        val date = LocalDate.of(2024, 6, 15)
        // 18+ hourly entries with rain triggers "All day"
        val forecasts = (0..23).map { hour ->
            val hourStr = String.format("%02d", hour)
            createForecast("2024-06-15T${hourStr}:00", "Rain", if (hour < 2) 20 else 60)
        }

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertTrue(result.hasRain)
        // 22 hours with prob >= 30 (hours 2-23), which is >= 18
        assertEquals("All day", result.summary)
    }

    @Test
    fun `analyzeDay does not return All day for partial rain`() {
        val date = LocalDate.of(2024, 6, 15)
        // Only 9 hours of rain — should NOT be "All day"
        val forecasts = listOf(
            createForecast("2024-06-15T06:00", "Rain", 60),
            createForecast("2024-06-15T08:00", "Rain", 70),
            createForecast("2024-06-15T10:00", "Rain", 70),
            createForecast("2024-06-15T12:00", "Rain", 80),
            createForecast("2024-06-15T14:00", "Rain", 75),
            createForecast("2024-06-15T16:00", "Rain", 70),
            createForecast("2024-06-15T18:00", "Rain", 65),
            createForecast("2024-06-15T20:00", "Rain", 60),
            createForecast("2024-06-15T22:00", "Rain", 55),
        )

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertTrue(result.hasRain)
        assertNotEquals("All day", result.summary)
    }

    @Test
    fun `analyzeDay filters by source when specified`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T14:00", "Rain", 60, "NWS"),
            createForecast("2024-06-15T14:00", "Clear", 0, "OPEN_METEO"),
        )

        val resultNws = RainAnalyzer.analyzeDay(forecasts, date, "NWS", now = testNow)
        val resultOpenMeteo = RainAnalyzer.analyzeDay(forecasts, date, "OPEN_METEO", now = testNow)

        assertTrue(resultNws.hasRain)
        assertFalse(resultOpenMeteo.hasRain)
    }

    @Test
    fun `hasRain returns correct boolean`() {
        val date = LocalDate.of(2024, 6, 15)
        val rainyForecasts = listOf(
            createForecast("2024-06-15T14:00", "Rain", 60),
        )
        val clearForecasts = listOf(
            createForecast("2024-06-15T14:00", "Sunny", 0),
        )

        assertTrue(RainAnalyzer.hasRain(rainyForecasts, date, now = testNow))
        assertFalse(RainAnalyzer.hasRain(clearForecasts, date, now = testNow))
    }

    @Test
    fun `getRainSummary returns formatted string or null`() {
        val date = LocalDate.of(2024, 6, 15)
        val rainyForecasts = listOf(
            createForecast("2024-06-15T14:00", "Rain", 60),
        )
        val clearForecasts = listOf(
            createForecast("2024-06-15T14:00", "Sunny", 0),
        )

        assertNotNull(RainAnalyzer.getRainSummary(rainyForecasts, date, now = testNow))
        assertNull(RainAnalyzer.getRainSummary(clearForecasts, date, now = testNow))
    }

    @Test
    fun `analyzeDay handles empty forecasts`() {
        val date = LocalDate.of(2024, 6, 15)
        val result = RainAnalyzer.analyzeDay(emptyList(), date)

        assertFalse(result.hasRain)
        assertNull(result.summary)
    }

    @Test
    fun `analyzeDay handles midnight and noon correctly`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T00:00", "Rain", 50),  // 12am
            createForecast("2024-06-15T06:00", "Clear", 0),  // 6am - no rain
            createForecast("2024-06-15T12:00", "Rain", 60),  // 12pm
            createForecast("2024-06-15T18:00", "Clear", 0),  // 6pm - no rain
        )

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertTrue(result.hasRain)
        // 12am and 12pm are more than 2 hours apart, so they form separate windows
        assertEquals("12am, 12pm", result.summary)
    }

    @Test
    fun `analyzeDay detects drizzle and showers when probability is null`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T10:00", "Drizzle", null),
            createForecast("2024-06-15T15:00", "Showers", null),
        )

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertTrue(result.hasRain)
        assertEquals(2, result.windows.size)
    }

    @Test
    fun `analyzeDay filters out past rain`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T08:00", "Rain", 60),  // Past rain
            createForecast("2024-06-15T14:00", "Rain", 70),  // Future rain
        )

        // At 12:00, only 14:00 rain should be counted
        val noon = LocalDateTime.of(2024, 6, 15, 12, 0)
        val result = RainAnalyzer.analyzeDay(forecasts, date, now = noon)

        assertTrue(result.hasRain)
        assertEquals("2pm", result.summary)  // Only 2pm, not 8am
    }

    @Test
    fun `analyzeDay returns no rain when all rain is in the past`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T08:00", "Rain", 60),  // Past rain
            createForecast("2024-06-15T10:00", "Rain", 70),  // Past rain
        )

        // At 18:00, all rain is in the past
        val evening = LocalDateTime.of(2024, 6, 15, 18, 0)
        val result = RainAnalyzer.analyzeDay(forecasts, date, now = evening)

        assertFalse(result.hasRain)
        assertNull(result.summary)
    }
}
