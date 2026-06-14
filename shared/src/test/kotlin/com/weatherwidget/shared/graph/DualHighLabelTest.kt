package com.weatherwidget.shared.graph

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualHighLabelTest {

    private val labelH = 20f

    @Test
    fun `null inputs never show both`() {
        assertFalse(DualHighLabel.showBoth(null, 70f, 0f, 40f, labelH))
        assertFalse(DualHighLabel.showBoth(70f, null, 0f, 40f, labelH))
    }

    // Min gap (px) the room test requires, given labelH.
    private val minGap = labelH * (1f - DualHighLabel.MAX_OVERLAP_FRACTION)

    @Test
    fun `difference below floor does not show both even with room`() {
        // Just under the floor but plenty of vertical room -> still suppressed by the floor.
        val belowFloor = DualHighLabel.MIN_DIFF_DEG - 1f
        assertFalse(DualHighLabel.showBoth(72f, 72f - belowFloor, 0f, 100f, labelH))
    }

    @Test
    fun `substantial difference with room shows both`() {
        // Comfortably over the floor and the two label boxes are well past the min gap.
        assertTrue(DualHighLabel.showBoth(80f, 72f, 0f, minGap + 20f, labelH))
    }

    @Test
    fun `substantial difference but boxes overlap too much does not show both`() {
        // Over the floor by value, but on a very compressed graph the labels are below the min gap.
        assertFalse(DualHighLabel.showBoth(80f, 72f, 0f, minGap - 4f, labelH))
    }

    @Test
    fun `gap exactly at the overlap boundary shows both`() {
        // gap == labelH * (1 - MAX_OVERLAP_FRACTION) -> inclusive.
        assertTrue(DualHighLabel.showBoth(80f, 72f, 0f, minGap, labelH))
    }

    @Test
    fun `isWideLabel true for 3-plus digit temps`() {
        assertTrue(DualHighLabel.isWideLabel("100°"))   // triple-digit int
        assertTrue(DualHighLabel.isWideLabel("97.7°"))  // decimal -> 3 digits
        assertFalse(DualHighLabel.isWideLabel("84°"))   // 2 digits
        assertFalse(DualHighLabel.isWideLabel("9°"))    // 1 digit
    }
}
