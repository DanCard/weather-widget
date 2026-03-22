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
import java.time.ZoneId

class DayClickHelperTest {

    // ── hasRainForecast: combines hourly RainAnalyzer + daily precipProbability ──

    @Test
    fun `hasRainForecast true when hourly rain detected`() {
        assertTrue(DayClickHelper.hasRainForecast(rainSummary = "2pm", dailyPrecipProbability = 0))
    }

    @Test
    fun `hasRainForecast true when daily precip probability is above threshold`() {
        // Threshold is now 8%
        assertTrue(DayClickHelper.hasRainForecast(rainSummary = null, dailyPrecipProbability = 9))
    }

    @Test
    fun `hasRainForecast false when daily precip probability is at or below threshold`() {
        assertFalse(DayClickHelper.hasRainForecast(rainSummary = null, dailyPrecipProbability = 8))
        assertFalse(DayClickHelper.hasRainForecast(rainSummary = null, dailyPrecipProbability = 5))
    }

    @Test
    fun `hasRainForecast true when both hourly and daily indicate rain`() {
        assertTrue(DayClickHelper.hasRainForecast(rainSummary = "2pm", dailyPrecipProbability = 60))
    }

    @Test
    fun `hasRainForecast true when hourly rain summary exists even if daily is low`() {
        // If RainAnalyzer detected a start time (>= 50% hourly), we always show rain graph
        assertTrue(DayClickHelper.hasRainForecast(rainSummary = "3pm", dailyPrecipProbability = 5))
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

    // ── shouldShowHistory: basic decision truth table ──

    @Test
    fun `past day shows history`() {
        assertTrue(DayClickHelper.shouldShowHistory(isPastDay = true))
    }

    @Test
    fun `today does not show history`() {
        assertFalse(DayClickHelper.shouldShowHistory(isPastDay = false))
    }

    // ── resolveTargetViewMode: basic decision truth table ──

    @Test
    fun `day with rain navigates to precipitation`() {
        assertEquals(com.weatherwidget.widget.ViewMode.PRECIPITATION, DayClickHelper.resolveTargetViewMode(hasRainForecast = true))
    }

    @Test
    fun `day without rain navigates to temperature`() {
        assertEquals(com.weatherwidget.widget.ViewMode.TEMPERATURE, DayClickHelper.resolveTargetViewMode(hasRainForecast = false))
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
            dateTime = LocalDateTime.parse(dateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
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
        val today = LocalDate.of(2024, 6, 15)
        val now = LocalDateTime.of(2024, 6, 15, 10, 0)
        val forecasts = listOf(
            createForecast("2024-06-15T14:00", precipProb = 6),
            createForecast("2024-06-15T15:00", precipProb = 3),
        )
        val dailyPrecipProbability = 16

        val rainSummary = RainAnalyzer.getRainSummary(forecasts, today, "NWS", now)
        val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProbability)

        assertTrue("Daily precipitation 16% should count as rain", hasRain)
        assertFalse("Today should NOT show history", DayClickHelper.shouldShowHistory(false))
        assertEquals(com.weatherwidget.widget.ViewMode.PRECIPITATION, DayClickHelper.resolveTargetViewMode(hasRain))
    }

    @Test
    fun `today with 8 percent daily precip and no hourly rain navigates to temperature`() {
        val today = LocalDate.of(2024, 6, 15)
        val now = LocalDateTime.of(2024, 6, 15, 10, 0)
        val forecasts = listOf(
            createForecast("2024-06-15T14:00", precipProb = 5),
        )
        val dailyPrecipProbability = 8

        val rainSummary = RainAnalyzer.getRainSummary(forecasts, today, "NWS", now)
        val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProbability)

        assertFalse("8% daily precip should NOT count as rain for navigation", hasRain)
        assertEquals(com.weatherwidget.widget.ViewMode.TEMPERATURE, DayClickHelper.resolveTargetViewMode(hasRain))
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

        assertTrue(hasRain)
        assertFalse(DayClickHelper.shouldShowHistory(false))
        assertEquals(com.weatherwidget.widget.ViewMode.PRECIPITATION, DayClickHelper.resolveTargetViewMode(hasRain))
    }

    @Test
    fun `today with zero daily precip and no hourly rain navigates to temperature`() {
        val today = LocalDate.of(2024, 6, 15)
        val now = LocalDateTime.of(2024, 6, 15, 10, 0)
        val forecasts = listOf(
            createForecast("2024-06-15T14:00", precipProb = 0),
        )

        val rainSummary = RainAnalyzer.getRainSummary(forecasts, today, "NWS", now)
        val hasRain = DayClickHelper.hasRainForecast(rainSummary, dailyPrecipProbability = 0)

        assertFalse(hasRain)
        assertFalse(DayClickHelper.shouldShowHistory(false))
        assertEquals(com.weatherwidget.widget.ViewMode.TEMPERATURE, DayClickHelper.resolveTargetViewMode(hasRain))
    }

    @Test
    fun `past day with daily precip shows history regardless of rain`() {
        val hasRain = DayClickHelper.hasRainForecast(rainSummary = null, dailyPrecipProbability = 80)
        assertTrue(hasRain)
        assertTrue("Past days ALWAYS show history", DayClickHelper.shouldShowHistory(isPastDay = true))
    }

    @Test
    fun `integration next 8 hour precip suppresses precipitation navigation when only past rain exists`() {
        val now = LocalDateTime.of(2026, 2, 22, 10, 0)
        val forecasts = listOf(
            createForecast("2026-02-22T09:00", precipProb = 26),
            createForecast("2026-02-22T10:00", precipProb = 0),
            createForecast("2026-02-22T11:00", precipProb = 0),
        )

        val todayNext8HourPrecip =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = 4,
                referenceTime = now,
            )

        val hasRain = DayClickHelper.hasRainForecast(rainSummary = null, dailyPrecipProbability = todayNext8HourPrecip)

        assertEquals(0, todayNext8HourPrecip)
        assertFalse(hasRain)
        assertEquals(com.weatherwidget.widget.ViewMode.TEMPERATURE, DayClickHelper.resolveTargetViewMode(hasRain))
    }
}
