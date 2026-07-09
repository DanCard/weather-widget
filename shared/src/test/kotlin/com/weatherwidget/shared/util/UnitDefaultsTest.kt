package com.weatherwidget.shared.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitDefaultsTest {
    @Test
    fun `US and territories default to Fahrenheit`() {
        assertFalse(UnitDefaults.defaultUseCelsius("US"))
        assertFalse(UnitDefaults.defaultUseCelsius("PR"))
        assertFalse(UnitDefaults.defaultUseCelsius("BZ"))
    }

    @Test
    fun `case insensitive region code`() {
        assertFalse(UnitDefaults.defaultUseCelsius("us"))
    }

    @Test
    fun `metric regions default to Celsius`() {
        assertTrue(UnitDefaults.defaultUseCelsius("DE"))
        assertTrue(UnitDefaults.defaultUseCelsius("FR"))
        assertTrue(UnitDefaults.defaultUseCelsius("UA"))
        assertTrue(UnitDefaults.defaultUseCelsius("MX"))
        assertTrue(UnitDefaults.defaultUseCelsius("GB"))
    }

    @Test
    fun `Liberia and Myanmar use Celsius for weather despite imperial distances`() {
        assertTrue(UnitDefaults.defaultUseCelsius("LR"))
        assertTrue(UnitDefaults.defaultUseCelsius("MM"))
    }

    @Test
    fun `unknown or absent region defaults to Celsius`() {
        assertTrue(UnitDefaults.defaultUseCelsius(null))
        assertTrue(UnitDefaults.defaultUseCelsius(""))
    }
}
