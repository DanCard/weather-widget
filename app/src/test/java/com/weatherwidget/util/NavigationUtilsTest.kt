package com.weatherwidget.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

class NavigationUtilsTest {
    @Test
    fun testGetDayOffsets_Narrow() {
        // 1 column: [0]
        assertEquals(listOf(0L), NavigationUtils.getDayOffsets(1))
        assertEquals(0, NavigationUtils.getMinOffset(1))
        assertEquals(0, NavigationUtils.getMaxOffset(1))

        // 2 columns: [0, 1]
        assertEquals(listOf(0L, 1L), NavigationUtils.getDayOffsets(2))
        assertEquals(0, NavigationUtils.getMinOffset(2))
        assertEquals(1, NavigationUtils.getMaxOffset(2))
    }

    @Test
    fun testGetDayOffsets_Standard() {
        // 3 columns: [-1, 0, 1]
        assertEquals(listOf(-1L, 0L, 1L), NavigationUtils.getDayOffsets(3))
        assertEquals(-1, NavigationUtils.getMinOffset(3))
        assertEquals(1, NavigationUtils.getMaxOffset(3))

        // 4 columns: [-1, 0, 1, 2]
        assertEquals(listOf(-1L, 0L, 1L, 2L), NavigationUtils.getDayOffsets(4))
        assertEquals(-1, NavigationUtils.getMinOffset(4))
        assertEquals(2, NavigationUtils.getMaxOffset(4))
    }

    @Test
    fun testGetDayOffsets_Wide() {
        // 7 columns: [-1, 0, 1, 2, 3, 4, 5]
        assertEquals(listOf(-1L, 0L, 1L, 2L, 3L, 4L, 5L), NavigationUtils.getDayOffsets(7))
        assertEquals(-1, NavigationUtils.getMinOffset(7))
        assertEquals(5, NavigationUtils.getMaxOffset(7))
    }

    @Test
    fun testGetDayOffsets_ExtraWide_Foldable() {
        // 8 columns: 8 days [-1, 0, 1, 2, 3, 4, 5, 6]
        assertEquals(listOf(-1L, 0L, 1L, 2L, 3L, 4L, 5L, 6L), NavigationUtils.getDayOffsets(8))
        assertEquals(-1, NavigationUtils.getMinOffset(8))
        assertEquals(6, NavigationUtils.getMaxOffset(8))

        // 10 columns: 10 days [-1, 0, 1, 2, 3, 4, 5, 6, 7, 8]
        assertEquals(listOf(-1L, 0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), NavigationUtils.getDayOffsets(10))
        assertEquals(-1, NavigationUtils.getMinOffset(10))
        assertEquals(8, NavigationUtils.getMaxOffset(10))
    }

    @Test
    fun testEveningMode_eachOffsetStepShowsDifferentRange() {
        val today = LocalDate.of(2026, 2, 14)
        val numColumns = 9 // 7 visible days

        // Each offset step should produce a distinct visible range
        for (offset in -3..3) {
            val range = NavigationUtils.getVisibleDateRange(today, offset, numColumns, isEveningMode = true)
            val nextRange = NavigationUtils.getVisibleDateRange(today, offset + 1, numColumns, isEveningMode = true)
            assertNotEquals(
                "offset=$offset and offset=${offset + 1} should show different ranges",
                range,
                nextRange,
            )
            // Each step should shift by exactly 1 day
            assertEquals(
                "offset=$offset to ${offset + 1}: leftmost should shift by 1 day",
                range.first.plusDays(1),
                nextRange.first,
            )
            assertEquals(
                "offset=$offset to ${offset + 1}: rightmost should shift by 1 day",
                range.second.plusDays(1),
                nextRange.second,
            )
        }
    }

    @Test
    fun testEveningMode_offset0_showsTodayFirst() {
        val today = LocalDate.of(2026, 2, 14)
        // Evening mode offset=0 should start from today (not yesterday)
        val range = NavigationUtils.getVisibleDateRange(today, 0, 9, isEveningMode = true)
        assertEquals(today, range.first)
    }
}
