package com.weatherwidget.shared.graph

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The current (incomplete) day's observed maximum is not its daily high if the day hasn't peaked yet
 * — e.g. mid-morning "now" with a much warmer afternoon still in the forecast. Such a morning bump
 * must NOT be labeled as that day's ACTUAL_HIGH (the desktop/emulator "67.6° mid-day" report).
 * Completed past days keep their actual high.
 */
class TemperatureExtremaIncompleteDayTest {

    private data class Pt(val dt: LocalDateTime, val temp: Float, val actual: Float?, val isActual: Boolean)

    private fun hour(day: Int, h: Int, temp: Float, actual: Float? = null, isActual: Boolean = false) =
        Pt(LocalDateTime.of(2026, 4, day, h, 0), temp, actual, isActual)

    private fun build(points: List<Pt>): List<HourData> =
        points.map { p ->
            HourData(
                dateTime = p.dt,
                temperature = p.temp,
                actualTemperature = p.actual,
                isActual = p.isActual,
                label = "${p.dt.hour}h",
            )
        }

    // Day 1 (Apr 8) is complete with a real afternoon high of 90°. Day 2 (Apr 9) is observed only
    // through 08:00 (morning max 67°); index 6 is "now". The forecast for the rest of Apr 9 climbs to
    // 85°, so the day has NOT peaked.
    private val incompleteDayPoints = listOf(
        hour(8, 12, temp = 85f, actual = 88f, isActual = true),
        hour(8, 13, temp = 88f, actual = 90f, isActual = true),   // idx1: Apr 8 actual high (90)
        hour(8, 14, temp = 86f, actual = 87f, isActual = true),
        hour(8, 15, temp = 84f, actual = 85f, isActual = true),
        hour(9, 6, temp = 60f, actual = 62f, isActual = true),
        hour(9, 7, temp = 64f, actual = 67f, isActual = true),    // idx5: Apr 9 morning max (67)
        hour(9, 8, temp = 63f, actual = 64f, isActual = true),    // idx6: "now" (cutoff)
        hour(9, 12, temp = 80f, actual = null, isActual = false),
        hour(9, 15, temp = 85f, actual = null, isActual = false), // idx8: Apr 9 forecast high (85)
        hour(9, 18, temp = 72f, actual = null, isActual = false),
    )

    @Test
    fun `incomplete current day's morning max is not labeled as the daily actual high`() {
        val extrema = TemperatureExtrema.compute(
            hours = build(incompleteDayPoints),
            transitionX = 100f,
            effectiveActualEndIndex = 6,
            fetchTime = null,
            prominenceThreshold = 1.5f,
        )

        assertTrue(
            "Completed Apr 8 actual high (idx 1) should be labeled. highs=${extrema.actualDailyHighIndices}",
            1 in extrema.actualDailyHighIndices,
        )
        assertFalse(
            "Incomplete Apr 9 morning max (idx 5) must NOT be labeled — the day's high is still ahead " +
                "in the forecast. highs=${extrema.actualDailyHighIndices}",
            5 in extrema.actualDailyHighIndices,
        )
    }

    @Test
    fun `current day's observed max IS the high when the rest of the day is forecast no warmer`() {
        // Same shape, but Apr 9's afternoon forecast stays cool (<= the 67° morning max), so the day
        // has effectively peaked and its actual high is kept.
        val coolAfternoon = incompleteDayPoints.toMutableList().apply {
            this[7] = hour(9, 12, temp = 66f, actual = null, isActual = false)
            this[8] = hour(9, 15, temp = 65f, actual = null, isActual = false)
            this[9] = hour(9, 18, temp = 62f, actual = null, isActual = false)
        }

        val extrema = TemperatureExtrema.compute(
            hours = build(coolAfternoon),
            transitionX = 100f,
            effectiveActualEndIndex = 6,
            fetchTime = null,
            prominenceThreshold = 1.5f,
        )

        assertTrue(
            "Apr 9 morning max (idx 5) should be kept when the rest of the day is no warmer. " +
                "highs=${extrema.actualDailyHighIndices}",
            5 in extrema.actualDailyHighIndices,
        )
    }

    // A partial edge day (Apr 8) is observed for only a short slice where the actual high (63.91°) and
    // low (63.88°) both round to "63.9°". Emitting both stacks two identical labels at the graph edge.
    // The degenerate day's low must be dropped (keep the high), while a normal day (Apr 9) with a
    // genuinely separated high/low keeps BOTH. Points are ordered low-then-high within Apr 8 so the
    // merged extrema never have same-type neighbours — isolating this from the shoulder-drop logic.
    private val degenerateDayPoints = listOf(
        hour(8, 6, temp = 63.88f, actual = 63.88f, isActual = true),   // idx0: Apr 8 actual low  -> "63.9"
        hour(8, 12, temp = 63.91f, actual = 63.91f, isActual = true),  // idx1: Apr 8 actual high -> "63.9"
        hour(9, 6, temp = 55f, actual = 55f, isActual = true),         // idx2: Apr 9 actual low  (55)
        hour(9, 12, temp = 75f, actual = 75f, isActual = true),        // idx3: Apr 9 actual high (75)
        hour(9, 18, temp = 60f, actual = 60f, isActual = true),
    )

    @Test
    fun `edge day whose actual high and low render identically keeps only the high`() {
        val extrema = TemperatureExtrema.compute(
            hours = build(degenerateDayPoints),
            transitionX = null,
            effectiveActualEndIndex = 4,
            fetchTime = null,
            prominenceThreshold = 1.5f,
        )

        assertTrue(
            "Apr 8 degenerate high (idx 1) should be kept. highs=${extrema.actualDailyHighIndices}",
            1 in extrema.actualDailyHighIndices,
        )
        assertFalse(
            "Apr 8 degenerate low (idx 0) must be dropped — it renders identically to the day's high " +
                "(\"63.9°\"). lows=${extrema.actualDailyLowIndices}",
            0 in extrema.actualDailyLowIndices,
        )
        assertTrue(
            "Apr 9's genuinely distinct low (idx 2) must NOT be over-suppressed. " +
                "lows=${extrema.actualDailyLowIndices}",
            2 in extrema.actualDailyLowIndices,
        )
    }

    // Apr 8 is a thin partial sliver observed only 22:00->23:00, descending (60.8 -> 58.9). It crosses
    // midnight into Apr 9's genuine overnight low (58.4 @ 00:00), then Apr 9 warms to a real high (74).
    // Apr 8's per-day "high" (idx0) is merely the warm START of one continuous descent into the Apr 9
    // valley — not a diurnal peak — and must be dropped so it doesn't stack a second pink label at the
    // left edge above the real low. The Apr 9 overnight low (idx2) is genuine and must be kept.
    private val descendingBoundarySliverPoints = listOf(
        hour(8, 22, temp = 60.8f, actual = 60.8f, isActual = true),  // idx0: Apr 8 boundary "high" (drop)
        hour(8, 23, temp = 58.9f, actual = 58.9f, isActual = true),  // idx1: Apr 8 raw low (shoulder)
        hour(9, 0, temp = 58.4f, actual = 58.4f, isActual = true),   // idx2: Apr 9 overnight low (KEEP)
        hour(9, 6, temp = 62f, actual = 62f, isActual = true),
        hour(9, 12, temp = 74f, actual = 74f, isActual = true),      // idx4: Apr 9 real high
        hour(9, 18, temp = 66f, actual = 66f, isActual = true),
    )

    @Test
    fun `descending boundary-start sliver high is dropped while the genuine nearby low is kept`() {
        val extrema = TemperatureExtrema.compute(
            hours = build(descendingBoundarySliverPoints),
            transitionX = null,
            effectiveActualEndIndex = 5,
            fetchTime = null,
            prominenceThreshold = 1.5f,
        )
        assertFalse(
            "Apr 8 boundary-start high (idx 0) must be dropped — it is the warm start of a descent " +
                "into the Apr 9 low, not a peak. highs=${extrema.actualDailyHighIndices}",
            0 in extrema.actualDailyHighIndices,
        )
        assertTrue(
            "Apr 9 overnight low (idx 2) is genuine and must be kept. lows=${extrema.actualDailyLowIndices}",
            2 in extrema.actualDailyLowIndices,
        )
        assertTrue(
            "Apr 9's real daytime high (idx 4) must still be labeled. highs=${extrema.actualDailyHighIndices}",
            4 in extrema.actualDailyHighIndices,
        )
    }

    // Apr 8 begins at its COLDEST observed point (50 @ 00:00 — the overnight low landing at the edge)
    // and only ever warms. This boundary LOW is a genuine value the user wants labeled; the asymmetric
    // rule must never drop it (actual_low_left_edge_label).
    private val ascendingBoundaryLowPoints = listOf(
        hour(8, 0, temp = 50f, actual = 50f, isActual = true),   // idx0: boundary low (KEEP)
        hour(8, 6, temp = 58f, actual = 58f, isActual = true),
        hour(8, 12, temp = 72f, actual = 72f, isActual = true),  // idx2: Apr 8 high
        hour(9, 0, temp = 60f, actual = 60f, isActual = true),
        hour(9, 12, temp = 78f, actual = 78f, isActual = true),
    )

    @Test
    fun `coldest-at-edge boundary low is never dropped`() {
        val extrema = TemperatureExtrema.compute(
            hours = build(ascendingBoundaryLowPoints),
            transitionX = null,
            effectiveActualEndIndex = 4,
            fetchTime = null,
            prominenceThreshold = 1.5f,
        )
        assertTrue(
            "Apr 8 boundary low (idx 0) is the genuine coldest-at-edge value and must be kept. " +
                "lows=${extrema.actualDailyLowIndices}",
            0 in extrema.actualDailyLowIndices,
        )
    }

    // Apr 8 begins warm at the boundary (70 @ 06:00 — the day max), dips (64), then climbs back to a
    // real interior peak (68) before falling into the next low. The descent from the boundary high to
    // that low is NOT monotonic, so the boundary high is a genuine separated peak and must be KEPT.
    private val separatedBoundaryHighPoints = listOf(
        hour(8, 6, temp = 70f, actual = 70f, isActual = true),   // idx0: Apr 8 boundary high (KEEP)
        hour(8, 10, temp = 64f, actual = 64f, isActual = true),
        hour(8, 14, temp = 68f, actual = 68f, isActual = true),  // interior peak breaks monotonicity
        hour(9, 0, temp = 55f, actual = 55f, isActual = true),   // idx3: next low
        hour(9, 12, temp = 78f, actual = 78f, isActual = true),
    )

    @Test
    fun `separated left-edge boundary high is kept when the descent is not monotonic`() {
        val extrema = TemperatureExtrema.compute(
            hours = build(separatedBoundaryHighPoints),
            transitionX = null,
            effectiveActualEndIndex = 4,
            fetchTime = null,
            prominenceThreshold = 1.5f,
        )
        assertTrue(
            "Apr 8 boundary high (idx 0) is a genuine separated peak (non-monotonic descent) and must " +
                "not be over-suppressed. highs=${extrema.actualDailyHighIndices}",
            0 in extrema.actualDailyHighIndices,
        )
    }
}
