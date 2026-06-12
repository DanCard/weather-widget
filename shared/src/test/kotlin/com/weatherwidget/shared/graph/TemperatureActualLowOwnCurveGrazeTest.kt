package com.weatherwidget.shared.graph

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Desktop "Thu 11 / 60.6°" bug: the ACTUAL_LOW label was flipped ABOVE its valley because the
 * observed line's own sub-hourly/smoothed dip a few px below the labeled minimum grazed the
 * below-box. The forecast curve was far away. ACTUAL_LOW must avoid only the FORECAST curve (it
 * labels its own actual line), so it sits below when the forecast is clear, and only flips above
 * when the forecast genuinely dips below the valley. See plans/samsung-clash-of-labels-*.md.
 */
class TemperatureActualLowOwnCurveGrazeTest {

    private val widthPx = 600
    private val heightPx = 400
    private val graphTop = 44f
    private val graphBottom = heightPx - 30f
    private val graphHeight = graphBottom - graphTop

    private class TestMetrics : LabelTextMetrics {
        override val ascent = -10f
        override val descent = 2f
        override fun width(text: String, isFuture: Boolean): Float = text.length * 6f
    }

    /** All-observed valley at [lowIdx] (value [lowTemp]); forecast given per-hour. */
    private fun buildHours(forecast: List<Float>, actual: List<Float>): List<HourData> {
        val start = LocalDateTime.of(2026, 4, 8, 0, 0)
        return forecast.indices.map { i ->
            HourData(
                dateTime = start.plusHours(i.toLong()),
                temperature = forecast[i],
                actualTemperature = actual[i],
                isActual = true,
                label = "${start.plusHours(i.toLong()).hour}",
                showLabel = true,
            )
        }
    }

    private fun run(
        forecast: List<Float>,
        actual: List<Float>,
        // optional extra actual-line point (x-fraction across graph, temp) to simulate a sub-hourly
        // dip below the labeled minimum.
        extraActualDip: Pair<Float, Float>? = null,
    ): List<PlacedLabel> {
        val hours = buildHours(forecast, actual)
        val minTemp = (forecast + actual).min() - 5f
        val maxTemp = (forecast + actual).max() + 5f
        val range = maxTemp - minTemp
        val tempToY = { t: Float -> graphTop + graphHeight * (1f - (t - minTemp) / range) }

        val minEpoch = hours.first().dateTime.toEpochSecond(ZoneOffset.UTC)
        val hourWidth = widthPx.toFloat() / hours.size
        fun xAt(i: Int) = ((hours[i].dateTime.toEpochSecond(ZoneOffset.UTC) - minEpoch) / 3600f) * hourWidth

        val originalPoints = hours.indices.map { xAt(it) to tempToY(actual[it]) }
        val forecastPoints = hours.indices.map { xAt(it) to tempToY(forecast[it]) }
        // Observed line = the actual points, plus an optional injected sub-hourly dip.
        val actualVisiblePoints = originalPoints.toMutableList().also { pts ->
            extraActualDip?.let { (frac, temp) ->
                val x = frac * widthPx
                val insertAt = pts.indexOfFirst { it.first > x }.let { if (it < 0) pts.size else it }
                pts.add(insertAt, x to tempToY(temp))
            }
        }
        // Observe through the whole window so the valley is in the visible (past) actual line.
        val observedAt = hours.last().dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val fetchTime = hours.last().dateTime

        return TemperatureLabelEngine.computePlacements(
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            density = 1f,
            originalPoints = originalPoints,
            forecastPoints = forecastPoints,
            actualVisiblePoints = actualVisiblePoints,
            transitionX = xAt(hours.lastIndex),
            fetchDotX = xAt(hours.lastIndex),
            lastObservedTemp = actual.last(),
            observedAt = observedAt,
            effectiveActualEndIndex = hours.lastIndex,
            fetchTime = fetchTime,
            numColumns = hours.size,
            tempToY = tempToY,
            metrics = TestMetrics(),
        )
    }

    @Test
    fun `actual low stays below its valley when only its own line grazes the below-box`() {
        // Flat-high forecast (80) far above the actual valley (67 at idx 6). The observed line dips
        // ~one degree below the labeled minimum just past the valley (sub-hourly graze).
        val forecast = List(13) { 80f }
        val actual = listOf(80f, 78f, 75f, 72f, 70f, 68f, 67f, 68f, 70f, 73f, 76f, 78f, 80f)
        val placements = run(forecast, actual, extraActualDip = 6.5f / 13f to 66f)

        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW }
        assertNotNull("ACTUAL_LOW (67°) should be labeled. placements=$placements", actualLow)
        assertFalse(
            "ACTUAL_LOW must stay below its valley despite its own line grazing the below-box " +
                "(reason=${actualLow!!.reason}, placedAbove=${actualLow.placedAbove})",
            actualLow.placedAbove,
        )
    }

    @Test
    fun `actual low still flips above when the forecast curve dips below the valley`() {
        // Forecast dips to 60 around the actual valley (67) — genuinely below it — so the ACTUAL_LOW
        // below-box is occupied by the forecast curve and the label must flip above (preserved).
        val forecast = listOf(80f, 78f, 74f, 70f, 66f, 62f, 61f, 60f, 62f, 66f, 72f, 78f, 80f)
        val actual = listOf(82f, 80f, 78f, 75f, 72f, 69f, 67f, 69f, 72f, 75f, 78f, 80f, 82f)
        val placements = run(forecast, actual)

        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW }
        assertNotNull("ACTUAL_LOW (67°) should be labeled. placements=$placements", actualLow)
        assertTrue(
            "ACTUAL_LOW must flip above when the forecast curve dips below its valley " +
                "(reason=${actualLow!!.reason}, placedAbove=${actualLow.placedAbove})",
            actualLow.placedAbove,
        )
    }
}
