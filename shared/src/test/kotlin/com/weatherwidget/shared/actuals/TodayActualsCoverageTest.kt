package com.weatherwidget.shared.actuals

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * [TodayActualsCoverage.dayStartUncovered] — the guard that stops a truncated observation window's
 * minimum being presented as the day's observed low.
 *
 * Spans are taken from the Samsung 2026-08-22 incident: the home site covered 00:00–19:26 (real low
 * 57.03 at 06:47), while the two GPS-excursion sites began at 12:00 and yielded 66.52, the noon
 * reading. See plans/260822-today-low-backfill-then-forecast-fallback.md.
 */
@Category(ShortDuration::class)
class TodayActualsCoverageTest {
    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")
    private val date: LocalDate = LocalDate.of(2026, 8, 22)

    /** Local wall-clock time on [date] as epoch ms. */
    private fun at(hour: Int, minute: Int = 0): Long =
        date.atStartOfDay(zone).plusHours(hour.toLong()).plusMinutes(minute.toLong())
            .toInstant().toEpochMilli()

    private fun uncovered(vararg timestamps: Long) =
        TodayActualsCoverage.dayStartUncovered(timestamps.toList(), date, zone)

    @Test
    fun `rows from midnight cover the day`() {
        assertFalse(uncovered(at(0), at(6, 47), at(12), at(19, 26)))
    }

    @Test
    fun `the excursion site's noon-onward span does not cover the day`() {
        assertTrue(uncovered(at(12), at(16, 40), at(19, 26)))
    }

    @Test
    fun `no observations at all is uncovered`() {
        assertTrue(TodayActualsCoverage.dayStartUncovered(emptyList(), date, zone))
    }

    @Test
    fun `a first row inside the grace window still counts as covered`() {
        assertFalse(uncovered(at(0, 45), at(8)))
    }

    @Test
    fun `a first row past the grace window does not`() {
        assertTrue(uncovered(at(1, 30), at(8)))
    }

    @Test
    fun `exactly at the grace boundary is covered`() {
        assertFalse(uncovered(at(1, 0)))
    }

    @Test
    fun `shortly after midnight a fresh row covers the day`() {
        // Must not demand a long history: at 00:20 the day is 20 minutes old and 00:05 covers it.
        assertFalse(uncovered(at(0, 5), at(0, 20)))
    }

    @Test
    fun `context rows from the previous day do not by themselves cover this day`() {
        // The blend pads its window with prior-day context; that padding must not be mistaken for
        // coverage of today's start.
        val yesterdayEvening = date.minusDays(1).atStartOfDay(zone).plusHours(21)
            .toInstant().toEpochMilli()
        assertTrue(uncovered(yesterdayEvening, at(12)))
    }

    @Test
    fun `context rows from the previous day do not disqualify a covered day either`() {
        val yesterdayEvening = date.minusDays(1).atStartOfDay(zone).plusHours(21)
            .toInstant().toEpochMilli()
        assertFalse(uncovered(yesterdayEvening, at(0, 10), at(6, 47)))
    }

    @Test
    fun `only prior-day rows is uncovered`() {
        val yesterdayEvening = date.minusDays(1).atStartOfDay(zone).plusHours(21)
            .toInstant().toEpochMilli()
        assertTrue(uncovered(yesterdayEvening))
    }

    @Test
    fun `ordering of the input does not matter`() {
        assertFalse(uncovered(at(19, 26), at(0), at(6, 47)))
    }
}
