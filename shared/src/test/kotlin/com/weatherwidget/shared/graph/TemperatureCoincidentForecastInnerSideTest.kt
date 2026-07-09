package com.weatherwidget.shared.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * When a forecast extreme shares its hour with a more-extreme labeled actual extreme (resolver
 * injects a coincident ACTUAL_HIGH/ACTUAL_LOW), the forecast label keeps its conventional side
 * (high above its peak, low below its valley) but sits flush against its own line with NO leader
 * line — instead of curve-avoiding up/down alongside the towering actual curve (the "Thu 11 / 91°
 * long leader line" desktop bug). See plans/samsung-clash-of-labels-reflective-narwhal.md.
 */
class TemperatureCoincidentForecastInnerSideTest {

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
        observedAt: Long,
    ): List<PlacedLabel> {
        val allTemps = hours.flatMap { listOf(it.temperature, it.actualTemperature ?: it.temperature) }
        val minTemp = (allTemps.minOrNull() ?: 50f) - 5f
        val maxTemp = (allTemps.maxOrNull() ?: 80f) + 5f
        val range = maxTemp - minTemp
        val graphTop = 44f
        val graphHeight = (heightPx - 30f) - graphTop
        val tempToY = { t: Float -> graphTop + graphHeight * (1f - (t - minTemp) / range) }

        val minTimeEpoch = hours.first().dateTime.toEpochSecond(ZoneOffset.UTC)
        val hourWidth = widthPx.toFloat() / hours.size.toFloat()
        fun xAt(i: Int) = ((hours[i].dateTime.toEpochSecond(ZoneOffset.UTC) - minTimeEpoch) / 3600f) * hourWidth
        val originalPoints = hours.indices.map { xAt(it) to tempToY(hours[it].actualTemperature ?: hours[it].temperature) }
        val forecastPoints = hours.indices.map { xAt(it) to tempToY(hours[it].temperature) }

        val fetchTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(observedAt), ZoneId.systemDefault())
        val effectiveActualEndIndex = hours.indexOfLast { !it.dateTime.isAfter(fetchTime) }
        val actualVisiblePoints = originalPoints.take(effectiveActualEndIndex + 1)
        val transitionX = if (effectiveActualEndIndex in hours.indices) originalPoints[effectiveActualEndIndex].first else null

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
            lastObservedTemp = hours.getOrNull(effectiveActualEndIndex)?.let { it.actualTemperature ?: it.temperature },
            observedAt = observedAt,
            effectiveActualEndIndex = effectiveActualEndIndex,
            fetchTime = fetchTime,
            numColumns = hours.size,
            tempToY = tempToY,
            metrics = TestLabelTextMetrics(), useCelsius = false,
        )
    }

    @Test
    fun `forecast high nested under a taller coincident actual high stays above its peak with no leader`() {
        // Forecast peaks at idx 6 (91°); the observed line peaks higher at the same idx 6 (97°).
        val forecast = listOf(80f, 83f, 86f, 88f, 90f, 90.5f, 91f, 90.5f, 90f, 88f, 86f, 83f)
        val actual = listOf<Float?>(85f, 88f, 92f, 94f, 96f, 96.8f, 97f, 96.8f, 96f, 94f, 92f, 88f)
        val hours = buildHours(forecast, actual)
        val observedAt = hours.last().dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val placements = runEngineTest(hours, widthPx = 700, heightPx = 400, observedAt = observedAt)

        val forecastHigh = placements.find {
            (it.role == TemperatureRole.HIGH || it.role == TemperatureRole.FORECAST_HIGH) && it.displayTemperature == 91f
        }
        val actualHigh = placements.find { it.role == TemperatureRole.ACTUAL_HIGH }

        assertNotNull("Forecast HIGH (91°) should be labeled. placements=$placements", forecastHigh)
        assertNotNull("Coincident ACTUAL_HIGH (97°) should be labeled. placements=$placements", actualHigh)
        assertTrue(
            "Forecast HIGH must stay above its own peak, not curve-avoid up (reason=${forecastHigh!!.reason})",
            forecastHigh.placedAbove,
        )
        assertFalse(
            "Forecast HIGH must NOT draw a leader line (reason=${forecastHigh.reason}, steps=${forecastHigh.displacementSteps})",
            forecastHigh.drawLeaderLine,
        )
        // The actual high also stays above its (taller) peak, clearly higher than the forecast label.
        assertTrue("ACTUAL_HIGH should stay above its peak", actualHigh!!.placedAbove)
        assertTrue(
            "ACTUAL_HIGH (taller) should sit higher on screen than the forecast HIGH. " +
                "actualHigh.y=${actualHigh.baselineY} forecastHigh.y=${forecastHigh.baselineY}",
            actualHigh.baselineY < forecastHigh.baselineY,
        )
    }

    @Test
    fun `forecast low nested above a deeper coincident actual low stays below its valley with no leader`() {
        // Forecast valleys at idx 6 (59°); the observed line dips deeper at the same idx 6 (52°).
        val forecast = listOf(70f, 67f, 64f, 62f, 60f, 59.5f, 59f, 59.5f, 60f, 62f, 64f, 67f)
        val actual = listOf<Float?>(66f, 62f, 58f, 55f, 53f, 52.4f, 52f, 52.4f, 53f, 55f, 58f, 62f)
        val hours = buildHours(forecast, actual)
        val observedAt = hours.last().dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val placements = runEngineTest(hours, widthPx = 700, heightPx = 400, observedAt = observedAt)

        val forecastLow = placements.find {
            (it.role == TemperatureRole.LOW || it.role == TemperatureRole.FORECAST_LOW) && it.displayTemperature == 59f
        }
        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW }

        assertNotNull("Forecast LOW (59°) should be labeled. placements=$placements", forecastLow)
        assertNotNull("Coincident ACTUAL_LOW (52°) should be labeled. placements=$placements", actualLow)
        assertFalse(
            "Forecast LOW must stay below its own valley (reason=${forecastLow!!.reason})",
            forecastLow.placedAbove,
        )
        assertFalse(
            "Forecast LOW must NOT draw a leader line (reason=${forecastLow.reason}, steps=${forecastLow.displacementSteps})",
            forecastLow.drawLeaderLine,
        )
    }

    // The Android case: at high point density the coincident actual extreme lands a few indices away
    // from the forecast extreme (not the same index). Forecast-only curve avoidance must still place
    // the forecast label flush with no leader line, regardless of that index gap.

    @Test
    fun `forecast high nested under a taller actual high at a different nearby index has no leader`() {
        // Forecast peaks at idx 6 (91°); the observed line peaks higher and a couple indices earlier
        // at idx 4 (97°). The actual curve still towers over the forecast peak at idx 6.
        val forecast = listOf(80f, 83f, 86f, 88f, 90f, 90.5f, 91f, 90.5f, 90f, 88f, 86f, 83f)
        val actual = listOf<Float?>(85f, 90f, 94f, 96f, 97f, 96f, 95f, 93f, 91f, 89f, 87f, 84f)
        val hours = buildHours(forecast, actual)
        val observedAt = hours.last().dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val placements = runEngineTest(hours, widthPx = 700, heightPx = 400, observedAt = observedAt)

        val forecastHigh = placements.find {
            (it.role == TemperatureRole.HIGH || it.role == TemperatureRole.FORECAST_HIGH) && it.displayTemperature == 91f
        }
        assertNotNull("Forecast HIGH (91°) should be labeled. placements=$placements", forecastHigh)
        assertTrue("Forecast HIGH stays above its own peak (reason=${forecastHigh!!.reason})", forecastHigh.placedAbove)
        assertFalse(
            "Forecast HIGH must NOT draw a leader line despite the non-coincident taller actual " +
                "(reason=${forecastHigh.reason}, steps=${forecastHigh.displacementSteps})",
            forecastHigh.drawLeaderLine,
        )
        assertEquals("No displacement expected", 0, forecastHigh.displacementSteps)
    }

    @Test
    fun `forecast low nested above a deeper actual low at a different nearby index has no leader`() {
        // Forecast valleys at idx 6 (59°); the observed line dips deeper a couple indices earlier at
        // idx 4 (52°), still below the forecast valley at idx 6.
        val forecast = listOf(70f, 67f, 64f, 62f, 60f, 59.5f, 59f, 59.5f, 60f, 62f, 64f, 67f)
        val actual = listOf<Float?>(66f, 60f, 56f, 53f, 52f, 53f, 54f, 55f, 56f, 58f, 60f, 63f)
        val hours = buildHours(forecast, actual)
        val observedAt = hours.last().dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val placements = runEngineTest(hours, widthPx = 700, heightPx = 400, observedAt = observedAt)

        val forecastLow = placements.find {
            (it.role == TemperatureRole.LOW || it.role == TemperatureRole.FORECAST_LOW) && it.displayTemperature == 59f
        }
        assertNotNull("Forecast LOW (59°) should be labeled. placements=$placements", forecastLow)
        assertFalse("Forecast LOW stays below its own valley (reason=${forecastLow!!.reason})", forecastLow.placedAbove)
        assertFalse(
            "Forecast LOW must NOT draw a leader line despite the non-coincident deeper actual " +
                "(reason=${forecastLow.reason}, steps=${forecastLow.displacementSteps})",
            forecastLow.drawLeaderLine,
        )
    }
}
