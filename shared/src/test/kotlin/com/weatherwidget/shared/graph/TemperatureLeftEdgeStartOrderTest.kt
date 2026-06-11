package com.weatherwidget.shared.graph

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TemperatureLeftEdgeStartOrderTest {

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
            metrics = TestLabelTextMetrics(),
        )
    }

    @Test
    fun `left-edge start pair is ordered by temperature - warmer actual above cooler forecast`() {
        // Forecast descends from 64 at the left edge (so START=64 is a local max -> defaults above).
        // The actual line is warmer, with a shallow valley at idx 2 (66.9) that resolves to an
        // ACTUAL_LOW near the edge. The pair should be ordered by value: warmer actual (66.9) ABOVE,
        // cooler forecast START (64) BELOW. (Mirrors the live case: actual 66.9 vs forecast 66.)
        val forecast = MutableList(24) { 60f }
        forecast[0] = 64f
        forecast[1] = 63f
        forecast[2] = 62f
        forecast[3] = 61f
        forecast[18] = 90f // global forecast HIGH lives far to the right, so idx 0 stays a START
        val actual = MutableList<Float?>(24) { null }
        actual[0] = 68f
        actual[1] = 67.5f
        actual[2] = 66.9f   // shallow turning point -> ACTUAL_LOW near the left edge, warmer than START
        actual[3] = 67.5f
        actual[4] = 68f
        for (i in 5..12) actual[i] = 68f + (i - 4) // rising so the observed region has data through fetch

        val observedAt = LocalDateTime.of(2026, 4, 8, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val placements = runEngineTest(
            hours = buildHours(forecast, actual),
            widthPx = 600,
            heightPx = 400,
            observedAt = observedAt,
        )

        val start = placements.find { it.role == TemperatureRole.START }
        val leftActual = placements.find {
            it.role in setOf(TemperatureRole.ACTUAL_LOW, TemperatureRole.ACTUAL_HIGH) && it.index <= 8
        }
        assertNotNull("START label should be placed", start)
        assertNotNull("a left-edge actual label should be placed", leftActual)
        assertTrue(
            "warmer actual (${leftActual!!.displayTemperature}) should be ABOVE",
            leftActual.placedAbove,
        )
        assertTrue(
            "cooler forecast START (${start!!.displayTemperature}) should be BELOW",
            !start.placedAbove,
        )
        assertTrue(
            "warmer label should sit higher on screen (smaller y): actual=${leftActual.baselineY} start=${start.baselineY}",
            leftActual.baselineY < start.baselineY,
        )
        // The pair sits flush against its own line start, so neither needs a leader line.
        assertTrue("actual label should have no leader line", !leftActual.drawLeaderLine)
        assertTrue("START label should have no leader line", !start.drawLeaderLine)
    }
}
