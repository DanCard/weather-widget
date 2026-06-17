package com.weatherwidget.shared.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Regression guard for the "stuck on Loading…" crash (2026-06-17): a hourly window that extended
 * past the loaded forecast horizon left trailing hours with a `Float.NaN` forecast temp
 * (ActualTemperatureSeriesBuilder fills missing forecasts with NaN). Those NaN temps reached the
 * label pipeline, where `roundToInt(NaN)` throws `IllegalArgumentException: Cannot round NaN value`,
 * aborting the whole graph render so the widget kept its initial placeholder. The original trigger
 * (the single-day pin forcing a 00:00→24:00 grid) is gone, but the renderer must never crash on a
 * NaN tail regardless of how the window is chosen — these tests lock that in for Android + desktop.
 */
class TemperatureExtremaNaNTest {

    // A today day-view: forecast present for idx 0..19, then NaN for 20..23 (the evening hours are
    // past the loaded forecast horizon). Observed line covers the morning (now ≈ idx 6).
    private fun nanTailHours(): List<HourData> {
        val start = LocalDateTime.of(2026, 6, 17, 0, 0)
        val forecast = listOf(
            61f, 60f, 59f, 58f, 59f, 61f, 63f, 66f, 69f, 72f, 74f, 76f,
            77f, 78f, 79f, 78f, 77f, 76f, 74f, 72f,
            Float.NaN, Float.NaN, Float.NaN, Float.NaN, // trailing gap past the forecast horizon
        )
        val actual = listOf(60f, 60.5f, 61f, 61.5f, 62f, 62.5f, 63f)
        return forecast.indices.map { i ->
            val dt = start.plusHours(i.toLong())
            HourData(
                dateTime = dt,
                temperature = forecast[i],
                label = "${dt.hour}h",
                isActual = i <= 6,
                actualTemperature = actual.getOrNull(i) ?: forecast[i],
            )
        }
    }

    @Test
    fun `NaN forecast tail does not crash label collection`() {
        val hours = nanTailHours()
        // The NOW boundary sits at the observation cutoff (idx 6); the right edge (idx 23) is NaN.
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 150f, 6, null)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 6,
            transitionX = 150f,
            observedAt = null,
            widthPx = 600,
        )

        // Pre-fix this line never executed — collectLabelCandidates threw "Cannot round NaN value".
        assertTrue("expected at least one label candidate", candidates.isNotEmpty())
        // No candidate may point at a NaN temperature (that is exactly what crashed roundToInt).
        assertFalse(
            "no label candidate should sit on a NaN temp, got ${candidates.map { it.role to it.index }}",
            candidates.any { it.labelTemps[it.index].isNaN() },
        )
    }

    @Test
    fun `daily and forecast extrema ignore NaN temps`() {
        val hours = nanTailHours()
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, 150f, 6, null)

        // The real daily high is 79 at idx 14 — NOT one of the NaN tail indices (Float.compareTo
        // ranks NaN as the largest value, so an unguarded maxByOrNull would have picked idx 20..23).
        assertEquals("daily high should be the finite peak at idx 14", 14, extrema.dailyHighIndex)
        assertFalse("dailyHighIndex temp must be finite", hours[extrema.dailyHighIndex].temperature.isNaN())
        assertFalse("forecastHighIndex temp must be finite", hours[extrema.forecastHighIndex].temperature.isNaN())
    }
}
