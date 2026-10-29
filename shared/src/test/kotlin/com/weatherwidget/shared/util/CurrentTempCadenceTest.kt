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

    private fun interval(charging: Boolean, screenOn: Boolean, battery: Int, armed: Boolean = false) =
        CurrentTempCadence.intervalMinutes(charging, screenOn, battery, armed)

    @Test
    fun `charging is 10 min screen on and hourly screen off at any battery`() {
        assertEquals(10L, interval(true, true, 5))
        assertEquals(60L, interval(true, false, 5))
        assertEquals(60L, interval(true, false, 100))
    }

    @Test
    fun `battery at 70 percent or more is 20 min screen on and none screen off`() {
        assertEquals(20L, interval(false, true, 70))
        assertNull(interval(false, false, 70))
        assertNull(interval(false, false, 100))
    }

    @Test
    fun `an armed dominant-station watch keeps the earlier screen-off pace`() {
        // plans/261028-screen-off-cadence-and-due-time-wakes.md
        assertEquals(30L, interval(true, false, 50, armed = true))
        assertEquals(45L, interval(false, false, 70, armed = true))
        assertNull(interval(false, false, 69, armed = true))
        assertEquals("screen on is unchanged", 10L, interval(true, true, 50, armed = true))
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
        assertNull(CurrentTempCadence.loopIntervalMinutes(false, false, 90, watchArmed = false))
        assertTrue(CurrentTempCadence.opportunisticAllowed(false, 66, screenOn = true, watchArmed = false))
        assertFalse(CurrentTempCadence.opportunisticAllowed(false, 69, screenOn = false, watchArmed = true))
        assertFalse(CurrentTempCadence.opportunisticAllowed(false, 70, screenOn = false, watchArmed = false))
        assertTrue(CurrentTempCadence.opportunisticAllowed(false, 70, screenOn = false, watchArmed = true))
        assertFalse(CurrentTempCadence.opportunisticAllowed(false, 65, screenOn = true, watchArmed = false))
    }

    @Test
    fun `on a charger the opportunistic band never fetches - the loop covers it`() {
        for (screenOn in listOf(true, false)) for (armed in listOf(true, false)) {
            assertFalse(CurrentTempCadence.opportunisticAllowed(true, 90, screenOn, armed))
        }
    }

    @Test
    fun `screen-on catch-up on battery at 70 percent fetches once 20 min have passed`() {
        assertEquals(ScreenOnCatchUp.FETCH_NOW, CurrentTempCadence.screenOnCatchUp(false, 70, 0L, now))
        assertEquals(ScreenOnCatchUp.FETCH_NOW, CurrentTempCadence.screenOnCatchUp(false, 80, now - 20 * minute, now))
        assertEquals(ScreenOnCatchUp.SCHEDULE, CurrentTempCadence.screenOnCatchUp(false, 80, now - 19 * minute, now))
        assertEquals(ScreenOnCatchUp.NONE, CurrentTempCadence.screenOnCatchUp(false, 69, 0L, now))
    }

    @Test
    fun `screen-on catch-up on a charger fetches once 15 min have passed, never schedules`() {
        assertEquals(ScreenOnCatchUp.FETCH_NOW, CurrentTempCadence.screenOnCatchUp(true, 90, 0L, now))
        assertEquals(ScreenOnCatchUp.FETCH_NOW, CurrentTempCadence.screenOnCatchUp(true, 30, now - 15 * minute, now))
        assertEquals(ScreenOnCatchUp.NONE, CurrentTempCadence.screenOnCatchUp(true, 90, now - 14 * minute, now))
    }

    @Test
    fun `first delay after a recent fetch is the rest of the 20 min, at least 1`() {
        assertEquals(15L, CurrentTempCadence.screenOnFirstDelayMinutes(now - 5 * minute, now))
        assertEquals(1L, CurrentTempCadence.screenOnFirstDelayMinutes(now - 20 * minute + 1, now))
    }
}
