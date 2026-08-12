package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class BatteryTierTest {

    @Test
    fun `treatAsCharging is true while actually charging regardless of level`() {
        assertTrue(BatteryTier.treatAsCharging(isCharging = true, batteryLevel = 5))
    }

    @Test
    fun `treatAsCharging is true at or above the threshold even unplugged`() {
        assertTrue(BatteryTier.treatAsCharging(isCharging = false, batteryLevel = BatteryTier.TREAT_AS_CHARGING_THRESHOLD))
        assertTrue(BatteryTier.treatAsCharging(isCharging = false, batteryLevel = 100))
    }

    @Test
    fun `treatAsCharging is false below the threshold when unplugged`() {
        assertFalse(BatteryTier.treatAsCharging(isCharging = false, batteryLevel = BatteryTier.TREAT_AS_CHARGING_THRESHOLD - 1))
    }

    @Test
    fun `computeFetchInterval uses charging interval while charging`() {
        assertEquals(30L, BatteryTier.computeFetchInterval(isCharging = true, batteryLevel = 5, chargingIntervalMinutes = 30L))
    }

    @Test
    fun `computeFetchInterval returns high tier above 70 percent`() {
        assertEquals(BatteryTier.INTERVAL_HIGH_MINUTES, BatteryTier.computeFetchInterval(false, 75, 30L))
    }

    @Test
    fun `computeFetchInterval returns medium tier between 50 and 70 percent`() {
        assertEquals(BatteryTier.INTERVAL_MEDIUM_MINUTES, BatteryTier.computeFetchInterval(false, 60, 30L))
    }

    @Test
    fun `computeFetchInterval returns null at or below 50 percent`() {
        assertNull(BatteryTier.computeFetchInterval(false, 50, 30L))
        assertNull(BatteryTier.computeFetchInterval(false, 30, 30L))
    }
}
