package com.weatherwidget.widget

import com.weatherwidget.shared.graph.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
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
        // Budget is 0.30 * 12 = 3.6px; these sub-budget overlaps are still allowed.
        assertTrue(GraphLabelPlacementUtils.shouldAllowMinorOverlap(role = TemperatureRole.LOW, overlapHeight = 3.0f, labelHeight = 12f))
        assertTrue(GraphLabelPlacementUtils.shouldAllowMinorOverlap(role = TemperatureRole.LOCAL, overlapHeight = 3.4f, labelHeight = 12f))
        assertTrue(GraphLabelPlacementUtils.shouldAllowMinorOverlap(role = TemperatureRole.END, overlapHeight = 3.4f, labelHeight = 12f))
    }

    @Test
    fun `shouldAllowMinorOverlap rejects overlap above threshold or ineligible roles`() {
        // 3.7px is just over the 3.6px budget (0.30 * 12) => rejected.
        assertFalse(GraphLabelPlacementUtils.shouldAllowMinorOverlap(role = TemperatureRole.LOW, overlapHeight = 3.7f, labelHeight = 12f))
        assertFalse(GraphLabelPlacementUtils.shouldAllowMinorOverlap(role = TemperatureRole.ACTUAL_END, overlapHeight = 1.0f, labelHeight = 12f))
    }

}
