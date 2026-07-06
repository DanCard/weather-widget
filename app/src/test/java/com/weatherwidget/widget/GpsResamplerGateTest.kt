package com.weatherwidget.widget

import com.weatherwidget.shared.util.BatteryTier
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class GpsResamplerGateTest {

    @Test
    fun `charging allows sampling regardless of battery level`() {
        assertTrue(GpsResampler.shouldSample(isEffectivelyCharging = true, batteryLevel = 5))
    }

    @Test
    fun `unplugged above high tier threshold allows sampling`() {
        assertTrue(
            GpsResampler.shouldSample(
                isEffectivelyCharging = false,
                batteryLevel = BatteryTier.TIER_HIGH_THRESHOLD + 1,
            ),
        )
    }

    @Test
    fun `unplugged exactly at high tier threshold skips sampling`() {
        assertFalse(
            GpsResampler.shouldSample(
                isEffectivelyCharging = false,
                batteryLevel = BatteryTier.TIER_HIGH_THRESHOLD,
            ),
        )
    }

    @Test
    fun `unplugged low battery skips sampling`() {
        assertFalse(GpsResampler.shouldSample(isEffectivelyCharging = false, batteryLevel = 30))
    }

    @Test
    fun `unknown battery level skips sampling when unplugged`() {
        // registerReceiver returning null yields level -1; never power up GPS blind.
        assertFalse(GpsResampler.shouldSample(isEffectivelyCharging = false, batteryLevel = -1))
    }
}
