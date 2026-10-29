package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** Bar → icon → low label → day name geometry shared by the Android widget and desktop. */
@Category(ShortDuration::class)
class DailyBottomStackTest {

    private fun stack(canvasHeight: Float = 600f, floor: Float = 0f) = DailyBottomStack(
        canvasHeightPx = canvasHeight,
        pxPerDp = 2f,
        iconSizePx = 60f,
        lowLabelHeightPx = 40f,
        dayLabelHeightPx = 30f,
        dayLabelBottomMarginPx = 10f,
        graphBottomFloorPx = floor,
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
}
