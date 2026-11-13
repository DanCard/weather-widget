package com.weatherwidget.shared.daily

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today's peak hour names the first hour of a plateau at the day max, and stays all day.
 * Desktop History of Forecasts 2026-11-13 12:12: OWM 100% from 10am to 1pm stored peak 10am, and the
 * Peak cell read "—" from 11:00. (plans/261113-today-rain-peak-plateau-hide.md)
 */
@Category(ShortDuration::class)
class DailyColumnTodayRainPlateauTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 13)
    private val source = "OPEN_WEATHER_MAP"
    private val lat = 37.417
    private val lon = -122.089

    private fun at(day: LocalDate, h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** OWM's newest hourly chances from the desktop DB (12:10 fetch). */
    private val owmChance = mapOf(8 to 59, 9 to 79, 10 to 100, 11 to 100, 12 to 100, 13 to 100, 14 to 93,
        15 to 85, 16 to 78, 17 to 82, 18 to 85, 19 to 89)

    private val stitched = generateSequence(at(today, 0)) { it + 3_600_000L }
        .takeWhile { it < at(today.plusDays(1), 12) }
        .map {
            val hour = java.time.Instant.ofEpochMilli(it).atZone(zone)
            val chance = if (hour.toLocalDate() == today) owmChance[hour.hour] ?: 50 else 50
            HourlyForecast(
                dateTime = it, temperature = 58f, condition = "Rain", precipProbability = chance,
                source = source, locationLat = lat, locationLon = lon,
            )
        }.toList()

    private val row = DailyForecast(
        date = today.toString(), highTemp = 58.9f, lowTemp = 57.2f, condition = "Rain",
        precipProbability = 100, source = source,
        daytimePrecipProbability = 100, nighttimePrecipProbability = 72,
        hourlyDayPrecipMax = 100, hourlyNightPrecipMax = 72, hourlyDayPrecipPeakAt = at(today, 10),
    )

    private fun build(nowHour: Int, nowMinute: Int) = DailyColumnBuilder.build(
        date = today, label = "x", today = today, now = today.atTime(nowHour, nowMinute), forecast = row,
        actual = null, snapshots = emptyList(), hourly = stitched, currentTemp = null,
        displaySourceId = source, centerLat = lat, centerLon = lon, use24HourClock = false,
    )

    @Test
    fun `mid-plateau keeps the first hour and its sort instant`() {
        val c = build(12, 12)
        println("OWM 12:12: rain=${c.dailyRainLabelText} peak=${c.dailyRainPeakLabelText} peakAt=${c.dailyRainPeakAt}")
        assertEquals("10am", c.dailyRainPeakLabelText)
        assertEquals(at(today, 10), c.dailyRainPeakAt)
    }

    @Test
    fun `the first hour stays after the plateau ends`() {
        // plans/261113-today-rain-peak-never-hidden.md: the clock never hides the peak hour.
        val after = build(14, 0)
        val evening = build(23, 0)
        println("OWM 14:00 peak=${after.dailyRainPeakLabelText}; 23:00 peak=${evening.dailyRainPeakLabelText}")
        assertEquals("10am", after.dailyRainPeakLabelText)
        assertEquals(at(today, 10), after.dailyRainPeakAt)
        assertEquals("10am", evening.dailyRainPeakLabelText)
    }
}
