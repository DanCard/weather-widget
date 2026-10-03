package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
class CurrentTempFetchPolicyTest {

    @Test
    fun `charging always allows fetch regardless of screen state`() {
        assertTrue(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = true,
                isScreenInteractive = true,
                isOpportunisticContext = false,
                batteryLevel = 10,
            ),
        )
        assertTrue(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = true,
                isScreenInteractive = false,
                isOpportunisticContext = false,
                batteryLevel = 10,
            ),
        )
    }

    @Test
    fun `manual triggers always bypass policy`() {
        assertTrue(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = false,
                isScreenInteractive = false,
                isOpportunisticContext = false,
                batteryLevel = 10,
                isManual = true,
            ),
        )
    }

    @Test
    fun `battery mode fetches opportunistically only above 65 percent`() {
        assertTrue(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = false,
                isScreenInteractive = false,
                isOpportunisticContext = true,
                batteryLevel = 66,
            ),
        )
        assertFalse(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = false,
                isScreenInteractive = false,
                isOpportunisticContext = false,
                batteryLevel = 100,
            ),
        )
        assertFalse(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = false,
                isScreenInteractive = false,
                isOpportunisticContext = true,
                batteryLevel = 65,
            ),
        )
    }

    @Test
    fun `opportunistic job schedule uses exclusive 65 percent cutoff`() {
        assertTrue(CurrentTempFetchPolicy.shouldScheduleOpportunisticJob(batteryLevel = 66))
        assertFalse(CurrentTempFetchPolicy.shouldScheduleOpportunisticJob(batteryLevel = 65))
        assertFalse(CurrentTempFetchPolicy.shouldScheduleOpportunisticJob(batteryLevel = 0))
        assertFalse(CurrentTempFetchPolicy.shouldScheduleOpportunisticJob(batteryLevel = -1))
    }

    @Test
    fun `opportunistic job is gated at 65 percent while charging too`() {
        assertFalse(CurrentTempFetchPolicy.shouldScheduleOpportunisticJob(batteryLevel = 65))
        assertFalse(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = true,
                isScreenInteractive = true,
                isOpportunisticContext = true,
                batteryLevel = 65,
            ),
        )
    }

    @Test
    fun `battery opportunistic fetch targets primary while charging remains unrestricted`() {
        assertEquals(
            "NWS",
            CurrentTempFetchPolicy.opportunisticTargetSourceId(
                isCharging = false,
                primarySourceId = "NWS",
            ),
        )
        assertEquals(
            null,
            CurrentTempFetchPolicy.opportunisticTargetSourceId(
                isCharging = true,
                primarySourceId = "NWS",
            ),
        )
    }

    @Test
    fun `opportunistic interval is 45 minutes`() {
        assertEquals(45L, CurrentTempFetchPolicy.OPPORTUNISTIC_INTERVAL_MINUTES)
    }

    @Test
    fun `loop interval table - charging 10 or 16, battery screen-on at 70 or more 20, else none`() {
        assertEquals(10L, CurrentTempFetchPolicy.loopIntervalMinutes(isCharging = true, isScreenInteractive = true, batteryLevel = 5))
        assertEquals(16L, CurrentTempFetchPolicy.loopIntervalMinutes(isCharging = true, isScreenInteractive = false, batteryLevel = 5))
        assertEquals(20L, CurrentTempFetchPolicy.loopIntervalMinutes(isCharging = false, isScreenInteractive = true, batteryLevel = 70))
        assertEquals(20L, CurrentTempFetchPolicy.loopIntervalMinutes(isCharging = false, isScreenInteractive = true, batteryLevel = 100))
        assertNull(CurrentTempFetchPolicy.loopIntervalMinutes(isCharging = false, isScreenInteractive = true, batteryLevel = 69))
        assertNull(CurrentTempFetchPolicy.loopIntervalMinutes(isCharging = false, isScreenInteractive = false, batteryLevel = 100))
    }

    /**
     * The three loop gates used to each collapse to `isCharging`; a disagreement between them is a
     * run that gets scheduled and then policy-blocked on arrival. Pin them to one table.
     */
    @Test
    fun `schedule, post-run and fetch gates agree with the loop interval in every state`() {
        for (charging in listOf(true, false)) {
            for (screenOn in listOf(true, false)) {
                for (level in listOf(0, 65, 66, 69, 70, 71, 100)) {
                    val allowed = CurrentTempFetchPolicy.loopIntervalMinutes(charging, screenOn, level) != null
                    val label = "charging=$charging screenOn=$screenOn level=$level"
                    assertEquals(label, allowed, CurrentTempFetchPolicy.shouldScheduleChargingLoop(charging, screenOn, level))
                    assertEquals(
                        label,
                        if (allowed) CurrentTempFetchPolicy.PostRunLoopAction.SCHEDULE_NEXT else CurrentTempFetchPolicy.PostRunLoopAction.NO_RESCHEDULE,
                        CurrentTempFetchPolicy.postRunLoopAction(charging, screenOn, level),
                    )
                    assertEquals(
                        label,
                        allowed,
                        CurrentTempFetchPolicy.shouldFetchNow(
                            isCharging = charging,
                            isScreenInteractive = screenOn,
                            isOpportunisticContext = false,
                            batteryLevel = level,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `loop reason names the cadence that ran`() {
        assertEquals("charging_loop", CurrentTempFetchPolicy.loopReason(isCharging = true, overdue = false))
        assertEquals("charging_loop_overdue", CurrentTempFetchPolicy.loopReason(isCharging = true, overdue = true))
        assertEquals("battery_screen_on_loop", CurrentTempFetchPolicy.loopReason(isCharging = false, overdue = false))
        assertEquals("battery_screen_on_loop_overdue", CurrentTempFetchPolicy.loopReason(isCharging = false, overdue = true))
    }

    @Test
    fun `screen-on catch-up fetches now when the last fetch is 20 minutes old or never`() {
        val now = 10_000_000_000L
        val min = 60_000L
        assertEquals(
            CurrentTempFetchPolicy.ScreenOnCatchUp.FETCH_NOW,
            CurrentTempFetchPolicy.screenOnCatchUp(isCharging = false, batteryLevel = 70, lastFetchMs = now - 20 * min, nowMs = now),
        )
        assertEquals(
            CurrentTempFetchPolicy.ScreenOnCatchUp.FETCH_NOW,
            CurrentTempFetchPolicy.screenOnCatchUp(isCharging = false, batteryLevel = 96, lastFetchMs = 0L, nowMs = now),
        )
        assertEquals(
            CurrentTempFetchPolicy.ScreenOnCatchUp.SCHEDULE,
            CurrentTempFetchPolicy.screenOnCatchUp(isCharging = false, batteryLevel = 96, lastFetchMs = now - 19 * min, nowMs = now),
        )
    }

    @Test
    fun `screen-on catch-up does nothing while charging or below 70 percent`() {
        val now = 10_000_000_000L
        assertEquals(
            CurrentTempFetchPolicy.ScreenOnCatchUp.NONE,
            CurrentTempFetchPolicy.screenOnCatchUp(isCharging = true, batteryLevel = 100, lastFetchMs = 0L, nowMs = now),
        )
        assertEquals(
            CurrentTempFetchPolicy.ScreenOnCatchUp.NONE,
            CurrentTempFetchPolicy.screenOnCatchUp(isCharging = false, batteryLevel = 69, lastFetchMs = 0L, nowMs = now),
        )
    }

    @Test
    fun `screen-on first delay is the remainder of the interval, at least 1 minute`() {
        val now = 10_000_000_000L
        val min = 60_000L
        assertEquals(15L, CurrentTempFetchPolicy.screenOnFirstDelayMinutes(lastFetchMs = now - 5 * min, nowMs = now))
        assertEquals(1L, CurrentTempFetchPolicy.screenOnFirstDelayMinutes(lastFetchMs = now - 19 * min - 50_000L, nowMs = now))
        assertEquals(20L, CurrentTempFetchPolicy.screenOnFirstDelayMinutes(lastFetchMs = now, nowMs = now))
    }

    @Test
    fun `charging interval is 10 minutes when screen is on`() {
        assertEquals(10L, CurrentTempFetchPolicy.chargingIntervalMinutes(isScreenInteractive = true))
    }

    @Test
    fun `charging interval is 16 minutes when screen is off`() {
        assertEquals(16L, CurrentTempFetchPolicy.chargingIntervalMinutes(isScreenInteractive = false))
    }

    @Test
    fun `post-run loop does not reschedule when the loop is not allowed (never cancels concurrent fetch)`() {
        // Regression guard: the worker must NOT cancel WORK_NAME_CURRENT_TEMP, since an
        // opportunistic fetch can be running under that same unique name. The loop instead dies by
        // not rescheduling. PostRunLoopAction has no CANCEL value precisely to make this impossible
        // to reintroduce through this path.
        assertEquals(
            CurrentTempFetchPolicy.PostRunLoopAction.NO_RESCHEDULE,
            CurrentTempFetchPolicy.postRunLoopAction(isCharging = false, isScreenInteractive = true, batteryLevel = 69),
        )
        assertEquals(
            CurrentTempFetchPolicy.PostRunLoopAction.NO_RESCHEDULE,
            CurrentTempFetchPolicy.postRunLoopAction(isCharging = false, isScreenInteractive = false, batteryLevel = 100),
        )
    }

    @Test
    fun `post-run repaint skipped when policy blocked the fetch`() {
        assertTrue(
            CurrentTempFetchPolicy.shouldSkipPostRunRepaint(
                policyBlocked = true,
                fetchFailed = false,
                attemptedSourceCount = 0,
            ),
        )
    }

    @Test
    fun `post-run repaint skipped when zero sources attempted (freshness skip or all throttled)`() {
        assertTrue(
            CurrentTempFetchPolicy.shouldSkipPostRunRepaint(
                policyBlocked = false,
                fetchFailed = false,
                attemptedSourceCount = 0,
            ),
        )
    }

    @Test
    fun `post-run repaint runs after a real fetch attempt`() {
        assertFalse(
            CurrentTempFetchPolicy.shouldSkipPostRunRepaint(
                policyBlocked = false,
                fetchFailed = false,
                attemptedSourceCount = 2,
            ),
        )
    }

    @Test
    fun `post-run repaint runs after a failed fetch so error indicators update`() {
        assertFalse(
            CurrentTempFetchPolicy.shouldSkipPostRunRepaint(
                policyBlocked = false,
                fetchFailed = true,
                attemptedSourceCount = 0,
            ),
        )
    }
}
