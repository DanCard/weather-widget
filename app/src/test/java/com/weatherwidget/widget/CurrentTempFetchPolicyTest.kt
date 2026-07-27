package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                isScreenInteractive = true,
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
    fun `charging loop runs whenever charging regardless of screen state`() {
        assertTrue(CurrentTempFetchPolicy.shouldScheduleChargingLoop(isCharging = true, isScreenInteractive = true))
        assertTrue(CurrentTempFetchPolicy.shouldScheduleChargingLoop(isCharging = true, isScreenInteractive = false))
        assertFalse(CurrentTempFetchPolicy.shouldScheduleChargingLoop(isCharging = false, isScreenInteractive = true))
        assertFalse(CurrentTempFetchPolicy.shouldScheduleChargingLoop(isCharging = false, isScreenInteractive = false))
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
    fun `post-run loop schedules next heartbeat while charging`() {
        assertEquals(
            CurrentTempFetchPolicy.PostRunLoopAction.SCHEDULE_NEXT,
            CurrentTempFetchPolicy.postRunLoopAction(isCharging = true, isScreenInteractive = true),
        )
        assertEquals(
            CurrentTempFetchPolicy.PostRunLoopAction.SCHEDULE_NEXT,
            CurrentTempFetchPolicy.postRunLoopAction(isCharging = true, isScreenInteractive = false),
        )
    }

    @Test
    fun `post-run loop does not reschedule on battery (never cancels concurrent fetch)`() {
        // Regression guard: on battery the worker must NOT cancel WORK_NAME_CURRENT_TEMP, since an
        // opportunistic fetch can be running under that same unique name. The loop instead dies by
        // not rescheduling. PostRunLoopAction has no CANCEL value precisely to make this impossible
        // to reintroduce through this path.
        assertEquals(
            CurrentTempFetchPolicy.PostRunLoopAction.NO_RESCHEDULE,
            CurrentTempFetchPolicy.postRunLoopAction(isCharging = false, isScreenInteractive = true),
        )
        assertEquals(
            CurrentTempFetchPolicy.PostRunLoopAction.NO_RESCHEDULE,
            CurrentTempFetchPolicy.postRunLoopAction(isCharging = false, isScreenInteractive = false),
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
