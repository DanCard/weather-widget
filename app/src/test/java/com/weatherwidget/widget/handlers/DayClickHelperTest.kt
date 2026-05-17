package com.weatherwidget.widget.handlers

import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.RainAnalyzer
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
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

    // ── shouldShowHistory: history routing is disabled; past days now go to temperature graph ──

    @Test
    fun `past day does not show history`() {
        assertFalse(DayClickHelper.shouldShowHistory(isPastDay = true))
    }

    @Test
    fun `today does not show history`() {
        assertFalse(DayClickHelper.shouldShowHistory(isPastDay = false))
    }

    // ── icon-home routing ──

    @Test
    fun `daily rainy icon navigates to precipitation`() {
        assertEquals(com.weatherwidget.widget.ViewMode.PRECIPITATION, DayClickHelper.resolveDailyTargetViewMode(R.drawable.ic_weather_rain))
    }

    @Test
    fun `daily cloud eligible icon navigates to temperature`() {
        assertEquals(com.weatherwidget.widget.ViewMode.TEMPERATURE, DayClickHelper.resolveDailyTargetViewMode(R.drawable.ic_weather_mostly_clear))
    }

    @Test
    fun `daily clear icon navigates to temperature`() {
        assertEquals(com.weatherwidget.widget.ViewMode.TEMPERATURE, DayClickHelper.resolveDailyTargetViewMode(R.drawable.ic_weather_clear))
    }

    @Test
    fun `bottom row day with rain navigates to precipitation`() {
        assertEquals(
            com.weatherwidget.widget.ViewMode.PRECIPITATION,
            DayClickHelper.resolveBottomRowTargetViewMode(R.drawable.ic_weather_snow),
        )
    }

    @Test
    fun `bottom row cloudy day without rain navigates to cloud cover`() {
        assertEquals(
            com.weatherwidget.widget.ViewMode.CLOUD_COVER,
            DayClickHelper.resolveBottomRowTargetViewMode(R.drawable.ic_weather_partly_cloudy),
        )
    }

    @Test
    fun `bottom row mostly clear day without rain navigates to cloud cover`() {
        assertEquals(
            ViewMode.CLOUD_COVER,
            DayClickHelper.resolveBottomRowTargetViewMode(R.drawable.ic_weather_mostly_clear),
        )
    }

    @Test
    fun `bottom row clear day without rain navigates to temperature`() {
        assertEquals(
            com.weatherwidget.widget.ViewMode.TEMPERATURE,
            DayClickHelper.resolveBottomRowTargetViewMode(R.drawable.ic_weather_clear),
        )
    }

    @Test
    fun `bottom row chance rain mixed icon navigates to precipitation`() {
        assertEquals(
            ViewMode.PRECIPITATION,
            DayClickHelper.resolveBottomRowTargetViewMode(R.drawable.ic_weather_partly_cloudy_chance_rain),
        )
    }

    @Test
    fun `daily chance rain mixed icon navigates to precipitation`() {
        assertEquals(
            ViewMode.PRECIPITATION,
            DayClickHelper.resolveDailyTargetViewMode(R.drawable.ic_weather_partly_cloudy_chance_rain),
        )
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
    fun `offset is zero when current time is exactly noon`() {
        val now = LocalDateTime.of(2024, 6, 15, 12, 0)
        assertEquals(0, DayClickHelper.calculatePrecipitationOffset(now, LocalDate.of(2024, 6, 15)))
    }

    @Test
    fun `offset remains calculated for future days using noon anchor`() {
        val now = LocalDateTime.of(2024, 6, 15, 14, 0)
        // Tomorrow noon is 22 hours from today 2pm.
        assertEquals(22, DayClickHelper.calculatePrecipitationOffset(now, LocalDate.of(2024, 6, 16)))
    }

    @Test
    fun `offset is positive for tomorrow`() {
        val now = LocalDateTime.of(2024, 6, 15, 14, 0)
        assertEquals(22, DayClickHelper.calculatePrecipitationOffset(now, LocalDate.of(2024, 6, 16)))
    }

    @Test
    fun `offset truncates current time to the hour before computing noon anchor`() {
        val now = LocalDateTime.of(2024, 6, 15, 10, 45)
        assertEquals(25, DayClickHelper.calculatePrecipitationOffset(now, LocalDate.of(2024, 6, 16)))
    }

    @Test
    fun `offset keeps future day wide view aligned to midnight boundaries after half hour`() {
        val now = LocalDateTime.of(2024, 6, 15, 10, 45)
        val targetDay = LocalDate.of(2024, 6, 16)

        val offset = DayClickHelper.calculatePrecipitationOffset(now, targetDay)
        val alignedCenter = WeatherTimeUtils.alignToNearestHourHalfUp(now.plusHours(offset.toLong()))

        assertEquals(targetDay.atStartOfDay(), alignedCenter.minusHours(12))
        assertEquals(targetDay.plusDays(1).atStartOfDay(), alignedCenter.plusHours(12))
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
        assertEquals(com.weatherwidget.widget.ViewMode.PRECIPITATION, DayClickHelper.resolveDailyTargetViewMode(R.drawable.ic_weather_rain))
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
        assertEquals(com.weatherwidget.widget.ViewMode.TEMPERATURE, DayClickHelper.resolveDailyTargetViewMode(R.drawable.ic_weather_clear))
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
        assertEquals(com.weatherwidget.widget.ViewMode.PRECIPITATION, DayClickHelper.resolveDailyTargetViewMode(R.drawable.ic_weather_storm))
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
        assertEquals(com.weatherwidget.widget.ViewMode.TEMPERATURE, DayClickHelper.resolveDailyTargetViewMode(R.drawable.ic_weather_clear))
    }

    @Test
    fun `past day with daily precip no longer routes to history`() {
        val hasRain = DayClickHelper.hasRainForecast(rainSummary = null, dailyPrecipProbability = 80)
        assertTrue(hasRain)
        assertFalse("Past days route to temperature graph, not history", DayClickHelper.shouldShowHistory(isPastDay = true))
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
        assertEquals(com.weatherwidget.widget.ViewMode.TEMPERATURE, DayClickHelper.resolveDailyTargetViewMode(R.drawable.ic_weather_clear))
    }

    // ── resolveHourlyBottomRowAction: icon-dependent routing for hourly graphs ──

    @Test
    fun `hourly bottom row rain icon on precipitation view returns null (zoom)`() {
        assertNull(DayClickHelper.resolveHourlyBottomRowAction(R.drawable.ic_weather_rain, ViewMode.PRECIPITATION))
    }

    @Test
    fun `hourly bottom row storm icon on precipitation view returns null (zoom)`() {
        assertNull(DayClickHelper.resolveHourlyBottomRowAction(R.drawable.ic_weather_storm, ViewMode.PRECIPITATION))
    }

    @Test
    fun `hourly bottom row snow icon on precipitation view returns null (zoom)`() {
        assertNull(DayClickHelper.resolveHourlyBottomRowAction(R.drawable.ic_weather_snow, ViewMode.PRECIPITATION))
    }

    @Test
    fun `hourly bottom row rain icon on temperature view navigates to precipitation`() {
        assertEquals(ViewMode.PRECIPITATION, DayClickHelper.resolveHourlyBottomRowAction(R.drawable.ic_weather_rain, ViewMode.TEMPERATURE))
    }

    @Test
    fun `hourly bottom row rain icon on cloud cover view navigates to precipitation`() {
        assertEquals(ViewMode.PRECIPITATION, DayClickHelper.resolveHourlyBottomRowAction(R.drawable.ic_weather_rain, ViewMode.CLOUD_COVER))
    }

    @Test
    fun `hourly bottom row partly cloudy icon on cloud cover view returns null (zoom)`() {
        assertNull(DayClickHelper.resolveHourlyBottomRowAction(R.drawable.ic_weather_partly_cloudy, ViewMode.CLOUD_COVER))
    }

    @Test
    fun `hourly bottom row mostly clear icon on cloud cover view returns null (zoom)`() {
        assertNull(DayClickHelper.resolveHourlyBottomRowAction(R.drawable.ic_weather_mostly_clear, ViewMode.CLOUD_COVER))
    }

    @Test
    fun `hourly bottom row cloudy icon on cloud cover view returns null (zoom)`() {
        assertNull(DayClickHelper.resolveHourlyBottomRowAction(R.drawable.ic_weather_cloudy, ViewMode.CLOUD_COVER))
    }

    @Test
    fun `hourly bottom row partly cloudy icon on temperature view navigates to cloud cover`() {
        assertEquals(ViewMode.CLOUD_COVER, DayClickHelper.resolveHourlyBottomRowAction(R.drawable.ic_weather_partly_cloudy, ViewMode.TEMPERATURE))
    }

    @Test
    fun `hourly bottom row mostly clear icon on temperature view navigates to cloud cover`() {
        assertEquals(ViewMode.CLOUD_COVER, DayClickHelper.resolveHourlyBottomRowAction(R.drawable.ic_weather_mostly_clear, ViewMode.TEMPERATURE))
    }

    @Test
    fun `hourly bottom row clear icon on temperature view returns null (zoom)`() {
        assertNull(DayClickHelper.resolveHourlyBottomRowAction(R.drawable.ic_weather_clear, ViewMode.TEMPERATURE))
    }

    @Test
    fun `hourly bottom row clear icon on cloud cover view navigates to temperature`() {
        assertEquals(ViewMode.TEMPERATURE, DayClickHelper.resolveHourlyBottomRowAction(R.drawable.ic_weather_clear, ViewMode.CLOUD_COVER))
    }

    @Test
    fun `hourly bottom row night icon on temperature view returns null (zoom)`() {
        assertNull(DayClickHelper.resolveHourlyBottomRowAction(R.drawable.ic_weather_night, ViewMode.TEMPERATURE))
    }

    @Test
    fun `hourly bottom row null icon returns null (zoom)`() {
        assertNull(DayClickHelper.resolveHourlyBottomRowAction(null, ViewMode.TEMPERATURE))
        assertNull(DayClickHelper.resolveHourlyBottomRowAction(null, ViewMode.CLOUD_COVER))
        assertNull(DayClickHelper.resolveHourlyBottomRowAction(null, ViewMode.PRECIPITATION))
    }

    // ── calculateNightCenterOffset: centers the precip graph on astronomical night ──

    private val sfLat = 37.7749
    private val sfLon = -122.4194

    @Test
    fun `calculateNightCenterOffset SF tonight from afternoon centers on night midpoint`() {
        // 2026-05-01 in SF: sunset ~20:11 PDT, sunrise (May 2) ~06:11 PDT
        // Night midpoint ≈ 01:11 on May 2 → ~9 hours from 17:00 May 1
        val now = LocalDateTime.of(2026, 5, 1, 17, 0)
        val target = LocalDate.of(2026, 5, 1)
        val offset = DayClickHelper.calculateNightCenterOffset(now, target, sfLat, sfLon)
        // Allow ±2h slack: civil-twilight approximation isn't astronomically exact
        assertTrue("offset $offset should be in 7..11", offset in 7..11)
    }

    @Test
    fun `calculateNightCenterOffset future day centers on that night midpoint`() {
        // Tap "tomorrow night" from morning of today: 2026-05-01 06:00 → night midpoint of May 1
        // Distance ~19 hours
        val now = LocalDateTime.of(2026, 5, 1, 6, 0)
        val target = LocalDate.of(2026, 5, 1)
        val offset = DayClickHelper.calculateNightCenterOffset(now, target, sfLat, sfLon)
        assertTrue("offset $offset should be roughly 18..21h", offset in 18..21)
    }

    @Test
    fun `calculateNightCenterOffset polar night degrades gracefully`() {
        // Antarctic interior in winter: sunsetHour=0 sunriseHour=0 → midpoint = (0 + 24 + 0)/2 = 12
        // i.e. centers on noon of the next day. Not astronomically meaningful, but doesn't crash.
        val now = LocalDateTime.of(2026, 7, 1, 12, 0)
        val target = LocalDate.of(2026, 7, 1)
        val offset = DayClickHelper.calculateNightCenterOffset(now, target, -82.0, 0.0)
        // Just verify it returns a finite int and doesn't throw
        assertTrue("offset $offset should be sane", offset in -100..100)
    }
}
