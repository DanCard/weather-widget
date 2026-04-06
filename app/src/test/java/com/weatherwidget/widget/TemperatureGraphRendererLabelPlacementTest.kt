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
        assertTrue(TemperatureGraphRenderer.isMinorOverlapEligible("LOW"))
        assertTrue(TemperatureGraphRenderer.isMinorOverlapEligible("FORECAST_HIGH"))
        assertTrue(TemperatureGraphRenderer.isMinorOverlapEligible("START"))
        assertTrue(TemperatureGraphRenderer.isMinorOverlapEligible("END"))
        assertTrue(TemperatureGraphRenderer.isMinorOverlapEligible("LOCAL"))
        assertFalse(TemperatureGraphRenderer.isMinorOverlapEligible("ACTUAL_END"))
    }

    @Test
    fun `shouldAllowMinorOverlap allows eligible roles within threshold`() {
        assertTrue(TemperatureGraphRenderer.shouldAllowMinorOverlap(role = "LOW", overlapHeight = 4.1f, labelHeight = 12f))
        assertTrue(TemperatureGraphRenderer.shouldAllowMinorOverlap(role = "LOCAL", overlapHeight = 4.2f, labelHeight = 12f))
        assertTrue(TemperatureGraphRenderer.shouldAllowMinorOverlap(role = "END", overlapHeight = 4.2f, labelHeight = 12f))
    }

    @Test
    fun `shouldAllowMinorOverlap rejects overlap above threshold or ineligible roles`() {
        assertFalse(TemperatureGraphRenderer.shouldAllowMinorOverlap(role = "LOW", overlapHeight = 5.5f, labelHeight = 12f))
        assertFalse(TemperatureGraphRenderer.shouldAllowMinorOverlap(role = "ACTUAL_END", overlapHeight = 1.0f, labelHeight = 12f))
    }

}
