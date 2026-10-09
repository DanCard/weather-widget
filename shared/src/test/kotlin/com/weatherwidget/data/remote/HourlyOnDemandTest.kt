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

    @Test
    fun `a tapped date's forced sync asks Google for that day, nothing else`() {
        assertEquals(
            HourlyOnDemand.Request(google, 165),
            HourlyOnDemand.requestFor(google, "2026-10-15", zone, now),
        )
        assertNull("other sources keep their whole horizon", HourlyOnDemand.requestFor(WeatherSource.NWS.id, "2026-10-15", zone, now))
        assertNull("no tapped date", HourlyOnDemand.requestFor(google, null, zone, now))
        assertNull("no target source", HourlyOnDemand.requestFor(null, "2026-10-15", zone, now))
        assertNull("garbage date", HourlyOnDemand.requestFor(google, "next thursday", zone, now))
        assertNull("past day", HourlyOnDemand.requestFor(google, "2026-10-08", zone, now))
    }

    private fun window(fromDay: LocalDate, fromHour: Int, toDay: LocalDate, toHour: Int) =
        ms(fromDay, fromHour) to ms(toDay, toHour)

    private fun pan(source: String, w: Pair<Long, Long>, stored: List<HourlyForecast>, hasDay: (LocalDate) -> Boolean) =
        HourlyOnDemand.panAction(source, w.first, w.second, zone, now, stored, hasDay)

    @Test
    fun `a window resting on an uncovered Google day fetches it`() {
        val thursday = LocalDate.of(2026, 10, 15)
        assertEquals(
            HourlyOnDemand.PanAction.Fetch(thursday, 165),
            pan(google, window(thursday, 3, thursday, 21), routine) { false },
        )
    }

    @Test
    fun `a window straddling into an uncovered day fetches that day, not just its centre`() {
        // The Pixel case: Mon 3 PM .. Tue 7 AM, centred on Monday 11 PM.
        val monday = today.plusDays(3)
        val tuesday = today.plusDays(4)
        val mondayCovered = routine + (0..23).map { row(ms(monday, it)) }
        val action = pan(google, window(monday, 15, tuesday, 7), mondayCovered) { it == monday }
        assertEquals(HourlyOnDemand.PanAction.Fetch(tuesday, 117), action)
    }

    @Test
    fun `two uncovered days in view cost one fetch, to the later one`() {
        val monday = today.plusDays(3)
        val tuesday = today.plusDays(4)
        val action = pan(google, window(monday, 15, tuesday, 7), routine) { false }
        assertEquals(HourlyOnDemand.PanAction.Fetch(tuesday, 117), action)
    }

    @Test
    fun `a covered window does nothing`() {
        assertEquals(HourlyOnDemand.PanAction.Nothing, pan(google, window(today, 9, today.plusDays(1), 9), routine) { true })
    }

    @Test
    fun `NWS past its horizon says where it ends, for the first empty day in view`() {
        val wednesday = today.plusDays(5)
        val action = pan(WeatherSource.NWS.id, window(wednesday, 15, wednesday.plusDays(1), 7), emptyList()) { it != wednesday.plusDays(1) }
        assertEquals(HourlyOnDemand.PanAction.NoDataMessage(wednesday.plusDays(1)), action)
    }

    @Test
    fun `past the 240 h reach says so instead of fetching`() {
        val far = today.plusDays(12)
        assertEquals(HourlyOnDemand.PanAction.NoDataMessage(far), pan(google, window(far, 3, far, 21), routine) { false })
    }

    @Test
    fun `history is never nagged about`() {
        val past = today.minusDays(3)
        assertEquals(HourlyOnDemand.PanAction.Nothing, pan(google, window(past, 3, past, 21), routine) { false })
        // A window from yesterday into a covered today: only today counts.
        assertEquals(HourlyOnDemand.PanAction.Nothing, pan(google, window(today.minusDays(1), 12, today, 12), routine) { it == today })
    }
}
