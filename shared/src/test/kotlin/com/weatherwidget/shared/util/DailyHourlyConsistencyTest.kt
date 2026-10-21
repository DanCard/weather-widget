package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.DailyHourlyConsistency.Day
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Category(ShortDuration::class)
class DailyHourlyConsistencyTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.parse("2026-10-20")
    private val wed = LocalDate.parse("2026-10-21")

    /** Hourly rows for [date] 00:00–23:00 from [temps] (24 values). */
    private fun dayHours(date: LocalDate, temps: List<Float>) = temps.mapIndexed { i, t ->
        HourlyForecast(
            dateTime = LocalDateTime.of(date, java.time.LocalTime.MIDNIGHT).plusHours(i.toLong()).atZone(zone).toInstant().toEpochMilli(),
            temperature = t,
            condition = "Clear",
        )
    }

    /** Night low 57.5 at 04:00, afternoon high 67.4, evening 60. */
    private val wedTemps = listOf(59f, 58.6f, 58f, 57.8f, 57.5f, 58f, 59f, 60f, 61f, 63f, 65f, 66f, 67f, 67.4f, 67f, 66f,
        65f, 64f, 63f, 62f, 61.5f, 61f, 60.5f, 60f)

    private fun check(day: Day, hours: List<HourlyForecast> = dayHours(wed, wedTemps)) =
        DailyHourlyConsistency.check(listOf(day), hours, today, zone)

    @Test
    fun `the 2026-10-21 Google low is flagged against its own hourly morning`() {
        val m = check(Day(wed, 67.4f, 51f)).single()
        assertEquals("low", m.field)
        assertEquals(51f, m.daily)
        assertEquals(57.5f, m.hourly)
        assertEquals(57.5f, m.hourlyDay)
    }

    @Test
    fun `a low within tolerance of the morning minimum is not flagged`() {
        assertTrue(check(Day(wed, 67f, 55f)).isEmpty())
        assertTrue(check(Day(wed, 67f, 60.4f)).isEmpty())
    }

    @Test
    fun `a midnight-to-midnight low from a colder evening is not flagged`() {
        // Morning 57.5, evening down to 52: Open-Meteo's daily min is 52, which is the day's minimum.
        val coldEvening = wedTemps.take(18) + listOf(57f, 56f, 55f, 54f, 53f, 52f)
        assertTrue(check(Day(wed, 67.4f, 52f), dayHours(wed, coldEvening)).isEmpty())
    }

    @Test
    fun `a high more than the tolerance from the hourly maximum is flagged`() {
        val m = check(Day(wed, 72f, 57.5f)).single()
        assertEquals("high", m.field)
        assertEquals(67.4f, m.hourly)
        assertTrue(check(Day(wed, 70f, 57.5f)).isEmpty())
    }

    @Test
    fun `today and days the hourly does not fully cover are skipped`() {
        val todayHours = dayHours(today, List(24) { 60f })
        assertTrue("today", DailyHourlyConsistency.check(listOf(Day(today, 90f, 30f)), todayHours, today, zone).isEmpty())
        val fromNoon = dayHours(wed, wedTemps).drop(12)
        assertTrue("partly covered", check(Day(wed, 90f, 30f), fromNoon).isEmpty())
        assertTrue("no hourly", check(Day(wed, 90f, 30f), emptyList()).isEmpty())
    }

    @Test
    fun `missing extremes are not compared`() {
        assertTrue(check(Day(wed, null, null)).isEmpty())
        assertTrue(check(Day(wed, Float.NaN, null)).isEmpty())
    }

    @Test
    fun `log message names the source, day, field and both values`() {
        val msg = DailyHourlyConsistency.logMessage("GOOGLE_WEATHER", check(Day(wed, 72f, 51f)))
        assertEquals(
            "source=GOOGLE_WEATHER 2026-10-21:high daily=72.0 hourly=67.4; 2026-10-21:low daily=51.0 hourly=57.5 day=57.5",
            msg,
        )
    }
}
