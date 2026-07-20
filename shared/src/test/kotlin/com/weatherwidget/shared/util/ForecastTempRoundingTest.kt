package com.weatherwidget.shared.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForecastTempRoundingTest {

    @Test
    fun `today keeps full decimal precision`() {
        assertEquals(90.61f, ForecastTempRounding.forStorage(90.61f, isToday = true))
        assertEquals(65.37f, ForecastTempRounding.forStorage(65.37f, isToday = true))
    }

    @Test
    fun `future day rounds to nearest integer`() {
        // 90.61 → 91 was the exact emulator-vs-desktop case that motivated the shared rule.
        assertEquals(91.0f, ForecastTempRounding.forStorage(90.61f, isToday = false))
        assertEquals(90.0f, ForecastTempRounding.forStorage(90.15f, isToday = false))
        assertEquals(65.0f, ForecastTempRounding.forStorage(65.37f, isToday = false))
    }

    @Test
    fun `future day rounds half up`() {
        assertEquals(91.0f, ForecastTempRounding.forStorage(90.5f, isToday = false))
    }

    @Test
    fun `null temp stays null`() {
        assertNull(ForecastTempRounding.forStorage(null, isToday = false))
        assertNull(ForecastTempRounding.forStorage(null, isToday = true))
    }

    @Test
    fun `non-finite temp becomes null instead of throwing`() {
        // roundToInt() throws on NaN; the rule must treat non-finite as missing.
        assertNull(ForecastTempRounding.forStorage(Float.NaN, isToday = false))
        assertNull(ForecastTempRounding.forStorage(Float.POSITIVE_INFINITY, isToday = false))
        assertNull(ForecastTempRounding.forStorage(Float.NaN, isToday = true))
    }
}
