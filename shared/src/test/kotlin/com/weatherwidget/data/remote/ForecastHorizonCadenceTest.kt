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
import java.time.ZoneOffset

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

    // --- Pre-wake: LONG before the alarm while charging (performance/261019-pre-alarm-long-fetch-while-charging.md)

    private fun preWake(wakeAt: Long?, lastLong: Long?, charging: Boolean = true, screenOn: Boolean = false, at: Long = now) =
        ForecastHorizonCadence.decide(charging, 85, screenOn, lastLong, lastShortMs = at - 2 * hour, nowMs = at, wakeAtMs = wakeAt)

    @Test
    fun `charging - LONG is due in the lead before the alarm though its interval is not`() {
        val d = preWake(wakeAt = now + 30 * minute, lastLong = now - 9 * hour)
        assertEquals(Horizon.LONG, d.due)
        assertEquals(now + 30 * minute - ForecastHorizonCadence.PRE_WAKE_LEAD_MS, d.preWakeAtMs)
    }

    @Test
    fun `before the lead - not due yet, but the due time is pulled forward to it`() {
        val wake = now + 2 * hour
        val d = preWake(wakeAt = wake, lastLong = now - 9 * hour)
        assertNull(d.due)
        assertEquals(wake - ForecastHorizonCadence.PRE_WAKE_LEAD_MS, d.longDueAtMs)
        assertEquals(wake - ForecastHorizonCadence.PRE_WAKE_LEAD_MS, d.nextDueAtMs)
    }

    @Test
    fun `at or after the alarm the rule is off`() {
        assertNull(preWake(wakeAt = now, lastLong = now - 9 * hour).preWakeAtMs)
        assertNull(preWake(wakeAt = now - minute, lastLong = now - 9 * hour).due)
    }

    @Test
    fun `on battery the rule never applies`() {
        val d = preWake(wakeAt = now + 30 * minute, lastLong = now - 9 * hour, charging = false)
        assertNull(d.preWakeAtMs)
        assertNull(d.due)
    }

    @Test
    fun `an alarm more than 14 h away is not tonight's wake`() {
        val far = now + (ForecastHorizonCadence.PRE_WAKE_MAX_AHEAD_HOURS + 1) * hour
        assertNull(preWake(wakeAt = far, lastLong = now - 9 * hour).preWakeAtMs)
        val near = now + ForecastHorizonCadence.PRE_WAKE_MAX_AHEAD_HOURS * hour
        assertEquals(near - ForecastHorizonCadence.PRE_WAKE_LEAD_MS, preWake(wakeAt = near, lastLong = now - 9 * hour).preWakeAtMs)
    }

    @Test
    fun `a LONG fetch within 3 h before the alarm already serves it - a snooze never fetches twice`() {
        val wake = now + 30 * minute
        assertNull(preWake(wakeAt = wake, lastLong = wake - 3 * hour).due)
        assertEquals(Horizon.LONG, preWake(wakeAt = wake, lastLong = wake - 3 * hour - minute).due)
        // Fetched at 06:00 for a 06:30 alarm, snoozed to 06:39: not due again.
        val fetched = now - 10 * minute
        val snoozed = preWake(wakeAt = now + 29 * minute, lastLong = fetched)
        assertNull(snoozed.preWakeAtMs)
        assertNull(snoozed.due)
    }

    @Test
    fun `no alarm - every table decision is unchanged`() {
        for (charging in listOf(true, false)) for (level in listOf(40, 69, 70, 85)) for (screenOn in listOf(true, false))
            for (age in listOf(null, 1L, 5L, 9L, 25L)) {
                val last = age?.let { now - it * hour }
                val plain = ForecastHorizonCadence.decide(charging, level, screenOn, last, last, now)
                val noAlarm = ForecastHorizonCadence.decide(charging, level, screenOn, last, last, now, wakeAtMs = null)
                assertEquals(plain, noAlarm)
                assertNull(noAlarm.preWakeAtMs)
            }
    }

    @Test
    fun `log fragment names the pre-wake time`() {
        val wake = now + 30 * minute
        val d = preWake(wakeAt = wake, lastLong = now - 9 * hour)
        val hhmm = java.time.Instant.ofEpochMilli(wake - ForecastHorizonCadence.PRE_WAKE_LEAD_MS).atZone(ZoneOffset.UTC).toLocalTime()
        assertEquals("NWS:LONG(long=24h/9h,short=skip(preWake)/2h,preWake=$hhmm)",
            ForecastHorizonCadence.logFragment("NWS", d, now, zone = ZoneOffset.UTC))
    }

    /**
     * A week of "charge 22:30–06:50, alarm 06:30", checked every 5 min. With the screen on at glances
     * (morning, lunch, evening) the pre-wake fetch re-phases a LONG, it does not add one. With the screen
     * on all 16 waking hours, starting the day's 8 h LONG chain 45 min earlier fits a third before bed:
     * at most one more per day, never more.
     */
    private var lastShortCounts: List<Int> = emptyList()

    @Test
    fun `steady state - an alarm re-phases LONG at glances, at most one more with the screen always on`() {
        val allDay: (Long) -> Boolean = { tod -> tod in (6 * hour + 30 * minute) until (22 * hour + 30 * minute) }
        val glances: (Long) -> Boolean = { tod ->
            tod in (6 * hour + 30 * minute) until (7 * hour + 30 * minute) || tod in (12 * hour) until (13 * hour) ||
                tod in (19 * hour) until (22 * hour + 30 * minute)
        }
        fun longFetchesPerDay(withAlarm: Boolean, screen: (Long) -> Boolean = allDay): List<Int> {
            val day = 24 * hour
            val start = 0L
            var lastLong: Long? = null
            var lastShort: Long? = null
            val perDay = IntArray(8)
            val shortPerDay = IntArray(8)
            var t = start
            while (t < start + 8 * day) {
                val tod = t % day
                val charging = tod >= 22 * hour + 30 * minute || tod < 6 * hour + 50 * minute
                val screenOn = screen(tod)
                val nextAlarm = (t / day) * day + 6 * hour + 30 * minute + (if (tod >= 6 * hour + 30 * minute) day else 0)
                val d = ForecastHorizonCadence.decide(charging, 85, screenOn, lastLong, lastShort, t,
                    wakeAtMs = nextAlarm.takeIf { withAlarm })
                when (d.due) {
                    Horizon.LONG -> { lastLong = t; lastShort = t; perDay[(t / day).toInt()]++ }
                    Horizon.SHORT -> { lastShort = t; shortPerDay[(t / day).toInt()]++ }
                    null -> {}
                }
                t += 5 * minute
            }
            lastShortCounts = shortPerDay.drop(1)
            return perDay.drop(1) // day 0 starts from nothing
        }
        val glancesWith = longFetchesPerDay(true, glances)
        val shortWith = lastShortCounts
        val glancesWithout = longFetchesPerDay(false, glances)
        val shortWithout = lastShortCounts
        println("LONG fetches/day, glances: with alarm=$glancesWith without=$glancesWithout")
        println("SHORT fetches/day, glances: with alarm=$shortWith without=$shortWithout")
        assertEquals(glancesWithout, glancesWith)
        // The overnight SHORT the pre-wake LONG would overwrite is skipped: fewer fetches in all.
        assertTrue("short with=$shortWith without=$shortWithout", shortWith.sum() < shortWithout.sum())
        val with = longFetchesPerDay(withAlarm = true)
        val without = longFetchesPerDay(withAlarm = false)
        println("LONG fetches/day, screen always on: with alarm=$with without=$without")
        with.zip(without).forEach { (w, wo) -> assertTrue("with=$with without=$without", w <= wo + 1) }
    }

    @Test
    fun `timed check - 40 min before the alarm, now inside the lead, none when passed or far`() {
        val lead = ForecastHorizonCadence.PRE_WAKE_LEAD_MS
        val slack = ForecastHorizonCadence.PRE_WAKE_CHECK_SLACK_MS
        assertEquals(now + 8 * hour - lead + slack, ForecastHorizonCadence.preWakeCheckAtMs(now + 8 * hour, now))
        assertEquals(now, ForecastHorizonCadence.preWakeCheckAtMs(now + 20 * minute, now))
        assertNull(ForecastHorizonCadence.preWakeCheckAtMs(now, now))
        assertNull(ForecastHorizonCadence.preWakeCheckAtMs(now + 15 * hour, now))
        assertNull(ForecastHorizonCadence.preWakeCheckAtMs(null, now))
        // The check lands where decide() says LONG is due.
        val wake = now + 8 * hour
        val checkAt = ForecastHorizonCadence.preWakeCheckAtMs(wake, now)!!
        assertEquals(Horizon.LONG, preWake(wakeAt = wake, lastLong = now - 9 * hour, at = checkAt).due)
    }

    @Test
    fun `charging, screen off - SHORT is skipped while the pre-wake comes within 8 h`() {
        val longAgo = now - 20 * hour
        // SHORT 9 h old would be due; the pre-wake LONG is 6 h away: skipped.
        val d = ForecastHorizonCadence.decide(true, 85, false, lastLongMs = longAgo, lastShortMs = now - 9 * hour, nowMs = now,
            wakeAtMs = now + 6 * hour)
        assertTrue(d.shortSkippedForPreWake)
        assertNull(d.shortDueAtMs)
        assertNull(d.due)
        // Pre-wake more than 8 h away: SHORT as usual.
        val far = ForecastHorizonCadence.decide(true, 85, false, longAgo, now - 9 * hour, now, wakeAtMs = now + 9 * hour)
        assertEquals(Horizon.SHORT, far.due)
        // Screen on (awake at 2 am): screen-on rules, SHORT due.
        val awake = ForecastHorizonCadence.decide(true, 85, true, now - 7 * hour, now - 5 * hour, now, wakeAtMs = now + 6 * hour)
        assertEquals(Horizon.SHORT, awake.due)
        // A LONG due by its own interval still runs.
        val longDue = ForecastHorizonCadence.decide(true, 85, false, now - 25 * hour, now - 9 * hour, now, wakeAtMs = now + 6 * hour)
        assertEquals(Horizon.LONG, longDue.due)
        // On battery (no pre-wake): unchanged.
        assertEquals(Horizon.SHORT, ForecastHorizonCadence.decide(false, 85, false, longAgo, now - 9 * hour, now, wakeAtMs = now + 6 * hour).due)
    }
}
