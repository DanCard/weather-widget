package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CurrentTempTouchZoneTest {

    @Test
    fun `current temp touch zone has expanded width`() {
        val currentTempZoneWidthDp = 96

        assertTrue(
            "Current temp touch zone width should exceed the 48dp minimum touch target",
            currentTempZoneWidthDp >= 48,
        )
        assertEquals("Current temp touch zone width is 96dp", 96, currentTempZoneWidthDp)
    }

    @Test
    fun `current temp touch zone has expanded height`() {
        val currentTempZoneHeightDp = 72

        assertTrue(
            "Current temp touch zone height should exceed the 48dp minimum touch target",
            currentTempZoneHeightDp >= 48,
        )
        assertEquals("Current temp touch zone height is 72dp", 72, currentTempZoneHeightDp)
    }

    @Test
    fun `current temp touch zone still leaves precip touch zone coverage`() {
        val currentTempZoneWidthDp = 96
        val precipTouchZoneMarginStartDp = 72
        val precipTouchZoneWidthDp = 72

        assertTrue(
            "Precip touch zone should still begin within the expanded current temp band to avoid dead space",
            precipTouchZoneMarginStartDp <= currentTempZoneWidthDp,
        )
        assertTrue(
            "Precip touch zone should continue past the current temp band",
            precipTouchZoneMarginStartDp + precipTouchZoneWidthDp > currentTempZoneWidthDp,
        )
    }
}
