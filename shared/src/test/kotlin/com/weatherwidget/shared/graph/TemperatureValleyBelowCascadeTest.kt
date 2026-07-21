package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TemperatureValleyBelowCascadeTest {

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
            metrics = metrics, useCelsius = false,
        )
    }

    @Test
    fun `valley below cascade prefers horizontal shift when overlap is partial`() {
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
            observedAt = observedAt,
        )

        val low52 = placements.find { it.displayTemperature == 52f }
        val low50 = placements.find { it.displayTemperature == 50f }

        assertTrue("Label for 52 should be placed", low52 != null)
        assertTrue("Label for 50 should be placed", low50 != null)
        assertTrue(
            "Both valley labels should be placed below (52 reason=${low52!!.reason}, 50 reason=${low50!!.reason})",
            !low52.placedAbove && !low50.placedAbove
        )
        assertTrue(
            "Lower temperature (50) should be below higher (52). 50_y=${low50.baselineY}, 52_y=${low52.baselineY}",
            low50.baselineY > low52.baselineY
        )
    }

    @Test
    fun `cascade falls back to above when all below options fail`() {
        val forecast = MutableList(24) { 60f }
        forecast[0] = 55f
        forecast[1] = 55f
        forecast[2] = 55f
        forecast[3] = 55f
        forecast[4] = 55f
        val actual = MutableList<Float?>(24) { null }
        for (i in 0..4) actual[i] = 55f
        actual[2] = 54f

        val observedAt = LocalDateTime.of(2026, 4, 8, 13, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val placements = runEngineTest(
            hours = buildHours(forecast, actual),
            widthPx = 200,
            heightPx = 200,
            currentTime = LocalDateTime.of(2026, 4, 8, 15, 0),
            observedAt = observedAt,
        )

        assertTrue(
            "Should have placed at least one label, got ${placements.size}",
            placements.isNotEmpty()
        )
    }
}
