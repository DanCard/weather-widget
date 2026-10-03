package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class BatteryObservationAlarmTest {
    private val now = 10_000_000L
    private val min = 60_000L

    @Test
    fun `arms when nothing is pending`() {
        assertTrue(BatteryObservationAlarm.shouldReplace(existingLatestMs = null, desiredLatestMs = now + 25 * min, nowMs = now))
    }

    @Test
    fun `keeps a pending alarm that lands no later than requested`() {
        assertFalse(BatteryObservationAlarm.shouldReplace(existingLatestMs = now + 10 * min, desiredLatestMs = now + 25 * min, nowMs = now))
        assertFalse(BatteryObservationAlarm.shouldReplace(existingLatestMs = now + 25 * min, desiredLatestMs = now + 25 * min, nowMs = now))
    }

    @Test
    fun `moves a later pending alarm earlier`() {
        assertTrue(BatteryObservationAlarm.shouldReplace(existingLatestMs = now + 30 * min, desiredLatestMs = now + 25 * min, nowMs = now))
    }

    @Test
    fun `replaces an alarm whose window has closed`() {
        assertTrue(BatteryObservationAlarm.shouldReplace(existingLatestMs = now - 1, desiredLatestMs = now + 25 * min, nowMs = now))
    }

    // Pixel 2026-10-03: alarm nominal 15:58, window to 16:03. A heartbeat at 16:00:30 — past the
    // trigger, inside the window — replaced it with a 16:20 alarm. It must be kept.
    @Test
    fun `keeps an alarm whose trigger has passed but whose window is still open`() {
        val armedAt = now
        val first = BatteryObservationAlarm.timing(nominalAtMs = armedAt + 8 * min, nowMs = armedAt)
        val heartbeatAt = armedAt + 10 * min + 30_000L // past first.triggerMs, before first.latestMs
        assertTrue(heartbeatAt > first.triggerMs && heartbeatAt < first.latestMs)
        val requested = BatteryObservationAlarm.timing(nominalAtMs = heartbeatAt + 20 * min, nowMs = heartbeatAt)

        assertFalse(BatteryObservationAlarm.shouldReplace(first.latestMs, requested.latestMs, heartbeatAt))
    }

    @Test
    fun `a 20-minute loop is delivered between about 14 and 25 minutes out`() {
        val t = BatteryObservationAlarm.timing(nominalAtMs = now + 20 * min, nowMs = now)

        assertEquals(now + 25 * min, t.latestMs)
        // trigger + 75% of its delay = 25 min  =>  trigger = 25 / 1.75 = 14.29 min
        assertEquals(25 * min / 1.75, (t.triggerMs - now).toDouble(), 1.0)
        assertEquals(t.latestMs.toDouble(), t.triggerMs + BatteryObservationAlarm.SYSTEM_WINDOW_FRACTION * (t.triggerMs - now), 5.0)
    }

    @Test
    fun `timing never lands in the past`() {
        val t = BatteryObservationAlarm.timing(nominalAtMs = now - 30 * min, nowMs = now)

        assertEquals(now, t.triggerMs)
        assertEquals(now, t.latestMs)
    }
}
