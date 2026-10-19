package com.weatherwidget.shared.util

import com.weatherwidget.shared.util.UnplugRoutine.DayType
import com.weatherwidget.shared.util.UnplugRoutine.Unplug
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

/** performance/261019-learned-unplug-time-and-overnight-short-skip.md */
@Category(ShortDuration::class)
class UnplugRoutineTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    private fun unplug(date: LocalDate, h: Int, m: Int, sessionHours: Int = 8) =
        Unplug(ms(date.atTime(h, m)), sessionHours * 60, exact = false)

    // Mon 2026-10-19 … ; weekdays of the two weeks before.
    private val monday = LocalDate.of(2026, 10, 19)
    private fun weekdaysBefore(days: Int) =
        (1..days).map { monday.minusDays(it.toLong()) }.filter { UnplugRoutine.dayType(it) == DayType.WEEKDAY }

    @Test
    fun `a weekday routine predicts tomorrow's unplug at its 25th percentile`() {
        val minutes = listOf(40, 35, 50, 42, 38, 45, 44, 41, 39, 47)
        val unplugs = weekdaysBefore(14).zip(minutes).map { (d, m) -> unplug(d, 6, m) }
        val now = ms(monday.atTime(22, 0))
        val r = UnplugRoutine.routine(unplugs, DayType.WEEKDAY, now, zone)
        assertTrue(r.usable)
        assertEquals(10, r.samples)
        // Sorted 35 38 39 40 41 42 44 45 47 50: P25 (nearest rank 3) = 39, P75 (rank 8) = 45.
        assertEquals(6 * 60 + 39, r.minuteOfDay)
        assertEquals(6, r.spreadMinutes)
        assertEquals(ms(monday.plusDays(1).atTime(6, 39)), UnplugRoutine.predictNextMs(unplugs, now, zone))
    }

    @Test
    fun `fewer than 4 samples is no routine`() {
        val unplugs = weekdaysBefore(4).take(3).map { unplug(it, 6, 40) }
        assertFalse(UnplugRoutine.routine(unplugs, DayType.WEEKDAY, ms(monday.atTime(22, 0)), zone).usable)
        assertNull(UnplugRoutine.predictNextMs(unplugs, ms(monday.atTime(22, 0)), zone))
    }

    @Test
    fun `a scattered spread is no routine`() {
        val unplugs = weekdaysBefore(14).zip(listOf(5, 6, 7, 8, 9, 10, 11, 12)).map { (d, h) -> unplug(d, h, 0) }
        val r = UnplugRoutine.routine(unplugs, DayType.WEEKDAY, ms(monday.atTime(22, 0)), zone)
        assertTrue(r.samples >= 4)
        assertFalse(r.usable)
    }

    @Test
    fun `short charges and old unplugs do not count`() {
        val topUps = weekdaysBefore(14).map { unplug(it, 6, 40, sessionHours = 2) }
        assertEquals(0, UnplugRoutine.routine(topUps, DayType.WEEKDAY, ms(monday.atTime(22, 0)), zone).samples)
        val old = (25..40).map { monday.minusDays(it.toLong()) }.map { unplug(it, 6, 40) }
        assertEquals(0, UnplugRoutine.routine(old, DayType.WEEKDAY, ms(monday.atTime(22, 0)), zone).samples)
    }

    @Test
    fun `Friday night predicts Saturday from the weekend routine, not the weekday one`() {
        val friday = LocalDate.of(2026, 10, 23)
        val weekdays = weekdaysBefore(14).map { unplug(it, 6, 40) }
        val now = ms(friday.atTime(22, 0))
        assertNull("no weekend routine", UnplugRoutine.predictNextMs(weekdays, now, zone))
        val weekends = (1..21).map { friday.minusDays(it.toLong()) }.filter { UnplugRoutine.dayType(it) == DayType.WEEKEND }
            .map { unplug(it, 8, 30) }
        assertEquals(ms(friday.plusDays(1).atTime(8, 30)), UnplugRoutine.predictNextMs(weekdays + weekends, now, zone))
    }

    @Test
    fun `predictions use local time across a DST change`() {
        // US DST ends Sun 2026-11-01. Weekdays before it at 06:40 PDT predict 06:40 PST after it.
        val mon = LocalDate.of(2026, 11, 2)
        val unplugs = (1..14).map { mon.minusDays(it.toLong()) }.filter { UnplugRoutine.dayType(it) == DayType.WEEKDAY }
            .map { unplug(it, 6, 40) }
        val now = ms(LocalDate.of(2026, 11, 1).atTime(22, 0))
        assertEquals(ms(mon.atTime(6, 40)), UnplugRoutine.predictNextMs(unplugs, now, zone))
    }

    @Test
    fun `a prediction more than 14 h ahead is not tonight's`() {
        val unplugs = weekdaysBefore(14).map { unplug(it, 6, 40) }
        // Monday 10:00: today's 06:40 has passed, tomorrow's is 20 h away.
        assertNull(UnplugRoutine.predictNextMs(unplugs, ms(monday.atTime(10, 0)), zone))
        // Monday 17:00: 13 h 40 m.
        assertEquals(ms(monday.plusDays(1).atTime(6, 40)), UnplugRoutine.predictNextMs(unplugs, ms(monday.atTime(17, 0)), zone))
    }

    @Test
    fun `log line`() {
        val unplugs = weekdaysBefore(14).map { unplug(it, 6, 40) }
        assertEquals("weekday=06:40±0m n=10 weekend=none n=0", UnplugRoutine.logLine(unplugs, ms(monday.atTime(22, 0)), zone))
    }

    @Test
    fun `a long daytime charge on a desk's USB is not an overnight unplug`() {
        val mornings = weekdaysBefore(14).map { unplug(it, 6, 40) }
        // Plugged 09:00–17:30 at a desk every weekday: 8.5 h, but not through 03:00.
        val desk = weekdaysBefore(14).map { Unplug(ms(it.atTime(17, 30)), 8 * 60 + 30, exact = true) }
        assertFalse(UnplugRoutine.isOvernight(desk.first(), zone))
        assertTrue(UnplugRoutine.isOvernight(mornings.first(), zone))
        val now = ms(monday.atTime(22, 0))
        val r = UnplugRoutine.routine(mornings + desk, DayType.WEEKDAY, now, zone)
        assertEquals(10, r.samples)
        assertEquals(ms(monday.plusDays(1).atTime(6, 40)), UnplugRoutine.predictNextMs(mornings + desk, now, zone))
    }

    @Test
    fun `a charge from 01 00 to 07 00 counts, one from 03 30 does not`() {
        val day = monday.minusDays(1)
        assertTrue(UnplugRoutine.isOvernight(Unplug(ms(day.atTime(7, 0)), 6 * 60, exact = true), zone))
        assertFalse(UnplugRoutine.isOvernight(Unplug(ms(day.atTime(8, 0)), (4.5 * 60).toInt(), exact = true), zone))
    }
}
