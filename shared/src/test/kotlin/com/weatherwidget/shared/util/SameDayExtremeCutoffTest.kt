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

    private val hour = 3_600_000L
    private val sixHours = 6 * hour
    private val lowCutoff get() = SameDayExtremeCutoff.cutoffMs(today, SameDayExtremeCutoff.LOW_CUTOFF, zone)
    private val highCutoff get() = SameDayExtremeCutoff.cutoffMs(today, SameDayExtremeCutoff.HIGH_CUTOFF, zone)

    private fun prior(value: Float?, fetchedAt: Long, lastConfirmedAt: Long = fetchedAt) =
        SameDayExtremeCutoff.Prior(value, fetchedAt, lastConfirmedAt)

    private fun settle(priors: List<SameDayExtremeCutoff.Prior>, sent: Float?, cutoff: Long, now: Long, maxAge: Long = sixHours) =
        SameDayExtremeCutoff.settle(priors, sent, cutoff, now, maxAge).also { println("settle sent=$sent -> $it") }

    @Test
    fun `before the cutoff every fetch writes`() {
        val s = settle(listOf(prior(55f, at(5, 0))), 52f, lowCutoff, at(5, 59))
        assertEquals(52f, s.value)
        assertFalse(s.held)
    }

    @Test
    fun `a fresh pre-cutoff low is held against a later hindcast`() {
        val s = settle(listOf(prior(55f, at(5, 30))), 49f, lowCutoff, at(9, 0))
        assertEquals(55f, s.value)
        assertTrue(s.held)
        assertEquals(SameDayExtremeCutoff.Reason.FRESH_PRIOR, s.reason)
    }

    @Test
    fun `the high is held after 4 pm the same way`() {
        val s = settle(listOf(prior(80f, at(15, 0))), 84f, highCutoff, at(16, 30))
        assertEquals(80f, s.value)
        assertTrue(s.held)
    }

    /** Emulator, 2026-11-06: Meteo today low 57 from a fetch about 50 h before 06:00; hourly said 46.6. */
    @Test
    fun `a stale prior counts as missing - the first fetch after the cutoff writes, later ones do not move it`() {
        val stale = prior(57f, lowCutoff - 50 * hour)
        val first = settle(listOf(stale), 46.6f, lowCutoff, at(8, 0))
        assertEquals(46.6f, first.value)
        assertFalse(first.held)
        assertEquals(SameDayExtremeCutoff.Reason.THIS_FETCH_FIRST, first.reason)

        val later = settle(listOf(stale, prior(46.6f, at(8, 0))), 44f, lowCutoff, at(11, 0))
        assertEquals(46.6f, later.value)
        assertTrue(later.held)
        assertEquals(SameDayExtremeCutoff.Reason.FIRST_AFTER_CUTOFF, later.reason)
    }

    @Test
    fun `no prior at all accepts this fetch`() {
        val s = settle(emptyList(), 50f, lowCutoff, at(12, 0))
        assertEquals(50f, s.value)
        assertFalse(s.held)
        assertNull(s.priorAgeMs)
    }

    @Test
    fun `only a stale prior and a fetch that sends nothing keeps the stale prior`() {
        assertEquals(57f, settle(listOf(prior(57f, lowCutoff - 50 * hour)), null, lowCutoff, at(8, 0)).value)
    }

    @Test
    fun `a pre-cutoff row confirmed close to the cutoff is fresh`() {
        // Fetched 22:00 yesterday (8 h before), re-confirmed unchanged at 05:30.
        val s = settle(listOf(prior(55f, lowCutoff - 8 * hour, lastConfirmedAt = at(5, 30))), 49f, lowCutoff, at(9, 0))
        assertEquals(55f, s.value)
        assertTrue(s.held)
    }

    @Test
    fun `a row re-confirmed after the cutoff counts as confirmed at the cutoff`() {
        val s = settle(listOf(prior(55f, lowCutoff - 8 * hour, lastConfirmedAt = at(10, 0))), 49f, lowCutoff, at(11, 0))
        assertEquals(55f, s.value)
    }

    @Test
    fun `the guard off writes every fetch`() {
        val s = settle(listOf(prior(55f, at(5, 30))), 49f, lowCutoff, at(9, 0), maxAge = 0)
        assertEquals(49f, s.value)
        assertFalse(s.held)
    }

    @Test
    fun `cutoffs use the local wall clock in the given zone`() {
        // 23:30 UTC is 16:30 PDT the same calendar day -> past the high cutoff.
        val nowMs = java.time.Instant.parse("2026-10-04T23:30:00Z").toEpochMilli()
        assertTrue(settle(listOf(prior(80f, at(15, 0))), 84f, highCutoff, nowMs).held)
        assertEquals(at(16, 0), highCutoff)
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
