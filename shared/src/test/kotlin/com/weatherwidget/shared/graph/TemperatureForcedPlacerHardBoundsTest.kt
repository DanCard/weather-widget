package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Regression for the forced-direction placers ignoring hard bounds. `placeActualHighAboveCurve` /
 * `placeActualLowBelowCurve` previously checked only already-placed labels, so an ACTUAL_HIGH whose
 * text differed from the fetch-dot value could be drawn across the fetch-dot's pink hard bound (the
 * same "drawn on top of the dot label" class of bug `reservedHardBounds` was introduced to prevent).
 * After the CollisionTester extraction they must step over (or drop for) hard bounds and icons
 * exactly as the main placement loop does.
 */
@Category(ShortDuration::class)
class TemperatureForcedPlacerHardBoundsTest {

    private class WideMetrics(
        val charWidth: Float = 20f,
        override val ascent: Float = -14f,
        override val descent: Float = 4f,
    ) : LabelTextMetrics {
        override fun width(text: String, isFuture: Boolean): Float = text.length * charWidth
    }

    private fun buildHours(
        forecastTemps: List<Float>,
        actualTemps: List<Float?>,
        start: LocalDateTime,
    ): List<HourData> =
        forecastTemps.mapIndexed { index, temp ->
            val dateTime = start.plusHours(index.toLong())
            HourData(
                dateTime = dateTime,
                temperature = temp,
                actualTemperature = actualTemps[index],
                isActual = actualTemps[index] != null,
                label = "${dateTime.hour}",
                showLabel = true,
            )
        }

    private fun place(
        hours: List<HourData>,
        widthPx: Int,
        heightPx: Int,
        observedAt: Long,
        metrics: LabelTextMetrics = WideMetrics(),
        reservedHardBounds: List<GraphRect> = emptyList(),
    ): List<PlacedLabel> {
        val allTemps = hours.flatMap { listOf(it.temperature, it.actualTemperature ?: it.temperature) }
        val minTemp = (allTemps.minOrNull() ?: 50f) - 5f
        val maxTemp = (allTemps.maxOrNull() ?: 80f) + 5f
        val tempRange = maxTemp - minTemp
        val graphTop = 44f
        val graphBottom = heightPx - 30f
        val graphHeight = graphBottom - graphTop
        val tempToY = { t: Float -> graphTop + graphHeight * (1f - (t - minTemp) / tempRange) }

        val minTimeEpoch = hours.first().dateTime.toEpochSecond(ZoneOffset.UTC)
        val hourWidth = widthPx.toFloat() / hours.size.toFloat()
        val originalPoints = hours.map { h ->
            val x = ((h.dateTime.toEpochSecond(ZoneOffset.UTC) - minTimeEpoch) / 3600f) * hourWidth
            x to tempToY(h.actualTemperature ?: h.temperature)
        }
        val forecastPoints = hours.map { h ->
            val x = ((h.dateTime.toEpochSecond(ZoneOffset.UTC) - minTimeEpoch) / 3600f) * hourWidth
            x to tempToY(h.temperature)
        }

        val fetchTime = java.time.Instant.ofEpochMilli(observedAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val fetchIdx = hours.indexOfLast { !it.dateTime.isAfter(fetchTime) }
        val transitionX = originalPoints.getOrNull(fetchIdx)?.first

        return TemperatureLabelEngine.computePlacements(
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            density = 1f,
            originalPoints = originalPoints,
            forecastPoints = forecastPoints,
            actualVisiblePoints = originalPoints.take(fetchIdx + 1),
            transitionX = transitionX,
            fetchDotX = transitionX,
            lastObservedTemp = hours.getOrNull(fetchIdx)?.let { it.actualTemperature ?: it.temperature },
            observedAt = observedAt,
            effectiveActualEndIndex = fetchIdx,
            fetchTime = fetchTime,
            numColumns = hours.size,
            tempToY = tempToY,
            metrics = metrics,
            reservedHardBounds = reservedHardBounds,
            useCelsius = false,
        )
    }

    private fun boxOf(p: PlacedLabel, metrics: WideMetrics): GraphRect {
        val halfWidth = p.text.length * metrics.charWidth / 2f
        return GraphRect(
            p.x - halfWidth,
            p.baselineY + metrics.ascent,
            p.x + halfWidth,
            p.baselineY + metrics.descent,
        )
    }

    @Test
    fun `forced actual high steps over a hard bound instead of drawing across it`() {
        val hours = 24
        val start = LocalDateTime.of(2026, 8, 7, 0, 0)
        val forecast = MutableList(hours) { 56f }
        val actual = MutableList<Float?>(hours) { 60f }
        actual[14] = 77.4f // single afternoon observed peak

        val metrics = WideMetrics()
        val observedAt = start.plusHours(23).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val control = place(buildHours(forecast, actual, start), 300, 400, observedAt)
        val controlHigh = control.firstOrNull { it.role == TemperatureRole.ACTUAL_HIGH }
        assertNotNull("fixture must produce an ACTUAL_HIGH via the forced placer", controlHigh)

        // Park a hard bound exactly where the forced ACTUAL_HIGH landed in the control run. The
        // placer must step up over it (or drop) — it must never draw across it.
        val hard = boxOf(controlHigh!!, metrics)

        val fixed = place(buildHours(forecast, actual, start), 300, 400, observedAt, reservedHardBounds = listOf(hard))
        for (h in fixed.filter { it.role == TemperatureRole.ACTUAL_HIGH }) {
            assertFalse(
                "forced ACTUAL_HIGH must not be drawn across the hard bound: '${h.text}' box=${boxOf(h, metrics)} hard=$hard",
                boxOf(h, metrics).intersects(hard),
            )
        }
    }
}
