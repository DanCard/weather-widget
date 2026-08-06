package com.weatherwidget.shared.graph

import com.weatherwidget.shared.graph.ForecastHistoryViewLogic
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.CurrentTemperatureResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Celsius display formatting across the shared seams: absolute temperatures convert via
 * (F − 32) / 1.8, while temperature DIFFERENCES (deltas, errors, biases) scale by 1/1.8 only.
 * Storage stays Fahrenheit everywhere; conversion happens at format time.
 */
@Category(ShortDuration::class)
class CelsiusDisplayTest {

    // ------------------------------------------------------------------ label resolver

    @Test
    fun `resolver formatTemp converts absolute values`() {
        assertEquals("50", TemperatureLabelResolver.formatTemp(50f, useCelsius = false))
        assertEquals("10", TemperatureLabelResolver.formatTemp(50f, useCelsius = true))
        assertEquals("0", TemperatureLabelResolver.formatTemp(32f, useCelsius = true))
        assertEquals("22", TemperatureLabelResolver.formatTemp(71.6f, useCelsius = true))
    }

    // Celsius rounds to the same 0.1° step as Fahrenheit, but 0.1 °C is a wider bucket (≈0.18 °F):
    // values distinct in °F can collide in °C. Duplicate-label suppression compares FORMATTED text,
    // so it must format in the display unit — this property is what the useCelsius threading through
    // deduplicateAnchors / TemperatureExtrema protects.
    @Test
    fun `values distinct in Fahrenheit can collide in Celsius`() {
        val f0 = TemperatureLabelResolver.formatTemp(64.0f, useCelsius = false)
        val f1 = TemperatureLabelResolver.formatTemp(64.1f, useCelsius = false)
        assertNotEquals(f0, f1) // "64" vs "64.1"

        val c0 = TemperatureLabelResolver.formatTemp(64.0f, useCelsius = true)
        val c1 = TemperatureLabelResolver.formatTemp(64.1f, useCelsius = true)
        assertEquals(c0, c1) // both "17.8"
    }

    // ------------------------------------------------------------------ evolution geometry

    @Test
    fun `axis labels convert absolute values`() {
        assertEquals("32°", ForecastEvolutionGeometry.formatAxisLabel(32f, useCelsius = false))
        assertEquals("0°", ForecastEvolutionGeometry.formatAxisLabel(32f, useCelsius = true))
        assertEquals("10°", ForecastEvolutionGeometry.formatAxisLabel(50f, useCelsius = true))
    }

    @Test
    fun `error labels scale deltas without the offset`() {
        assertEquals("+1.8°", ForecastEvolutionGeometry.formatErrorLabel(1.8f, useCelsius = false))
        assertEquals("+1°", ForecastEvolutionGeometry.formatErrorLabel(1.8f, useCelsius = true))
        assertEquals("-2°", ForecastEvolutionGeometry.formatErrorLabel(-3.6f, useCelsius = true))
        assertEquals("0°", ForecastEvolutionGeometry.formatErrorLabel(0.05f, useCelsius = true))
    }

    // ------------------------------------------------------------------ accuracy bias

    @Test
    fun `bias scales as a delta and keeps its display threshold`() {
        assertEquals(" (0.6° low)", ForecastHistoryViewLogic.formatBias(0.6, useCelsius = false))
        assertEquals(" (1.0° low)", ForecastHistoryViewLogic.formatBias(1.8, useCelsius = true))
        assertEquals(" (2.0° high)", ForecastHistoryViewLogic.formatBias(-3.6, useCelsius = true))
        // 0.4 °F bias = 0.22 °C: below the (scaled) suppression threshold in both units.
        assertEquals("", ForecastHistoryViewLogic.formatBias(0.4, useCelsius = false))
        assertEquals("", ForecastHistoryViewLogic.formatBias(0.4, useCelsius = true))
    }

    // ------------------------------------------------------------------ forecast delta

    @Test
    fun `forecast delta scales without the offset`() {
        assertEquals("+1.8 from forecast", ForecastDeltaLabel.format(1.8f, useCelsius = false))
        assertEquals("+1.0 from forecast", ForecastDeltaLabel.format(1.8f, useCelsius = true))
        assertEquals("-0.5 from forecast", ForecastDeltaLabel.format(-0.9f, useCelsius = true))
    }

    // ------------------------------------------------------------------ header current temp

    // Both the full render (via TemperatureStateResolver) and every partial/UI-only update path
    // format the header through this single function; these assertions pin the unit behavior the
    // widget header must show regardless of which path repainted it.
    @Test
    fun `header temp converts in both column layouts`() {
        assertEquals("50.0°", CurrentTemperatureResolver.formatDisplayTemperature(50f, 2, false, useCelsius = false))
        assertEquals("10.0°", CurrentTemperatureResolver.formatDisplayTemperature(50f, 2, false, useCelsius = true))
        assertEquals("10°", CurrentTemperatureResolver.formatDisplayTemperature(50f, 1, false, useCelsius = true))
    }
}
