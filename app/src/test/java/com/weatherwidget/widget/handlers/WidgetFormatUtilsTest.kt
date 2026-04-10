package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.Locale

@Category(ShortDuration::class)
class WidgetFormatUtilsTest {

    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    // --- US locale (inches) ---

    @Test
    fun `formatPrecipAmount US locale returns inches`() {
        Locale.setDefault(Locale.US)
        assertEquals("1in", formatPrecipAmount(25.4f))
    }

    @Test
    fun `formatPrecipAmount tiny amount uses 3 decimal places`() {
        Locale.setDefault(Locale.US)
        // 0.508mm = 0.02in
        assertEquals(".02in", formatPrecipAmount(0.508f))
    }

    @Test
    fun `formatPrecipAmount sub-inch uses 2 decimal places`() {
        Locale.setDefault(Locale.US)
        // 11.176mm = 0.44in
        assertEquals(".44in", formatPrecipAmount(11.176f))
    }

    @Test
    fun `formatPrecipAmount large amount uses 1 decimal place`() {
        Locale.setDefault(Locale.US)
        // 50.8mm = 2.0in
        assertEquals("2in", formatPrecipAmount(50.8f))
    }

    @Test
    fun `formatPrecipAmount fractional large amount`() {
        Locale.setDefault(Locale.US)
        // 38.1mm = 1.5in
        assertEquals("1.5in", formatPrecipAmount(38.1f))
    }

    // --- Metric locale (millimeters) ---

    @Test
    fun `formatPrecipAmount metric locale returns mm`() {
        Locale.setDefault(Locale.GERMANY)
        // >=10mm uses 0 decimal places, so 25.4 rounds to 25
        assertEquals("25mm", formatPrecipAmount(25.4f))
    }

    @Test
    fun `formatPrecipAmount small mm value uses 1 decimal`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("2.5mm", formatPrecipAmount(2.5f))
    }

    @Test
    fun `formatPrecipAmount large mm rounds to integer`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("25mm", formatPrecipAmount(25.0f))
    }
}
