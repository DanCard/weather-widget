package com.weatherwidget.data.remote

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Category(ShortDuration::class)
class HourlyOnDemandTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val hour = 3_600_000L
    private val google = WeatherSource.GOOGLE_WEATHER.id

    // Fri Oct 9 2026, 03:24 PDT — the reported tap.
    private val now = LocalDateTime.of(2026, 10, 9, 3, 24).atZone(zone).toInstant().toEpochMilli()
    private val today = LocalDate.of(2026, 10, 9)

    private fun ms(date: LocalDate, h: Int) = date.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun row(dateTime: Long, fetchedAt: Long = now) =
        HourlyForecast(dateTime, 60f, "Clear", source = google, fetchedAt = fetchedAt)

    /** The routine fetch: 72 hours from the current hour (Fri 03:00 .. Mon 02:00). */
    private val routine = (0 until 72).map { row(now - Math.floorMod(now, hour) + it * hour) }
    private val monday = today.plusDays(3)

    @Test
    fun `only Google extends on demand`() {
        assertTrue(HourlyOnDemand.extendsHourly(google))
        WeatherSource.entries.filter { it != WeatherSource.GOOGLE_WEATHER }.forEach {
            assertFalse(it.id, HourlyOnDemand.extendsHourly(it.id))
        }
        assertNull(HourlyOnDemand.hoursToCover(WeatherSource.NWS.id, today.plusDays(6), zone, now, emptyList()))
    }

    @Test
    fun `only Google has an extension window, starting past its routine 72 hours`() {
        assertEquals(now - Math.floorMod(now, hour) + 72 * hour, HourlyOnDemand.extensionStartMs(google, now))
        assertNull(HourlyOnDemand.extensionStartMs(WeatherSource.OPEN_METEO.id, now))
        assertEquals(72, HourlyOnDemand.hoursAhead(google, null))
        assertEquals(120, HourlyOnDemand.hoursAhead(google, HourlyOnDemand.Request(google, 120)))
        assertEquals(72, HourlyOnDemand.hoursAhead(google, HourlyOnDemand.Request(WeatherSource.NWS.id, 120)))
    }

    @Test
    fun `next Thursday needs hours through its 23 00`() {
        val hours = HourlyOnDemand.hoursToCover(google, LocalDate.of(2026, 10, 15), zone, now, routine)!!

        // 03:00 Fri → 23:00 Thu is 164 hours, plus the current hour.
        assertEquals(165, hours)
        assertEquals("6.9 pages, so 7 billed requests", 7, (hours + 23) / 24)
    }

    @Test
    fun `days inside the routine window need nothing`() {
        assertNull(HourlyOnDemand.hoursToCover(google, today, zone, now, routine))
        assertNull(HourlyOnDemand.hoursToCover(google, today.plusDays(2), zone, now, routine))
    }

    @Test
    fun `a day stored to its last hour needs nothing, one hour short still fetches`() {
        assertEquals("Monday ends past the routine 72 h", 93, HourlyOnDemand.hoursToCover(google, monday, zone, now, routine))
        assertNull(HourlyOnDemand.hoursToCover(google, monday, zone, now, routine + row(ms(monday, 23))))
        assertEquals(93, HourlyOnDemand.hoursToCover(google, monday, zone, now, routine + row(ms(monday, 22))))
    }

    @Test
    fun `on-demand hours stop counting once older than 12 hours`() {
        val fetched11hAgo = row(ms(monday, 23), fetchedAt = now - 11 * hour)
        val fetched13hAgo = row(ms(monday, 23), fetchedAt = now - 13 * hour)
        assertNull(HourlyOnDemand.hoursToCover(google, monday, zone, now, routine + fetched11hAgo))
        assertEquals(93, HourlyOnDemand.hoursToCover(google, monday, zone, now, routine + fetched13hAgo))
    }

    @Test
    fun `routine-window rows count however old`() {
        val old = routine.map { it.copy(fetchedAt = 0L) }
        assertNull(HourlyOnDemand.hoursToCover(google, today.plusDays(1), zone, now, old))
    }

    @Test
    fun `never asks for less than the routine horizon`() {
        assertEquals(GoogleWeatherApi.FORECAST_HOURS, HourlyOnDemand.hoursToCover(google, today, zone, now, emptyList()))
    }

    @Test
    fun `past days and days beyond the loaders' reach fetch nothing`() {
        assertNull(HourlyOnDemand.hoursToCover(google, today.minusDays(1), zone, now, emptyList()))
        assertNull(
            "starts past now + 240 h",
            HourlyOnDemand.hoursToCover(google, today.plusDays(11), zone, now, emptyList()),
        )
    }

    @Test
    fun `every daily column a week or more out gets a full day`() {
        // Fri Oct 16 (the reported day+7 that got only 00:00–03:00 at a 168 h reach): through 23:00.
        assertEquals(189, HourlyOnDemand.hoursToCover(google, today.plusDays(7), zone, now, routine))
        assertEquals(237, HourlyOnDemand.hoursToCover(google, today.plusDays(9), zone, now, routine))
    }

    @Test
    fun `a day straddling the reach is fetched only up to it`() {
        // Mon Oct 19 starts 236.6 h out; the reach ends at 03:24 that morning.
        assertEquals(HourlyOnDemand.REACH_HOURS, HourlyOnDemand.hoursToCover(google, today.plusDays(10), zone, now, routine))
    }
}
