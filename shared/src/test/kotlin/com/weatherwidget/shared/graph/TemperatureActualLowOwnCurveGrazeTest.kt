package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.experimental.categories.Category

/**
 * Desktop "Thu 11 / 60.6°" bug: the ACTUAL_LOW label was flipped ABOVE its valley because the
 * observed line's own sub-hourly/smoothed dip a few px below the labeled minimum grazed the
 * below-box. The forecast curve was far away. ACTUAL_LOW must avoid only the FORECAST curve (it
 * labels its own actual line), so it sits below when the forecast is clear, and only flips above
 * when the forecast genuinely dips below the valley. See plans/samsung-clash-of-labels-*.md.
 */
@Category(ShortDuration::class)
class TemperatureActualLowOwnCurveGrazeTest {

    private val widthPx = 600
    private val heightPx = 400
    private val graphTop = 44f
    private val graphBottom = heightPx - 30f
    private val graphHeight = graphBottom - graphTop

    private class TestMetrics : LabelTextMetrics {
        override val ascent = -10f
        override val descent = 2f
        override fun width(text: String, isFuture: Boolean): Float = text.length * 6f
    }

    /** All-observed valley at [lowIdx] (value [lowTemp]); forecast given per-hour. */
    private fun buildHours(forecast: List<Float>, actual: List<Float>): List<HourData> {
        val start = LocalDateTime.of(2026, 4, 8, 0, 0)
        return forecast.indices.map { i ->
            HourData(
                dateTime = start.plusHours(i.toLong()),
                temperature = forecast[i],
                actualTemperature = actual[i],
                isActual = true,
                label = "${start.plusHours(i.toLong()).hour}",
                showLabel = true,
            )
        }
    }

    /** The observed polyline the last [run] built, for after-the-fact collision assertions. */
    private var lastActualVisiblePoints: List<Pair<Float, Float>> = emptyList()

    private fun run(
        forecast: List<Float>,
        actual: List<Float>,
        // optional extra actual-line point (x-fraction across graph, temp) to simulate a sub-hourly
        // dip below the labeled minimum.
        extraActualDip: Pair<Float, Float>? = null,
    ): List<PlacedLabel> {
        val hours = buildHours(forecast, actual)
        val minTemp = (forecast + actual).min() - 5f
        val maxTemp = (forecast + actual).max() + 5f
        val range = maxTemp - minTemp
        val tempToY = { t: Float -> graphTop + graphHeight * (1f - (t - minTemp) / range) }

        val minEpoch = hours.first().dateTime.toEpochSecond(ZoneOffset.UTC)
        val hourWidth = widthPx.toFloat() / hours.size
        fun xAt(i: Int) = ((hours[i].dateTime.toEpochSecond(ZoneOffset.UTC) - minEpoch) / 3600f) * hourWidth

        val originalPoints = hours.indices.map { xAt(it) to tempToY(actual[it]) }
        val forecastPoints = hours.indices.map { xAt(it) to tempToY(forecast[it]) }
        // Observed line = the actual points, plus an optional injected sub-hourly dip.
        val actualVisiblePoints = originalPoints.toMutableList().also { pts ->
            extraActualDip?.let { (frac, temp) ->
                val x = frac * widthPx
                val insertAt = pts.indexOfFirst { it.first > x }.let { if (it < 0) pts.size else it }
                pts.add(insertAt, x to tempToY(temp))
            }
        }
        lastActualVisiblePoints = actualVisiblePoints
        // Observe through the whole window so the valley is in the visible (past) actual line.
        val observedAt = hours.last().dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val fetchTime = hours.last().dateTime

        return TemperatureLabelEngine.computePlacements(
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            density = 1f,
            originalPoints = originalPoints,
            forecastPoints = forecastPoints,
            actualVisiblePoints = actualVisiblePoints,
            transitionX = xAt(hours.lastIndex),
            fetchDotX = xAt(hours.lastIndex),
            lastObservedTemp = actual.last(),
            observedAt = observedAt,
            effectiveActualEndIndex = hours.lastIndex,
            fetchTime = fetchTime,
            numColumns = hours.size,
            tempToY = tempToY,
            metrics = TestMetrics(), useCelsius = false,
        )
    }

    @Test
    fun `actual low stays below its valley when only its own line grazes the below-box`() {
        // Flat-high forecast (80) far above the actual valley (67 at idx 6). The observed line dips
        // ~one degree below the labeled minimum just past the valley (sub-hourly graze).
        val forecast = List(13) { 80f }
        val actual = listOf(80f, 78f, 75f, 72f, 70f, 68f, 67f, 68f, 70f, 73f, 76f, 78f, 80f)
        val placements = run(forecast, actual, extraActualDip = 6.5f / 13f to 66f)

        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW }
        assertNotNull("ACTUAL_LOW (67°) should be labeled. placements=$placements", actualLow)
        assertFalse(
            "ACTUAL_LOW must stay below its valley despite its own line grazing the below-box " +
                "(reason=${actualLow!!.reason}, placedAbove=${actualLow.placedAbove})",
            actualLow.placedAbove,
        )
    }

    @Test
    fun `actual low stays below with no leader when the forecast only shallowly grazes below the valley`() {
        // Forecast gently descends across the window, grazing a half-degree below the actual valley
        // (67 at idx 6 -> forecast 66.5) — a shallow overlap into the below-box. It is monotonic, so
        // there is no competing same-index forecast valley and no sharp-V smoothing overshoot.
        // Partial forecast-curve overlap is acceptable for ACTUAL_LOW, so the label must stay flush
        // below its valley with NO leader line rather than flip above and be pushed off-anchor.
        val actual = listOf(90f, 86f, 80f, 74f, 70f, 68f, 67f, 68f, 70f, 74f, 80f, 86f, 90f)
        val forecast = listOf(72f, 71f, 70f, 69f, 68f, 67f, 66.5f, 66f, 65f, 64f, 63f, 62f, 61f)
        val placements = run(forecast, actual)

        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW }
        assertNotNull("ACTUAL_LOW (67°) should be labeled. placements=$placements", actualLow)
        assertFalse(
            "ACTUAL_LOW must stay below for a shallow forecast graze " +
                "(reason=${actualLow!!.reason}, placedAbove=${actualLow.placedAbove})",
            actualLow.placedAbove,
        )
        assertFalse(
            "ACTUAL_LOW must draw no leader line for a shallow forecast graze " +
                "(reason=${actualLow.reason}, displacementSteps=${actualLow.displacementSteps})",
            actualLow.drawLeaderLine,
        )
    }

    /**
     * Samsung Fold, 2026-08-15: `60.9°` (ACTUAL_LOW) was drawn straight across the pink observed
     * line. `computeForcedAboveLowIndices` flipped it ABOVE its trough so it would read in
     * temperature order over the cooler `59°` forecast LOW beside it — but ACTUAL_LOW's carve-out
     * from actual-curve avoidance was unconditional, so above the trough the whole observed hump
     * was invisible to the collision test. The carve-out is a below-direction rule; above, the
     * label must clear its own line like every other role.
     */
    @Test
    fun `actual low flipped above by a cooler neighbour still clears its own observed line`() {
        // Sharp observed valley (67.4 at idx 6) with steep shoulders, so the observed line's arms
        // run through the box directly above the trough. A forecast LOW of 66.4 one index over is
        // strictly cooler (67 vs 66 rounded), which is what forces the warmer actual low above.
        //
        // The 1° separation matters: the flip is gated on the two lows' below-boxes actually
        // contending, and the surrounding highs widen the temperature range to ~10px/° so the
        // anchors land ~10px apart — inside the label-height-plus-gap threshold. Spread the pair
        // further and the engine (correctly) leaves the low below its own trough.
        val actual = listOf(88f, 86f, 84f, 80f, 76f, 74f, 67.4f, 74f, 76f, 80f, 84f, 86f, 88f)
        val forecast = listOf(78f, 77f, 75f, 73f, 71f, 69f, 67f, 66.4f, 67f, 69f, 71f, 74f, 77f)
        val placements = run(forecast, actual)

        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW }
        assertNotNull("ACTUAL_LOW (67.4°) should be labeled. placements=$placements", actualLow)
        assertTrue(
            "ACTUAL_LOW must flip above the cooler forecast low " +
                "(reason=${actualLow!!.reason}, placedAbove=${actualLow.placedAbove})",
            actualLow.placedAbove,
        )
        assertObservedLineClear(actualLow)
    }

    /**
     * The observed polyline must not run across the label's glyph body. A sharp valley's arms
     * always clip the bottom corners of a box sitting above it — that is the engine's standard
     * graze allowance — so the contract asserted here is that the line stays out of the half of
     * the box the digits occupy, never that it misses the box entirely.
     */
    private fun assertObservedLineClear(label: PlacedLabel) {
        val metrics = TestMetrics()
        val halfWidth = metrics.width(label.text, label.isFuture) / 2f
        val bounds = GraphRect(
            label.x - halfWidth,
            label.baselineY + metrics.ascent,
            label.x + halfWidth,
            label.baselineY + metrics.descent,
        )
        val intrusion = curveIntrusionInLabel(lastActualVisiblePoints, bounds)
        if (intrusion.isEmpty) return
        val midY = (bounds.top + bounds.bottom) / 2f
        // Above-placed: the line comes up from below, so its topmost point inside the box (minY)
        // must stay under the midline. Below-placed: it comes down from above, so its lowest point
        // inside (maxY) must stay over it.
        val clear = if (label.placedAbove) intrusion.minY >= midY else intrusion.maxY <= midY
        assertTrue(
            "observed line must not cross the ${label.role} glyph body: bounds=$bounds midY=$midY " +
                "intrusion=(${intrusion.minY}..${intrusion.maxY}) reason=${label.reason} " +
                "placedAbove=${label.placedAbove}",
            clear,
        )
    }

    /**
     * Renamed and re-fixtured 2026-08-15. This was `actual low still flips above when the forecast
     * curve dips below the valley`, but it asserted a flip the engine does not perform and its
     * fixture never reached the path it documented:
     *
     * - The fixture put the forecast 6-7° under the valley — ~65px below a 12px-tall label box, so
     *   the forecast curve never touched the below-box at all. It passed on the cooler-neighbour
     *   ordering rule, duplicating the test above.
     * - Forecast intrusion does not flip ACTUAL_LOW above. Since 2026-06-14 a blocked below
     *   direction routes to `ActualExtremePlacers.place(placeAbove = false)` — the tight
     *   below-trough hug (`reason=belowActualCurve`) — precisely so a low stays under its valley
     *   instead of being driven above with a long leader. Only the ordering rules flip it.
     *
     * So it now pins the behaviour that actually exists, with a forecast that genuinely crosses the
     * below-box.
     */
    @Test
    fun `actual low hugs tight below its trough when the forecast crosses the below-box`() {
        // Monotonic forecast descending through the valley level right at idx 6 — 68 one index
        // before (above the box) to 65 at the valley (below it) — so the segment sweeps the whole
        // box, well past ACTUAL_LOW's 0.5x-label-height forecast tolerance. Monotonic on purpose:
        // no competing same-index FORECAST_LOW, and no bezier overshoot from a sharp V.
        val forecast = listOf(72f, 71f, 70f, 69f, 68.5f, 68f, 65f, 63f, 61f, 59f, 57f, 55f, 53f)
        val actual = listOf(82f, 80f, 78f, 75f, 72f, 69f, 67f, 69f, 72f, 75f, 78f, 80f, 82f)
        val placements = run(forecast, actual)

        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW }
        assertNotNull("ACTUAL_LOW (67°) should be labeled. placements=$placements", actualLow)
        assertFalse(
            "ACTUAL_LOW must stay below its trough when the forecast crosses the below-box " +
                "(reason=${actualLow!!.reason}, placedAbove=${actualLow.placedAbove})",
            actualLow.placedAbove,
        )
        assertEquals(
            "a below direction blocked by the forecast must take the tight below-trough hug, " +
                "not a displaced below slot (reason=${actualLow.reason})",
            "belowActualCurve",
            actualLow.reason,
        )
        assertFalse(
            "the tight below-trough hug draws no leader line (reason=${actualLow.reason})",
            actualLow.drawLeaderLine,
        )
    }

    /**
     * Samsung Fold, 2026-08-15 (follow-up): with the observed `60.9°` clear of its own line, it was
     * still being lifted into the crook of that line by `computeForcedAboveLowIndices`, because a
     * `59°` forecast low sat a few indices away. The two anchors were ~40px apart on different
     * curves, so nothing was contending for the space and below — the low's natural slot under its
     * own trough — was wide open.
     *
     * `tempToY` is monotonic, so both lows sitting below their own curves already read in
     * temperature order; the flip is only needed when the below-boxes overlap and the de-collision
     * cascade could invert them.
     */
    @Test
    fun `actual low stays below when the cooler neighbour is too far below to contend`() {
        // Same shape as the flip case, but the forecast low is 7° below the actual valley rather
        // than 1°, so the below-boxes cannot overlap. The forecast stays clear of the below-box
        // itself (it runs far under it), leaving the ordering rule as the only thing that could
        // lift the label.
        val actual = listOf(88f, 86f, 84f, 80f, 76f, 74f, 67.4f, 74f, 76f, 80f, 84f, 86f, 88f)
        val forecast = listOf(72f, 71f, 69f, 67f, 65f, 62f, 61f, 60.4f, 61f, 63f, 66f, 69f, 72f)
        val placements = run(forecast, actual)

        val actualLow = placements.find { it.role == TemperatureRole.ACTUAL_LOW }
        assertNotNull("ACTUAL_LOW (67.4°) should be labeled. placements=$placements", actualLow)
        assertFalse(
            "ACTUAL_LOW must stay below its trough when the cooler low is far enough below that " +
                "their below-boxes cannot contend (reason=${actualLow!!.reason}, " +
                "placedAbove=${actualLow.placedAbove})",
            actualLow.placedAbove,
        )
        assertObservedLineClear(actualLow)
    }
}
