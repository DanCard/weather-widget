package com.weatherwidget.data.remote

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ForecastHorizonCadence.Horizon
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

/** The user's table, 2026-10-16 (performance/261016-unified-long-short-forecast-horizons.md). */
@Category(ShortDuration::class)
class ForecastHorizonCadenceTest {
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val now = 1_000 * hour
    private val google = WeatherSource.GOOGLE_WEATHER.id
    private val openMeteo = WeatherSource.OPEN_METEO.id

    private fun decide(level: Int, screenOn: Boolean, lastLong: Long?, lastShort: Long?, charging: Boolean = false) =
        ForecastHorizonCadence.decide(charging, level, screenOn, lastLong, lastShort, now)

    @Test
    fun `every row of the table`() {
        // ≥ 70 %, screen on: LONG 8 h, SHORT 4 h.
        assertEquals(8, ForecastHorizonCadence.longIntervalHours(false, 85, isScreenOn = true))
        assertEquals(4, ForecastHorizonCadence.shortIntervalHours(false, 85, isScreenOn = true))
        // ≥ 70 %, screen off: LONG 24 h, SHORT 8 h.
        assertEquals(24, ForecastHorizonCadence.longIntervalHours(false, 85, isScreenOn = false))
        assertEquals(8, ForecastHorizonCadence.shortIntervalHours(false, 85, isScreenOn = false))
        // < 70 %, either screen state: LONG 24 h, no SHORT.
        for (screenOn in listOf(true, false)) {
            assertEquals(24, ForecastHorizonCadence.longIntervalHours(false, 60, screenOn))
            assertNull(ForecastHorizonCadence.shortIntervalHours(false, 60, screenOn))
        }
    }

    @Test
    fun `70 percent is high, 69 is low`() {
        assertTrue(ForecastHorizonCadence.isHighBattery(false, 70))
        assertFalse(ForecastHorizonCadence.isHighBattery(false, 69))
        assertEquals(4, ForecastHorizonCadence.shortIntervalHours(false, 70, true))
        assertNull(ForecastHorizonCadence.shortIntervalHours(false, 69, true))
    }

    @Test
    fun `charging counts as high whatever the level`() {
        assertTrue(ForecastHorizonCadence.isHighBattery(true, 15))
        assertEquals(8, ForecastHorizonCadence.longIntervalHours(true, 15, true))
        assertEquals(4, ForecastHorizonCadence.shortIntervalHours(true, 15, true))
    }

    @Test
    fun `never fetched is LONG at once`() {
        assertEquals(Horizon.LONG, decide(85, true, null, null).due)
        assertEquals(Horizon.LONG, decide(30, false, null, null).due)
    }

    @Test
    fun `LONG when 8 h old, SHORT when 4 h, nothing sooner (screen on, high)`() {
        assertEquals(Horizon.LONG, decide(85, true, lastLong = now - 8 * hour, lastShort = now - 8 * hour).due)
        assertNull(decide(85, true, lastLong = now - 7 * hour, lastShort = now - 3 * hour).due)
        assertEquals(Horizon.SHORT, decide(85, true, lastLong = now - 6 * hour, lastShort = now - 4 * hour).due)
        assertNull(decide(85, true, lastLong = now - 6 * hour, lastShort = now - 3 * hour).due)
    }

    @Test
    fun `a check two minutes early still counts`() {
        assertEquals(Horizon.SHORT, decide(85, true, now - 6 * hour, now - 4 * hour + 2 * minute).due)
        assertNull(decide(85, true, now - 6 * hour, now - 4 * hour + 3 * minute).due)
    }

    @Test
    fun `a LONG fetch also counts as SHORT`() {
        // The SHORT marker is older, but the LONG fetch 1 h ago restarted SHORT too.
        val d = decide(85, true, lastLong = now - hour, lastShort = now - 10 * hour)
        assertNull(d.due)
        assertEquals(now - hour, d.lastShortMs)
    }

    @Test
    fun `the screen coming on makes a 5-h-old SHORT due`() {
        val lastLong = now - 6 * hour
        val lastShort = now - 5 * hour
        assertNull("screen off: 8 h", decide(85, false, lastLong, lastShort).due)
        assertEquals("screen on: 4 h", Horizon.SHORT, decide(85, true, lastLong, lastShort).due)
    }

    @Test
    fun `low battery only ever fetches LONG, once a day`() {
        assertNull(decide(60, true, lastLong = now - 23 * hour, lastShort = now - 23 * hour).due)
        assertEquals(Horizon.LONG, decide(60, true, lastLong = now - 24 * hour, lastShort = now - hour).due)
        assertNull(decide(60, true, now - hour, now - hour).shortDueAtMs)
    }

    @Test
    fun `next due is the earlier of the two`() {
        val d = decide(85, true, lastLong = now - 2 * hour, lastShort = now - hour)
        assertEquals(now - 2 * hour + 8 * hour - 2 * minute, d.longDueAtMs)
        assertEquals(now - hour + 4 * hour - 2 * minute, d.shortDueAtMs)
        assertEquals(d.shortDueAtMs, d.nextDueAtMs)
        val longFirst = decide(85, true, lastLong = now - 10 * hour, lastShort = now - hour)
        assertEquals(longFirst.longDueAtMs, longFirst.nextDueAtMs)
        val low = decide(60, true, lastLong = now - 10 * hour, lastShort = now - hour)
        assertEquals(low.longDueAtMs, low.nextDueAtMs)
    }

    @Test
    fun `soonest interval is SHORT, else LONG`() {
        assertEquals(4 * hour, ForecastHorizonCadence.soonestIntervalMs(false, 85, true))
        assertEquals(8 * hour, ForecastHorizonCadence.soonestIntervalMs(false, 85, false))
        assertEquals(24 * hour, ForecastHorizonCadence.soonestIntervalMs(false, 60, true))
    }

    @Test
    fun `a forced fetch is LONG for the user's Refresh, else what is due, at least SHORT`() {
        val nothingDue = decide(85, true, now - hour, now - hour)
        assertEquals(Horizon.LONG, ForecastHorizonCadence.forced(nothingDue, userRefresh = true))
        assertEquals(Horizon.SHORT, ForecastHorizonCadence.forced(nothingDue, userRefresh = false))
        assertEquals(Horizon.LONG, ForecastHorizonCadence.forced(decide(85, true, null, null), userRefresh = false))
        assertEquals(Horizon.SHORT, ForecastHorizonCadence.forced(null, userRefresh = false))
    }

    @Test
    fun `Google asks 72 h SHORT and 8 days LONG, keeps them live, snapshots 72 h`() {
        assertEquals(72, ForecastHorizonCadence.askHours(google, Horizon.SHORT))
        assertEquals(192, ForecastHorizonCadence.askHours(google, Horizon.LONG))
        assertEquals(72, ForecastHorizonCadence.storeHours(google, Horizon.SHORT))
        assertEquals(192, ForecastHorizonCadence.storeHours(google, Horizon.LONG))
        assertEquals(72, ForecastHorizonCadence.snapshotHours(google, Horizon.SHORT))
        assertEquals(72, ForecastHorizonCadence.snapshotHours(google, Horizon.LONG))
    }

    @Test
    fun `a LONG fetch keeps each source's download live to 8 days, a SHORT one 72 h`() {
        val expected = mapOf(
            WeatherSource.GOOGLE_WEATHER to 192,
            WeatherSource.OPEN_METEO to 192,
            WeatherSource.SILURIAN to 192,
            WeatherSource.NWS to 156,
            WeatherSource.TOMORROW_IO to 120,
            WeatherSource.OPEN_WEATHER_MAP to 120,
            WeatherSource.WEATHER_API to 72,
        )
        expected.forEach { (source, hours) ->
            assertEquals(source.id, hours, HourlyHorizons.of(source.id).longHours)
            assertEquals(source.id, hours, ForecastHorizonCadence.storeHours(source.id, Horizon.LONG))
            assertEquals(source.id, 72, ForecastHorizonCadence.storeHours(source.id, Horizon.SHORT))
            assertEquals(source.id, 72, ForecastHorizonCadence.snapshotHours(source.id, Horizon.LONG))
        }
        WeatherSource.entries.forEach {
            val h = HourlyHorizons.of(it.id)
            assertTrue(it.id, h.longHours in h.routineHours..h.maxHours)
        }
    }

    @Test
    fun `keep splits live from snapshot only for a LONG routine fetch`() {
        assertEquals(HourlyOnDemand.Keep(192, 72), HourlyOnDemand.keep(google, null, Horizon.LONG))
        assertEquals(HourlyOnDemand.Keep(72, 72), HourlyOnDemand.keep(google, null, Horizon.SHORT))
        assertEquals(HourlyOnDemand.Keep(72, 72), HourlyOnDemand.keep(google, null, null))
        assertEquals(HourlyOnDemand.Keep(192, 72), HourlyOnDemand.keep(openMeteo, null, Horizon.LONG))
        assertEquals(HourlyOnDemand.Keep(156, 72), HourlyOnDemand.keep(WeatherSource.NWS.id, null, Horizon.LONG))
    }

    @Test
    fun `an on-demand fetch keeps its depth both live and snapshot, as before`() {
        assertEquals(HourlyOnDemand.Keep(120, 120), HourlyOnDemand.keep(google, HourlyOnDemand.Request(google, 120), Horizon.LONG))
        assertEquals(HourlyOnDemand.Keep(216, 216), HourlyOnDemand.keep(google, HourlyOnDemand.Request(google, 216)))
        val om = HourlyOnDemand.Request(openMeteo, 96)
        assertEquals(
            HourlyOnDemand.Keep(HourlyOnDemand.REACH_HOURS, HourlyOnDemand.REACH_HOURS),
            HourlyOnDemand.keep(openMeteo, om),
        )
    }

    @Test
    fun `keep until is measured from the current hour`() {
        val now = 1_000L * hour + 25 * 60_000L
        val until = HourlyOnDemand.Keep(192, 72).untilMs(now)
        assertEquals(1_192L * hour, until.liveMs)
        assertEquals(1_072L * hour, until.snapshotMs)
    }

    @Test
    fun `free sources ask their routine reach, which their API ignores`() {
        assertEquals(72, ForecastHorizonCadence.askHours(openMeteo, Horizon.LONG))
        assertEquals(72, ForecastHorizonCadence.askHours(openMeteo, Horizon.SHORT))
    }

    @Test
    fun `a SHORT fetch stores today and the next two days of daily rows, LONG stores all`() {
        val today = LocalDate.of(2026, 10, 16)
        assertTrue(ForecastHorizonCadence.storesDailyDate(Horizon.SHORT, today.minusDays(1), today))
        assertTrue(ForecastHorizonCadence.storesDailyDate(Horizon.SHORT, today, today))
        assertTrue(ForecastHorizonCadence.storesDailyDate(Horizon.SHORT, today.plusDays(2), today))
        assertFalse(ForecastHorizonCadence.storesDailyDate(Horizon.SHORT, today.plusDays(3), today))
        assertTrue(ForecastHorizonCadence.storesDailyDate(Horizon.LONG, today.plusDays(7), today))
        assertTrue("unjudged stores all", ForecastHorizonCadence.storesDailyDate(null, today.plusDays(7), today))
    }

    @Test
    fun `on-demand hours are unchanged by the horizon`() {
        assertEquals(72, HourlyOnDemand.hoursAhead(google, null))
        assertEquals(72, HourlyOnDemand.askHours(google, null, null))
        val request = HourlyOnDemand.Request(google, 120)
        assertEquals(120, HourlyOnDemand.hoursAhead(google, request, Horizon.SHORT))
        assertEquals(120, HourlyOnDemand.askHours(google, request, Horizon.LONG))
        assertEquals(192, HourlyOnDemand.askHours(google, null, Horizon.LONG))
        assertEquals("a LONG fetch keeps its 8 days live", 192, HourlyOnDemand.hoursAhead(google, null, Horizon.LONG))
    }

    @Test
    fun `the marker is valid only at its own site and round trips`() {
        val marker = ForecastHorizonCadence.Marker.at(37.4220, -122.0841, 12345L)
        assertEquals(12345L, marker.forSite(37.4220, -122.0841))
        assertNull(marker.forSite(50.45, 30.52))
        assertEquals(marker, ForecastHorizonCadence.Marker.decode(marker.encode()))
        assertNull(ForecastHorizonCadence.Marker.decode("nope"))
        assertNull(ForecastHorizonCadence.Marker.decode(null))
    }

    @Test
    fun `log fragment names the verdict, both intervals and ages`() {
        val d = decide(85, true, lastLong = now - 5 * hour, lastShort = now - 4 * hour)
        assertEquals("NWS:SHORT(long=8h/5h,short=4h/4h)", ForecastHorizonCadence.logFragment("NWS", d, now))
        val low = decide(60, true, null, null)
        assertEquals("NWS:LONG(long=24h/none,short=off/none,quotaHeldMin=30)",
            ForecastHorizonCadence.logFragment("NWS", low, now, fullyBlockedUntilMs = now + 30 * minute))
    }
}
