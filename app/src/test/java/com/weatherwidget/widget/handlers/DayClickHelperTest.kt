package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.RainAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DayClickHelperTest {

    // ── hasRainForecast: combines hourly RainAnalyzer + daily precipProbability ──

    @Test
    fun `hasRainForecast true when hourly rain detected`() {
        assertTrue(DayClickHelper.hasRainForecast(rainSummary = "2pm", dailyPrecipProbability = 0))
    }

    @Test
    fun `hasRainForecast true when daily precip probability is positive`() {
        // Key bug scenario: widget shows "16%" but RainAnalyzer found nothing
        assertTrue(DayClickHelper.hasRainForecast(rainSummary = null, dailyPrecipProbability = 16))
    }

    @Test
    fun `hasRainForecast true when both hourly and daily indicate rain`() {
        assertTrue(DayClickHelper.hasRainForecast(rainSummary = "2pm", dailyPrecipProbability = 60))
    }

    @Test
    fun `hasRainForecast false when no rain from either source`() {
        assertFalse(DayClickHelper.hasRainForecast(rainSummary = null, dailyPrecipProbability = 0))
    }

    @Test
    fun `hasRainForecast false when daily precip is null`() {
        assertFalse(DayClickHelper.hasRainForecast(rainSummary = null, dailyPrecipProbability = null))
    }

    @Test
    fun `hasRainForecast false for empty rain summary with zero daily precip`() {
        assertFalse(DayClickHelper.hasRainForecast(rainSummary = "", dailyPrecipProbability = 0))
    }

    // ── shouldNavigateToPrecipitation: basic decision truth table ──

    @Test
    fun `today with rain navigates to precipitation`() {
        assertTrue(DayClickHelper.shouldNavigateToPrecipitation(isPastDay = false, hasRainForecast = true))
    }

    @Test
    fun `today without rain does not navigate to precipitation`() {
        assertFalse(DayClickHelper.shouldNavigateToPrecipitation(isPastDay = false, hasRainForecast = false))
    }

    @Test
    fun `past day with rain does not navigate to precipitation`() {
        assertFalse(DayClickHelper.shouldNavigateToPrecipitation(isPastDay = true, hasRainForecast = true))
    }

    @Test
    fun `past day without rain does not navigate to precipitation`() {
        assertFalse(DayClickHelper.shouldNavigateToPrecipitation(isPastDay = true, hasRainForecast = false))
    }

    @Test
    fun `helper is inverse of showHistory logic`() {
        for (isHistory in listOf(true, false)) {
            for (hasRain in listOf(true, false)) {
                val showHistory = isHistory || !hasRain
                val shouldNav = DayClickHelper.shouldNavigateToPrecipitation(isHistory, hasRain)
                assertEquals(
                    "isHistory=$isHistory, hasRain=$hasRain",
                    showHistory,
                    !shouldNav,
                )
            }
        }
    }

    // ── calculatePrecipitationOffset ──

    @Test
    fun `offset is zero for today regardless of time`() {
        val now = LocalDateTime.of(2024, 6, 15, 0, 0)
        assertEquals(0, DayClickHelper.calculatePrecipitationOffset(now, LocalDate.of(2024, 6, 15)))
        
        val now2 = LocalDateTime.of(2024, 6, 15, 14, 0)
        assertEquals(0, DayClickHelper.calculatePrecipitationOffset(now2, LocalDate.of(2024, 6, 15)))
        
        val now3 = LocalDateTime.of(2024, 6, 15, 10, 45)
        assertEquals(0, DayClickHelper.calculatePrecipitationOffset(now3, LocalDate.of(2024, 6, 15)))
    }

    @Test
    fun `offset is zero when current time is exactly 8am`() {
        val now = LocalDateTime.of(2024, 6, 15, 8, 0)
        assertEquals(0, DayClickHelper.calculatePrecipitationOffset(now, LocalDate.of(2024, 6, 15)))
    }

    @Test
    fun `offset remains calculated for future days`() {
        val now = LocalDateTime.of(2024, 6, 15, 14, 0)
        // Tomorrow 8am is 18 hours from today 2pm
        assertEquals(18, DayClickHelper.calculatePrecipitationOffset(now, LocalDate.of(2024, 6, 16)))
    }

    @Test
    fun `offset is positive for tomorrow`() {
        val now = LocalDateTime.of(2024, 6, 15, 14, 0)
        assertEquals(18, DayClickHelper.calculatePrecipitationOffset(now, LocalDate.of(2024, 6, 16)))
    }

    // ── End-to-end: daily precip + hourly data drive click decision ──
    // These tests reproduce the real-world bug: widget shows "16%" daily precip
    // but no individual hour exceeds the 40% RainAnalyzer threshold.

    private fun createForecast(
        dateTime: String,
        precipProb: Int? = 0,
        source: String = "NWS",
    ): HourlyForecastEntity {
        return HourlyForecastEntity(
            dateTime = dateTime,
            locationLat = 37.7749,
            locationLon = -122.4194,
            temperature = 70f,
            condition = if ((precipProb ?: 0) >= 40) "Rain" else "Clear",
            source = source,
            precipProbability = precipProb,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    @Test
    fun `today with daily precip but no hourly rain navigates to precipitation`() {
        // Real-world scenario from device: NWS daily says 16% precip, but
        // hourly data has max 6% — no hour exceeds 40% threshold.
        val today = LocalDate.of(2024, 6, 15)
        val now = LocalDateTime.of(2024, 6, 15, 10, 0)
        val forecasts = listOf(
            createForecast("2024-06-15T14:00", precipProb = 6),
            createForecast("2024-06-15T15:00", precipProb = 3),
        )
        val dailyPrecipProbability = 16 // Shown on widget as "16%"

        val rainSummary = RainAnalyzer.getRainSummary(forecasts, today, "NWS", now)
        assertNull("Hourly data should NOT meet 40% threshold", rainSummary)

        // Despite no hourly rain, daily data shows 16% — widget displays this.
        // Clicking today should navigate to precipitation graph.
        val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProbability)
        assertTrue(
            "Daily precipitation 16% should count as rain for navigation",
            hasRain,
        )
        assertTrue(
            "Today with 16% daily precipitation should navigate to precipitation graph",
            DayClickHelper.shouldNavigateToPrecipitation(false, hasRain),
        )
    }

    @Test
    fun `today with 60 percent hourly rain navigates to precipitation`() {
        val today = LocalDate.of(2024, 6, 15)
        val now = LocalDateTime.of(2024, 6, 15, 10, 0)
        val forecasts = listOf(
            createForecast("2024-06-15T14:00", precipProb = 60),
        )

        val rainSummary = RainAnalyzer.getRainSummary(forecasts, today, "NWS", now)
        val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProbability = 60)

        assertNotNull("60% hourly rain should be detected", rainSummary)
        assertTrue(DayClickHelper.shouldNavigateToPrecipitation(false, hasRain))
    }

    @Test
    fun `today with zero daily precip and no hourly rain shows history`() {
        val today = LocalDate.of(2024, 6, 15)
        val now = LocalDateTime.of(2024, 6, 15, 10, 0)
        val forecasts = listOf(
            createForecast("2024-06-15T14:00", precipProb = 0),
        )

        val rainSummary = RainAnalyzer.getRainSummary(forecasts, today, "NWS", now)
        val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProbability = 0)

        assertNull(rainSummary)
        assertFalse(hasRain)
        assertFalse(DayClickHelper.shouldNavigateToPrecipitation(false, hasRain))
    }

    @Test
    fun `today with null daily precip and no hourly rain shows history`() {
        val today = LocalDate.of(2024, 6, 15)
        val now = LocalDateTime.of(2024, 6, 15, 10, 0)

        val rainSummary = RainAnalyzer.getRainSummary(emptyList(), today, "NWS", now)
        val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProbability = null)

        assertFalse(hasRain)
        assertFalse(DayClickHelper.shouldNavigateToPrecipitation(false, hasRain))
    }

    @Test
    fun `past day with daily precip does not navigate to precipitation`() {
        // Even with 80% daily probability, past days always go to history
        val hasRain = DayClickHelper.hasRainForecast(rainSummary = null, dailyPrecipProbability = 80)
        assertTrue("Should detect rain", hasRain)
        assertFalse(
            "Past days never navigate to precipitation",
            DayClickHelper.shouldNavigateToPrecipitation(isPastDay = true, hasRainForecast = hasRain),
        )
    }

    @Test
    fun `today with 4 percent daily precip navigates to precipitation`() {
        // Even small probabilities (e.g., Open-Meteo's 4%) should trigger
        // navigation since the widget shows this to the user
        val hasRain = DayClickHelper.hasRainForecast(rainSummary = null, dailyPrecipProbability = 4)
        assertTrue(hasRain)
        assertTrue(DayClickHelper.shouldNavigateToPrecipitation(false, hasRain))
    }

    @Test
    fun `integration forward-looking today precip suppresses navigation when only past rain exists`() {
        val now = LocalDateTime.of(2026, 2, 22, 10, 0)
        val forecasts = listOf(
            createForecast("2026-02-22T09:00", precipProb = 26),
            createForecast("2026-02-22T10:00", precipProb = 0),
            createForecast("2026-02-22T11:00", precipProb = 0),
        )

        val todayForwardLookingPrecip =
            HeaderPrecipCalculator.getForwardLookingTodayPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = 4,
                now = now,
            )

        val hasRain = DayClickHelper.hasRainForecast(rainSummary = null, dailyPrecipProbability = todayForwardLookingPrecip)

        assertNull(todayForwardLookingPrecip)
        assertFalse(hasRain)
        assertFalse(DayClickHelper.shouldNavigateToPrecipitation(isPastDay = false, hasRainForecast = hasRain))
    }
}
