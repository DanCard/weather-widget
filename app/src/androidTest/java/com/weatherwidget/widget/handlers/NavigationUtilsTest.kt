package com.weatherwidget.widget.handlers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.util.NavigationUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for NavigationUtils.
 */
@RunWith(AndroidJUnit4::class)
class NavigationUtilsTest {
    @Test
    fun getDayOffsets_1column_returnsCorrectOffsets() {
        val offsets = NavigationUtils.getDayOffsets(1)
        assertEquals(listOf(0L), offsets)
    }

    @Test
    fun getDayOffsets_2columns_returnsCorrectOffsets() {
        val offsets = NavigationUtils.getDayOffsets(2)
        assertEquals(listOf(0L, 1L), offsets)
    }

    @Test
    fun getDayOffsets_3columns_returnsCorrectOffsets() {
        val offsets = NavigationUtils.getDayOffsets(3)
        assertEquals(listOf(-1L, 0L, 1L), offsets)
    }

    @Test
    fun getDayOffsets_6columns_returnsCorrectOffsets() {
        val offsets = NavigationUtils.getDayOffsets(6)
        assertEquals(listOf(-1L, 0L, 1L, 2L, 3L, 4L), offsets)
    }

    @Test
    fun getMinOffset_1column_returnsZero() {
        assertEquals(0, NavigationUtils.getMinOffset(1))
    }

    @Test
    fun getMinOffset_2columns_returnsZero() {
        assertEquals(0, NavigationUtils.getMinOffset(2))
    }

    @Test
    fun getMinOffset_3columns_returnsNegative() {
        assertEquals(-1, NavigationUtils.getMinOffset(3))
    }

    @Test
    fun getMinOffset_6columns_returnsNegative() {
        assertEquals(-1, NavigationUtils.getMinOffset(6))
    }

    @Test
    fun getMaxOffset_1column_returnsZero() {
        assertEquals(0, NavigationUtils.getMaxOffset(1))
    }

    @Test
    fun getMaxOffset_2columns_returnsPositive() {
        assertEquals(1, NavigationUtils.getMaxOffset(2))
    }

    @Test
    fun getMaxOffset_3columns_returnsPositive() {
        assertEquals(1, NavigationUtils.getMaxOffset(3))
    }

    @Test
    fun getMaxOffset_6columns_returnsPositive() {
        assertEquals(4, NavigationUtils.getMaxOffset(6))
    }
}
