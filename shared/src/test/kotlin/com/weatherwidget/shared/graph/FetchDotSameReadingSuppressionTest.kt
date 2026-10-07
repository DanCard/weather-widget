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

    // 2026-10-07 desktop: ACTUAL_LOW "60.6°" at 05:55, the dot reading 60.9° at 07:10, ~118px apart.
    private fun resolveLowAt(temps: List<Float>, lowIdx: Int, dotIdx: Int): ResolvedLabelGeometry? {
        val xs = temps.indices.map { it * 8f }
        val points = xs.zip(temps.map { 300f - it })
        return LabelGeometryResolver.resolve(
            candidate = TempLabelCandidate(lowIdx, TemperatureRole.ACTUAL_LOW, temps, temps[lowIdx], forceForecastSeries = false),
            originalPoints = points,
            forecastPoints = points,
            transitionX = xs[dotIdx],
            widthPx = 2000,
            density = 1f,
            fetchDotX = xs[dotIdx],
            lastObservedTemp = temps[dotIdx],
            tempToY = { 300f - it },
            metrics = Metrics,
            useCelsius = false,
        )
    }

    @Test
    fun `observed low on a plateau running into the dot is dropped however far away`() {
        val temps = MutableList(160) { 66f - it * 0.04f }
        for (i in 130..145) temps[i] = 60.65f + (i - 130) * 0.017f // 60.65 → 60.9, flat into NOW
        for (i in 146..159) temps[i] = 61f + (i - 146) * 0.5f
        assertNull(resolveLowAt(temps, lowIdx = 130, dotIdx = 145))
    }

    @Test
    fun `observed low that recovered and came back keeps its label`() {
        val temps = MutableList(160) { 66f }
        temps[100] = 60.6f
        for (i in 101..144) temps[i] = 64f
        temps[145] = 60.9f
        assertNotNull(resolveLowAt(temps, lowIdx = 100, dotIdx = 145))
    }

    @Test
    fun `observed low more than a degree under the dot keeps its label`() {
        val temps = MutableList(160) { 66f }
        for (i in 130..145) temps[i] = 59.5f + (i - 130) * 0.09f
        assertNotNull(resolveLowAt(temps, lowIdx = 130, dotIdx = 145))
    }

    @Test
    fun `forecast high beside the dot keeps the exact-text rule`() {
        // Different series: only an identical printed number duplicates the dot.
        assertNotNull(resolve(TemperatureRole.FORECAST_HIGH, peak = 84.52f, dotTemp = 84.4f))
        assertNull(resolve(TemperatureRole.FORECAST_HIGH, peak = 84.4f, dotTemp = 84.4f))
    }
}
