package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** Bar → icon → low label → day name geometry shared by the Android widget and desktop. */
@Category(ShortDuration::class)
class DailyBottomStackTest {

    private fun stack(
        canvasHeight: Float = 600f,
        floor: Float = 0f,
        blankBelow: Float = 0f,
        blankAbove: Float = 0f,
    ) = DailyBottomStack(
        canvasHeightPx = canvasHeight,
        pxPerDp = 2f,
        iconSizePx = 60f,
        lowLabelHeightPx = 40f,
        dayLabelHeightPx = 30f,
        dayLabelBottomMarginPx = 10f,
        graphBottomFloorPx = floor,
        lowLabelBlankBelowPx = blankBelow,
        dayLabelBlankAbovePx = blankAbove,
    )

    @Test
    fun `graph bottom reserves exactly the stack above the day names`() {
        val s = stack()
        assertEquals(560f, s.dayLabelTop, 0.001f)
        // 3dp gap + icon + 0dp + low label + 1dp gap, at 2 px/dp.
        assertEquals(6f + 60f + 0f + 40f + 2f, s.stackHeightPx, 0.001f)
        assertEquals(452f, s.graphBottom, 0.001f)
        assertEquals(148f, s.tapStripHeightPx, 0.001f)
    }

    @Test
    fun `coldest bar's low label ends one gap above the day names`() {
        val s = stack()
        val iconTop = s.iconTop(s.graphBottom)
        assertEquals(458f, iconTop, 0.001f)
        assertEquals(518f, s.lowLabelTop(iconTop), 0.001f)
        assertEquals(s.dayLabelTop - s.lowDayGapPx, s.lowLabelTop(iconTop) + 40f, 0.001f)
    }

    @Test
    fun `icon is held above the day names when the floor pushes a bar into the stack`() {
        val s = stack(canvasHeight = 200f, floor = 80f)
        assertTrue(s.requestedGraphBottom < 80f)
        assertEquals(80f, s.graphBottom, 0.001f)
        val iconTop = s.iconTop(s.graphBottom)
        assertEquals(s.dayLabelTop - s.lowDayGapPx - 40f - 60f, iconTop, 0.001f)
    }

    @Test
    fun `a taller low label lifts the icon only when it would reach the day names`() {
        val s = stack()
        assertEquals(306f, s.iconTop(300f, lowLabelHeightPx = 80f), 0.001f)
        assertEquals(s.dayLabelTop - s.lowDayGapPx - 80f - 60f, s.iconTop(s.graphBottom, lowLabelHeightPx = 80f), 0.001f)
    }

    @Test
    fun `no measured blank space reclaims nothing`() {
        // Android passes no blanks; its stack must stay exactly as before (pixel parity).
        assertEquals(0f, stack().reclaimPx, 0.001f)
        assertEquals(stack().graphBottom, stack(blankBelow = 0f, blankAbove = 0f).graphBottom, 0.001f)
        // Blank space smaller than the ink-gap top-up reclaims nothing either.
        assertEquals(0f, stack(blankBelow = 2f, blankAbove = 1f).reclaimPx, 0.001f)
    }

    @Test
    fun `blank line-box space is reclaimed down to the ink gap`() {
        // Desktop at 2 px/dp: 19 px blank under the digits, 8 px over the capitals.
        val s = stack(blankBelow = 19f, blankAbove = 8f)
        val inkGapTopUp = (DailyBottomStack.LOW_DAY_INK_GAP_DP - DailyBottomStack.LOW_DAY_GAP_DP) * 2f
        assertEquals(19f + 8f - inkGapTopUp, s.reclaimPx, 0.001f)
        assertEquals(stack().graphBottom + s.reclaimPx, s.graphBottom, 0.001f)

        // The coldest column: digit ink bottom → capital ink top is exactly the ink gap.
        val lowLabelBottom = s.lowLabelTop(s.iconTop(s.graphBottom)) + 40f
        val inkGap = (s.dayLabelTop + 8f) - (lowLabelBottom - 19f)
        assertEquals(DailyBottomStack.LOW_DAY_INK_GAP_DP * 2f, inkGap, 0.001f)
    }

    @Test
    fun `icon clamp follows the reclaim`() {
        val s = stack(canvasHeight = 200f, floor = 80f, blankBelow = 19f, blankAbove = 8f)
        assertEquals(s.dayLabelTop - s.lowDayGapPx + s.reclaimPx - 40f - 60f, s.iconTop(s.graphBottom), 0.001f)
    }
}
