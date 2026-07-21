package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
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

    @Test
    fun `explicit OS temperature unit beats region default`() {
        // US region, but the user told the OS they want Celsius.
        assertTrue(UnitDefaults.defaultUseCelsius("celsius", "US"))
        // Metric region, but the user told the OS they want Fahrenheit.
        assertFalse(UnitDefaults.defaultUseCelsius("fahrenhe", "DE"))
    }

    @Test
    fun `CLDR truncated and full fahrenheit spellings both recognized`() {
        assertFalse(UnitDefaults.defaultUseCelsius("fahrenhe", "DE"))
        assertFalse(UnitDefaults.defaultUseCelsius("fahrenheit", "DE"))
        assertFalse(UnitDefaults.defaultUseCelsius("FAHRENHE", "DE"))
    }

    @Test
    fun `kelvin maps to Celsius`() {
        assertTrue(UnitDefaults.defaultUseCelsius("kelvin", "US"))
    }

    @Test
    fun `absent or unrecognized explicit unit falls through to region`() {
        assertFalse(UnitDefaults.defaultUseCelsius(null, "US"))
        assertFalse(UnitDefaults.defaultUseCelsius("", "US"))
        assertFalse(UnitDefaults.defaultUseCelsius("rankine", "US"))
        assertTrue(UnitDefaults.defaultUseCelsius(null, "DE"))
    }

    @Test
    fun `locale overload reads the mu unicode extension`() {
        val usWithCelsius = java.util.Locale.forLanguageTag("en-US-u-mu-celsius")
        assertTrue(UnitDefaults.defaultUseCelsius(usWithCelsius))

        val germanyWithFahrenheit = java.util.Locale.forLanguageTag("de-DE-u-mu-fahrenhe")
        assertFalse(UnitDefaults.defaultUseCelsius(germanyWithFahrenheit))

        val plainUs = java.util.Locale.forLanguageTag("en-US")
        assertFalse(UnitDefaults.defaultUseCelsius(plainUs))

        val plainGermany = java.util.Locale.forLanguageTag("de-DE")
        assertTrue(UnitDefaults.defaultUseCelsius(plainGermany))
    }
}
