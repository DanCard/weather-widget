package com.weatherwidget.shared.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TemperatureLabelCollisionOrderTest {

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

    private data class ComputedPoints(
        val originalPoints: List<Pair<Float, Float>>,
        val forecastPoints: List<Pair<Float, Float>>,
        val actualVisiblePoints: List<Pair<Float, Float>>,
        val transitionX: Float?,
        val effectiveActualEndIndex: Int,
        val fetchDotX: Float?,
    )

    private fun computePointsForTest(
        hours: List<HourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        observedAt: Long?,
        minTemp: Float,
        maxTemp: Float,
    ): ComputedPoints {
        val tempRange = maxTemp - minTemp
        val graphTop = 44f
        val graphBottom = heightPx - 30f
        val graphHeight = graphBottom - graphTop
        val hourWidth = widthPx.toFloat() / hours.size.toFloat()
        
        val minTimeEpoch = hours.first().dateTime.toEpochSecond(java.time.ZoneOffset.UTC)
        val SECONDS_PER_HOUR = 3600f

        val originalPoints = mutableListOf<Pair<Float, Float>>()
        val forecastPoints = mutableListOf<Pair<Float, Float>>()

        fun tempToY(t: Float): Float = graphTop + graphHeight * (1f - (t - minTemp) / tempRange)

        hours.indices.forEach { index ->
            val pointEpoch = hours[index].dateTime.toEpochSecond(java.time.ZoneOffset.UTC)
            val x = ((pointEpoch - minTimeEpoch) / SECONDS_PER_HOUR) * hourWidth
            
            val yActual = tempToY(hours[index].actualTemperature ?: hours[index].temperature)
            originalPoints.add(x to yActual)

            val yForecast = tempToY(hours[index].temperature)
            forecastPoints.add(x to yForecast)
        }

        val fetchTime = observedAt?.let {
            java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }
        val fetchIdx = fetchTime?.let { time -> hours.indexOfLast { !it.dateTime.isAfter(time) } } ?: -1
        
        val effectiveActualEndIndex = fetchIdx
        val transitionX = if (fetchIdx in hours.indices) originalPoints[fetchIdx].first else null
        val fetchDotX = transitionX

        val actualVisiblePoints = originalPoints.take(effectiveActualEndIndex + 1)

        return ComputedPoints(
            originalPoints = originalPoints,
            forecastPoints = forecastPoints,
            actualVisiblePoints = actualVisiblePoints,
            transitionX = transitionX,
            effectiveActualEndIndex = effectiveActualEndIndex,
            fetchDotX = fetchDotX,
        )
    }

    private class TestLabelTextMetrics(
        val charWidth: Float = 6f,
        override val ascent: Float = -10f,
        override val descent: Float = 2f
    ) : LabelTextMetrics {
        override fun width(text: String, isFuture: Boolean): Float = text.length * charWidth
    }

    private fun runEngineTest(
        hours: List<HourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        observedAt: Long?,
        metrics: LabelTextMetrics = TestLabelTextMetrics()
    ): List<PlacedLabel> {
        val allTemps = hours.flatMap { listOf(it.temperature, it.actualTemperature ?: it.temperature) }
        val minTemp = (allTemps.minOrNull() ?: 50f) - 5f
        val maxTemp = (allTemps.maxOrNull() ?: 80f) + 5f
        val tempRange = maxTemp - minTemp
        val graphTop = 44f
        val graphBottom = heightPx - 30f
        val graphHeight = graphBottom - graphTop
        val tempToY = { t: Float -> graphTop + graphHeight * (1f - (t - minTemp) / tempRange) }

        val pts = computePointsForTest(hours, widthPx, heightPx, currentTime, observedAt, minTemp, maxTemp)
        
        val fetchTime = observedAt?.let {
            java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }

        return TemperatureLabelEngine.computePlacements(
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            density = 1f,
            originalPoints = pts.originalPoints,
            forecastPoints = pts.forecastPoints,
            actualVisiblePoints = pts.actualVisiblePoints,
            transitionX = pts.transitionX,
            fetchDotX = pts.fetchDotX,
            lastObservedTemp = if (observedAt != null && pts.effectiveActualEndIndex in hours.indices) {
                val h = hours[pts.effectiveActualEndIndex]
                h.actualTemperature ?: h.temperature
            } else null,
            observedAt = observedAt,
            effectiveActualEndIndex = pts.effectiveActualEndIndex,
            fetchTime = fetchTime,
            numColumns = hours.size,
            tempToY = tempToY,
            metrics = metrics,
        )
    }

    @Test
    fun `when two valleys collide the lower temperature should be on the bottom`() {
        val forecast = MutableList(24) { 60f }
        forecast[10] = 52f
        val actual = MutableList<Float?>(24) { null }
        // Observed valley with neighbours on both sides so idx 12 is a genuine turning point
        // (a lone edge sample is no longer treated as an actual extreme).
        actual[11] = 52f
        actual[12] = 50f
        actual[13] = 52f

        val observedAt = LocalDateTime.of(2026, 4, 8, 13, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val placements = runEngineTest(
            hours = buildHours(forecast, actual),
            widthPx = 300,
            heightPx = 400,
            currentTime = LocalDateTime.of(2026, 4, 8, 15, 0),
            observedAt = observedAt
        )

        val low52 = placements.find { it.displayTemperature == 52f }
        val low50 = placements.find { it.displayTemperature == 50f }

        assertNotNull("Label for 52 should be placed", low52)
        assertNotNull("Label for 50 should be placed", low50)

        // Lower temperature (50) should be on the bottom (larger Y)
        assertTrue(
            "Expected 50° label to be below 52° label. 50_y=${low50!!.baselineY}, 52_y=${low52!!.baselineY}",
            low50.baselineY > low52.baselineY
        )
    }

    @Test
    fun `when two peaks collide the higher temperature should be on top`() {
        val forecast = MutableList(24) { 70f }
        forecast[10] = 85f
        val actual = MutableList<Float?>(24) { null }
        // Observed peak with neighbours on both sides so idx 12 is a genuine turning point.
        actual[11] = 85f
        actual[12] = 87f
        actual[13] = 85f

        val observedAt = LocalDateTime.of(2026, 4, 8, 13, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val placements = runEngineTest(
            hours = buildHours(forecast, actual),
            widthPx = 300,
            heightPx = 400,
            currentTime = LocalDateTime.of(2026, 4, 8, 15, 0),
            observedAt = observedAt
        )

        val high85 = placements.find { it.displayTemperature == 85f }
        val high87 = placements.find { it.displayTemperature == 87f }

        assertNotNull("Label for 85 should be placed", high85)
        assertNotNull("Label for 87 should be placed", high87)

        // Higher temperature (87) should be on top (smaller Y)
        assertTrue(
            "Expected 87° label to be above 85° label. 87_y=${high87!!.baselineY}, 85_y=${high85!!.baselineY}",
            high87.baselineY < high85.baselineY
        )
    }

    @Test
    fun `warmer forecast low flips above a heavily overlapping colder actual low`() {
        val forecast = MutableList(48) { 70f }
        forecast[12] = 81f
        forecast[24] = 54f
        forecast[25] = 54f
        forecast[26] = 54f
        val actual = MutableList<Float?>(48) { null }
        // Observed valley with neighbours so idx 25 is a genuine turning point; idx 24/26 are the
        // (edge) flanks and carry no actual label themselves.
        actual[24] = 54f
        actual[25] = 53.2f
        actual[26] = 54f

        val observedAt = LocalDateTime.of(2026, 4, 9, 2, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val placements = runEngineTest(
            hours = buildHours(forecast, actual),
            widthPx = 900,
            heightPx = 400,
            currentTime = LocalDateTime.of(2026, 4, 9, 12, 0),
            observedAt = observedAt,
            metrics = TestLabelTextMetrics(ascent = -20f, descent = 4f)
        )

        val forecastLow = placements.find { it.displayTemperature == 54f }
        val actualLow = placements.find { it.displayTemperature == 53.2f }

        assertNotNull("Forecast LOW (54) should be placed", forecastLow)
        assertNotNull("ACTUAL_LOW (53) should be placed", actualLow)

        // Warmer label (54) lifted above the line; colder label (53) stays below.
        assertTrue(
            "Expected warmer 54° to be placed above. forecastLow=$forecastLow",
            forecastLow!!.placedAbove
        )
        assertTrue(
            "Expected colder 53° to stay below. actualLow=$actualLow",
            !actualLow!!.placedAbove
        )
        assertTrue(
            "Expected 54° above 53°. 54_y=${forecastLow.baselineY}, 53_y=${actualLow.baselineY}",
            forecastLow.baselineY < actualLow.baselineY
        )
    }

    @Test
    fun `forecast low flips above when it rounds equal but is rawer warmer than actual low`() {
        val forecast = MutableList(48) { 70f }
        forecast[12] = 81f
        forecast[24] = 50f
        forecast[25] = 50f
        forecast[26] = 50f
        val actual = MutableList<Float?>(48) { null }
        // Observed valley with neighbours so idx 25 is a genuine turning point.
        actual[24] = 50f
        actual[25] = 49.8f
        actual[26] = 50f

        val observedAt = LocalDateTime.of(2026, 4, 9, 2, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val placements = runEngineTest(
            hours = buildHours(forecast, actual),
            widthPx = 900,
            heightPx = 400,
            currentTime = LocalDateTime.of(2026, 4, 9, 12, 0),
            observedAt = observedAt
        )

        val forecastLow = placements.find { it.role == TemperatureRole.LOW && it.displayTemperature == 50f }
        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW }

        assertNotNull("Forecast LOW (50) should be placed", forecastLow)
        assertNotNull("ACTUAL_LOW (49.8) should be placed", actualLow)

        assertTrue(
            "Expected the rawer-warmer 50° forecast low above. forecastLow=$forecastLow",
            forecastLow!!.placedAbove
        )
        assertTrue(
            "Expected the colder 49.8° actual low below. actualLow=$actualLow",
            !actualLow!!.placedAbove
        )
        assertTrue(
            "Expected 50° above 49.8°. 50_y=${forecastLow.baselineY}, 49.8_y=${actualLow.baselineY}",
            forecastLow.baselineY < actualLow.baselineY
        )
    }

    @Test
    fun `actual low at valley with forecast curve dipping below places tight below trough with no leader`() {
        val start = LocalDateTime.of(2026, 6, 25, 8, 0)
        // 20 hours with valley at index 13 (to stay outside the left-edge start window of 8).
        // Actual dips to 50° (clear valley), forecast dips even further to 45°. On the graph,
        // cooler temps have higher y, so the forecast line extends BELOW the actual trough.
        // The below-direction label must pass through the forecast curve, triggering the
        // tight-below-trough fallback.
        val forecastTemps = listOf(
            70f, 70f, 70f, 70f, 70f, 70f, 70f, 70f,
            70f, 65f, 60f, 55f, 52f, 48f, 53f, 58f, 63f, 68f, 72f, 75f
        )
        val actualTemps = listOf(
            70f, 70f, 70f, 70f, 70f, 70f, 70f, 70f,
            70f, 65f, 60f, 55f, 52f, 50f, 53f, 58f, 63f, 68f, 72f, 75f
        )
        val hours = buildHours(forecastTemps, actualTemps, start)
        val observedAt = start.plusHours(19).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val placements = runEngineTest(
            hours = hours,
            widthPx = 800,
            heightPx = 400,
            currentTime = start.plusHours(19),
            observedAt = observedAt,
        )

        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW }
        assertNotNull("Expected ACTUAL_LOW label. placements=$placements", actualLow)
        System.err.println("DEBUG actualLow=$actualLow")
        assertFalse(
            "ACTUAL_LOW should be placed BELOW the trough, not above. placement=$actualLow",
            actualLow!!.placedAbove,
        )
        assertFalse(
            "ACTUAL_LOW should have no leader line (tight below trough). placement=$actualLow",
            actualLow.drawLeaderLine,
        )
        assertEquals(
            "ACTUAL_LOW reason should be belowActualCurve",
            "belowActualCurve",
            actualLow.reason,
        )
    }
}
