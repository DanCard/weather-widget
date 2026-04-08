package com.weatherwidget.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
class TemperatureGraphRendererLabelPlacementTest {

    @Test
    fun `isMinorOverlapEligible covers essential and local labels`() {
        assertTrue(TemperatureGraphRenderer.isMinorOverlapEligible(TemperatureGraphRenderer.TemperatureRole.LOW))
        assertTrue(TemperatureGraphRenderer.isMinorOverlapEligible(TemperatureGraphRenderer.TemperatureRole.FORECAST_HIGH))
        assertTrue(TemperatureGraphRenderer.isMinorOverlapEligible(TemperatureGraphRenderer.TemperatureRole.START))
        assertTrue(TemperatureGraphRenderer.isMinorOverlapEligible(TemperatureGraphRenderer.TemperatureRole.END))
        assertTrue(TemperatureGraphRenderer.isMinorOverlapEligible(TemperatureGraphRenderer.TemperatureRole.LOCAL))
        assertFalse(TemperatureGraphRenderer.isMinorOverlapEligible(TemperatureGraphRenderer.TemperatureRole.ACTUAL_END))
    }

    @Test
    fun `shouldAllowMinorOverlap allows eligible roles within threshold`() {
        assertTrue(TemperatureGraphRenderer.shouldAllowMinorOverlap(role = TemperatureGraphRenderer.TemperatureRole.LOW, overlapHeight = 4.1f, labelHeight = 12f))
        assertTrue(TemperatureGraphRenderer.shouldAllowMinorOverlap(role = TemperatureGraphRenderer.TemperatureRole.LOCAL, overlapHeight = 4.2f, labelHeight = 12f))
        assertTrue(TemperatureGraphRenderer.shouldAllowMinorOverlap(role = TemperatureGraphRenderer.TemperatureRole.END, overlapHeight = 4.2f, labelHeight = 12f))
    }

    @Test
    fun `shouldAllowMinorOverlap rejects overlap above threshold or ineligible roles`() {
        assertFalse(TemperatureGraphRenderer.shouldAllowMinorOverlap(role = TemperatureGraphRenderer.TemperatureRole.LOW, overlapHeight = 5.5f, labelHeight = 12f))
        assertFalse(TemperatureGraphRenderer.shouldAllowMinorOverlap(role = TemperatureGraphRenderer.TemperatureRole.ACTUAL_END, overlapHeight = 1.0f, labelHeight = 12f))
    }

}
