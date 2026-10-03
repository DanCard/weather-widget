package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime

/**
 * Past-forecast high/low follow the actual series' edge rule: a window edge (left edge or NOW) is
 * never an extreme, and a more-extreme edge blocks any lesser interior substitute.
 *
 * Desktop 2026-10-03: the forecast was still falling (80 → 64, flat at 64 across NOW, then 63 at
 * 04:00), and a bare minByOrNull labelled the start of the flat run "64°" as PAST_FORECAST_LOW.
 * See plans/261003-past-forecast-extrema-edge-rule.md.
 */
@Category(ShortDuration::class)
class TemperatureExtremaPastForecastEdgeTest {

    private fun hours(forecast: List<Float>, nowIdx: Int): List<HourData> {
        val start = LocalDateTime.of(2026, 10, 2, 18, 0)
        return forecast.indices.map { i ->
            val dt = start.plusHours(i.toLong())
            HourData(
                dateTime = dt,
                temperature = forecast[i],
                label = "${dt.hour}h",
                isActual = i <= nowIdx,
                actualTemperature = forecast[i] - 0.5f,
            )
        }
    }

    private fun extrema(forecast: List<Float>, nowIdx: Int) =
        TemperatureLabelResolver.computeExtremaIndices(hours(forecast, nowIdx), 150f, nowIdx, null, useCelsius = false)

    @Test
    fun `plateau running into NOW is not a past-forecast low`() {
        // idx:              0    1    2    3    4    5    6    7   (NOW=7)  8    9   10   11
        val f = listOf(80f, 76f, 72f, 68f, 66f, 64f, 64f, 64f, 64f, 63f, 69f, 86f)
        val e = extrema(f, nowIdx = 7)
        assertEquals("flat run reaching NOW must not be labelled", -1, e.pastForecastLowIndex)
    }

    @Test
    fun `monotonic descent to NOW has no past-forecast low`() {
        val f = listOf(80f, 76f, 72f, 68f, 66f, 64f, 63f, 62f, 61f, 70f)
        assertEquals(-1, extrema(f, nowIdx = 6).pastForecastLowIndex)
    }

    @Test
    fun `interior valley before NOW is labelled, at the plateau's first sample`() {
        val f = listOf(70f, 66f, 62f, 62f, 65f, 68f, 71f, 73f, 70f)
        assertEquals(2, extrema(f, nowIdx = 6).pastForecastLowIndex)
    }

    @Test
    fun `warm left edge is not a past-forecast high and no lesser bump substitutes`() {
        // Absolute max is the left edge (80); the interior bump at idx 3 (74) must NOT stand in.
        val f = listOf(80f, 76f, 72f, 74f, 70f, 66f, 64f, 70f, 75f)
        assertEquals(-1, extrema(f, nowIdx = 6).pastForecastHighIndex)
    }

    @Test
    fun `interior peak before NOW is a past-forecast high`() {
        val f = listOf(60f, 66f, 72f, 75f, 72f, 66f, 62f, 64f, 70f)
        assertEquals(3, extrema(f, nowIdx = 6).pastForecastHighIndex)
    }
}
