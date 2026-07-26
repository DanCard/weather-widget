package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.experimental.categories.Category

/**
 * Regression for the left-edge forecast-HIGH ↔ ACTUAL_HIGH stack: a forecast daily high at the
 * left edge (e.g. "74") and a near-coincident equal-or-cooler observed high one hour later (e.g.
 * "73.7") would both be force-placed above the curve and overlap. The cooler observed high should
 * drop BELOW its own peak while the warmer forecast high stays above.
 *
 * Harness mirrors TemperatureLeftEdgeStartOrderTest (its helpers are private).
 */
@Category(ShortDuration::class)
class TemperatureLeftEdgeHighOrderTest {

    private fun buildHours(
        forecastTemps: List<Float>,
        actualTemps: List<Float?>,
        startTime: LocalDateTime = LocalDateTime.of(2026, 4, 8, 0, 0),
    ): List<HourData> =
        forecastTemps.mapIndexed { index, temp ->
            val dateTime = startTime.plusHours(index.toLong())
            HourData(
                dateTime = dateTime,
                temperature = temp,
                actualTemperature = actualTemps[index],
                isActual = actualTemps[index] != null,
                label = "${dateTime.hour}",
                showLabel = true,
            )
        }

    private class TestLabelTextMetrics(
        val charWidth: Float = 6f,
        override val ascent: Float = -10f,
        override val descent: Float = 2f,
    ) : LabelTextMetrics {
        override fun width(text: String, isFuture: Boolean): Float = text.length * charWidth
    }

    private fun runEngineTest(
        hours: List<HourData>,
        widthPx: Int,
        heightPx: Int,
        observedAt: Long?,
    ): List<PlacedLabel> {
        val allTemps = hours.flatMap { listOf(it.temperature, it.actualTemperature ?: it.temperature) }
        val minTemp = (allTemps.minOrNull() ?: 50f) - 5f
        val maxTemp = (allTemps.maxOrNull() ?: 80f) + 5f
        val tempRange = maxTemp - minTemp
        val graphTop = 44f
        val graphBottom = heightPx - 30f
        val graphHeight = graphBottom - graphTop
        val tempToY = { t: Float -> graphTop + graphHeight * (1f - (t - minTemp) / tempRange) }
        val hourWidth = widthPx.toFloat() / hours.size.toFloat()
        val minTimeEpoch = hours.first().dateTime.toEpochSecond(java.time.ZoneOffset.UTC)

        val originalPoints = mutableListOf<Pair<Float, Float>>()
        val forecastPoints = mutableListOf<Pair<Float, Float>>()
        hours.indices.forEach { index ->
            val pointEpoch = hours[index].dateTime.toEpochSecond(java.time.ZoneOffset.UTC)
            val x = ((pointEpoch - minTimeEpoch) / 3600f) * hourWidth
            originalPoints.add(x to tempToY(hours[index].actualTemperature ?: hours[index].temperature))
            forecastPoints.add(x to tempToY(hours[index].temperature))
        }

        val fetchTime = observedAt?.let {
            java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }
        val fetchIdx = fetchTime?.let { time -> hours.indexOfLast { !it.dateTime.isAfter(time) } } ?: -1
        val effectiveActualEndIndex = fetchIdx
        val transitionX = if (fetchIdx in hours.indices) originalPoints[fetchIdx].first else null
        val actualVisiblePoints = originalPoints.take(effectiveActualEndIndex + 1)

        return TemperatureLabelEngine.computePlacements(
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            density = 1f,
            originalPoints = originalPoints,
            forecastPoints = forecastPoints,
            actualVisiblePoints = actualVisiblePoints,
            transitionX = transitionX,
            fetchDotX = transitionX,
            lastObservedTemp = if (observedAt != null && effectiveActualEndIndex in hours.indices) {
                val h = hours[effectiveActualEndIndex]
                h.actualTemperature ?: h.temperature
            } else null,
            observedAt = observedAt,
            effectiveActualEndIndex = effectiveActualEndIndex,
            fetchTime = fetchTime,
            numColumns = hours.size,
            tempToY = tempToY,
            metrics = TestLabelTextMetrics(), useCelsius = false,
        )
    }

    // Forecast global high at idx 0; observed peak one hour later, equal-or-cooler.
    private fun buildForecast(): MutableList<Float> {
        // Strictly descending from 74 so the daily high is idx 0 and the daily low is the right edge.
        val forecast = MutableList(24) { 60f }
        for (i in 0..12) forecast[i] = 74f - i // 74, 73, ... 62
        return forecast
    }

    private fun buildActual(peak: Float): MutableList<Float?> {
        // Observed through idx 11; max at idx 1, a shallow min around idx 10 (-> ACTUAL_LOW).
        val actual = MutableList<Float?>(24) { null }
        actual[0] = 73f
        actual[1] = peak
        actual[2] = 73.3f
        actual[3] = 72.8f
        actual[4] = 72.3f
        actual[5] = 71.8f
        actual[6] = 71.3f
        actual[7] = 70.8f
        actual[8] = 70.3f
        actual[9] = 70.1f
        actual[10] = 69.9f
        actual[11] = 70.5f
        return actual
    }

    private val observedAt =
        LocalDateTime.of(2026, 4, 8, 11, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `cooler observed high at left edge drops below warmer forecast high`() {
        val placements = runEngineTest(
            hours = buildHours(buildForecast(), buildActual(peak = 73.7f)),
            widthPx = 600,
            heightPx = 400,
            observedAt = observedAt,
        )

        val forecastHigh = placements.find { it.role == TemperatureRole.HIGH && it.index <= 8 }
        val actualHigh = placements.find { it.role == TemperatureRole.ACTUAL_HIGH }
        assertNotNull("forecast HIGH should be placed at the left edge", forecastHigh)
        assertNotNull("ACTUAL_HIGH should be placed", actualHigh)

        assertTrue(
            "warmer forecast high (${forecastHigh!!.displayTemperature}) should stay ABOVE",
            forecastHigh.placedAbove,
        )
        assertFalse(
            "cooler observed high (${actualHigh!!.displayTemperature}) should drop BELOW the curve",
            actualHigh.placedAbove,
        )
        assertTrue(
            "below-placed actual should sit lower on screen (larger y): actual=${actualHigh.baselineY} forecast=${forecastHigh.baselineY}",
            actualHigh.baselineY > forecastHigh.baselineY,
        )
    }

    @Test
    fun `cooler observed high at same left-edge sample is drawn below forecast without overlap`() {
        val forecast = MutableList(24) { 60f }.apply {
            for (i in 0..12) {
                this[i] = 72f - i
            }
        }
        val actual = MutableList<Float?>(24) { null }.apply {
            for (i in 0..11) {
                this[i] = 71.9334f - i * 0.4f
            }
        }

        val placements = runEngineTest(
            hours = buildHours(forecast, actual),
            widthPx = 584,
            heightPx = 385,
            observedAt = observedAt,
        )

        val forecastHigh = placements.find { it.role == TemperatureRole.HIGH && it.index == 0 }
        val actualHigh = placements.find { it.role == TemperatureRole.ACTUAL_HIGH && it.index == 0 }
        assertNotNull("forecast HIGH should be placed at index 0", forecastHigh)
        assertNotNull("ACTUAL_HIGH should be placed at index 0", actualHigh)

        assertTrue("warmer 72 forecast high should remain above", forecastHigh!!.placedAbove)
        assertFalse("lower 71.9 actual high should move below its curve", actualHigh!!.placedAbove)

        val forecastBottom = forecastHigh.baselineY + TestLabelTextMetrics().descent
        val actualTop = actualHigh.baselineY + TestLabelTextMetrics().ascent
        assertTrue(
            "same-index forecast and actual label boxes must not overlap: forecastBottom=$forecastBottom actualTop=$actualTop",
            actualTop >= forecastBottom,
        )
    }

    @Test
    fun `genuinely warmer observed high keeps its default above placement`() {
        // Same geometry, but the observed peak is WARMER than the forecast high: the helper returns
        // empty and ACTUAL_HIGH keeps its force-above placement, proving the fix is scoped to the
        // equal-or-cooler case.
        val placements = runEngineTest(
            hours = buildHours(buildForecast(), buildActual(peak = 75f)),
            widthPx = 600,
            heightPx = 400,
            observedAt = observedAt,
        )

        val actualHigh = placements.find { it.role == TemperatureRole.ACTUAL_HIGH }
        assertNotNull("ACTUAL_HIGH should be placed", actualHigh)
        assertTrue(
            "warmer observed high (${actualHigh!!.displayTemperature}) should remain ABOVE",
            actualHigh.placedAbove,
        )
    }
}
