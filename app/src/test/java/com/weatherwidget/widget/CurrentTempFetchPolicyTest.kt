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
            ),
        )
        assertTrue(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = true,
                isScreenInteractive = false,
                isOpportunisticContext = false,
            ),
        )
        assertTrue(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = true,
                isScreenInteractive = false,
                isOpportunisticContext = true,
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
                isManual = true,
            ),
        )
    }

    @Test
    fun `battery mode only fetches in opportunistic contexts`() {
        assertTrue(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = false,
                isScreenInteractive = false,
                isOpportunisticContext = true,
            ),
        )
        assertFalse(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = false,
                isScreenInteractive = true,
                isOpportunisticContext = false,
            ),
        )
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
