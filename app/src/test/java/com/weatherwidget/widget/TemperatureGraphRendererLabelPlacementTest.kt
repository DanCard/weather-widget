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
        assertTrue(GraphLabelPlacementUtils.isMinorOverlapEligible(TemperatureRole.LOW))
        assertTrue(GraphLabelPlacementUtils.isMinorOverlapEligible(TemperatureRole.FORECAST_HIGH))
        assertTrue(GraphLabelPlacementUtils.isMinorOverlapEligible(TemperatureRole.START))
        assertTrue(GraphLabelPlacementUtils.isMinorOverlapEligible(TemperatureRole.END))
        assertTrue(GraphLabelPlacementUtils.isMinorOverlapEligible(TemperatureRole.LOCAL))
        assertFalse(GraphLabelPlacementUtils.isMinorOverlapEligible(TemperatureRole.ACTUAL_END))
    }

    @Test
    fun `shouldAllowMinorOverlap allows eligible roles within threshold`() {
        assertTrue(GraphLabelPlacementUtils.shouldAllowMinorOverlap(role = TemperatureRole.LOW, overlapHeight = 4.1f, labelHeight = 12f))
        assertTrue(GraphLabelPlacementUtils.shouldAllowMinorOverlap(role = TemperatureRole.LOCAL, overlapHeight = 4.2f, labelHeight = 12f))
        assertTrue(GraphLabelPlacementUtils.shouldAllowMinorOverlap(role = TemperatureRole.END, overlapHeight = 4.2f, labelHeight = 12f))
    }

    @Test
    fun `shouldAllowMinorOverlap rejects overlap above threshold or ineligible roles`() {
        assertFalse(GraphLabelPlacementUtils.shouldAllowMinorOverlap(role = TemperatureRole.LOW, overlapHeight = 5.5f, labelHeight = 12f))
        assertFalse(GraphLabelPlacementUtils.shouldAllowMinorOverlap(role = TemperatureRole.ACTUAL_END, overlapHeight = 1.0f, labelHeight = 12f))
    }

}
