package com.weatherwidget.shared.daily

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today's rain % on the shared column (Android widget and desktop) matches the hourly graph:
 * the stitched hours keep a started hour's pre-hour forecast, while the row's max came from one
 * mid-hour download. Tomorrow.io 2026-11-03: 08:57 run 45% at 10am, 10:02 run 0% everywhere →
 * graph 45%, desktop label blank. (plans/261103-today-rain-chance-from-stitched-hours.md)
 */
@Category(ShortDuration::class)
class DailyColumnTodayRainTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 3)
    private val source = "TOMORROW_IO"
    private val lat = 37.417
    private val lon = -122.089

    private fun at(day: LocalDate, h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** Stitched hours, as `HourlyForecastStitcher` hands them to both platforms. */
    private val stitched = generateSequence(at(today, 0)) { it + 3_600_000L }
        .takeWhile { it < at(today.plusDays(1), 12) }
        .map {
            HourlyForecast(
                dateTime = it, temperature = 60f, condition = "Cloudy",
                precipProbability = when (it) {
                    at(today, 10) -> 45
                    at(today, 11) -> 38
                    else -> 0
                },
                source = source, locationLat = lat, locationLon = lon,
            )
        }.toList()

    /** The 10:02 mid-hour row: every hour 0%, 0.508 mm. */
    private val row = DailyForecast(
        date = today.toString(), highTemp = 67.8f, lowTemp = 48f, condition = "Cloudy",
        precipProbability = 0, precipAmountMm = 0.508f, source = source,
        daytimePrecipProbability = 0, nighttimePrecipProbability = 0,
        hourlyDayPrecipMax = 0, hourlyNightPrecipMax = 0, hourlyDayPrecipPeakAt = at(today, 8),
    )

    private fun build(date: LocalDate, forecast: DailyForecast, nowHour: Int, nowMinute: Int) = DailyColumnBuilder.build(
        date = date, label = "x", today = today, now = today.atTime(nowHour, nowMinute), forecast = forecast,
        actual = null, snapshots = emptyList(), hourly = stitched, currentTemp = null,
        displaySourceId = source, centerLat = lat, centerLon = lon, use24HourClock = false,
    )

    @Test
    fun `today's column shows the stitched 45 percent and its 10am peak`() {
        // 10:30: the 10am hour has started; the row (fetched 10:02) says 0, the graph 45.
        val c = build(today, row, 10, 30)
        println("today column 10:30: rain=${c.dailyRainLabelText} peak=${c.dailyRainPeakLabelText} day%=${c.dayPrecipProbability}")
        assertEquals(45, c.dayPrecipProbability)
        assertEquals("45%", c.dailyRainLabelText)
        assertEquals("10am", c.dailyRainPeakLabelText)
    }

    @Test
    fun `after the peak hour the 45 percent stays and the passed hour drops`() {
        val c = build(today, row, 11, 15)
        println("today column 11:15: rain=${c.dailyRainLabelText} peak=${c.dailyRainPeakLabelText}")
        assertEquals("45%", c.dailyRainLabelText)
        assertNull("a passed peak hour is not shown (buildDailyRainPeakLabel)", c.dailyRainPeakLabelText)
    }
}
