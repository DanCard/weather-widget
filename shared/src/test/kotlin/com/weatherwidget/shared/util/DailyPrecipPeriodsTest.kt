package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * The stored day/night precip rule both platforms apply at save time (2026-10-07). Moved here from
 * Android's ForecastRepositoryDayNightPrecipTest, whose windowing cases it keeps.
 */
@Category(ShortDuration::class)
class DailyPrecipPeriodsTest {
    private val zone = ZoneId.systemDefault()
    private val date = LocalDate.of(2026, 5, 25)
    private fun at(day: LocalDate, h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun hour(ms: Long, pop: Int?, lat: Double? = null, lon: Double? = null) =
        HourlyForecast(dateTime = ms, temperature = 60f, condition = "Rain", precipProbability = pop, locationLat = lat, locationLon = lon)

    private val hourly = listOf(
        hour(at(date, 8), 10), hour(at(date, 12), 50), hour(at(date, 19), 20),
        hour(at(date, 20), 80), hour(at(date, 23), 100),
        hour(at(date.plusDays(1), 4), 30), hour(at(date.plusDays(1), 7), 10),
        hour(at(date.plusDays(1), 8), 95), // next day's daytime, not this night
    )

    private fun summary(rows: List<HourlyForecast>) = DailyHourlySummaries.forDate(rows, date, "OPEN_METEO", zone)

    @Test
    fun `day is the 8am-8pm max and night the 8pm-8am max`() {
        assertEquals(DailyPrecipPeriods.Periods(50, 100), DailyPrecipPeriods.resolve(null, null, summary(hourly)))
    }

    /** NWS "Tomorrow 30%" is its forecast for the period; one 95% hour must not replace it (3fa341b6). */
    @Test
    fun `the provider's own values win over hourly rows`() {
        assertEquals(DailyPrecipPeriods.Periods(30, 70), DailyPrecipPeriods.resolve(30, 70, summary(hourly)))
    }

    @Test
    fun `hourly rows fill a period the provider leaves empty`() {
        assertEquals(DailyPrecipPeriods.Periods(30, 100), DailyPrecipPeriods.resolve(30, null, summary(hourly)))
        assertEquals(DailyPrecipPeriods.Periods(null, null), DailyPrecipPeriods.resolve(null, null, summary(emptyList())))
    }

    /**
     * Rows ending part-way through the night: no night value at all rather than a partial max — the
     * row keeps the previous fetch's value instead ([DailyHourlySummaries.carryForward]).
     */
    @Test
    fun `a period the rows only partly cover is left empty, not a partial max`() {
        val dayOnly = hourly.filter { it.dateTime < at(date, 23) }
        assertEquals(DailyPrecipPeriods.Periods(50, null), DailyPrecipPeriods.resolve(null, null, summary(dayOnly)))
    }

    @Test
    fun `read range spans the first day's 8am to the day after the last at 8am`() {
        assertEquals(at(date, 8), DailyPrecipPeriods.readStartMs(date, zone))
        assertEquals(at(date.plusDays(3), 8), DailyPrecipPeriods.readEndMs(date.plusDays(2), zone))
    }

    @Test
    fun `only rows under this site's write key count`() {
        val here = hour(at(date, 12), 50, 37.417, -122.089)
        val neighbour = hour(at(date, 13), 90, 37.418, -122.089)
        val unkeyed = hour(at(date, 14), 20)
        assertEquals(
            listOf(here, unkeyed),
            DailyPrecipPeriods.atSite(listOf(here, neighbour, unkeyed), 37.41680, -122.08890),
        )
    }
}
