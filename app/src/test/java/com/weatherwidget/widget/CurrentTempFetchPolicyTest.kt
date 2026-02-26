package com.weatherwidget.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTempFetchPolicyTest {

    @Test
    fun `charging requires interactive screen for fetch`() {
        assertTrue(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = true,
                isScreenInteractive = true,
                isOpportunisticContext = false,
            ),
        )
        assertFalse(
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = true,
                isScreenInteractive = false,
                isOpportunisticContext = true,
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
