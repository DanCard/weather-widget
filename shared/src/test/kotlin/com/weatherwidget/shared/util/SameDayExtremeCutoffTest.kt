package com.weatherwidget.shared.util

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
class SameDayExtremeCutoffTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 4)

    private fun at(h: Int, m: Int = 0): Long =
        LocalDateTime.of(today, java.time.LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `same-day low is never frozen`() {
        for (time in listOf(at(5, 59), at(6, 0), at(12, 0), at(23, 59))) {
            val filtered = SameDayExtremeCutoff.filter(today, 80f, 55f, time, zone)
            assertEquals(55f, filtered.lowTemp)
        }
    }

    @Test
    fun `same-day high is kept before 4 pm and dropped at 4 pm`() {
        val before = SameDayExtremeCutoff.filter(today, 80f, 55f, at(15, 59), zone)
        assertEquals(80f, before.highTemp)
        assertFalse(before.frozeHigh)

        val atCutoff = SameDayExtremeCutoff.filter(today, 80f, 55f, at(16, 0), zone)
        assertNull(atCutoff.highTemp)
        assertTrue(atCutoff.frozeHigh)
    }

    @Test
    fun `after 6 am both sides still update`() {
        val filtered = SameDayExtremeCutoff.filter(today, 82f, 56f, at(7, 0), zone)
        assertEquals(82f, filtered.highTemp)
        assertEquals(56f, filtered.lowTemp)
        assertFalse(filtered.frozeHigh)
    }

    @Test
    fun `after 4 pm only the high is frozen`() {
        val filtered = SameDayExtremeCutoff.filter(today, 82f, 56f, at(16, 30), zone)
        assertNull(filtered.highTemp)
        assertEquals(56f, filtered.lowTemp)
        assertTrue(filtered.frozeHigh)
    }

    @Test
    fun `tomorrow's row is never gated`() {
        val tomorrow = today.plusDays(1)
        val filtered = SameDayExtremeCutoff.filter(tomorrow, 80f, 55f, at(17, 0), zone)
        assertEquals(80f, filtered.highTemp)
        assertEquals(55f, filtered.lowTemp)
        assertFalse(filtered.frozeHigh)
    }

    @Test
    fun `yesterday's row is left to the writers' date filter`() {
        val yesterday = today.minusDays(1)
        val filtered = SameDayExtremeCutoff.filter(yesterday, 80f, 55f, at(17, 0), zone)
        assertEquals(80f, filtered.highTemp)
        assertEquals(55f, filtered.lowTemp)
        assertFalse(filtered.frozeHigh)
    }

    @Test
    fun `cutoffs use the local wall clock in the given zone`() {
        // 23:30 UTC is 16:30 PDT the same calendar day → high frozen.
        val nowMs = java.time.Instant.parse("2026-10-04T23:30:00Z").toEpochMilli()
        val filtered = SameDayExtremeCutoff.filter(today, 80f, 55f, nowMs, zone)
        assertNull(filtered.highTemp)
        assertEquals(55f, filtered.lowTemp)
    }

    @Test
    fun `freezing reports only fields that had a value`() {
        val withHigh = SameDayExtremeCutoff.filter(today, 80f, null, at(17, 0), zone)
        assertTrue(withHigh.frozeHigh)
        val noHigh = SameDayExtremeCutoff.filter(today, null, 55f, at(17, 0), zone)
        assertFalse(noHigh.frozeHigh)
    }

    // ---- today's low when a fetch sends none (plans/261010-google-daily-low-filed-under-the-morning-it-ends.md) ----

    @Test
    fun `today keeps a stored low fetched within a day`() {
        val now = 1_000_000_000_000L
        assertEquals(58.2f, SameDayExtremeCutoff.keptTodayLow(true, 58.2f, now - 23 * 3_600_000L, now))
    }

    @Test
    fun `an older stored low, another day, or no stored low keeps nothing`() {
        val now = 1_000_000_000_000L
        org.junit.Assert.assertNull(SameDayExtremeCutoff.keptTodayLow(true, 58.2f, now - 25 * 3_600_000L, now))
        org.junit.Assert.assertNull(SameDayExtremeCutoff.keptTodayLow(false, 58.2f, now, now))
        org.junit.Assert.assertNull(SameDayExtremeCutoff.keptTodayLow(true, null, now, now))
        org.junit.Assert.assertNull(SameDayExtremeCutoff.keptTodayLow(true, 58.2f, null, now))
    }
}
