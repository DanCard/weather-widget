package com.weatherwidget.data.repository

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
 * NWS backfill self-heal a past day whose daily_extremes row exists but was computed from a
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
        // Absent days are handled by the daily_extremes row-presence check, not this function.
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
}
