package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime

/**
 * Exact-outcome tests for [TemperatureExtrema]: each case states the indices the rule must return for
 * a small hand-built series, so a flipped comparison or an off-by-one in a range changes the result.
 * The scenario tests in [TemperatureExtremaIncompleteDayTest] cover the reported bugs; these cover the
 * rules themselves.
 */
@Category(ShortDuration::class)
class TemperatureExtremaRulesTest {

    private fun at(day: Int, hour: Int, forecast: Float, actual: Float? = null) =
        HourData(
            dateTime = LocalDateTime.of(2026, 4, day, hour, 0),
            temperature = forecast,
            actualTemperature = actual,
            isActual = actual != null,
            label = "${hour}h",
        )

    /** Hourly observed samples on one day, starting at midnight. */
    private fun observed(vararg temps: Float): List<HourData> =
        temps.mapIndexed { i, t -> at(8, i, forecast = t, actual = t) }

    private fun compute(
        hours: List<HourData>,
        nowIndex: Int? = null,
        fetchTime: LocalDateTime? = null,
        prominenceThreshold: Float = 1.5f,
    ) = TemperatureExtrema.compute(
        hours = hours,
        transitionX = if (nowIndex != null) 100f else null,
        effectiveActualEndIndex = nowIndex ?: -1,
        fetchTime = fetchTime,
        prominenceThreshold = prominenceThreshold,
        useCelsius = false,
    )

    // --- findLocalExtremaIndices -----------------------------------------------------------------

    private fun extrema(vararg temps: Float) = TemperatureExtrema.findLocalExtremaIndices(temps.toList())

    @Test
    fun `fewer than three points have no local extrema`() {
        assertEquals(emptyList<Int>(), extrema(1f, 2f))
    }

    @Test
    fun `a strict peak and a strict valley are each found and a slope is not`() {
        assertEquals(listOf(1), extrema(1f, 3f, 2f))
        assertEquals(listOf(1), extrema(3f, 1f, 2f))
        assertEquals(emptyList<Int>(), extrema(1f, 2f, 3f))
        assertEquals(listOf(1, 2, 3), extrema(1f, 3f, 2f, 4f, 0f))
    }

    @Test
    fun `a plateau peak or valley is reported once at its middle`() {
        assertEquals(listOf(2), extrema(1f, 3f, 3f, 3f, 1f))
        assertEquals(listOf(2), extrema(5f, 2f, 2f, 6f))
    }

    @Test
    fun `a plateau that is a step or runs to the end is not an extremum`() {
        assertEquals(emptyList<Int>(), extrema(1f, 3f, 3f, 5f))
        assertEquals(emptyList<Int>(), extrema(1f, 3f, 3f, 3f))
    }

    @Test
    fun `scanning resumes correctly after a plateau`() {
        // Plateau peak 1..2, strict valley 3, plateau peak 4..5.
        assertEquals(listOf(2, 3, 5), extrema(1f, 3f, 3f, 1f, 4f, 4f, 0f))
        // A leading flat run is not a plateau extremum; the valley after it still is.
        assertEquals(listOf(3), extrema(3f, 3f, 3f, 1f, 2f))
    }

    // --- bilateralExtremaProminence --------------------------------------------------------------

    @Test
    fun `prominence is the smaller of the two side swings, each stopping at the next extremum`() {
        val temps = listOf(20f, 12f, 14f, 15f, 9f)
        val all = listOf(1, 2, 3)
        // Index 3 (15): left passes the adjacent extremum 2 (swing 1), stops at extremum 1 (swing 3),
        // and never reaches index 0 (swing 5). Right swing is 6.
        assertEquals(3f, TemperatureExtrema.bilateralExtremaProminence(3, temps, all), 0f)
        // With no other extrema listed the left walk runs to the edge.
        assertEquals(5f, TemperatureExtrema.bilateralExtremaProminence(3, temps, listOf(3)), 0f)
    }

    @Test
    fun `prominence is zero at an edge or on a flat run`() {
        assertEquals(0f, TemperatureExtrema.bilateralExtremaProminence(0, listOf(10f, 14f, 11f), listOf(1)), 0f)
        assertEquals(0f, TemperatureExtrema.bilateralExtremaProminence(1, listOf(5f, 5f, 5f), emptyList()), 0f)
    }

    // --- Whole-window and forecast extrema -------------------------------------------------------

    @Test
    fun `a forecast-only window reports forecast extrema and no actual or past-forecast ones`() {
        val hours = listOf(50f, 55f, 52f, 58f, 51f).mapIndexed { i, t -> at(8, i, t) }
        val e = compute(hours)
        assertEquals(listOf(50f, 55f, 52f, 58f, 51f), e.labelTemps)
        assertEquals(e.labelTemps, e.actualLabelTemps)
        assertEquals(3 to 0, e.dailyHighIndex to e.dailyLowIndex)
        assertEquals(3 to 0, e.forecastHighIndex to e.forecastLowIndex)
        assertEquals(-1 to -1, e.actualHighIndex to e.actualLowIndex)
        assertEquals(-1 to -1, e.pastForecastHighIndex to e.pastForecastLowIndex)
        assertEquals(4, e.actualEndIndex)
        assertEquals(emptyList<Int>(), e.actualDailyHighIndices + e.actualDailyLowIndices)
        assertEquals(emptyList<Int>(), e.actualProminentHighIndices + e.actualProminentLowIndices)
        assertEquals(-1, e.fetchIdx)
    }

    @Test
    fun `local extrema are kept at or above the prominence threshold`() {
        // Prominences: index 1 = 3, index 2 = 3, index 3 = 6.
        val hours = listOf(50f, 55f, 52f, 58f, 51f).mapIndexed { i, t -> at(8, i, t) }
        assertEquals(listOf(1, 2, 3), compute(hours, prominenceThreshold = 3f).significantLocalExtrema)
        assertEquals(listOf(3), compute(hours, prominenceThreshold = 3.5f).significantLocalExtrema)
        assertEquals(emptyList<Int>(), compute(hours, prominenceThreshold = 6.5f).significantLocalExtrema)
    }

    @Test
    fun `the fetch index is the last hour at or before the fetch time`() {
        val hours = listOf(50f, 55f, 52f, 58f, 51f).mapIndexed { i, t -> at(8, i, t) }
        assertEquals(2, compute(hours, fetchTime = LocalDateTime.of(2026, 4, 8, 2, 0)).fetchIdx)
        assertEquals(2, compute(hours, fetchTime = LocalDateTime.of(2026, 4, 8, 2, 59)).fetchIdx)
        assertEquals(-1, compute(hours, fetchTime = LocalDateTime.of(2026, 4, 7, 23, 0)).fetchIdx)
    }

    @Test
    fun `hours with no forecast are never chosen as an extreme`() {
        val hours = listOf(50f, 60f, Float.NaN, Float.NaN).mapIndexed { i, t -> at(8, i, t) }
        val e = compute(hours)
        assertEquals(1 to 0, e.dailyHighIndex to e.dailyLowIndex)
        assertEquals(1 to 0, e.forecastHighIndex to e.forecastLowIndex)
    }

    @Test
    fun `an observed hour without a reading falls back to its forecast temperature`() {
        val hours = listOf(
            at(8, 0, forecast = 50f, actual = 51f),
            at(8, 1, forecast = 55f).copy(isActual = true),
            at(8, 2, forecast = 52f),
        )
        assertEquals(listOf(51f, 55f, 52f), compute(hours).actualLabelTemps)
    }

    // One day; hours 0..3 observed, "now" at index 3, forecast after it.
    private fun splitDay(remainingForecastPeak: Float = 71f) = listOf(
        at(8, 8, forecast = 60f, actual = 61f),
        at(8, 9, forecast = 66f, actual = 66f),
        at(8, 10, forecast = 58f, actual = 63f),
        at(8, 11, forecast = 62f, actual = 65f),
        at(8, 12, forecast = 70f),
        at(8, 13, forecast = remainingForecastPeak),
        at(8, 14, forecast = 68f),
        at(8, 15, forecast = 65f),
    )

    @Test
    fun `with a now boundary each series is searched on its own side`() {
        val e = compute(splitDay(), nowIndex = 3)
        assertEquals(5 to 2, e.dailyHighIndex to e.dailyLowIndex)
        assertEquals(1 to 0, e.actualHighIndex to e.actualLowIndex)
        // Forecast side starts AT the now index: 62, 70, 71, 68, 65.
        assertEquals(5 to 3, e.forecastHighIndex to e.forecastLowIndex)
        // Past forecast 60, 66, 58, 62: both extremes turn inside the segment.
        assertEquals(1 to 2, e.pastForecastHighIndex to e.pastForecastLowIndex)
        assertEquals(3, e.actualEndIndex)
        assertEquals(listOf(1) to listOf(2), e.actualProminentHighIndices to e.actualProminentLowIndices)
        assertEquals(listOf(1, 2, 5), e.significantLocalExtrema)
    }

    @Test
    fun `today's observed high counts once the remaining forecast is within five degrees of it`() {
        // Observed max so far is 66.
        assertEquals(listOf(1), compute(splitDay(remainingForecastPeak = 71f), nowIndex = 3).actualDailyHighIndices)
        assertEquals(emptyList<Int>(), compute(splitDay(remainingForecastPeak = 71.5f), nowIndex = 3).actualDailyHighIndices)
    }

    @Test
    fun `a hotter forecast on a later day does not hide today's observed high`() {
        val hours = splitDay().dropLast(2) + listOf(at(9, 14, forecast = 90f), at(9, 15, forecast = 88f))
        assertEquals(listOf(1), compute(hours, nowIndex = 3).actualDailyHighIndices)
    }

    @Test
    fun `a past forecast extreme on a plateau counts when the plateau turns before now`() {
        val past = listOf(60f, 66f, 66f, 62f)
        val hours = past.mapIndexed { i, t -> at(8, i, forecast = t, actual = t) } + at(8, 4, forecast = 70f)
        assertEquals(1, compute(hours, nowIndex = 3).pastForecastHighIndex)
    }

    @Test
    fun `a past forecast extreme still flat or still moving at now is not labeled`() {
        fun pastHigh(vararg past: Float): Int {
            val hours = past.mapIndexed { i, t -> at(8, i, forecast = t, actual = t) } + at(8, past.size, forecast = 70f)
            return compute(hours, nowIndex = past.lastIndex).pastForecastHighIndex
        }
        assertEquals("flat run reaches now", -1, pastHigh(60f, 62f, 66f, 66f))
        assertEquals("maximum is the now edge", -1, pastHigh(60f, 62f, 64f, 66f))
        assertEquals("maximum is the left edge", -1, pastHigh(66f, 64f, 62f, 60f))
    }

    @Test
    fun `a past forecast low at the left edge or at now is not labeled`() {
        fun pastLow(vararg past: Float): Int {
            val hours = past.mapIndexed { i, t -> at(8, i, forecast = t, actual = t) } + at(8, past.size, forecast = 70f)
            return compute(hours, nowIndex = past.lastIndex).pastForecastLowIndex
        }
        assertEquals(-1, pastLow(60f, 62f, 64f, 66f))
        assertEquals(-1, pastLow(66f, 64f, 62f, 60f))
        assertEquals(2, pastLow(66f, 64f, 60f, 62f))
    }

    // --- Observed turning points (reversal gate, 0.75 degrees) -----------------------------------

    private fun turns(vararg temps: Float): Pair<List<Int>, List<Int>> {
        val e = compute(observed(*temps))
        return e.actualProminentHighIndices to e.actualProminentLowIndices
    }

    @Test
    fun `turning points alternate and ignore a reversal smaller than the gate`() {
        // 61 -> 60.5 is a 0.5 degree wiggle inside the first rise; the peak is the later 61.2.
        assertEquals(listOf(3, 5) to listOf(4, 6), turns(60f, 61f, 60.5f, 61.2f, 58f, 59f, 57f, 60f))
    }

    @Test
    fun `a reversal of exactly the gate counts`() {
        assertEquals(listOf(1, 3) to listOf(2), turns(60f, 62f, 61.25f, 63f, 60f))
        assertEquals(listOf(3) to emptyList<Int>(), turns(60f, 62f, 61.5f, 63f, 60f))
    }

    @Test
    fun `the gate applies at exactly its width in every direction`() {
        // First move up by 0.75, then back down by 0.75.
        assertEquals(listOf(1) to listOf(2), turns(60f, 60.75f, 60f, 61f))
        // First move down by 0.75, then back up by 0.75.
        assertEquals(listOf(2) to listOf(1), turns(60f, 59.25f, 60f, 59f))
        // While falling, a rise of exactly 0.75 confirms the valley.
        assertEquals(listOf(2) to listOf(1), turns(62f, 60f, 60.75f, 59f))
    }

    @Test
    fun `a window that starts by falling yields the valley first`() {
        assertEquals(listOf(3) to listOf(2), turns(60f, 59f, 58f, 59.5f, 57f))
    }

    @Test
    fun `the window edges are never turning points`() {
        // 65 at the left edge is the warmest sample and 66 at the right edge ends an unfinished rise.
        assertEquals(emptyList<Int>() to listOf(2), turns(65f, 64.5f, 63f, 66f))
        assertEquals(emptyList<Int>() to emptyList<Int>(), turns(60f, 70f))
    }

    @Test
    fun `a flat peak or valley is reported at its last sample`() {
        assertEquals(listOf(2) to emptyList<Int>(), turns(60f, 62f, 62f, 60f))
        assertEquals(emptyList<Int>() to listOf(2), turns(62f, 60f, 60f, 62f))
    }

    @Test
    fun `turning points are reported as window indices when the window starts with forecast-only hours`() {
        val hours = listOf(at(8, 0, forecast = 50f), at(8, 1, forecast = 51f)) +
            listOf(60f, 62f, 61.25f, 63f, 60f).mapIndexed { i, t -> at(8, i + 2, forecast = t, actual = t) }
        val e = compute(hours)
        assertEquals(listOf(3, 5) to listOf(4), e.actualProminentHighIndices to e.actualProminentLowIndices)
        assertEquals(5 to 2, e.actualHighIndex to e.actualLowIndex)
    }

    // --- Per-day observed highs and lows ---------------------------------------------------------

    private fun daily(hours: List<HourData>): Pair<List<Int>, List<Int>> {
        val e = compute(hours)
        return e.actualDailyHighIndices to e.actualDailyLowIndices
    }

    private fun obs(day: Int, hour: Int, temp: Float) = at(day, hour, forecast = temp, actual = temp)

    @Test
    fun `a day's high on a two-sample plateau is still a turning point`() {
        assertEquals(listOf(1) to emptyList<Int>(), daily(observed(60f, 70f, 70f, 65f)))
    }

    @Test
    fun `a day's warmest sample on the slope down from the previous evening is not its high`() {
        // Day 9 starts at 72 and only cools; 73 at 23:00 the night before shows it is still descending.
        val hours = listOf(
            obs(8, 18, 75f), obs(8, 20, 80f), obs(8, 22, 68f), obs(8, 23, 73f),
            obs(9, 0, 72f), obs(9, 3, 60f), obs(9, 6, 65f),
        )
        // Day 8's low (index 2) then has no high between it and day 9's colder low, so it is dropped.
        assertEquals(listOf(1) to listOf(5), daily(hours))
    }

    /**
     * Two days. Day 8's coldest sample is its 23:00 shoulder and day 9's is its last sample, so neither
     * day yields a low of its own; the real overnight trough is index 5.
     */
    private fun twoDays(firstHigh: Float, between: List<Float>, secondHigh: Float, first: Float = 70f) = listOf(
        obs(8, 12, first),
        obs(8, 14, firstHigh),
        obs(8, 18, between[0]),
        obs(8, 23, between[1]),
        obs(9, 0, between[2]),
        obs(9, 3, between[3]),
        obs(9, 14, secondHigh),
        obs(9, 20, 60f),
        obs(9, 23, 45f),
    )

    @Test
    fun `the overnight trough between two daily highs is added when neither day owns it`() {
        assertEquals(
            listOf(1, 6) to listOf(5),
            daily(twoDays(firstHigh = 80f, between = listOf(70f, 60f, 55f, 50f), secondHigh = 82f)),
        )
    }

    @Test
    fun `the added trough is searched only between the two highs`() {
        // The window's first sample is colder than the trough but lies before the first high.
        assertEquals(
            listOf(1, 6) to listOf(5),
            daily(twoDays(firstHigh = 80f, between = listOf(70f, 60f, 55f, 50f), secondHigh = 82f, first = 40f)),
        )
    }

    @Test
    fun `a trough must sit three degrees below the cooler high to split the two highs`() {
        // Cooler high is 80, so the limit is 77.
        assertEquals(
            listOf(1, 6) to listOf(5),
            daily(twoDays(firstHigh = 80f, between = listOf(79f, 78.5f, 78f, 77f), secondHigh = 82f)),
        )
        // Half a degree short: the two highs are one peak split by midnight, and the warmer one stays.
        assertEquals(
            listOf(6) to emptyList<Int>(),
            daily(twoDays(firstHigh = 80f, between = listOf(79f, 78.5f, 78f, 77.5f), secondHigh = 82f)),
        )
    }

    @Test
    fun `of two highs with no low between them the warmer stays, and the later one on a tie`() {
        assertEquals(
            listOf(1) to emptyList<Int>(),
            daily(twoDays(firstHigh = 85f, between = listOf(84f, 83.5f, 83f, 82.5f), secondHigh = 84f)),
        )
        assertEquals(
            listOf(6) to emptyList<Int>(),
            daily(twoDays(firstHigh = 82f, between = listOf(81f, 80.5f, 80.2f, 80f), secondHigh = 82f)),
        )
    }

    /** Two days whose warmest samples are both at window edges, so only the lows (1 and 4) remain. */
    private fun twoLows(secondLow: Float) = listOf(
        obs(8, 10, 60f),
        obs(8, 12, 50f),
        obs(8, 20, 58f),
        obs(9, 2, 57f),
        obs(9, 5, secondLow),
        obs(9, 10, 59f),
        obs(9, 12, 61f),
    )

    @Test
    fun `of two lows with no high between them the colder stays, and the later one on a tie`() {
        assertEquals(emptyList<Int>() to listOf(1), daily(twoLows(secondLow = 52f)))
        assertEquals(emptyList<Int>() to listOf(4), daily(twoLows(secondLow = 50f)))
        assertEquals(emptyList<Int>() to listOf(4), daily(twoLows(secondLow = 48f)))
    }

    @Test
    fun `a day keeps its low only when it sits a full noise floor below the high`() {
        // High 60.75 at index 1; the low is index 3.
        assertEquals(listOf(1) to listOf(3), daily(observed(60.5f, 60.75f, 60.3f, 60f, 60.4f)))
        assertEquals(listOf(1) to emptyList<Int>(), daily(observed(60.5f, 60.75f, 60.3f, 60.01f, 60.4f)))
    }
}
