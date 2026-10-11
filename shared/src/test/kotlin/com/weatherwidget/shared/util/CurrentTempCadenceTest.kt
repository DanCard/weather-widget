package com.weatherwidget.shared.util

import com.weatherwidget.shared.util.CurrentTempCadence.ScreenOnCatchUp
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CurrentTempCadenceTest {
    private val minute = 60_000L
    private val now = 10_000 * minute

    private fun interval(charging: Boolean, screenOn: Boolean, battery: Int) =
        CurrentTempCadence.intervalMinutes(charging, screenOn, battery)

    @Test
    fun `charging is 10 min screen on and 16 min screen off at any battery`() {
        assertEquals(10L, interval(true, true, 5))
        assertEquals(16L, interval(true, false, 5))
        assertEquals(16L, interval(true, false, 100))
    }

    @Test
    fun `battery at 70 percent or more is 20 min screen on and 45 min screen off`() {
        assertEquals(20L, interval(false, true, 70))
        assertEquals(45L, interval(false, false, 70))
        assertEquals(45L, interval(false, false, 100))
    }

    @Test
    fun `battery 66 to 69 percent is 45 min screen on and none screen off`() {
        assertEquals(45L, interval(false, true, 69))
        assertEquals(45L, interval(false, true, 66))
        assertNull(interval(false, false, 69))
        assertNull(interval(false, false, 66))
    }

    @Test
    fun `battery at 65 percent or below is none`() {
        assertNull(interval(false, true, 65))
        assertNull(interval(false, false, 65))
        assertNull(interval(false, true, 10))
    }

    @Test
    fun `the loop bands exclude the 45-min band, which is the opportunistic rule`() {
        assertNull(CurrentTempCadence.loopIntervalMinutes(false, false, 90))
        assertTrue(CurrentTempCadence.opportunisticAllowed(66, screenOn = true))
        assertFalse(CurrentTempCadence.opportunisticAllowed(69, screenOn = false))
        assertTrue(CurrentTempCadence.opportunisticAllowed(70, screenOn = false))
        assertFalse(CurrentTempCadence.opportunisticAllowed(65, screenOn = true))
    }

    @Test
    fun `screen-on catch-up on battery at 70 percent fetches once 20 min have passed`() {
        assertEquals(ScreenOnCatchUp.FETCH_NOW, CurrentTempCadence.screenOnCatchUp(false, 70, 0L, now))
        assertEquals(ScreenOnCatchUp.FETCH_NOW, CurrentTempCadence.screenOnCatchUp(false, 80, now - 20 * minute, now))
        assertEquals(ScreenOnCatchUp.SCHEDULE, CurrentTempCadence.screenOnCatchUp(false, 80, now - 19 * minute, now))
        assertEquals(ScreenOnCatchUp.NONE, CurrentTempCadence.screenOnCatchUp(false, 69, 0L, now))
        assertEquals(ScreenOnCatchUp.NONE, CurrentTempCadence.screenOnCatchUp(true, 90, 0L, now))
    }

    @Test
    fun `first delay after a recent fetch is the rest of the 20 min, at least 1`() {
        assertEquals(15L, CurrentTempCadence.screenOnFirstDelayMinutes(now - 5 * minute, now))
        assertEquals(1L, CurrentTempCadence.screenOnFirstDelayMinutes(now - 20 * minute + 1, now))
    }
}
