package com.weatherwidget.shared.daily

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * The peak hour is shown on every day the % has one, whatever the clock. Fold 4 History of Forecasts
 * 2026-11-13 1:11 PM: NWS 99% peaking at noon only (97 at 11am, 89 at 1pm) read "—"; past days never
 * showed an hour. (plans/261113-today-rain-peak-never-hidden.md)
 */
@Category(ShortDuration::class)
class DailyColumnRainPeakEveryDayTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 13)
    private val yesterday = today.minusDays(1)
    private val source = "NWS"
    private val lat = 37.417
    private val lon = -122.089

    private fun at(day: LocalDate, h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** NWS's 13:09 fetch on the Fold 4, 8am–7pm; 30% elsewhere. */
    private val nwsChance = mapOf(8 to 28, 9 to 54, 10 to 71, 11 to 97, 12 to 99, 13 to 89, 14 to 62,
        15 to 23, 16 to 44, 17 to 29, 18 to 62, 19 to 36)

    private fun hours(day: LocalDate) = generateSequence(at(day, 0)) { it + 3_600_000L }
        .takeWhile { it < at(day.plusDays(1), 12) }
        .map {
            val hour = java.time.Instant.ofEpochMilli(it).atZone(zone)
            val chance = if (hour.toLocalDate() == day) nwsChance[hour.hour] ?: 30 else 30
            HourlyForecast(
                dateTime = it, temperature = 60f, condition = "Rain", precipProbability = chance,
                source = source, locationLat = lat, locationLon = lon,
            )
        }.toList()

    private fun frozen(chance: Int?, peakAt: Long?) = DailyHistory(
        date = yesterday.toEpochDay() * 86_400_000L, source = source, locationLat = lat, locationLon = lon,
        computedHighTemp = 63f, computedLowTemp = 54f, condition = "Rain", updatedAt = 1L,
        forecastDayPrecipChance = chance, forecastDayPrecipPeakAt = peakAt,
    )

    private fun pastColumn(actual: DailyHistory, hourly: List<HourlyForecast> = emptyList()) = DailyColumnBuilder.build(
        date = yesterday, label = "x", today = today, now = today.atTime(13, 11), forecast = null,
        actual = actual, snapshots = emptyList(), hourly = hourly, currentTemp = null,
        displaySourceId = source, centerLat = lat, centerLon = lon, use24HourClock = false,
    )

    @Test
    fun `today's one-hour peak stays after it has passed`() {
        val row = DailyForecast(
            date = today.toString(), highTemp = 63f, lowTemp = 54f, condition = "Rain",
            precipProbability = 99, source = source, daytimePrecipProbability = 99, nighttimePrecipProbability = 45,
            hourlyDayPrecipMax = 99, hourlyNightPrecipMax = 45, hourlyDayPrecipPeakAt = at(today, 12),
        )
        val c = DailyColumnBuilder.build(
            date = today, label = "x", today = today, now = today.atTime(13, 11), forecast = row,
            actual = null, snapshots = emptyList(), hourly = hours(today), currentTemp = null,
            displaySourceId = source, centerLat = lat, centerLon = lon, use24HourClock = false,
        )
        println("NWS today 13:11: rain=${c.dailyRainLabelText} peak=${c.dailyRainPeakLabelText} peakAt=${c.dailyRainPeakAt}")
        assertEquals("99%", c.dailyRainLabelText)
        assertEquals("12pm", c.dailyRainPeakLabelText)
        assertEquals(at(today, 12), c.dailyRainPeakAt)
    }

    @Test
    fun `a past day shows the hour frozen with its chance`() {
        val c = pastColumn(frozen(chance = 99, peakAt = at(yesterday, 12)))
        println("NWS yesterday frozen: rain=${c.dailyRainLabelText} peak=${c.dailyRainPeakLabelText}")
        assertEquals("12pm", c.dailyRainPeakLabelText)
        assertEquals(at(yesterday, 12), c.dailyRainPeakAt)
    }

    @Test
    fun `a past day frozen before the column borrows the hours' peak only at the same max`() {
        val matching = pastColumn(frozen(chance = 99, peakAt = null), hours(yesterday))
        val other = pastColumn(frozen(chance = 100, peakAt = null), hours(yesterday))
        println("pre-column: matching peak=${matching.dailyRainPeakLabelText} other=${other.dailyRainPeakLabelText}")
        assertEquals("12pm", matching.dailyRainPeakLabelText)
        assertNull("never an hour belonging to a different %", other.dailyRainPeakLabelText)
    }
}
