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
        assertEquals(55, DailyLayoutConstants.CELL_WIDTH_DP)
        assertEquals(90, DailyLayoutConstants.CELL_HEIGHT_DP)
        assertEquals(130, DailyLayoutConstants.ICON_WIDTH_THRESHOLD_DP)
        assertTrue(DailyLayoutConstants.CELL_WIDTH_DP > 0)
    }
}
