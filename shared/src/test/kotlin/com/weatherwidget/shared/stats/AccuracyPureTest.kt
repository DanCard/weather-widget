package com.weatherwidget.shared.stats

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Accuracy is scored at the stored precision (plans/261105-store-full-precision-forecast-temps.md).
 * Until then both sides were rounded to whole degrees first, a leftover of the 2026-02-25 Int-to-Float
 * move: 72.4 vs 72.6 scored 1°, and a 3.4° miss counted as within 3°.
 */
@Category(ShortDuration::class)
class AccuracyPureTest {
    private fun day(actualHigh: Float, forecastHigh: Float, actualLow: Float = 50f, forecastLow: Float = 50f) =
        AccuracyPure.buildDailyAccuracy("2026-11-01", actualHigh, actualLow, forecastHigh, forecastLow, "NWS")!!

    @Test
    fun `error is the raw difference, actual minus forecast`() {
        val d = day(actualHigh = 72.6f, forecastHigh = 72.4f)
        println("AccuracyPureTest error: high=${d.highError} low=${d.lowError}")
        assertEquals(0.2f, d.highError, 0.001f) // old: 73 - 72 = 1
        assertEquals(72.6f, d.computedHighTemp, 0.001f)
        assertEquals(72.4f, d.forecastHigh, 0.001f)
    }

    @Test
    fun `a 3_4 degree miss is not within 3 degrees`() {
        val stats = AccuracyPure.computeStatistics(
            listOf(day(actualHigh = 75.4f, forecastHigh = 72f), day(actualHigh = 70f, forecastHigh = 70f)),
            "NWS",
            30,
        )!!
        assertEquals(50.0, stats.percentWithin3Degrees, 0.001) // old: 3.4 rounded to 3, 100%
        assertEquals(3.4, stats.maxError, 0.001)
        assertEquals(1.7, stats.avgHighError, 0.001)
        assertEquals(1.7, stats.highBias, 0.001)
    }

    @Test
    fun `sub-degree errors average instead of snapping to 0 or 1`() {
        val stats = AccuracyPure.computeStatistics(
            listOf(day(actualHigh = 72.6f, forecastHigh = 72.4f), day(actualHigh = 73.4f, forecastHigh = 72.6f)),
            "NWS",
            30,
        )!!
        // Old: 73-72=1 and 73-73=0. Raw: 0.2 and 0.8. Same 0.5 average, but the max tells them apart.
        assertEquals(0.5, stats.avgHighError, 0.001)
        assertEquals(0.8, stats.maxError, 0.001) // old: 1
    }
}
