package com.weatherwidget.util

import org.junit.Assert.assertEquals
import org.junit.Test

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
        // 7 columns: [-1, 0, 1, 2, 3, 4]
        assertEquals(listOf(-1L, 0L, 1L, 2L, 3L, 4L), NavigationUtils.getDayOffsets(7))
        assertEquals(-1, NavigationUtils.getMinOffset(7))
        assertEquals(4, NavigationUtils.getMaxOffset(7))
    }

    @Test
    fun testGetDayOffsets_ExtraWide_Foldable() {
        // 8 columns: capped at 7 days [-1, 0, 1, 2, 3, 4, 5]
        assertEquals(listOf(-1L, 0L, 1L, 2L, 3L, 4L, 5L), NavigationUtils.getDayOffsets(8))
        assertEquals(-1, NavigationUtils.getMinOffset(8))
        assertEquals(5, NavigationUtils.getMaxOffset(8))

        // 10 columns: still capped at 7 days [-1, 0, 1, 2, 3, 4, 5]
        assertEquals(listOf(-1L, 0L, 1L, 2L, 3L, 4L, 5L), NavigationUtils.getDayOffsets(10))
        assertEquals(-1, NavigationUtils.getMinOffset(10))
        assertEquals(5, NavigationUtils.getMaxOffset(10))
    }
}
