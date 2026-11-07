package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class MidWindowTempLabelGateTest {

    @Test
    fun `shown when only the far left and far right labels are placed`() {
        assertTrue(MidWindowTempLabelGate.shouldShow(listOf(0, 92), lastIndex = 92))
        assertNull(MidWindowTempLabelGate.hiddenReason(listOf(0, 92), lastIndex = 92))
    }

    @Test
    fun `shown with a single edge label or none`() {
        assertTrue(MidWindowTempLabelGate.shouldShow(listOf(0), lastIndex = 92))
        assertTrue(MidWindowTempLabelGate.shouldShow(listOf(92), lastIndex = 92))
        assertTrue(MidWindowTempLabelGate.shouldShow(emptyList(), lastIndex = 92))
    }

    // 2026-11-07 desktop: 58 @0, 51 @86, 47.7 @86 (ACTUAL_LOW), 65 @94, 74 @98. The mid label
    // printed a second 47.7° beside the ACTUAL_LOW label.
    @Test
    fun `hidden when an interior label is placed`() {
        val placed = listOf(0, 86, 86, 94, 98)
        assertFalse(MidWindowTempLabelGate.shouldShow(placed, lastIndex = 98))
        assertEquals("interior_label idx=86", MidWindowTempLabelGate.hiddenReason(placed, lastIndex = 98))
    }

    // Same day, 07:23: the high sits at idx 90 of 92, near but not on the right edge.
    @Test
    fun `a label one hour in from the edge counts as interior`() {
        assertFalse(MidWindowTempLabelGate.shouldShow(listOf(0, 90, 92), lastIndex = 92))
    }
}
