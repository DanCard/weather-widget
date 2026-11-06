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
    fun `a panel click catches up after 10 min at any power`() {
        val now = 10_000 * MS_PER_MINUTE
        assertEquals(true, DesktopFetchStrategy.shouldCatchUpObservations(now - 10 * MS_PER_MINUTE, now))
        assertEquals(false, DesktopFetchStrategy.shouldCatchUpObservations(now - 5 * MS_PER_MINUTE, now))
        assertEquals(true, DesktopFetchStrategy.shouldCatchUpObservations(null, now))
    }

    /**
     * 2026-11-06: the 08:29 pass read the monitor (DPMS standby) as off and slept 60 min; with the
     * screen back on at 08:36 a re-plan waits only until 10 min after the 08:29 fetch.
     * plans/261106-screen-on-replans-observation-loop.md
     */
    @Test
    fun `observation loop re-plans a screen-off hour down to the rest of 10 min once the screen is on`() {
        val fetch = 10_000 * MS_PER_MINUTE
        val offInterval = DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = true, batteryLevel = 100, screenOn = false)!! / MS_PER_MINUTE
        val onInterval = DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging = true, batteryLevel = 100, screenOn = true)!! / MS_PER_MINUTE
        assertEquals(60 * MS_PER_MINUTE, DesktopFetchStrategy.observationLoopWaitMs(offInterval, fetch, fetch, fetch))
        val wake = fetch + 7 * MS_PER_MINUTE
        assertEquals(3 * MS_PER_MINUTE, DesktopFetchStrategy.observationLoopWaitMs(onInterval, fetch, fetch, wake))
        assertEquals("overdue → now", 0L, DesktopFetchStrategy.observationLoopWaitMs(onInterval, fetch, fetch, fetch + 15 * MS_PER_MINUTE))
        assertNull("power policy: none", DesktopFetchStrategy.observationLoopWaitMs(null, fetch, fetch, wake))
    }

    /**
     * 2026-11-06: fetch at 10:30:31, source toggles until 10:33:44; each loop restart logged
     * waitSec=599, so no fetch ran until 10:43:44. A restart must keep the deadline set by the fetch.
     * plans/261106-loop-restart-keeps-observation-deadline.md
     */
    @Test
    fun `a loop restart keeps the deadline of the last observation fetch`() {
        val restart = 10_000 * MS_PER_MINUTE
        val seed = DesktopFetchStrategy.OBSERVATION_LOOP_FIRST_PASS_SEED_MS
        val wait = DesktopFetchStrategy.observationLoopWaitMs(10, restart - 3 * MS_PER_MINUTE, seed, restart)
        println("restart 3 min after fetch: waitMin=${wait!! / MS_PER_MINUTE.toDouble()}")
        assertEquals("rest of the interval, not a fresh 10 min", 7 * MS_PER_MINUTE, wait)
        assertEquals(
            "overdue at restart → fetch on the first pass",
            0L,
            DesktopFetchStrategy.observationLoopWaitMs(10, restart - 25 * MS_PER_MINUTE, seed, restart),
        )
        assertEquals("never fetched → now", 0L, DesktopFetchStrategy.observationLoopWaitMs(10, null, seed, restart))
    }

    @Test
    fun `a failed or skipped pass counts, so the loop never spins on an old fetch`() {
        val now = 10_000 * MS_PER_MINUTE
        val oldFetch = now - 3 * 60 * MS_PER_MINUTE
        assertEquals(10 * MS_PER_MINUTE, DesktopFetchStrategy.observationLoopWaitMs(10, oldFetch, lastLoopPassMs = now, nowMs = now))
        assertEquals(0L, DesktopFetchStrategy.observationLoopWaitMs(10, null, lastLoopPassMs = 0L, nowMs = now))
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
