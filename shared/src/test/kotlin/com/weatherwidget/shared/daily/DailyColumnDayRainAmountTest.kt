package com.weatherwidget.shared.daily

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * A future day's day and night rain amounts split the hours between them. Silurian Fri 2026-11-13
 * (desktop, fetched 2026-11-06): the day label printed the row's midnight-to-midnight .26in and the
 * night label the 8pm–8am .24in, so 8pm–midnight (.146in) was counted twice; 8am–8pm was .10in.
 * (plans/261106-day-rain-amount-daytime-window.md)
 */
@Category(ShortDuration::class)
class DailyColumnDayRainAmountTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 6)
    private val friday = LocalDate.of(2026, 11, 13)
    private val source = "SILURIAN"
    private val lat = 37.417
    private val lon = -122.089
    private lateinit var savedLocale: Locale

    @Before fun setUp() { savedLocale = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun tearDown() { Locale.setDefault(savedLocale) }

    private fun at(day: LocalDate, h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** Friday 00:00 → Saturday 08:00 in the shape of the Nov 6 run (mm per hour). */
    private fun mm(day: LocalDate, h: Int): Float = when {
        day == friday && h < 8 -> 0.0425f        // 0.34 mm overnight before the day
        day == friday && h < 20 -> 0.2167f       // 2.60 mm daytime
        day == friday -> 0.925f                  // 3.70 mm 8pm–midnight
        else -> 0.3f                             // 2.40 mm Sat 00–08
    }

    private val hourly = (0 until 32).map { i ->
        val day = if (i < 24) friday else friday.plusDays(1)
        val h = i % 24
        HourlyForecast(
            dateTime = at(day, h), temperature = 55f, condition = "Rain", precipProbability = 100,
            precipAmountMm = mm(day, h), source = source, locationLat = lat, locationLon = lon,
        )
    }

    private val row = DailyForecast(
        date = friday.toString(), highTemp = 58f, lowTemp = 50f, condition = "Rain",
        precipProbability = 100, precipAmountMm = 6.64f, source = source,
        daytimePrecipProbability = 100, nighttimePrecipProbability = 100,
    )

    @Test
    fun `friday's day label holds only the daytime rain`() {
        val c = DailyColumnBuilder.build(
            date = friday, label = "Fri", today = today, now = today.atTime(19, 0), forecast = row,
            actual = null, snapshots = emptyList(), hourly = hourly, currentTemp = null,
            displaySourceId = source, centerLat = lat, centerLon = lon, use24HourClock = false,
        )
        println("Fri column: day=${c.dailyRainLabelText} night=${c.nightRainLabelText} row total=${row.precipAmountMm}mm")
        assertEquals(".1in", c.dailyRainLabelText)
        assertEquals(".24in", c.nightRainLabelText)
    }

    @Test
    fun `without complete daytime hours the day label falls back to the chance, not the day total`() {
        val c = DailyColumnBuilder.build(
            date = friday, label = "Fri", today = today, now = today.atTime(19, 0), forecast = row,
            actual = null, snapshots = emptyList(), hourly = hourly.filter { it.dateTime >= at(friday, 20) },
            currentTemp = null, displaySourceId = source, centerLat = lat, centerLon = lon, use24HourClock = false,
        )
        println("Fri column, night hours only: day=${c.dailyRainLabelText} night=${c.nightRainLabelText}")
        assertEquals("100%", c.dailyRainLabelText)
    }
}
