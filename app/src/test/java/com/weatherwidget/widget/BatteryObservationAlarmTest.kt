package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class BatteryObservationAlarmTest {
    private val now = 1_000_000L

    @Test
    fun `arms when nothing is pending`() {
        assertTrue(BatteryObservationAlarm.shouldReplace(existingTriggerMs = null, desiredTriggerMs = now + 20, nowMs = now))
    }

    @Test
    fun `keeps an earlier pending alarm`() {
        assertFalse(BatteryObservationAlarm.shouldReplace(existingTriggerMs = now + 5, desiredTriggerMs = now + 20, nowMs = now))
        assertFalse(BatteryObservationAlarm.shouldReplace(existingTriggerMs = now + 20, desiredTriggerMs = now + 20, nowMs = now))
    }

    @Test
    fun `moves a later pending alarm earlier`() {
        assertTrue(BatteryObservationAlarm.shouldReplace(existingTriggerMs = now + 30, desiredTriggerMs = now + 20, nowMs = now))
    }

    @Test
    fun `replaces an alarm whose time has passed`() {
        assertTrue(BatteryObservationAlarm.shouldReplace(existingTriggerMs = now - 1, desiredTriggerMs = now + 20, nowMs = now))
    }

    @Test
    fun `window is centred on the nominal time and never opens in the past`() {
        val min = 60_000L
        assertEquals(now + 15 * min, BatteryObservationAlarm.windowStartMs(triggerAtMs = now + 20 * min, nowMs = now))
        assertEquals(now, BatteryObservationAlarm.windowStartMs(triggerAtMs = now + 2 * min, nowMs = now))
        assertEquals(10 * min, BatteryObservationAlarm.WINDOW_MS)
    }
}
