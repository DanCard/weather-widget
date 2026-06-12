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
 * Regression coverage for the Samsung/desktop "forecast LOW drawn on top of the fetch-dot value
 * label" bug (the garbled "631°"). When the valley forecast LOW's natural below-slot is occupied by
 * the fetch-dot's pink actual-temp label (passed as a HARD obstacle), the engine must flip the LOW
 * above the curve instead of stacking it on top. See plans/samsung-clash-of-labels-*.md.
 */
class TemperatureLabelFetchDotHardBoundsTest {

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
        override val descent: Float = 2f
    ) : LabelTextMetrics {
        override fun width(text: String, isFuture: Boolean): Float = text.length * charWidth
    }

    private val graphTop = 44f
    private fun graphBottom(heightPx: Int) = heightPx - 30f

    private fun tempToYFor(heightPx: Int, minTemp: Float, maxTemp: Float): (Float) -> Float {
        val range = maxTemp - minTemp
        val gh = graphBottom(heightPx) - graphTop
        return { t -> graphTop + gh * (1f - (t - minTemp) / range) }
    }

    private fun runEngineTest(
        hours: List<HourData>,
        widthPx: Int,
        heightPx: Int,
        reservedHardBounds: List<GraphRect> = emptyList(),
        metrics: LabelTextMetrics = TestLabelTextMetrics(),
    ): List<PlacedLabel> {
        val allTemps = hours.flatMap { listOf(it.temperature, it.actualTemperature ?: it.temperature) }
        val minTemp = (allTemps.minOrNull() ?: 50f) - 5f
        val maxTemp = (allTemps.maxOrNull() ?: 80f) + 5f
        val tempToY = tempToYFor(heightPx, minTemp, maxTemp)

        val minTimeEpoch = hours.first().dateTime.toEpochSecond(ZoneOffset.UTC)
        val hourWidth = widthPx.toFloat() / hours.size.toFloat()
        val forecastPoints = hours.map {
            val x = ((it.dateTime.toEpochSecond(ZoneOffset.UTC) - minTimeEpoch) / 3600f) * hourWidth
            x to tempToY(it.temperature)
        }
        val originalPoints = hours.map {
            val x = ((it.dateTime.toEpochSecond(ZoneOffset.UTC) - minTimeEpoch) / 3600f) * hourWidth
            x to tempToY(it.actualTemperature ?: it.temperature)
        }

        return TemperatureLabelEngine.computePlacements(
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            density = 1f,
            originalPoints = originalPoints,
            forecastPoints = forecastPoints,
            actualVisiblePoints = emptyList(),
            transitionX = null,
            fetchDotX = null,
            lastObservedTemp = null,
            observedAt = null,
            effectiveActualEndIndex = -1,
            fetchTime = null,
            numColumns = hours.size,
            tempToY = tempToY,
            metrics = metrics,
            reservedHardBounds = reservedHardBounds,
        )
    }

    /** A wide rect spanning the column just BELOW the valley anchor — where the LOW would normally go. */
    private fun belowSlotHardBound(valleyTemp: Float, valleyIdx: Int, hours: List<HourData>, widthPx: Int, heightPx: Int): GraphRect {
        val allTemps = hours.flatMap { listOf(it.temperature, it.actualTemperature ?: it.temperature) }
        val minTemp = (allTemps.minOrNull() ?: 50f) - 5f
        val maxTemp = (allTemps.maxOrNull() ?: 80f) + 5f
        val anchorY = tempToYFor(heightPx, minTemp, maxTemp)(valleyTemp)
        val hourWidth = widthPx.toFloat() / hours.size.toFloat()
        val cx = valleyIdx * hourWidth
        return GraphRect(cx - 25f, anchorY + 3f, cx + 25f, anchorY + 70f)
    }

    @Test
    fun `valley low flips above the curve when its below-slot is a hard obstacle`() {
        val forecast = MutableList(24) { 60f }
        forecast[12] = 52f
        val actual = MutableList<Float?>(24) { null }
        val hours = buildHours(forecast, actual)

        val hard = belowSlotHardBound(52f, 12, hours, 300, 400)

        // Control: no hard bound — the valley low sits below (reproduces the bug position).
        val control = runEngineTest(hours, 300, 400)
        val controlLow = control.find { it.displayTemperature == 52f }
        assertNotNull("Low label should be placed (control)", controlLow)
        assertFalse("Without hard bound the valley low is placed below", controlLow!!.placedAbove)

        // With the hard bound at its below-slot, the low must flip above the curve.
        val fixed = runEngineTest(hours, 300, 400, reservedHardBounds = listOf(hard))
        val fixedLow = fixed.find { it.displayTemperature == 52f }
        assertNotNull("Low label should still be placed (fixed)", fixedLow)
        assertTrue(
            "Valley low must flip ABOVE when its below-slot is a hard obstacle (reason=${fixedLow!!.reason}, y=${fixedLow.baselineY})",
            fixedLow.placedAbove
        )
    }

    @Test
    fun `hard bound far from the low leaves it below`() {
        val forecast = MutableList(24) { 60f }
        forecast[12] = 52f
        val hours = buildHours(forecast, MutableList(24) { null })

        // A hard bound up in the top-left corner, nowhere near the valley low.
        val farHard = GraphRect(0f, 0f, 20f, 20f)
        val placements = runEngineTest(hours, 300, 400, reservedHardBounds = listOf(farHard))
        val low = placements.find { it.displayTemperature == 52f }
        assertNotNull("Low label should be placed", low)
        assertFalse("A distant hard bound must not push the valley low above", low!!.placedAbove)
    }

    @Test
    fun `empty hard bounds reproduce default placement`() {
        val forecast = MutableList(24) { 60f }
        forecast[12] = 52f
        val hours = buildHours(forecast, MutableList(24) { null })

        val withDefault = runEngineTest(hours, 300, 400)
        val withEmpty = runEngineTest(hours, 300, 400, reservedHardBounds = emptyList())
        assertEquals(withDefault.size, withEmpty.size)
        val a = withDefault.find { it.displayTemperature == 52f }!!
        val b = withEmpty.find { it.displayTemperature == 52f }!!
        assertEquals(a.placedAbove, b.placedAbove)
        assertEquals(a.baselineY, b.baselineY, 0.001f)
    }

    @Test
    fun `peak high flips below when its above-slot is a hard obstacle`() {
        // Symmetric case: if the fetch-dot value label is drawn ABOVE the dot, a forecast HIGH that
        // would naturally sit above the curve must avoid it (flip below) rather than stack on it.
        val forecast = MutableList(24) { 60f }
        forecast[12] = 70f
        val hours = buildHours(forecast, MutableList(24) { null })

        val allTemps = hours.map { it.temperature }
        val minTemp = allTemps.min() - 5f
        val maxTemp = allTemps.max() + 5f
        val anchorY = tempToYFor(400, minTemp, maxTemp)(70f)
        val cx = 12 * (300f / 24f)
        val aboveHard = GraphRect(cx - 25f, anchorY - 70f, cx + 25f, anchorY - 3f)

        val control = runEngineTest(hours, 300, 400)
        val controlHigh = control.find { it.displayTemperature == 70f }
        assertNotNull("High label should be placed (control)", controlHigh)
        assertTrue("Without hard bound the peak high is placed above", controlHigh!!.placedAbove)

        val fixed = runEngineTest(hours, 300, 400, reservedHardBounds = listOf(aboveHard))
        val fixedHigh = fixed.find { it.displayTemperature == 70f }
        assertNotNull("High label should still be placed (fixed)", fixedHigh)
        assertFalse(
            "Peak high must flip BELOW when its above-slot is a hard obstacle (reason=${fixedHigh!!.reason}, y=${fixedHigh.baselineY})",
            fixedHigh.placedAbove
        )
    }
}
