package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * ACTUAL_HIGH and (as a fallback) ACTUAL_LOW are placed by two functions that force a direction
 * instead of searching for a free slot — `placeActualHighAboveCurve` / `placeActualLowBelowCurve`.
 * Both originally emitted unconditionally, on the assumption that "above the global observed peak
 * there is only headroom". That holds for *the* peak and fails the moment a window has several, which
 * is how three observed highs ended up printed on top of each other on the desktop graph.
 *
 * These tests pin the yield rule: overlap up to the engine's standard minor-overlap budget is fine
 * (a pink ACTUAL_LOW is *supposed* to graze an amber FORECAST_LOW in the same valley), anything more
 * gets stepped over or dropped.
 */
@Category(ShortDuration::class)
class ActualExtremeLabelStackingTest {

    /** Deliberately wide glyphs: simulates a narrow widget, where labels collide at normal spacing. */
    private class WideMetrics(
        val charWidth: Float = 34f,
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
            useCelsius = false,
        )
    }

    /**
     * Boxes are reconstructed the way the engine builds them: centered on x, spanning ascent..descent
     * from the baseline. Good enough to detect the glyph-on-glyph stacking this guards against.
     */
    private fun boxOf(p: PlacedLabel, metrics: WideMetrics): GraphRect {
        val halfWidth = p.text.length * metrics.charWidth / 2f
        return GraphRect(
            p.x - halfWidth,
            p.baselineY + metrics.ascent,
            p.x + halfWidth,
            p.baselineY + metrics.descent,
        )
    }

    private fun assertNoStacking(placements: List<PlacedLabel>, metrics: WideMetrics) {
        val labelHeight = metrics.descent - metrics.ascent
        val budget = labelHeight * GraphLabelPlacementUtils.MINOR_OVERLAP_HEIGHT_RATIO
        for (i in placements.indices) {
            for (j in i + 1 until placements.size) {
                val a = boxOf(placements[i], metrics)
                val b = boxOf(placements[j], metrics)
                val horizontal = minOf(a.right, b.right) - maxOf(a.left, b.left)
                val vertical = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                if (horizontal <= 0f || vertical <= 0f) continue
                assertTrue(
                    "labels overlap by ${vertical}px (budget ${budget}px): " +
                        "'${placements[i].text}'@${placements[i].role}(${placements[i].x},${placements[i].baselineY}) vs " +
                        "'${placements[j].text}'@${placements[j].role}(${placements[j].x},${placements[j].baselineY})",
                    vertical <= budget + 0.01f,
                )
            }
        }
    }

    /**
     * Two completed days, each with its own observed afternoon peak at the same temperature, on a
     * narrow graph where the two labels' boxes overlap horizontally. Both reach
     * `placeActualHighAboveCurve`, which before the fix stamped the second one straight over the first.
     */
    @Test
    fun `two daily observed highs do not print on top of each other`() {
        val hours = 48
        val start = LocalDateTime.of(2026, 8, 7, 0, 0)
        val forecast = MutableList(hours) { 60f }
        val actual = MutableList<Float?>(hours) { 60f }
        // Day 1 peak at 14:00, day 2 peak at 14:00 — same value, so same y.
        for (i in 0 until hours) actual[i] = 60f + if (i in 12..16 || i in 36..40) 4f else 0f
        actual[14] = 77.4f
        actual[38] = 77.4f
        // Keep the forecast flat and cool so the forecast HIGH does not land on the same spot.
        for (i in 0 until hours) forecast[i] = 58f

        val metrics = WideMetrics()
        val observedAt = start.plusHours(47).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val placements = place(
            hours = buildHours(forecast, actual, start),
            widthPx = 300,
            heightPx = 400,
            observedAt = observedAt,
            metrics = metrics,
        )

        val actualHighs = placements.filter { it.role == TemperatureRole.ACTUAL_HIGH }
        assertTrue(
            "fixture must produce at least two ACTUAL_HIGH labels or it proves nothing; got " +
                placements.map { "${it.role}@${it.x}" },
            actualHighs.size >= 2,
        )
        assertNoStacking(placements, metrics)
    }
}
