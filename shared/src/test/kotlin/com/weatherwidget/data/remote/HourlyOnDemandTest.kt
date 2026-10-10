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

    private val nws = WeatherSource.NWS.id

    private fun nwsRows(throughHoursAhead: Int, fetchedAt: Long = now) =
        (0..throughHoursAhead).map {
            HourlyForecast(now - Math.floorMod(now, hour) + it * hour, 60f, "Clear", source = nws, fetchedAt = fetchedAt)
        }

    @Test
    fun `the horizon table, not a source check, says who has an on-demand range`() {
        val horizon = HourlyHorizons.of(google)
        assertEquals(72, horizon.routineHours)
        assertEquals(HourlyOnDemand.REACH_HOURS, horizon.maxHours)
        assertTrue(horizon.costsPerExtraDay && horizon.hasOnDemandRange)
        // Every source stores 72 h routinely (performance/261010-daily-view-summaries-instead-of-far-hourly.md);
        // only Google pays for reaching further.
        WeatherSource.entries.filter { it != WeatherSource.GOOGLE_WEATHER }.forEach {
            val h = HourlyHorizons.of(it.id)
            assertFalse(it.id, h.costsPerExtraDay)
            assertEquals(it.id, minOf(72, h.maxHours), h.routineHours)
            assertEquals(it.id, h.maxHours > 72, h.hasOnDemandRange)
            assertTrue(it.id, h.maxHours <= HourlyOnDemand.REACH_HOURS)
        }
        assertEquals(156, HourlyHorizons.of(nws).maxHours)
        assertTrue(HourlyHorizons.of(nws).hasOnDemandRange)
    }

    @Test
    fun `any source fetches a day it has nothing stored for`() {
        // Thu Oct 15 starts 140.6 h out, inside NWS's 156 h.
        assertEquals(156, HourlyOnDemand.hoursToCover(nws, today.plusDays(6), zone, now, emptyList()))
    }

    @Test
    fun `a source fetched recently whose data simply ends sooner is not fetched again`() {
        // Stored to 150 h, fresh: the day's wanted end (the 156 h edge) is not covered, but another
        // fetch would bring the same thing.
        assertNull(HourlyOnDemand.hoursToCover(nws, today.plusDays(6), zone, now, nwsRows(150)))
        assertEquals(
            "13 h old: refetch",
            156,
            HourlyOnDemand.hoursToCover(nws, today.plusDays(6), zone, now, nwsRows(150, fetchedAt = now - 13 * hour)),
        )
    }

    @Test
    fun `a day starting past the source's own horizon fetches nothing`() {
        assertNull("Fri Oct 16 starts 164.6 h out, past NWS's 156 h", HourlyOnDemand.hoursToCover(nws, today.plusDays(7), zone, now, emptyList()))
    }

    @Test
    fun `every source's extension window starts past its routine 72 hours`() {
        val routineEnd = now - Math.floorMod(now, hour) + 72 * hour
        assertEquals(routineEnd, HourlyOnDemand.extensionStartMs(google, now))
        assertEquals(routineEnd, HourlyOnDemand.extensionStartMs(WeatherSource.OPEN_METEO.id, now))
        assertEquals(routineEnd, HourlyOnDemand.extensionStartMs(nws, now))
        assertNull("WeatherAPI's API ends at 72 h", HourlyOnDemand.extensionStartMs(WeatherSource.WEATHER_API.id, now))
    }

    @Test
    fun `a fetch stores 72 h routinely, the requested depth for Google, the whole horizon for a free source`() {
        assertEquals(72, HourlyOnDemand.hoursAhead(google, null))
        assertEquals(120, HourlyOnDemand.hoursAhead(google, HourlyOnDemand.Request(google, 120)))
        assertEquals(72, HourlyOnDemand.hoursAhead(google, HourlyOnDemand.Request(nws, 120)))
        assertEquals(72, HourlyOnDemand.hoursAhead(nws, null))
        assertEquals("one free call: keep all of it", 156, HourlyOnDemand.hoursAhead(nws, HourlyOnDemand.Request(nws, 120)))
        assertEquals(HourlyOnDemand.REACH_HOURS, HourlyOnDemand.hoursAhead(WeatherSource.OPEN_METEO.id, HourlyOnDemand.Request(WeatherSource.OPEN_METEO.id, 96)))
    }

    /** Open-Meteo stored to 72 h by the routine fetch: day 6 is on demand, like Google's. */
    @Test
    fun `a free source stored to 72 h fetches a later day, and not again once that fetch is fresh`() {
        val om = WeatherSource.OPEN_METEO.id
        fun omRows(through: Int, fetchedAt: Long = now) = (0..through).map {
            HourlyForecast(now - Math.floorMod(now, hour) + it * hour, 60f, "Clear", source = om, fetchedAt = fetchedAt)
        }
        val day6 = today.plusDays(6)
        val hours = HourlyOnDemand.hoursToCover(om, day6, zone, now, omRows(71))!!
        assertTrue("reaches day 6's last hour", hours >= ((ms(day6, 23) - (now - Math.floorMod(now, hour))) / hour + 1).toInt())
        assertNull("covered once stored", HourlyOnDemand.hoursToCover(om, day6, zone, now, omRows(240)))
        assertEquals(
            "a 13 h-old deep fetch no longer vouches for day 6",
            hours,
            HourlyOnDemand.hoursToCover(om, day6, zone, now, omRows(71) + omRows(240, fetchedAt = now - 13 * hour).drop(72)),
        )
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
    fun `NWS whose fresh data ends before a day in view says where it ends`() {
        val wednesday = today.plusDays(5)
        val thursday = wednesday.plusDays(1)
        val action = pan(nws, window(wednesday, 15, thursday, 7), nwsRows(140)) { it != thursday }
        assertEquals(HourlyOnDemand.PanAction.NoDataMessage(thursday), action)
    }

    @Test
    fun `NWS with nothing stored for a day in view fetches it`() {
        val wednesday = today.plusDays(5)
        val action = pan(nws, window(wednesday, 15, wednesday.plusDays(1), 7), emptyList()) { false }
        assertEquals(HourlyOnDemand.PanAction.Fetch(wednesday.plusDays(1), 156), action)
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
