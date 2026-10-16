package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ForecastFetchPolicyTest {

    // Which horizon is due is ForecastHorizonCadence (`:shared`, ForecastHorizonCadenceTest). This
    // is the periodic tick that asks it.

    @Test
    fun `periodicTickMinutes is 60 while charging regardless of battery`() {
        assertEquals(60L, ForecastFetchPolicy.periodicTickMinutes(isCharging = true, batteryLevel = 5))
        assertEquals(60L, ForecastFetchPolicy.periodicTickMinutes(isCharging = true, batteryLevel = 100))
    }

    @Test
    fun `periodicTickMinutes is 60 when battery is at or above 80 percent even off-charger`() {
        assertEquals(60L, ForecastFetchPolicy.periodicTickMinutes(isCharging = false, batteryLevel = 80))
        assertEquals(60L, ForecastFetchPolicy.periodicTickMinutes(isCharging = false, batteryLevel = 100))
    }

    @Test
    fun `periodicTickMinutes off-charger at 70 to 79 percent is the 4-h SHORT interval`() {
        assertEquals(240L, ForecastFetchPolicy.periodicTickMinutes(isCharging = false, batteryLevel = 75))
        // 70 is high for the horizon cadence; the old battery tier made it 480.
        assertEquals(240L, ForecastFetchPolicy.periodicTickMinutes(isCharging = false, batteryLevel = 70))
    }

    @Test
    fun `periodicTickMinutes off-charger below 70 percent keeps the battery tier`() {
        assertEquals(480L, ForecastFetchPolicy.periodicTickMinutes(isCharging = false, batteryLevel = 60))
    }

    @Test
    fun `periodicTickMinutes off-charger below 50 percent falls back to 24 hours`() {
        // BatteryFetchStrategy returns null; policy provides a safety-net daily tick.
        val tick = ForecastFetchPolicy.periodicTickMinutes(isCharging = false, batteryLevel = 20)
        assertEquals(24 * 60L, tick)
    }

    @Test
    fun `isDue is true when elapsed equals interval`() {
        val intervalMinutes = 60L
        val now = 1_000_000_000L
        val last = now - intervalMinutes * 60_000L
        assertTrue(ForecastFetchPolicy.isDue(last, intervalMinutes, now, graceMs = 0L))
    }

    @Test
    fun `isDue is false well before interval`() {
        val intervalMinutes = 60L
        val now = 1_000_000_000L
        val last = now - 10 * 60_000L
        assertFalse(ForecastFetchPolicy.isDue(last, intervalMinutes, now))
    }

    @Test
    fun `isDue grace window fires up to 2 minutes early`() {
        val intervalMinutes = 60L
        val now = 1_000_000_000L
        // 58 minutes since last — inside the 2-minute grace
        val last = now - 58 * 60_000L
        assertTrue(ForecastFetchPolicy.isDue(last, intervalMinutes, now))
        // 57 minutes — outside the grace
        val lastTooSoon = now - 57 * 60_000L
        assertFalse(ForecastFetchPolicy.isDue(lastTooSoon, intervalMinutes, now))
    }

    @Test
    fun `isDue treats zero last-fetch as overdue under realistic clock`() {
        // Real System.currentTimeMillis() values are in the trillions; lastFetchTimeMs=0
        // means we've never fetched, which should always read as due.
        val nowMs = 1_700_000_000_000L
        assertTrue(ForecastFetchPolicy.isDue(lastFetchTimeMs = 0L, intervalMinutes = 60L, nowMs = nowMs))
    }

    @Test
    fun `nonPrimaryObservationIntervalMinutes returns 30 when charging and screen interactive`() {
        assertEquals(30L, ForecastFetchPolicy.nonPrimaryObservationIntervalMinutes(isCharging = true, isScreenInteractive = true))
        assertNull(ForecastFetchPolicy.nonPrimaryObservationIntervalMinutes(isCharging = true, isScreenInteractive = false))
        assertNull(ForecastFetchPolicy.nonPrimaryObservationIntervalMinutes(isCharging = false, isScreenInteractive = true))
        assertNull(ForecastFetchPolicy.nonPrimaryObservationIntervalMinutes(isCharging = false, isScreenInteractive = false))
    }
}
