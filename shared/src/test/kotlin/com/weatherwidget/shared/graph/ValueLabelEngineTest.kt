package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Plain-JUnit tests for the shared [ValueLabelEngine] (the precip / cloud "%"-value label
 * placement). Mirrors the [NowIndicatorGeometryTest] style: identity dpToPx, a simple measureText,
 * and assertions on which labels are placed and roughly where.
 */
@Category(ShortDuration::class)
class ValueLabelEngineTest {

    private val noScale: (Float) -> Float = { it }
    private val measure: (String) -> Float = { it.length * 6f }
    private val ascent = -10f
    private val descent = 2f

    private val graphTop = 38f
    private val graphBottom = 661f
    private val graphHeight = graphBottom - graphTop
    private val widthPx = 1000f
    private val heightPx = 700f

    private fun geometry() = ValueLabelEngine.Geometry(graphTop, graphBottom, graphHeight, widthPx, heightPx)

    /** Maps an integer signal to evenly-spaced points using the renderers' y-scale convention. */
    private fun points(signal: List<Int>): List<ValueLabelEngine.GraphPoint> {
        val scaleMax = ((signal.maxOrNull() ?: 0) * 1.15f).coerceAtLeast(10f).coerceAtMost(100f)
        val stepX = widthPx / (signal.size - 1).coerceAtLeast(1)
        return signal.mapIndexed { i, v ->
            ValueLabelEngine.GraphPoint(stepX * i, graphBottom - graphHeight * (v / scaleMax))
        }
    }

    private fun run(signal: List<Int>, config: ValueLabelEngine.Config = ValueLabelEngine.Config()) =
        ValueLabelEngine.computePlacements(
            labelSignal = signal,
            points = points(signal),
            geometry = geometry(),
            config = config,
            measureText = measure,
            textAscent = ascent,
            textDescent = descent,
            dpToPx = noScale,
        )

    // --- The regression: low edge values near the bottom must still be labeled ---------------------

    @Test
    fun `end label is placed when the curve descends to a low value at the right edge`() {
        // Mirrors the live cloud bug: peak early, then descending to 1% at the last point (near bottom).
        val signal = listOf(66, 71, 60, 45, 30, 18, 8, 1)
        val placements = run(signal, ValueLabelEngine.Config.cloud())

        val end = placements.firstOrNull { it.index == signal.lastIndex }
        assertNotNull("end label should be placed even when the curve is low at the edge", end)
        assertEquals("1%", end!!.text)
        // It is allowed below the safeBottom buffer (low preferred-below), but never past the canvas.
        assertTrue(end.box.bottom <= heightPx)
    }

    @Test
    fun `start label is placed when the curve is low at the left edge`() {
        // Low start value, neighbor differs enough that left-edge suppression does NOT fire.
        val signal = listOf(2, 55, 60, 40, 20, 10)
        val placements = run(signal)

        val start = placements.firstOrNull { it.index == 0 }
        assertNotNull("low start label should be placed", start)
        assertEquals("2%", start!!.text)
    }

    // --- Core placement behavior -------------------------------------------------------------------

    @Test
    fun `the global-max peak is labeled above the curve`() {
        val signal = listOf(10, 30, 80, 30, 10)
        val placements = run(signal)
        val peak = placements.firstOrNull { it.text == "80%" }
        assertNotNull(peak)
        assertTrue("peak should sit above its curve point", peak!!.placedAbove)
        assertEquals("peak", peak.reason)
    }

    @Test
    fun `placed labels do not overlap each other`() {
        val signal = listOf(80, 10, 75, 12, 78, 9, 70)
        val placements = run(signal)
        for (a in placements.indices) {
            for (b in a + 1 until placements.size) {
                assertFalse(
                    "labels ${placements[a].text}@${placements[a].index} and ${placements[b].text}@${placements[b].index} overlap",
                    placements[a].box.intersects(placements[b].box),
                )
            }
        }
    }

    @Test
    fun `box and center coordinates are consistent`() {
        val signal = listOf(10, 30, 80, 30, 10)
        val placements = run(signal)
        for (p in placements) {
            val halfW = measure(p.text) / 2f
            assertEquals(p.centerX, (p.box.left + p.box.right) / 2f, 0.01f)
            assertEquals(halfW * 2f, p.box.width, 0.01f)
        }
    }

    @Test
    fun `requireNonZeroExtrema suppresses an interior label on an all-zero signal`() {
        val signal = listOf(0, 0, 0, 0, 0)
        val placements = run(signal) // precip config: requireNonZeroExtrema = true
        // Edges are always candidates ("0%"), but no interior peak/valley label should be created.
        assertTrue(placements.all { it.index == 0 || it.index == signal.lastIndex })
    }

    @Test
    fun `left-edge label is suppressed when a near neighbor has a similar value`() {
        // Start (40) within 5 of the global-max peak at idx2 (44), which survives dense filtering
        // (immovable) -> shouldSuppressLeftEdgeLabel fires.
        val signal = listOf(40, 20, 44, 20, 10)
        val placements = run(signal)
        assertNull("near-duplicate left edge should be suppressed", placements.firstOrNull { it.index == 0 })
    }

    @Test
    fun `dense near-duplicate candidates are thinned`() {
        // Several similar peaks close together should not all survive (maxCandidates / dense thinning).
        val signal = listOf(50, 51, 52, 51, 50, 51, 52, 51, 50)
        val placements = run(signal)
        assertTrue("dense similar candidates should be thinned", placements.size <= ValueLabelEngine.Config().maxCandidates)
    }

    // --- Exact outcomes --------------------------------------------------------------------------
    // Each case below pins the whole result as "index:text:A|B:reason" (A = above the curve point,
    // B = below), so a flipped condition anywhere in selection or placement changes the list.

    private fun List<ValueLabelEngine.ValueLabelPlacement>.summary(): List<String> =
        map { "${it.index}:${it.text}:${if (it.placedAbove) "A" else "B"}:${it.reason}" }

    private fun List<ValueLabelEngine.ValueLabelPlacement>.at(index: Int) = firstOrNull { it.index == index }

    /** Runs the engine on hand-placed points, for the collision and canvas-limit cases. */
    private fun runAt(
        signal: List<Int>,
        pts: List<ValueLabelEngine.GraphPoint>,
        config: ValueLabelEngine.Config = ValueLabelEngine.Config(),
        obstacles: List<GraphRect> = emptyList(),
    ) = ValueLabelEngine.computePlacements(
        labelSignal = signal,
        points = pts,
        geometry = geometry(),
        config = config,
        measureText = measure,
        textAscent = ascent,
        textDescent = descent,
        dpToPx = noScale,
        drawnIconBounds = obstacles,
    )

    private fun pt(x: Float, y: Float) = ValueLabelEngine.GraphPoint(x, y)

    @Test
    fun `an empty signal or a point count that differs from the signal yields no labels`() {
        assertEquals(emptyList<String>(), run(emptyList()).summary())
        assertEquals(emptyList<String>(), runAt(listOf(10, 30, 80), listOf(pt(0f, 100f), pt(500f, 100f))).summary())
    }

    @Test
    fun `a single peak gives the peak above and the falling end below, with the similar start dropped`() {
        // Start (10) matches the end candidate (10) four columns away, so the start is the redundant one.
        assertEquals(listOf("2:80%:A:peak", "4:10%:B:end"), run(listOf(10, 30, 80, 30, 10)).summary())
    }

    @Test
    fun `with left-edge suppression off the similar start label is kept`() {
        val placements = run(listOf(10, 30, 80, 30, 10), ValueLabelEngine.Config(suppressLeftEdge = false))
        assertEquals(listOf("0:10%:B:valley", "2:80%:A:peak", "4:10%:B:end"), placements.summary())
    }

    @Test
    fun `the first positive hour after a dry start is labeled below the curve`() {
        val placements = run(listOf(0, 0, 20, 40, 60, 80))
        assertEquals(listOf("0:0%:B:valley", "2:20%:B:other", "5:80%:A:peak"), placements.summary())
        assertEquals(listOf(2), placements.filter { it.isFirstRising }.map { it.index })
    }

    @Test
    fun `without first-positive candidates the same hour is only the injected midpoint and sits above`() {
        val signal = listOf(0, 0, 20, 40, 60, 80)
        val noFirstPositive = ValueLabelEngine.Config(includeFirstPositive = false)
        val placements = run(signal, noFirstPositive)
        assertEquals(listOf("0:0%:B:valley", "2:20%:A:other", "5:80%:A:peak"), placements.summary())
        assertTrue(placements.none { it.isFirstRising })

        val edgesOnly = run(signal, noFirstPositive.copy(addMidpointWhenOnlyEdges = false))
        assertEquals(listOf("0:0%:B:valley", "5:80%:A:peak"), edgesOnly.summary())
    }

    @Test
    fun `a renderer-supplied first labeled positive becomes a below-curve label`() {
        val signal = listOf(0, 0, 20, 40, 60, 80)
        val placements = ValueLabelEngine.computePlacements(
            labelSignal = signal, points = points(signal), geometry = geometry(), config = ValueLabelEngine.Config(),
            measureText = measure, textAscent = ascent, textDescent = descent, dpToPx = noScale,
            firstLabeledPositive = 3,
        )
        assertEquals(listOf("0:0%:B:valley", "2:20%:B:other", "3:40%:B:other", "5:80%:A:peak"), placements.summary())
        assertEquals(listOf(2, 3), placements.filter { it.isFirstRising }.map { it.index })
    }

    @Test
    fun `a first labeled positive outside the signal is ignored`() {
        val signal = listOf(0, 0, 20, 40, 60, 80)
        val placements = ValueLabelEngine.computePlacements(
            labelSignal = signal, points = points(signal), geometry = geometry(), config = ValueLabelEngine.Config(),
            measureText = measure, textAscent = ascent, textDescent = descent, dpToPx = noScale,
            firstLabeledPositive = 99,
        )
        assertEquals(run(signal).summary(), placements.summary())
    }

    @Test
    fun `a flat precip span is labeled at the two edges only`() {
        // The midpoint would repeat the edge value, so precip does not inject it.
        assertEquals(listOf("0:40%:B:peak", "6:40%:A:end"), run(List(7) { 40 }).summary())
    }

    @Test
    fun `a flat cloud span gets a midpoint label on wide widgets only`() {
        val signal = List(7) { 40 }
        fun cloud(columns: Int) = ValueLabelEngine.computePlacements(
            labelSignal = signal, points = points(signal), geometry = geometry(), config = ValueLabelEngine.Config.cloud(),
            measureText = measure, textAscent = ascent, textDescent = descent, dpToPx = noScale,
            numColumns = columns,
        ).summary()
        assertEquals(listOf("0:40%:A:peak", "3:40%:A:other", "6:40%:A:end"), cloud(5))
        assertEquals(listOf("0:40%:A:peak", "6:40%:A:end"), cloud(4))
    }

    @Test
    fun `a steady precip ramp gets a distinct midpoint label`() {
        assertEquals(
            listOf("0:10%:B:valley", "3:40%:A:other", "6:70%:A:peak"),
            run(listOf(10, 20, 30, 40, 50, 60, 70)).summary(),
        )
    }

    @Test
    fun `a plain interior label goes below the curve only above 50 percent`() {
        assertEquals("3:50%:A:other", run(listOf(10, 20, 30, 50, 60, 70, 80)).summary()[1])
        assertEquals("3:51%:B:other", run(listOf(10, 20, 30, 51, 60, 70, 80)).summary()[1])
    }

    @Test
    fun `an all-zero precip signal labels both edges above and nothing between`() {
        assertEquals(listOf("0:0%:A:peak", "4:0%:A:end"), run(listOf(0, 0, 0, 0, 0)).summary())
    }

    @Test
    fun `an all-zero cloud signal also gets the midpoint`() {
        assertEquals(listOf(0, 2, 4), run(listOf(0, 0, 0, 0, 0), ValueLabelEngine.Config.cloud()).map { it.index })
    }

    @Test
    fun `a zero-valued dip is labeled only when zero extrema are allowed`() {
        val signal = listOf(50, 0, 50)
        assertEquals(listOf("0:50%:B:peak", "2:50%:A:end"), run(signal).summary())
        assertEquals(
            listOf("0:50%:B:peak", "1:0%:B:valley", "2:50%:A:end"),
            run(signal, ValueLabelEngine.Config(requireNonZeroExtrema = false)).summary(),
        )
    }

    @Test
    fun `valleys and soft dips go below, the peak above, and each carries its classification`() {
        // Index 2 (60) is not a local minimum (50 sits left of it) but is walled in by 90 and 95.
        val placements = run(listOf(90, 50, 60, 95, 40))
        assertEquals(
            listOf("0:90%:B:start", "1:50%:B:valley", "2:60%:B:softDip", "3:95%:A:peak", "4:40%:B:valley"),
            placements.summary(),
        )
        assertEquals(listOf(1, 2), placements.filter { it.isSoftDip }.map { it.index })
        assertEquals(listOf(3), placements.filter { it.isGlobalMax }.map { it.index })
        assertEquals(listOf(4), placements.filter { it.isGlobalMin }.map { it.index })
        assertEquals(listOf(3), placements.filter { it.isPeak }.map { it.index })
        assertEquals(listOf(1, 4), placements.filter { it.isValley }.map { it.index })
    }

    @Test
    fun `a soft dip is recognised up to the ceiling value and not above it`() {
        assertEquals("softDip", run(listOf(90, 50, 65, 95, 40)).at(2)?.reason)
        assertNull(run(listOf(90, 50, 66, 95, 40)).at(2))
    }

    @Test
    fun `a soft dip needs both walls to rise by the minimum elevation`() {
        // 60 needs 68 on each side (precip elevation 8).
        assertEquals("softDip", run(listOf(68, 50, 60, 68, 40)).at(2)?.reason)
        assertNull("left wall one short", run(listOf(67, 50, 60, 68, 40)).at(2))
        assertNull("right wall one short", run(listOf(68, 50, 60, 67, 40)).at(2))
    }

    @Test
    fun `a soft dip wall counts only inside the window`() {
        // The 90 wall is six columns left of the 50 at index 6.
        val signal = listOf(90, 20, 21, 22, 23, 24, 50, 95)
        assertNull(run(signal).at(6))
        assertEquals("softDip", run(signal, ValueLabelEngine.Config(softDipWindowSize = 6)).at(6)?.reason)
    }

    @Test
    fun `a rising right edge is labeled above even when its value is high`() {
        assertEquals(
            listOf("0:90%:B:peak", "1:20%:B:valley", "2:30%:B:softDip", "3:40%:B:softDip", "4:60%:A:end"),
            run(listOf(90, 20, 30, 40, 60)).summary(),
        )
    }

    @Test
    fun `near-duplicate neighbours are thinned to the peak and the end`() {
        assertEquals(
            listOf("2:52%:A:peak", "8:50%:B:end"),
            run(listOf(50, 51, 52, 51, 50, 51, 52, 51, 50)).summary(),
        )
    }

    @Test
    fun `labels are clamped inside the canvas width and keep their gap from the curve`() {
        val signal = listOf(0, 0, 20, 40, 60, 80)
        val pts = points(signal)
        val placements = run(signal)
        val start = placements.at(0)!!
        val end = placements.at(5)!!
        assertEquals(6f, start.centerX, 0.001f) // "0%" is 12 px wide
        assertEquals(widthPx - 9f, end.centerX, 0.001f) // "80%" is 18 px wide
        // Preferred gaps: 1 below, 2 above. Baseline below = y + gap - ascent; above = y - gap - descent.
        assertEquals(pts[0].y + 1f + 10f, start.baselineY, 0.001f)
        assertEquals(pts[5].y - 2f - 2f, end.baselineY, 0.001f)
        assertEquals(GraphRect(widthPx - 18f, end.baselineY - 10f, widthPx, end.baselineY + 2f), end.box)
    }

    @Test
    fun `a custom formatter supplies the label text and its measured width`() {
        val signal = listOf(10, 30, 80, 30, 10)
        val placements = ValueLabelEngine.computePlacements(
            labelSignal = signal, points = points(signal), geometry = geometry(), config = ValueLabelEngine.Config(),
            measureText = measure, textAscent = ascent, textDescent = descent, dpToPx = noScale,
            labelText = { "$it" },
        )
        assertEquals(listOf("80", "10"), placements.map { it.text })
        assertEquals(12f, placements[0].box.width, 0.001f)
    }

    @Test
    fun `a peak blocked above falls back below with the wider fallback gap`() {
        val signal = listOf(10, 30, 80, 30, 10)
        val pts = points(signal)
        val peakY = pts[2].y
        val blocker = GraphRect(480f, peakY - 12f, 520f, peakY - 6f)
        val peak = runAt(signal, pts, obstacles = listOf(blocker)).at(2)!!
        assertFalse(peak.placedAbove)
        assertEquals(peakY + 4f + 10f, peak.baselineY, 0.001f)
    }

    @Test
    fun `a label blocked in both directions is dropped`() {
        val signal = listOf(10, 30, 80, 30, 10)
        val pts = points(signal)
        val peakY = pts[2].y
        val blocker = GraphRect(480f, peakY - 12f, 520f, peakY + 12f)
        assertEquals(listOf("4:10%:B:end"), runAt(signal, pts, obstacles = listOf(blocker)).summary())
    }

    @Test
    fun `a label that would collide with an earlier label takes the other side`() {
        // Both want to sit below; the second is 5 px to the right of the first.
        val placements = runAt(listOf(60, 60), listOf(pt(100f, 300f), pt(105f, 300f)))
        assertEquals(listOf("0:60%:B:peak", "1:60%:A:end"), placements.summary())
    }

    @Test
    fun `the top of the canvas is the upper limit for a label`() {
        val signal = listOf(10, 30, 80, 30, 10)
        fun peakAt(y: Float) =
            runAt(signal, listOf(pt(0f, 600f), pt(250f, 400f), pt(500f, y), pt(750f, 400f), pt(1000f, 600f))).at(2)!!
        assertTrue("box top lands exactly on the canvas top", peakAt(14f).placedAbove)
        assertFalse("box top would be above the canvas", peakAt(13.5f).placedAbove)
    }

    @Test
    fun `a high value below the curve must clear the bottom inset`() {
        // Below box bottom = y + 13; the inset line is graphBottom - 2 = 659.
        fun flat(y: Float) = runAt(listOf(56, 56), listOf(pt(100f, y), pt(900f, y))).summary()
        assertEquals(listOf("0:56%:B:peak", "1:56%:B:end"), flat(646f))
        assertEquals(listOf("0:56%:A:peak", "1:56%:A:end"), flat(646.5f))
    }

    @Test
    fun `a low value below the curve may use the footer band down to the canvas bottom`() {
        // 55 is the highest value allowed past the inset; the canvas is 700 px tall.
        fun flat(y: Float) = runAt(listOf(55, 55), listOf(pt(100f, y), pt(900f, y))).summary()
        assertEquals(listOf("0:55%:B:peak", "1:55%:B:end"), flat(687f))
        // Half a pixel lower the below box leaves the canvas, and the above box (bottom = y - 2) is
        // still under the inset line, so neither direction fits and the label is dropped.
        assertEquals(emptyList<String>(), flat(687.5f))
    }

    @Test
    fun `the first positive hour outranks a nearly equal peak beside it`() {
        // 20 (first positive) and the 22 peak are within the 5-point declutter band; only one stays.
        val signal = listOf(0, 20, 22, 20, 60, 90)
        assertEquals(listOf("0:0%:B:valley", "1:20%:B:other", "5:90%:A:peak"), run(signal).summary())
        assertEquals(
            listOf("0:0%:B:valley", "2:22%:A:peak", "5:90%:A:peak"),
            run(signal, ValueLabelEngine.Config(includeFirstPositive = false)).summary(),
        )
    }

    @Test
    fun `a soft-dip plateau is labeled at its middle column`() {
        // The 60 plateau (2..4) is not a local minimum because 50 sits to its left.
        val placements = run(listOf(90, 50, 60, 60, 60, 95, 40))
        assertEquals(listOf(1, 3), placements.filter { it.isSoftDip }.map { it.index })
        assertEquals(listOf(0, 1, 3, 5, 6), placements.map { it.index })
    }

    @Test
    fun `no precip midpoint is injected when it would repeat either edge value`() {
        assertEquals(listOf("0:50%:B:peak", "6:30%:B:valley"), run(listOf(50, 50, 50, 50, 50, 50, 30)).summary())
        assertEquals(listOf("0:70%:B:peak", "6:50%:A:end"), run(listOf(70, 60, 50, 50, 50, 50, 50)).summary())
    }
}
