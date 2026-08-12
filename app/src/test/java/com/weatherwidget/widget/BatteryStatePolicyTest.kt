package com.weatherwidget.widget

import android.os.BatteryManager
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class BatteryStatePolicyTest {

    @Test
    fun `100 percent battery counts as charging even when unplugged`() {
        assertTrue(
            BatteryStatePolicy.isEffectivelyCharging(
                status = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                plugged = 0,
                batteryLevel = 100,
            ),
        )
    }

    @Test
    fun `unplugged non-full battery remains not charging`() {
        assertFalse(
            BatteryStatePolicy.isEffectivelyCharging(
                status = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                plugged = 0,
                batteryLevel = 99,
            ),
        )
    }

    @Test
    fun `charging status still counts as charging`() {
        assertTrue(
            BatteryStatePolicy.isEffectivelyCharging(
                status = BatteryManager.BATTERY_STATUS_CHARGING,
                plugged = 0,
                batteryLevel = 50,
            ),
        )
    }

    @Test
    fun `batteryLevelPercent normalizes against scale`() {
        assertEquals(50, BatteryStatePolicy.batteryLevelPercent(rawLevel = 50, scale = 100))
        assertEquals(50, BatteryStatePolicy.batteryLevelPercent(rawLevel = 5, scale = 10))
        assertEquals(100, BatteryStatePolicy.batteryLevelPercent(rawLevel = 100, scale = 100))
        assertEquals(0, BatteryStatePolicy.batteryLevelPercent(rawLevel = 0, scale = 100))
    }

    @Test
    fun `batteryLevelPercent returns unknown for missing or invalid scale`() {
        assertEquals(-1, BatteryStatePolicy.batteryLevelPercent(rawLevel = -1, scale = 100))
        assertEquals(-1, BatteryStatePolicy.batteryLevelPercent(rawLevel = 50, scale = -1))
        assertEquals(-1, BatteryStatePolicy.batteryLevelPercent(rawLevel = 50, scale = 0))
    }
}
