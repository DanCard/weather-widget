package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate
import org.junit.experimental.categories.Category

/**
 * Regression tests for [NwsDailyMapper.buildDailyForecasts] — the desktop entry point.
 *
 * The bug these guard against: the desktop previously grouped night periods by their start date, so
 * the final forecast day (which has only a daytime period) lost its overnight low and rendered
 * `low == high` (a flat bar). The shared mapper keys a night low by the date it ends (the morning),
 * so a calendar day pairs its morning low with its afternoon high, and gridpoints backstop the rest.
 */
@Category(ShortDuration::class)
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
        assertEquals(84f, sunday!!.highTemp!!, 0.001f)
        // The bug produced 84 here; the morning low of Sunday is the Saturday-night low.
        assertEquals(57f, sunday.lowTemp!!, 0.001f)
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
        assertEquals(84f, sunday!!.highTemp!!, 0.001f)
        assertEquals(55f, sunday.lowTemp!!, 0.001f)
    }

    // ---- One pipeline (NwsDailyMapper.assemble): desktop used to run its own order and rules. ----
    // plans/261002-share-nws-daily-pipeline-and-partial-days.md

    private val zone = java.time.ZoneOffset.ofHours(-7)

    private fun hour(date: String, h: Int, temp: Float) = NwsApi.HourlyForecastPeriod(
        startTime = java.time.LocalDateTime.parse("${date}T%02d:00".format(h)).atOffset(zone).toInstant().toEpochMilli(),
        localDate = date,
        localHour = h,
        temperature = temp,
        shortForecast = "Clear",
    )

    @Test
    fun `evening drop leaves today's low null instead of copying the high`() {
        // Evening: no period covers the rest of today; the gridpoints still have today's max only.
        val extremes = NwsApi.DailyTemperatureExtremes(maxByDate = mapOf("2026-06-13" to 80f), minByDate = emptyMap())
        val daily = NwsDailyMapper.buildDailyForecasts(emptyList(), extremes, LocalDate.parse("2026-06-13"))
        val today = daily.single { it.date == "2026-06-13" }
        assertEquals(80f, today.highTemp!!, 0f)
        org.junit.Assert.assertNull("desktop stored low = high here (a flat bar)", today.lowTemp)
    }

    @Test
    fun `gridpoint values win over period values`() {
        val periods = listOf(day("Sunday", "2026-06-14T06:00:00-07:00", "2026-06-14T18:00:00-07:00", 84, true))
        val extremes = NwsApi.DailyTemperatureExtremes(maxByDate = mapOf("2026-06-14" to 86f), minByDate = mapOf("2026-06-14" to 55f))
        val sunday = NwsDailyMapper.buildDailyForecasts(periods, extremes, LocalDate.parse("2026-06-13")).single { it.date == "2026-06-14" }
        assertEquals("Android merged the gridpoints first; desktop let the period win", 86f, sunday.highTemp!!, 0f)
    }

    @Test
    fun `terminal low-only day is kept with a null high`() {
        val periods = listOf(
            day("Saturday", "2026-06-13T06:00:00-07:00", "2026-06-13T18:00:00-07:00", 80, true),
            day("Saturday Night", "2026-06-13T18:00:00-07:00", "2026-06-14T06:00:00-07:00", 57, false),
            day("Sunday", "2026-06-14T06:00:00-07:00", "2026-06-14T18:00:00-07:00", 84, true),
            day("Sunday Night", "2026-06-14T18:00:00-07:00", "2026-06-15T06:00:00-07:00", 58, false),
        )
        val daily = NwsDailyMapper.buildDailyForecasts(periods, noExtremes, LocalDate.parse("2026-06-13"))
        val monday = daily.single { it.date == "2026-06-15" }
        org.junit.Assert.assertNull(monday.highTemp)
        assertEquals("desktop dropped this day", 58f, monday.lowTemp!!, 0f)
    }

    @Test
    fun `a low far from the hourly series is cleared and repaired from it`() {
        val periods = listOf(
            day("Saturday Night", "2026-06-13T18:00:00-07:00", "2026-06-14T06:00:00-07:00", 20, false),
            day("Sunday", "2026-06-14T06:00:00-07:00", "2026-06-14T18:00:00-07:00", 84, true),
        )
        // The provider's own hourly series bottoms out at 57 that night.
        val hourly = (18..23).map { hour("2026-06-13", it, 62f - (it - 18)) } +
            (0..17).map { hour("2026-06-14", it, if (it < 7) 57f + it * 0.1f else 60f + (it - 7) * 2.4f) }
        val sunday = NwsDailyMapper.buildDailyForecasts(periods, noExtremes, LocalDate.parse("2026-06-13"), hourly)
            .single { it.date == "2026-06-14" }
        assertEquals("desktop skipped the divergence check and kept 20", 57f, sunday.lowTemp!!, 1f)
    }
}
