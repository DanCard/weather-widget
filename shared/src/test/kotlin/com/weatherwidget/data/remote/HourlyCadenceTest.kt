package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
class HourlyCadenceTest {
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val now = 10_000 * hour

    private fun other(charging: Boolean, battery: Int, viewAgeMs: Long?) =
        HourlyCadence.intervalHours(charging, battery, isDisplayed = false, viewAgeMs = viewAgeMs)

    @Test
    fun `displayed source is 4 h charging, 8 h above 50 percent, 24 h at or below`() {
        assertEquals(4, HourlyCadence.intervalHours(true, 30, true, null))
        assertEquals(8, HourlyCadence.intervalHours(false, 79, true, null))
        assertEquals(8, HourlyCadence.intervalHours(false, 51, true, null))
        assertEquals(24, HourlyCadence.intervalHours(false, 50, true, null))
        assertEquals(24, HourlyCadence.intervalHours(false, 5, true, null))
    }

    @Test
    fun `80 percent unplugged counts as charging`() {
        assertEquals(4, HourlyCadence.intervalHours(false, 80, true, null))
        assertEquals(4, other(false, 80, 2 * hour))
    }

    @Test
    fun `other source on the charger steps with view age at each boundary`() {
        assertEquals(4, other(true, 90, 0))
        assertEquals(4, other(true, 90, 24 * hour))
        assertEquals(6, other(true, 90, 24 * hour + minute))
        assertEquals(6, other(true, 90, 36 * hour))
        assertEquals(8, other(true, 90, 36 * hour + minute))
        assertEquals(8, other(true, 90, 48 * hour))
        assertEquals(12, other(true, 90, 48 * hour + minute))
        assertEquals(12, other(true, 90, 72 * hour))
        assertNull(other(true, 90, 72 * hour + minute))
    }

    @Test
    fun `other source on battery above 50 percent only if viewed within 24 h`() {
        assertEquals(8, other(false, 60, 24 * hour))
        assertNull(other(false, 60, 24 * hour + minute))
        assertEquals(8, other(false, 51, hour))
    }

    @Test
    fun `other source at or below 50 percent is never fetched`() {
        assertNull(other(false, 50, 0))
        assertNull(other(false, 10, hour))
    }

    @Test
    fun `never viewed other source is not fetched, except during grace`() {
        assertNull(other(true, 90, HourlyCadence.viewAgeMs(null, inGrace = false, nowMs = now)))
        assertEquals(12, other(true, 90, HourlyCadence.viewAgeMs(null, inGrace = true, nowMs = now)))
        assertNull(other(false, 60, HourlyCadence.viewAgeMs(null, inGrace = true, nowMs = now)))
    }

    @Test
    fun `a real view wins over grace`() {
        assertEquals(2 * hour, HourlyCadence.viewAgeMs(now - 2 * hour, inGrace = true, nowMs = now))
    }

    @Test
    fun `grace lasts the first 3 days of tracking`() {
        val today = LocalDate.of(2026, 10, 11)
        assertTrue(HourlyCadence.inGrace(null, today))
        assertTrue(HourlyCadence.inGrace(today.minusDays(2), today))
        assertFalse(HourlyCadence.inGrace(today.minusDays(3), today))
    }

    @Test
    fun `due once the interval has passed, with two minutes of slack`() {
        assertTrue(HourlyCadence.isDue(null, 4, now))
        assertTrue(HourlyCadence.isDue(now - 4 * hour, 4, now))
        assertTrue(HourlyCadence.isDue(now - 4 * hour + minute, 4, now))
        assertFalse(HourlyCadence.isDue(now - 4 * hour + 3 * minute, 4, now))
        assertFalse(HourlyCadence.isDue(null, null, now))
    }

    @Test
    fun `decide ties the pieces together`() {
        val d = HourlyCadence.decide(true, 90, false, lastViewedAtMs = now - 30 * hour, inGrace = false, lastHourlyFetchMs = now - 7 * hour, nowMs = now)
        assertEquals(6, d.intervalHours)
        assertTrue(d.due)
        assertEquals("NWS:6h(view=30h,lastHourly=7h,due=true)", HourlyCadence.logFragment("NWS", d.intervalHours, d.viewAgeMs, d.lastHourlyFetchMs, now, d.due))
    }
}
