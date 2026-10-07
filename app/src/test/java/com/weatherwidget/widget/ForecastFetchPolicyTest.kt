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

    // Charger values from ForecastCadence (user, 2026-10-07): displayed 4 h / 6 h, others 8 h / 12 h
    // (screen on / off). Was 60 / 120 and 360 / 480; the displayed source's hourly refetch spent a
    // provider's per-project daily quota by 03:16.

    @Test
    fun `charging + screen on + displayed source is 4 hours`() {
        assertEquals(240L, ForecastFetchPolicy.intervalMinutes(true, isScreenInteractive = true, isActiveSource = true, batteryLevel = 100))
    }

    @Test
    fun `charging + screen off + displayed source is 6 hours`() {
        assertEquals(360L, ForecastFetchPolicy.intervalMinutes(true, isScreenInteractive = false, isActiveSource = true, batteryLevel = 100))
    }

    @Test
    fun `charging + screen on + other source is 8 hours`() {
        assertEquals(480L, ForecastFetchPolicy.intervalMinutes(true, isScreenInteractive = true, isActiveSource = false, batteryLevel = 100))
    }

    @Test
    fun `charging + screen off + other source is 12 hours`() {
        assertEquals(720L, ForecastFetchPolicy.intervalMinutes(true, isScreenInteractive = false, isActiveSource = false, batteryLevel = 100))
    }

    @Test
    fun `off-charger at 80 percent uses the charger values`() {
        assertEquals(240L, ForecastFetchPolicy.intervalMinutes(false, isScreenInteractive = true, isActiveSource = true, batteryLevel = 80))
        assertEquals(480L, ForecastFetchPolicy.intervalMinutes(false, isScreenInteractive = true, isActiveSource = false, batteryLevel = 80))
        assertEquals(720L, ForecastFetchPolicy.intervalMinutes(false, isScreenInteractive = false, isActiveSource = false, batteryLevel = 80))
    }

    @Test
    fun `off-charger 70 to 79 percent delegates to BatteryFetchStrategy 240 minutes`() {
        val interval = ForecastFetchPolicy.intervalMinutes(
            isCharging = false,
            isScreenInteractive = true,
            isActiveSource = true,
            batteryLevel = 75,
        )
        assertEquals(240L, interval)
    }

    @Test
    fun `off-charger 50 to 70 percent delegates to BatteryFetchStrategy 480 minutes`() {
        val interval = ForecastFetchPolicy.intervalMinutes(
            isCharging = false,
            isScreenInteractive = true,
            isActiveSource = true,
            batteryLevel = 60,
        )
        assertEquals(480L, interval)
    }

    @Test
    fun `off-charger below 50 percent returns null - no scheduled fetch`() {
        val interval = ForecastFetchPolicy.intervalMinutes(
            isCharging = false,
            isScreenInteractive = true,
            isActiveSource = true,
            batteryLevel = 30,
        )
        assertNull(interval)
    }

    @Test
    fun `off-charger ignores the screen distinction when battery is below 80 percent`() {
        // Screen state no longer matters off-charger, but active-vs-non-active does (see below).
        val a = ForecastFetchPolicy.intervalMinutes(false, isScreenInteractive = true, isActiveSource = true, batteryLevel = 75)
        val b = ForecastFetchPolicy.intervalMinutes(false, isScreenInteractive = false, isActiveSource = true, batteryLevel = 75)
        assertEquals(a, b)
    }

    @Test
    fun `off-charger non-active source doubles the battery-tier interval`() {
        // 240-tier -> 480 for a background (not currently-displayed) source.
        assertEquals(
            240L * com.weatherwidget.shared.util.ForecastCadence.OFF_CHARGER_OTHER_MULTIPLIER,
            ForecastFetchPolicy.intervalMinutes(false, isScreenInteractive = true, isActiveSource = false, batteryLevel = 75),
        )
        // 480-tier -> 960.
        assertEquals(
            480L * com.weatherwidget.shared.util.ForecastCadence.OFF_CHARGER_OTHER_MULTIPLIER,
            ForecastFetchPolicy.intervalMinutes(false, isScreenInteractive = true, isActiveSource = false, batteryLevel = 60),
        )
    }

    @Test
    fun `off-charger active source keeps the plain battery-tier interval`() {
        assertEquals(240L, ForecastFetchPolicy.intervalMinutes(false, isScreenInteractive = true, isActiveSource = true, batteryLevel = 75))
        assertEquals(480L, ForecastFetchPolicy.intervalMinutes(false, isScreenInteractive = true, isActiveSource = true, batteryLevel = 60))
    }

    @Test
    fun `off-charger below 50 percent returns null even for a non-active source`() {
        // The battery tier suppresses the fetch entirely; the multiplier must not resurrect it.
        assertNull(ForecastFetchPolicy.intervalMinutes(false, isScreenInteractive = true, isActiveSource = false, batteryLevel = 30))
    }

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
    fun `periodicTickMinutes off-charger below 80 percent matches BatteryFetchStrategy tiers`() {
        assertEquals(240L, ForecastFetchPolicy.periodicTickMinutes(isCharging = false, batteryLevel = 75))
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
