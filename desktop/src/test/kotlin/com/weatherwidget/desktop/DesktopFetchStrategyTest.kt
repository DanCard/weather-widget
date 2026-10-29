package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopFetchStrategyTest {

    companion object {
        private const val MS_PER_MINUTE = 60 * 1000L
    }

    @Test
    fun `getObservationRefreshDelayMs returns 10 min when charging and screen on`() {
        assertEquals(10 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = true, batteryLevel = 20, screenOn = true))
        assertEquals(10 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = true, batteryLevel = 90, screenOn = true))
    }

    @Test
    fun `getObservationRefreshDelayMs is hourly when charging and screen off, 30 min while the watch is armed`() {
        assertEquals(60 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = true, batteryLevel = 20, screenOn = false))
        assertEquals(60 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = true, batteryLevel = 90, screenOn = false))
        assertEquals(30 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = true, batteryLevel = 90, screenOn = false, watchArmed = true))
    }

    @Test
    fun `shouldCatchUpObservations returns true when no prior fetch`() {
        assertEquals(true, DesktopFetchStrategy.shouldCatchUpObservations(lastFetchMs = null, nowMs = 1_000_000L))
    }

    @Test
    fun `shouldCatchUpObservations returns false when fetch is recent`() {
        val now = 1_000_000L
        val recentFetch = now - (5 * MS_PER_MINUTE)
        assertEquals(false, DesktopFetchStrategy.shouldCatchUpObservations(lastFetchMs = recentFetch, nowMs = now))
    }

    @Test
    fun `shouldCatchUpObservations returns true when fetch is older than threshold`() {
        val now = 1_000_000L
        val staleFetch = now - (10 * MS_PER_MINUTE)
        val veryStaleFetch = now - (25 * MS_PER_MINUTE)
        assertEquals(true, DesktopFetchStrategy.shouldCatchUpObservations(lastFetchMs = staleFetch, nowMs = now))
        assertEquals(true, DesktopFetchStrategy.shouldCatchUpObservations(lastFetchMs = veryStaleFetch, nowMs = now))
    }

    @Test
    fun `getObservationRefreshDelayMs on battery at 70 or more with screen off is none, 45 min while the watch is armed`() {
        assertNull(DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 71, screenOn = false))
        assertNull(DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 70, screenOn = false))
        assertEquals(45 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 70, screenOn = false, watchArmed = true))
    }

    @Test
    fun `getObservationRefreshDelayMs returns 20 min on battery at 70 or more with screen on`() {
        assertEquals(20 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 70, screenOn = true))
        assertEquals(20 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 100, screenOn = true))
    }

    @Test
    fun `getObservationRefreshDelayMs - 66 to 69 percent is 45 min with the screen on, none with it off`() {
        assertEquals(45 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 69, screenOn = true))
        assertEquals(45 * MS_PER_MINUTE, DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 66, screenOn = true))
        assertNull(DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 69, screenOn = false))
    }

    @Test
    fun `getObservationRefreshDelayMs returns null at 65 percent or below`() {
        assertNull(DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 65))
        assertNull(DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 51))
        assertNull(DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = false, batteryLevel = 10, screenOn = false))
    }

    @Test
    fun `screen wake catches up on battery at 70 percent after 20 min, a click after 10 min at any power`() {
        val now = 10_000 * MS_PER_MINUTE
        val wake = "screensaver:wake"
        assertEquals(true, DesktopFetchStrategy.shouldCatchUpOnKick(wake, now - 20 * MS_PER_MINUTE, false, 80, now))
        assertEquals(false, DesktopFetchStrategy.shouldCatchUpOnKick(wake, now - 15 * MS_PER_MINUTE, false, 80, now))
        assertEquals("below 70 %", false, DesktopFetchStrategy.shouldCatchUpOnKick(wake, null, false, 69, now))
        // On AC the screen-off loop runs 30 min apart: an unblank fetches once 15 min have passed.
        assertEquals("on AC, never fetched", true, DesktopFetchStrategy.shouldCatchUpOnKick(wake, null, true, 90, now))
        assertEquals("on AC, 15 min old", true, DesktopFetchStrategy.shouldCatchUpOnKick(wake, now - 15 * MS_PER_MINUTE, true, 90, now))
        assertEquals("on AC, 14 min old", false, DesktopFetchStrategy.shouldCatchUpOnKick(wake, now - 14 * MS_PER_MINUTE, true, 90, now))
        assertEquals(true, DesktopFetchStrategy.shouldCatchUpOnKick("genmon:click", now - 10 * MS_PER_MINUTE, true, 20, now))
        assertEquals(false, DesktopFetchStrategy.shouldCatchUpOnKick("genmon:click", now - 5 * MS_PER_MINUTE, true, 20, now))
    }

    // Forecast cadence: ForecastHorizonCadence (`:shared`, ForecastHorizonCadenceTest).

    @Test
    fun `getNonPrimaryObservationDelayMs returns 30 min only when charging and screen on`() {
        assertEquals(30 * MS_PER_MINUTE, DesktopFetchStrategy.getNonPrimaryObservationDelayMs(isCharging = true, screenOn = true))
    }

    @Test
    fun `getNonPrimaryObservationDelayMs skips when on battery`() {
        assertNull(DesktopFetchStrategy.getNonPrimaryObservationDelayMs(isCharging = false, screenOn = true))
    }

    @Test
    fun `getNonPrimaryObservationDelayMs skips when screen off`() {
        assertNull(DesktopFetchStrategy.getNonPrimaryObservationDelayMs(isCharging = true, screenOn = false))
        assertNull(DesktopFetchStrategy.getNonPrimaryObservationDelayMs(isCharging = false, screenOn = false))
    }
}
