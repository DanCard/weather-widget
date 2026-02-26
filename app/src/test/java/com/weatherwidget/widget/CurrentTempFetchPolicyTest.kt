package com.weatherwidget.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTempFetchPolicyTest {

    @Test
    fun `charging allows fetch if interactive or opportunistic`() {
        // Interactive + non-opportunistic (10-min loop) = OK
        assertTrue(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = true,
                isScreenInteractive = true,
                isOpportunisticContext = false,
            ),
        )
        // Non-interactive + opportunistic (30-min system job) = OK (Relaxed)
        assertTrue(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = true,
                isScreenInteractive = false,
                isOpportunisticContext = true,
            ),
        )
        // Non-interactive + non-opportunistic (10-min loop) = FAIL
        assertFalse(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = true,
                isScreenInteractive = false,
                isOpportunisticContext = false,
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
    fun `charging loop only runs while charging and interactive`() {
        assertTrue(CurrentTempFetchPolicy.shouldScheduleChargingLoop(isCharging = true, isScreenInteractive = true))
        assertFalse(CurrentTempFetchPolicy.shouldScheduleChargingLoop(isCharging = true, isScreenInteractive = false))
        assertFalse(CurrentTempFetchPolicy.shouldScheduleChargingLoop(isCharging = false, isScreenInteractive = true))
    }
}
