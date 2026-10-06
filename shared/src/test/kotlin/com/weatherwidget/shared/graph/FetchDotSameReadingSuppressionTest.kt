package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * An observed-series label beside the NOW dot repeats the dot's own value label when the readings
 * are within [LabelGeometryResolver.FETCH_DOT_SAME_READING_DEGREES]. 2026-10-06 emulator: pink
 * ACTUAL_HIGH "84.5°" (15:55, 84.52) printed over the dot's "84.4°" — the old rule only dropped an
 * exact text match. See plans/261006-hourly-actual-extreme-label-redundant-with-now-dot.md.
 */
@Category(ShortDuration::class)
class FetchDotSameReadingSuppressionTest {

    private object Metrics : LabelTextMetrics {
        override val ascent = -10f
        override val descent = 2f
        override fun width(text: String, isFuture: Boolean) = text.length * 6f
    }

    // 10 samples, 30px apart; the peak is at idx 7 (x=210), the NOW dot at idx 8 (x=240) unless moved.
    private val xs = (0 until 10).map { it * 30f }

    private fun resolve(role: TemperatureRole, peak: Float, dotTemp: Float, dotX: Float = 220f): ResolvedLabelGeometry? {
        val temps = MutableList(10) { 70f + it }.also { it[7] = peak; it[8] = dotTemp }
        val points = xs.zip(temps.map { 300f - it })
        return LabelGeometryResolver.resolve(
            candidate = TempLabelCandidate(7, role, temps, peak, forceForecastSeries = false),
            originalPoints = points,
            forecastPoints = points,
            transitionX = 240f,
            widthPx = 400,
            density = 1f,
            fetchDotX = dotX,
            lastObservedTemp = dotTemp,
            tempToY = { 300f - it },
            metrics = Metrics,
            useCelsius = false,
        )
    }

    @Test
    fun `observed high a tenth above the NOW reading is dropped`() {
        assertNull(resolve(TemperatureRole.ACTUAL_HIGH, peak = 84.52f, dotTemp = 84.4f))
    }

    @Test
    fun `observed low within a degree of the NOW reading is dropped`() {
        assertNull(resolve(TemperatureRole.ACTUAL_LOW, peak = 60.8f, dotTemp = 61.5f))
    }

    @Test
    fun `observed high clearly above the NOW reading keeps its label`() {
        assertNotNull(resolve(TemperatureRole.ACTUAL_HIGH, peak = 86.0f, dotTemp = 84.4f))
    }

    @Test
    fun `observed high far from the dot keeps its label`() {
        assertNotNull(resolve(TemperatureRole.ACTUAL_HIGH, peak = 84.52f, dotTemp = 84.4f, dotX = 380f))
    }

    @Test
    fun `forecast high beside the dot keeps the exact-text rule`() {
        // Different series: only an identical printed number duplicates the dot.
        assertNotNull(resolve(TemperatureRole.FORECAST_HIGH, peak = 84.52f, dotTemp = 84.4f))
        assertNull(resolve(TemperatureRole.FORECAST_HIGH, peak = 84.4f, dotTemp = 84.4f))
    }
}
