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
}
