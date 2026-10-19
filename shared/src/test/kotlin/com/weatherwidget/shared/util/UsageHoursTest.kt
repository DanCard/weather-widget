package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/** performance/261019-android-usage-hours-slow-fetching-when-unused.md (phase 1) */
@Category(ShortDuration::class)
class UsageHoursTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val hour = 3_600_000L

    // Monday 2026-10-19.
    private fun at(day: Int, h: Int, m: Int = 0) =
        LocalDateTime.of(2026, 10, 19, 0, 0).plusDays(day.toLong()).withHour(h).withMinute(m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `the night prior - 00 to 05 unused from day one, other hours used`() {
        val s = UsageHours.initial()
        assertTrue(UsageHours.isUnused(s, at(0, 2), zone))
        assertFalse("04:00 and 05:00: 05:00 is a day hour", UsageHours.isUnused(s, at(0, 4), zone))
        assertFalse(UsageHours.isUnused(s, at(0, 14), zone))
        assertEquals("weekday=05-23 weekend=05-23 weeks=0", UsageHours.hoursLogLine(s))
    }

    @Test
    fun `slots are local hours of the week across DST`() {
        val monday0 = LocalDateTime.of(2026, 11, 2, 0, 0).atZone(zone).toInstant().toEpochMilli() / hour
        assertEquals(0, UsageHours.slot(monday0, zone))
        val sunday2pdt = LocalDateTime.of(2026, 10, 25, 23, 0).atZone(zone).toInstant().toEpochMilli() / hour
        assertEquals(6 * 24 + 23, UsageHours.slot(sunday2pdt, zone))
    }

    /** Weeks of a user who is up 07:00–23:00, plus [extra] use at an hour; returns the state. */
    private fun weeks(n: Int, extraHour: Int? = null): UsageHours.State {
        var s = UsageHours.initial()
        for (d in 0 until 7 * n) for (h in 0 until 24) {
            val t = at(d, h, 30)
            s = if (h in 7..22 || h == extraHour) UsageHours.sight(s, t, zone) else UsageHours.advance(s, t, zone)
        }
        return UsageHours.advance(s, at(7 * n, 0, 30), zone)
    }

    @Test
    fun `a week of use at 02 00 lifts it above unused - a shift worker's nights fill in`() {
        val s = weeks(1, extraHour = 2)
        assertFalse(UsageHours.isUnused(s, at(7, 2), zone))
        // 0.05 → after one used Monday 02:00: 0.05·0.75 + 0.25 = 0.2875.
        assertEquals(0.2875, s.p[2], 1e-9)
    }

    @Test
    fun `a 07-23 user's 23 00 to 07 00 decays to unused, and USAGE_HOURS reports the day`() {
        val s = weeks(4)
        assertTrue(UsageHours.isUnused(s, at(28, 1), zone))
        assertTrue("23:00 learned unused too", s.p[23] < UsageHours.UNUSED_BELOW + 0.1)
        assertEquals("weekday=07-22 weekend=07-22 weeks=4", UsageHours.hoursLogLine(s))
    }

    @Test
    fun `a sighting in a predicted-unused hour is a miss in the day's check`() {
        var s = UsageHours.initial()
        s = UsageHours.advance(s, at(0, 0, 10), zone)
        s = UsageHours.sight(s, at(0, 3, 15), zone) // 03:00 is predicted unused
        assertNull("no completed day yet", UsageHours.checkLogLine(s))
        s = UsageHours.advance(s, at(1, 0, 10), zone)
        assertEquals("day=2026-10-19 predictedUnusedHours=5 misses=1 missHours=03", UsageHours.checkLogLine(s))
    }

    @Test
    fun `a device off for days counts those hours unused once each`() {
        var s = UsageHours.sight(UsageHours.initial(), at(0, 9), zone)
        s = UsageHours.advance(s, at(30, 9), zone)
        assertEquals("each slot updated once", UsageHours.SLOTS, s.hoursRolled)
    }

    @Test
    fun `encode round-trips`() {
        val s = weeks(1, extraHour = 2)
        assertEquals(s, UsageHours.decode(UsageHours.encode(s)))
        assertNull(UsageHours.decode("junk"))
    }

    @Test
    fun `ranges`() {
        assertEquals("00-02,05,07-08", UsageHours.ranges(listOf(0, 1, 2, 5, 7, 8)))
        assertEquals("", UsageHours.ranges(emptyList()))
    }
}
