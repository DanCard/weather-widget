package com.weatherwidget.widget.handlers

import com.weatherwidget.util.NavigationUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyViewHandlerUnitTest {

    @Test
    fun navigationUtils_getDayOffsets_returnsCorrectNumber() {
        val numColumns = 3
        val dayOffsets = NavigationUtils.getDayOffsets(numColumns)

        assertEquals(numColumns, dayOffsets.size)
    }

    @Test
    fun navigationUtils_getDayOffsets_includesToday() {
        val numColumns = 3
        val dayOffsets = NavigationUtils.getDayOffsets(numColumns)

        assertTrue("Today (offset 0) should be included", dayOffsets.contains(0))
    }
}