package com.weatherwidget.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate

/**
 * Regression tests for [NwsDailyMapper.buildDailyForecasts] — the desktop entry point.
 *
 * The bug these guard against: the desktop previously grouped night periods by their start date, so
 * the final forecast day (which has only a daytime period) lost its overnight low and rendered
 * `low == high` (a flat bar). The shared mapper keys a night low by the date it ends (the morning),
 * so a calendar day pairs its morning low with its afternoon high, and gridpoints backstop the rest.
 */
class NwsDailyMapperBuildTest {

    private fun day(name: String, start: String, end: String, temp: Int, isDaytime: Boolean) =
        NwsApi.ForecastPeriod(
            name = name,
            startTime = start,
            endTime = end,
            temperature = temp,
            temperatureUnit = "F",
            shortForecast = if (isDaytime) "Sunny" else "Clear",
            isDaytime = isDaytime,
        )

    private val noExtremes = NwsApi.DailyTemperatureExtremes(emptyMap(), emptyMap())

    @Test
    fun `terminal daytime-only day takes its low from the preceding night period`() {
        // Saturday day+night, then Sunday day with no following "Sunday Night" period (horizon cutoff).
        val periods = listOf(
            day("Saturday", "2026-06-13T06:00:00-07:00", "2026-06-13T18:00:00-07:00", 80, true),
            day("Saturday Night", "2026-06-13T18:00:00-07:00", "2026-06-14T06:00:00-07:00", 57, false),
            day("Sunday", "2026-06-14T06:00:00-07:00", "2026-06-14T18:00:00-07:00", 84, true),
        )

        val daily = NwsDailyMapper.buildDailyForecasts(periods, noExtremes, LocalDate.parse("2026-06-13"))

        val sunday = daily.firstOrNull { it.date == "2026-06-14" }
        assertNotNull("Sunday should be present", sunday)
        assertEquals(84f, sunday!!.highTemp, 0.001f)
        // The bug produced 84 here; the morning low of Sunday is the Saturday-night low.
        assertEquals(57f, sunday.lowTemp, 0.001f)
    }

    @Test
    fun `gridpoints backstop fills the low when no night period exists`() {
        // Only a daytime Sunday period; the overnight low comes from raw gridpoints minByDate.
        val periods = listOf(
            day("Sunday", "2026-06-14T06:00:00-07:00", "2026-06-14T18:00:00-07:00", 84, true),
        )
        val extremes = NwsApi.DailyTemperatureExtremes(
            maxByDate = mapOf("2026-06-14" to 84f),
            minByDate = mapOf("2026-06-14" to 55f),
        )

        val daily = NwsDailyMapper.buildDailyForecasts(periods, extremes, LocalDate.parse("2026-06-13"))

        val sunday = daily.firstOrNull { it.date == "2026-06-14" }
        assertNotNull(sunday)
        assertEquals(84f, sunday!!.highTemp, 0.001f)
        assertEquals(55f, sunday.lowTemp, 0.001f)
    }
}
