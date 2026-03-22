package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.testutil.TestData
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
            dateTime = TestData.toEpoch(dateTime),
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
        assertEquals("2pm", result.summary)  // Start hour only, no range
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
        // NWS often reports "Slight Chance Rain Showers" with low probability (18% or 30%)
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T08:00", "Mostly Cloudy", 0),
            createForecast("2024-06-15T16:00", "Slight Chance Rain Showers", 18),
            createForecast("2024-06-15T17:00", "Slight Chance Rain Showers", 30),
            createForecast("2024-06-15T18:00", "Slight Chance Rain Showers", 49),
            createForecast("2024-06-15T20:00", "Clear", 0),
        )

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertFalse(result.hasRain)
        assertNull(result.summary)
    }

    @Test
    fun `analyzeDay detects rain at 50 percent threshold`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T12:00", "Cloudy", 50),
        )

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertTrue(result.hasRain)
        assertEquals("12pm", result.summary)
    }

    @Test
    fun `analyzeDay ignores rain below 50 percent threshold`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T12:00", "Cloudy", 49),
            createForecast("2024-06-15T14:00", "Cloudy", 40),
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
            createForecast("2024-06-15T18:00", "Showers", 55), // Evening rain
            createForecast("2024-06-15T19:00", "Showers", 60),
        )

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertTrue(result.hasRain)
        assertEquals("10am", result.summary)  // Only first window start
        assertEquals(2, result.windows.size)
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
        // Summary only shows start of first window
        assertEquals("12am", result.summary)
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
        assertEquals("10am", result.summary)  // Start of first window
    }

    @Test
    fun `analyzeDay filters out past rain`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T00:00", "Rain", 60),  // Past rain (midnight)
            createForecast("2024-06-15T14:00", "Rain", 70),  // Future rain
        )

        // At 12:00, only 14:00 rain should be counted as a window.
        // 12am rain is 14 hours before 2pm — gap > 12h — genuine new start
        val noon = LocalDateTime.of(2024, 6, 15, 12, 0)
        val result = RainAnalyzer.analyzeDay(forecasts, date, now = noon)

        assertTrue(result.hasRain)
        assertEquals("2pm", result.summary)
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

    @Test
    fun `analyzeDay suppresses imminent rain within 2 hours`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T13:00", "Rain", 70),  // 1 hour away — suppressed
            createForecast("2024-06-15T13:30", "Rain", 60),  // 1.5 hours away — suppressed
        )

        val noon = LocalDateTime.of(2024, 6, 15, 12, 0)
        val result = RainAnalyzer.analyzeDay(forecasts, date, now = noon)

        assertFalse(result.hasRain)
        assertNull(result.summary)
    }

    @Test
    fun `analyzeDay shows rain at exactly 2 hours out`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T14:00", "Rain", 70),  // Exactly 2 hours away — shown
        )

        val noon = LocalDateTime.of(2024, 6, 15, 12, 0)
        val result = RainAnalyzer.analyzeDay(forecasts, date, now = noon)

        assertTrue(result.hasRain)
        assertEquals("2pm", result.summary)
    }

    @Test
    fun `analyzeDay suppresses later rain when imminent rain makes it a continuation`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T13:00", "Rain", 70),  // 1 hour away — suppressed by imminent filter
            createForecast("2024-06-15T18:00", "Rain", 60),  // 6 hours away — but only 5h after 1pm rain
        )

        // 1pm rain is imminent (suppressed from window). 6pm rain: most recent prior rain is 1pm.
        // Gap = 5 hours < 12h → it's a continuation → summary suppressed
        val noon = LocalDateTime.of(2024, 6, 15, 12, 0)
        val result = RainAnalyzer.analyzeDay(forecasts, date, now = noon)

        assertTrue(result.hasRain)
        assertNull(result.summary)  // Suppressed — 6pm is continuation of 1pm rain
    }

    @Test
    fun `analyzeDay shows later rain when no recent prior rain`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T18:00", "Rain", 60),  // 6 hours away, no prior rain at all
        )

        val noon = LocalDateTime.of(2024, 6, 15, 12, 0)
        val result = RainAnalyzer.analyzeDay(forecasts, date, now = noon)

        assertTrue(result.hasRain)
        assertEquals("6pm", result.summary)  // Shown — no prior rain
    }

    @Test
    fun `summary shows only start hour for multi-hour rain window`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T09:00", "Rain", 50),
            createForecast("2024-06-15T10:00", "Rain", 60),
            createForecast("2024-06-15T11:00", "Rain", 55),
            createForecast("2024-06-15T12:00", "Rain", 50),
        )

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertEquals("9am", result.summary)  // Just start, no range
        assertEquals(1, result.windows.size)
    }

    @Test
    fun `summary shows only first window start for multiple rain windows`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T08:00", "Rain", 60),
            createForecast("2024-06-15T09:00", "Rain", 50),
            createForecast("2024-06-15T15:00", "Rain", 70),
            createForecast("2024-06-15T16:00", "Rain", 65),
            createForecast("2024-06-15T21:00", "Rain", 55),
        )

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertEquals("8am", result.summary)  // Only first window start
        assertEquals(3, result.windows.size)
    }

    // --- Dry gap suppression tests ---

    @Test
    fun `summary suppressed when rain is continuation of recent rain`() {
        val date = LocalDate.of(2024, 6, 15)
        // Rain at 9am (past), then again at 12pm (future, 2h from now).
        // Gap = 3h, well under 12h threshold → continuation
        val forecasts = listOf(
            createForecast("2024-06-15T09:00", "Rain", 60),
            createForecast("2024-06-15T12:00", "Rain", 70),
        )

        val now = LocalDateTime.of(2024, 6, 15, 10, 0)
        val result = RainAnalyzer.analyzeDay(forecasts, date, now = now)

        assertTrue(result.hasRain)
        assertNull(result.summary)  // Suppressed — it's a continuation
    }

    @Test
    fun `summary suppressed when overnight gap is under 12 hours`() {
        // Rain ending at 10pm, resuming at 4am — 6 hour overnight gap < 12h
        val tomorrow = LocalDate.of(2024, 6, 16)
        val forecasts = listOf(
            createForecast("2024-06-15T22:00", "Rain", 60),  // Last night
            createForecast("2024-06-16T04:00", "Rain", 70),  // Early morning
        )

        val result = RainAnalyzer.analyzeDay(forecasts, tomorrow, now = testNow)

        assertTrue(result.hasRain)
        assertNull(result.summary)  // Suppressed — same multi-day rain pattern
    }

    @Test
    fun `summary shown when rain starts after 12 hour dry gap`() {
        val date = LocalDate.of(2024, 6, 15)
        // Rain at midnight, then again at 2pm — 14h gap, above 12h threshold
        val forecasts = listOf(
            createForecast("2024-06-15T00:00", "Rain", 60),
            createForecast("2024-06-15T14:00", "Rain", 70),
        )

        val now = LocalDateTime.of(2024, 6, 15, 11, 0)
        val result = RainAnalyzer.analyzeDay(forecasts, date, now = now)

        assertTrue(result.hasRain)
        assertEquals("2pm", result.summary)  // Shown — genuine new start after long dry spell
    }

    @Test
    fun `summary suppressed for cross-day continuation`() {
        // Today has rain at 11pm, tomorrow starts with rain at 4am
        // Only 5h gap — continuation of the same weather pattern
        val tomorrow = LocalDate.of(2024, 6, 16)
        val forecasts = listOf(
            createForecast("2024-06-15T23:00", "Rain", 70),
            createForecast("2024-06-16T04:00", "Rain", 80),
            createForecast("2024-06-16T05:00", "Rain", 75),
        )

        val result = RainAnalyzer.analyzeDay(forecasts, tomorrow, now = testNow)

        assertTrue(result.hasRain)
        assertNull(result.summary)  // Suppressed — continuation from today
    }

    @Test
    fun `summary shown for tomorrow when today rain ended early`() {
        // Today had rain at 6am, tomorrow has rain at 8pm — gap is 38 hours
        val tomorrow = LocalDate.of(2024, 6, 16)
        val forecasts = listOf(
            createForecast("2024-06-15T06:00", "Rain", 60),  // Today's early rain
            createForecast("2024-06-16T20:00", "Rain", 70),  // Tomorrow's evening rain
        )

        val result = RainAnalyzer.analyzeDay(forecasts, tomorrow, now = testNow)

        assertTrue(result.hasRain)
        assertEquals("8pm", result.summary)  // Shown — long dry gap
    }

    @Test
    fun `rain window extends through midnight`() {
        val date = LocalDate.of(2024, 6, 15)
        val forecasts = listOf(
            createForecast("2024-06-15T21:00", "Rain", 70),
            createForecast("2024-06-15T22:00", "Rain", 75),
            createForecast("2024-06-15T23:00", "Rain", 80),
            createForecast("2024-06-16T00:00", "Rain", 82),
        )

        val result = RainAnalyzer.analyzeDay(forecasts, date, now = testNow)

        assertTrue(result.hasRain)
        assertEquals("9pm", result.summary)  // Start of the window
        assertEquals(1, result.windows.size)
    }

    // --- New Sensitivity and Late-Night Suppression Tests ---

    @Test
    fun `summary suppressed when currently raining (low probability)`() {
        val date = LocalDate.of(2024, 6, 15)
        // Now (10am) it is raining with 10% probability (isAnyRainHour = true).
        // Future rain at 2pm with 70% probability (isRainHour = true).
        // Gap is only 4h < 12h → continuation → summary suppressed.
        val forecasts = listOf(
            createForecast("2024-06-15T10:00", "Rain", 10),  // Current light rain
            createForecast("2024-06-15T14:00", "Rain", 70),  // Future heavy rain
        )

        val now = LocalDateTime.of(2024, 6, 15, 10, 0)
        val result = RainAnalyzer.analyzeDay(forecasts, date, now = now)

        assertTrue(result.hasRain)
        assertNull(result.summary)  // Suppressed because we are already raining
    }

    @Test
    fun `summary suppressed for Today late night rain (11pm)`() {
        val today = LocalDate.of(2024, 6, 15)
        // Rain starts at 11pm (23:00) — unhelpful timing for "Today" label.
        val forecasts = listOf(
            createForecast("2024-06-15T23:00", "Rain", 70),
        )

        val now = LocalDateTime.of(2024, 6, 15, 10, 0)
        val result = RainAnalyzer.analyzeDay(forecasts, today, now = now)

        assertTrue(result.hasRain)
        assertNull(result.summary)  // Suppressed for Today (late night)
    }

    @Test
    fun `summary suppressed for Today early morning rain (4am)`() {
        val today = LocalDate.of(2024, 6, 15)
        // Rain starts at 4am (04:00) — also unhelpful timing for "Today" label.
        val forecasts = listOf(
            createForecast("2024-06-15T04:00", "Rain", 70),
        )

        val now = LocalDateTime.of(2024, 6, 15, 0, 0)
        val result = RainAnalyzer.analyzeDay(forecasts, today, now = now)

        assertTrue(result.hasRain)
        assertNull(result.summary)  // Suppressed for Today (early morning)
    }

    @Test
    fun `summary NOT suppressed for Today daytime rain (8am)`() {
        val today = LocalDate.of(2024, 6, 15)
        // Rain starts at 8am (08:00) — helpful daytime timing.
        val forecasts = listOf(
            createForecast("2024-06-15T08:00", "Rain", 70),
        )

        val now = LocalDateTime.of(2024, 6, 15, 5, 0)
        val result = RainAnalyzer.analyzeDay(forecasts, today, now = now)

        assertTrue(result.hasRain)
        assertEquals("8am", result.summary)  // Shown — daytime start
    }
}
