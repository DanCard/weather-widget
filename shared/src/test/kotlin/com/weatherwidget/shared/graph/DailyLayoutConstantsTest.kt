package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyLayoutConstantsTest {
    @Test
    fun `layout constants have expected values for compact daily view`() {
        assertEquals(90, DailyLayoutConstants.CELL_HEIGHT_DP)
        assertEquals(130, DailyLayoutConstants.ICON_WIDTH_THRESHOLD_DP)
    }

    @Test
    fun `narrow layouts use 55dp cells, 436dp and wider use 60dp`() {
        assertEquals(55, DailyLayoutConstants.cellWidthDp(337))
        assertEquals(55, DailyLayoutConstants.cellWidthDp(435))
        assertEquals(60, DailyLayoutConstants.cellWidthDp(436))
        assertEquals(60, DailyLayoutConstants.cellWidthDp(662))
        assertTrue(DailyLayoutConstants.NARROW_CELL_WIDTH_DP < DailyLayoutConstants.WIDE_CELL_WIDTH_DP)
    }
}
