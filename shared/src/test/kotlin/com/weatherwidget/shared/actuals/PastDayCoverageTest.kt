package com.weatherwidget.shared.actuals

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Unit tests for [pastDayLacksAfternoonCoverage], the coverage check that lets the background
 * NWS backfill self-heal a past day whose daily_history row exists but was computed from a
 * partial (overnight-only) slice of observations — the emulator bug where 2026-05-31 showed a
 * 54° high because the device was off from 05:20 onward.
 */
@Category(ShortDuration::class)
class PastDayCoverageTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")
    private val today: LocalDate = LocalDate.of(2026, 6, 1)

    private fun ts(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `past day with only overnight observations lacks afternoon coverage`() {
        val date = LocalDate.of(2026, 5, 31)
        val overnightOnly = listOf(
            ts("2026-05-31T00:10"),
            ts("2026-05-31T02:40"),
            ts("2026-05-31T05:20"),
        )

        assertTrue(pastDayLacksAfternoonCoverage(overnightOnly, date, zone, today, daytimeHour = 14))
    }

    @Test
    fun `past day covering the afternoon is complete`() {
        val date = LocalDate.of(2026, 5, 31)
        val fullDay = listOf(
            ts("2026-05-31T00:10"),
            ts("2026-05-31T08:00"),
            ts("2026-05-31T14:30"),
            ts("2026-05-31T23:50"),
        )

        assertFalse(pastDayLacksAfternoonCoverage(fullDay, date, zone, today, daytimeHour = 14))
    }

    @Test
    fun `observation exactly at the daytime cutoff counts as covered`() {
        val date = LocalDate.of(2026, 5, 31)
        val reachesCutoff = listOf(ts("2026-05-31T06:00"), ts("2026-05-31T14:00"))

        assertFalse(pastDayLacksAfternoonCoverage(reachesCutoff, date, zone, today, daytimeHour = 14))
    }

    @Test
    fun `day with no observations is not flagged here`() {
        // Absent days are handled by the daily_history row-presence check, not this function.
        val date = LocalDate.of(2026, 5, 31)

        assertFalse(pastDayLacksAfternoonCoverage(emptyList(), date, zone, today, daytimeHour = 14))
    }

    @Test
    fun `today is never flagged even with only overnight coverage`() {
        val partialToday = listOf(ts("2026-06-01T00:10"), ts("2026-06-01T05:20"))

        assertFalse(pastDayLacksAfternoonCoverage(partialToday, today, zone, today, daytimeHour = 14))
    }

    @Test
    fun `future day is never flagged`() {
        val future = LocalDate.of(2026, 6, 2)
        val obs = listOf(ts("2026-06-02T00:10"))

        assertFalse(pastDayLacksAfternoonCoverage(obs, future, zone, today, daytimeHour = 14))
    }

    // --- dayHasOfficialGap ---

    private val hourlyDay = (0..23).map { ts("2026-05-31T%02d:00".format(it)) }

    @Test
    fun `hourly official readings leave no gap`() {
        assertFalse(dayHasOfficialGap(hourlyDay, LocalDate.of(2026, 5, 31), zone, ts("2026-06-01T10:00")))
    }

    @Test
    fun `a mid-day hole is a gap`() {
        val holed = hourlyDay.filterNot { it in ts("2026-05-31T09:00")..ts("2026-05-31T12:00") }

        assertTrue(dayHasOfficialGap(holed, LocalDate.of(2026, 5, 31), zone, ts("2026-06-01T10:00")))
    }

    @Test
    fun `the edges count - a late first reading or an early last one is a gap`() {
        val date = LocalDate.of(2026, 5, 31)
        val now = ts("2026-06-01T10:00")

        assertTrue(dayHasOfficialGap(hourlyDay.filter { it >= ts("2026-05-31T03:30") }, date, zone, now))
        assertTrue(dayHasOfficialGap(hourlyDay.filter { it <= ts("2026-05-31T20:30") }, date, zone, now))
    }

    @Test
    fun `today is judged only up to now`() {
        val obs = listOf(ts("2026-06-01T00:10"), ts("2026-06-01T01:20"), ts("2026-06-01T02:30"))

        assertFalse(dayHasOfficialGap(obs, today, zone, ts("2026-06-01T03:45")))
        assertTrue(dayHasOfficialGap(obs, today, zone, ts("2026-06-01T03:46")))
    }

    @Test
    fun `no readings is one long hole once the span passes 75 min`() {
        assertFalse(dayHasOfficialGap(emptyList(), today, zone, ts("2026-06-01T01:15")))
        assertTrue(dayHasOfficialGap(emptyList(), today, zone, ts("2026-06-01T01:16")))
    }

    @Test
    fun `one late hourly METAR is not a gap`() {
        val metars = (0..9).map { ts("2026-06-01T%02d:53".format(it)) }
            .map { if (it == ts("2026-06-01T05:53")) ts("2026-06-01T06:03") else it }

        assertFalse(dayHasOfficialGap(listOf(ts("2026-06-01T00:00")) + metars, today, zone, ts("2026-06-01T10:00")))
    }

    @Test
    fun `stray wake readings do not hide a night asleep`() {
        // Desktop, 2026-10-27: asleep 00:40-06:55; the wake fetch stored KNUQ 03:15 and KSJC 05:53,
        // leaving holes of 2 h 40 m and 2 h 38 m that the old 3 h limit passed.
        val obs = listOf("00:00", "00:15", "00:35", "03:15", "05:53", "05:55", "06:35", "06:40", "06:53")
            .map { ts("2026-06-01T$it") }

        assertTrue(dayHasOfficialGap(obs, today, zone, ts("2026-06-01T06:55")))
    }
}
