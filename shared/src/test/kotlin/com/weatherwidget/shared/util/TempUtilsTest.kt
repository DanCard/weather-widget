package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TempUtilsTest {

    @Test
    fun `fahrenheitToCelsius converts correctly`() {
        assertEquals(0f, TempUtils.fahrenheitToCelsius(32f), 0.001f)
        assertEquals(100f, TempUtils.fahrenheitToCelsius(212f), 0.001f)
        assertEquals(10f, TempUtils.fahrenheitToCelsius(50f), 0.001f)
        assertEquals(-40f, TempUtils.fahrenheitToCelsius(-40f), 0.001f)
    }

    @Test
    fun `celsiusToFahrenheit converts correctly`() {
        assertEquals(32f, TempUtils.celsiusToFahrenheit(0f), 0.001f)
        assertEquals(212f, TempUtils.celsiusToFahrenheit(100f), 0.001f)
        assertEquals(50f, TempUtils.celsiusToFahrenheit(10f), 0.001f)
        assertEquals(-40f, TempUtils.celsiusToFahrenheit(-40f), 0.001f)
    }

    @Test
    fun `formatTemp returns null when input is null`() {
        assertNull(TempUtils.formatTemp(null, useCelsius = false))
        assertNull(TempUtils.formatTemp(null, useCelsius = true))
    }

    @Test
    fun `formatTemp formats clean integer values without decimal in Fahrenheit`() {
        assertEquals("50°", TempUtils.formatTemp(50f, useCelsius = false))
        assertEquals("72°", TempUtils.formatTemp(72.001f, useCelsius = false))
    }

    @Test
    fun `formatTemp formats clean integer values without decimal in Celsius`() {
        assertEquals("10°", TempUtils.formatTemp(50f, useCelsius = true)) // 50F is 10C
        assertEquals("0°", TempUtils.formatTemp(32f, useCelsius = true))  // 32F is 0C
    }

    @Test
    fun `formatTemp formats fractional values with one decimal place in Fahrenheit`() {
        assertEquals("50.4°", TempUtils.formatTemp(50.4f, useCelsius = false))
        assertEquals("72.8°", TempUtils.formatTemp(72.82f, useCelsius = false))
    }

    @Test
    fun `formatTemp formats fractional values with one decimal place in Celsius`() {
        // 50.9F = 10.5C
        assertEquals("10.5°", TempUtils.formatTemp(50.9f, useCelsius = true))
    }

    @Test
    fun `formatDayTemp rounds a whole-degree column and keeps the tenth otherwise`() {
        assertEquals("72°", TempUtils.formatDayTemp(72.4f, useCelsius = false, wholeDegrees = true))
        assertEquals("73°", TempUtils.formatDayTemp(72.5f, useCelsius = false, wholeDegrees = true))
        assertEquals("72.4°", TempUtils.formatDayTemp(72.4f, useCelsius = false, wholeDegrees = false))
        assertEquals("72°", TempUtils.formatDayTemp(72f, useCelsius = false, wholeDegrees = false))
        // 72.4F = 22.4C: a future day rounds the Celsius value, not the stored Fahrenheit.
        assertEquals("22°", TempUtils.formatDayTemp(72.4f, useCelsius = true, wholeDegrees = true))
        assertEquals(null, TempUtils.formatDayTemp(null, useCelsius = false, wholeDegrees = true))
    }

    @Test
    fun `formatSignedDelta prints a full-precision error like formatTemp, with its sign`() {
        assertEquals("+2°", TempUtils.formatSignedDelta(2f, useCelsius = false))
        assertEquals("+0.2°", TempUtils.formatSignedDelta(0.2f, useCelsius = false))
        assertEquals("-1.4°", TempUtils.formatSignedDelta(-1.4f, useCelsius = false))
        assertEquals("0°", TempUtils.formatSignedDelta(0f, useCelsius = false))
        assertEquals("0°", TempUtils.formatSignedDelta(-0.004f, useCelsius = false))
        assertEquals("+1°", TempUtils.formatSignedDelta(1.8f, useCelsius = true))
    }
}
