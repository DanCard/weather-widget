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
}
