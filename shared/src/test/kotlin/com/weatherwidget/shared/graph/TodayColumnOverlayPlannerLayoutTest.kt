package com.weatherwidget.shared.graph

import com.weatherwidget.shared.graph.TodayColumnOverlayPlanner.Bounds
import com.weatherwidget.shared.graph.TodayColumnOverlayPlanner.Line
import com.weatherwidget.shared.graph.TodayColumnOverlayPlanner.Zone
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Covers the interval-packing rewrite of [TodayColumnOverlayPlanner]
 * (plans/260806-today-overlay-placement-rewrite-maximal.md).
 *
 * The old planner placed each block independently, maximizing its clearance — its distance to the
 * nearest obstacle. That parks a block in the MIDDLE of a free run, the one position that splits a
 * usable gap into two unusable ones, so a second block fell through to being drawn across the
 * forecast bars even when the total free space was ample.
 */
@Category(ShortDuration::class)
class TodayColumnOverlayPlannerLayoutTest {

    // ---- Free-interval arithmetic (newly exactly testable) --------------------------------------

    private fun runs(obstacles: List<Bounds>, bandStart: Float = 0f, bandEnd: Float = 100f) =
        TodayColumnOverlayPlanner.freeRuns(bandStart, bandEnd, obstacles, xLeft = 0f, xRight = 10f)

    @Test
    fun `no obstacles yields the whole band`() {
        assertEquals(listOf(0f..100f), runs(emptyList()))
    }

    @Test
    fun `interior obstacle splits the band at exact boundaries`() {
        assertEquals(listOf(0f..40f, 60f..100f), runs(listOf(Bounds(0f, 40f, 10f, 60f))))
    }

    @Test
    fun `obstacle outside the horizontal extent is ignored`() {
        // Neighbouring columns' labels only matter where they actually overlap the stack.
        assertEquals(listOf(0f..100f), runs(listOf(Bounds(50f, 40f, 90f, 60f))))
    }

    @Test
    fun `overlapping obstacles merge into one exclusion`() {
        val merged = runs(listOf(Bounds(0f, 30f, 10f, 55f), Bounds(0f, 45f, 10f, 70f)))
        assertEquals(listOf(0f..30f, 70f..100f), merged)
    }

    @Test
    fun `adjacent obstacles leave no zero-width phantom run`() {
        val merged = runs(listOf(Bounds(0f, 30f, 10f, 50f), Bounds(0f, 50f, 10f, 70f)))
        assertEquals(listOf(0f..30f, 70f..100f), merged)
        assertTrue("zero-width runs must not be emitted", merged.none { it.endInclusive <= it.start })
    }

    @Test
    fun `obstacle covering the band leaves no runs`() {
        assertTrue(runs(listOf(Bounds(0f, -10f, 10f, 110f))).isEmpty())
    }

    @Test
    fun `obstacle straddling an edge clips rather than splits`() {
        assertEquals(listOf(30f..100f), runs(listOf(Bounds(0f, -20f, 10f, 30f))))
    }

    // ---- The reported regression ----------------------------------------------------------------

    /**
     * The exact geometry captured from the emulator (density 2.625, so VERTICAL_PADDING_DP=3 is
     * 7.875 px). The ABOVE band is 59.07..165.28 but today's own high label caps the usable run at
     * ~145, leaving ~86 px for a 26.15 px delta plus a 53.61 px temp/age stack — ample in total.
     *
     * The old planner centred the delta at 85.32..111.47, leaving 26.25 px and ~33.5 px fragments,
     * and pushed temp/age to ON_COLUMN with score -976.375.
     */
    private val emulatorInput =
        TodayColumnOverlayPlanner.Input(
            columnLeft = 126.27f,
            columnRight = 205.19f,
            graphTop = 51.20f,
            graphBottom = 359.60f,
            barTop = 173.15f,
            barBottom = 290.52f,
            hardObstacles = listOf(Bounds(126.27f, 145f, 205.19f, 173.15f)),
            horizontalPadding = 2.625f,
            padding = 7.875f,
            rowSpacing = 2.625f,
        )

    private val emulatorLines =
        listOf(
            Line("delta", "+1.8 fcst", 68.12f, 26.15f),
            Line("dominant_temp_age", "65.4°\n0m", 50.0f, 53.61f),
        )

    @Test
    fun `both blocks land above the bars in the reported emulator geometry`() {
        val placements = TodayColumnOverlayPlanner.place(emulatorLines, emulatorInput)

        val zones = placements.associate { it.key to it.zone }
        assertEquals("both blocks must be placed; got $zones", 2, placements.size)
        assertEquals(
            "temp/age regressed onto the bars — the greedy-fragmentation bug; zones=$zones",
            Zone.ABOVE,
            zones["dominant_temp_age"],
        )
        assertEquals(Zone.ABOVE, zones["delta"])
        assertFalse(
            "stacked blocks must not overlap: ${placements.map { it.bounds }}",
            placements[0].bounds.intersects(placements[1].bounds),
        )
    }

    @Test
    fun `stack is laid out top to bottom in input order`() {
        val placements = TodayColumnOverlayPlanner.place(emulatorLines, emulatorInput)
        assertEquals("delta", placements[0].key)
        assertTrue(
            "delta must sit above temp/age: ${placements.map { it.key to it.bounds.top }}",
            placements[0].bounds.top < placements[1].bounds.top,
        )
        assertEquals(
            "blocks are separated by exactly rowSpacing",
            emulatorInput.rowSpacing,
            placements[1].bounds.top - placements[0].bounds.bottom,
            0.01f,
        )
    }

    @Test
    fun `no block lands on the bars when the whole stack fits above`() {
        // Generalized invariant, swept across band heights that comfortably fit the stack.
        val stackHeight = emulatorLines.sumOf { it.height.toDouble() }.toFloat() + emulatorInput.rowSpacing
        listOf(0f, 10f, 25f, 40f).forEach { slack ->
            val barTop = emulatorInput.padding * 2 + emulatorInput.graphTop + stackHeight + slack
            val input = emulatorInput.copy(barTop = barTop, hardObstacles = emptyList())
            val placements = TodayColumnOverlayPlanner.place(emulatorLines, input)
            val bandHeight = (barTop - input.padding) - (input.graphTop + input.padding)
            assertTrue(
                "slack=$slack put a block on the bars " +
                    "(stack=$stackHeight band=$bandHeight headroom=${bandHeight - stackHeight}): " +
                    placements.map { it.key to it.zone },
                placements.none { it.zone == Zone.ON_COLUMN },
            )
        }
    }

    @Test
    fun `block order does not change the chosen zones`() {
        val forward = TodayColumnOverlayPlanner.place(emulatorLines, emulatorInput)
        val reversed = TodayColumnOverlayPlanner.place(emulatorLines.reversed(), emulatorInput)
        assertEquals(
            forward.associate { it.key to it.zone },
            reversed.associate { it.key to it.zone },
        )
    }

    // ---- Cost ordering ---------------------------------------------------------------------------

    @Test
    fun `above wins over below even when below offers more clearance`() {
        // BELOW is a much taller band here, so the old clearance-first comparator picked it; the
        // rewrite makes zone preference a real term instead of an unreachable tie-break.
        val line = listOf(Line("delta", "+1.8", 40f, 20f))
        val input =
            TodayColumnOverlayPlanner.Input(
                columnLeft = 0f,
                columnRight = 100f,
                graphTop = 0f,
                graphBottom = 400f,
                barTop = 30f,
                barBottom = 60f,
                hardObstacles = emptyList(),
                horizontalPadding = 1f,
                padding = 2f,
            )
        assertEquals(Zone.ABOVE, TodayColumnOverlayPlanner.place(line, input).single().zone)
    }

    @Test
    fun `dropping a row is preferred over drawing across the bars`() {
        // No font-shrink ladder (removed at user request): a column too tight for the richest
        // variant at the fixed text size degrades to a poorer variant, never to smaller text.
        val input = tightInput(aboveHeight = 44f)
        val result =
            TodayColumnOverlayPlanner.layout(
                variantCount = 2,
                measureAt = { variant -> ladderLines(variant) },
                input = input,
            )
        assertEquals("poorer variant should have been chosen", 1, result.variantIndex)
        assertEquals(listOf("delta"), result.placements.map { it.key })
        assertTrue(result.placements.none { it.zone == Zone.ON_COLUMN })
    }

    @Test
    fun `clearance still centres a lone block in a roomy run`() {
        // The old aesthetic survives as the final tie-break: nothing left to fragment once the
        // stack moves as a unit.
        val line = listOf(Line("delta", "+1.8", 40f, 20f))
        val input =
            TodayColumnOverlayPlanner.Input(
                columnLeft = 0f,
                columnRight = 100f,
                graphTop = 0f,
                graphBottom = 200f,
                barTop = 100f,
                barBottom = 150f,
                hardObstacles = emptyList(),
                horizontalPadding = 1f,
                padding = 0f,
            )
        val bounds = TodayColumnOverlayPlanner.place(line, input).single().bounds
        assertEquals("block should be centred in the 0..100 band", 40f, bounds.top, 0.01f)
    }

    // ---- Ladder laziness --------------------------------------------------------------------------

    @Test
    fun `a roomy column measures exactly once`() {
        var calls = 0
        TodayColumnOverlayPlanner.layout(
            variantCount = 3,
            measureAt = { calls++; emulatorLines },
            input = emulatorInput,
        )
        assertEquals("the ladder must short-circuit; extra measurement is wasted text layout", 1, calls)
    }

    // ---- Hysteresis --------------------------------------------------------------------------------

    @Test
    fun `previous zone is retained when still available at the same strength`() {
        val line = listOf(Line("delta", "+1.8", 40f, 20f))
        val input =
            TodayColumnOverlayPlanner.Input(
                columnLeft = 0f,
                columnRight = 100f,
                graphTop = 0f,
                graphBottom = 400f,
                barTop = 30f,
                barBottom = 60f,
                hardObstacles = emptyList(),
                horizontalPadding = 1f,
                padding = 2f,
                previousZones = mapOf("delta" to Zone.BELOW),
            )
        assertEquals(
            "should stay BELOW rather than migrate to the nominally-preferred ABOVE",
            Zone.BELOW,
            TodayColumnOverlayPlanner.place(line, input).single().zone,
        )
    }

    @Test
    fun `previous zone is abandoned once it no longer fits`() {
        val line = listOf(Line("delta", "+1.8", 40f, 20f))
        val input =
            TodayColumnOverlayPlanner.Input(
                columnLeft = 0f,
                columnRight = 100f,
                graphTop = 0f,
                graphBottom = 400f,
                barTop = 30f,
                barBottom = 60f,
                // An obstacle now fills BELOW entirely.
                hardObstacles = listOf(Bounds(0f, 60f, 100f, 400f)),
                horizontalPadding = 1f,
                padding = 2f,
                previousZones = mapOf("delta" to Zone.BELOW),
            )
        assertEquals(Zone.ABOVE, TodayColumnOverlayPlanner.place(line, input).single().zone)
    }

    @Test
    fun `hysteresis never retains a materially weaker layout`() {
        // Previous frame had the block ON_COLUMN; a clean ABOVE slot now exists, so rank 1 wins.
        val line = listOf(Line("delta", "+1.8", 40f, 20f))
        val input =
            TodayColumnOverlayPlanner.Input(
                columnLeft = 0f,
                columnRight = 100f,
                graphTop = 0f,
                graphBottom = 400f,
                barTop = 30f,
                barBottom = 60f,
                hardObstacles = emptyList(),
                horizontalPadding = 1f,
                padding = 2f,
                previousZones = mapOf("delta" to Zone.ON_COLUMN),
            )
        assertNotEquals(Zone.ON_COLUMN, TodayColumnOverlayPlanner.place(line, input).single().zone)
    }

    @Test
    fun `sub-pixel obstacle jitter does not migrate a block between zones`() {
        // The anti-flap regression: the old knife-edge grid max could flip zones on a tiny shift.
        var zones = emptyMap<String, Zone>()
        listOf(0f, 0.2f, -0.15f, 0.31f).forEach { jitter ->
            val input = emulatorInput.copy(
                hardObstacles = listOf(Bounds(126.27f, 145f + jitter, 205.19f, 173.15f)),
                previousZones = zones,
            )
            val placements = TodayColumnOverlayPlanner.place(emulatorLines, input)
            val next = placements.associate { it.key to it.zone }
            if (zones.isNotEmpty()) {
                assertEquals("jitter=$jitter migrated a block between zones", zones, next)
            }
            zones = next
        }
    }

    // ---- Degenerate inputs -------------------------------------------------------------------------

    @Test
    fun `zero blocks yields an empty layout`() {
        assertTrue(TodayColumnOverlayPlanner.place(emptyList(), emulatorInput).isEmpty())
    }

    @Test
    fun `a single block behaves as a direct placement`() {
        val placements = TodayColumnOverlayPlanner.place(listOf(emulatorLines.first()), emulatorInput)
        assertEquals(1, placements.size)
        assertEquals(Zone.ABOVE, placements.single().zone)
    }

    @Test
    fun `a block taller than every zone still emits something`() {
        val giant = listOf(Line("delta", "+1.8", 40f, 10_000f))
        assertTrue(TodayColumnOverlayPlanner.place(giant, emulatorInput).isEmpty())
    }

    /**
     * A line with no measurable height must still be placed. Robolectric supplies no font engine, so
     * `fontDescent - fontAscent` is 0 and a one-row block measures 0 high — the delta row in
     * `DailyLargeTodayLayoutRoboTest`. The old planner discarded such lines and only recovered them
     * via the renderer's `combined` retry, which this rewrite removes.
     */
    @Test
    fun `zero-height lines are still placed rather than silently dropped`() {
        val lines =
            listOf(
                Line("delta", "-3.1 yest", 8f, 0f),
                Line("dominant_temp_age", "62.5°\n5m", 5f, 1f),
            )
        val placements = TodayColumnOverlayPlanner.place(lines, emulatorInput)
        assertEquals(
            "a row with unmeasurable height must not disappear; got ${placements.map { it.key }}",
            listOf("delta", "dominant_temp_age"),
            placements.map { it.key },
        )
    }

    @Test
    fun `non-finite line metrics are rejected without taking the rest down`() {
        val lines =
            listOf(
                Line("delta", "+1.8", Float.NaN, 20f),
                Line("dominant_temp_age", "65.4°", 40f, 20f),
            )
        val placements = TodayColumnOverlayPlanner.place(lines, emulatorInput)
        assertEquals(listOf("dominant_temp_age"), placements.map { it.key })
    }

    @Test
    fun `degenerate column or graph bounds yield nothing`() {
        assertTrue(
            TodayColumnOverlayPlanner.place(emulatorLines, emulatorInput.copy(columnRight = 0f)).isEmpty(),
        )
        assertTrue(
            TodayColumnOverlayPlanner.place(emulatorLines, emulatorInput.copy(graphBottom = 0f)).isEmpty(),
        )
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** ABOVE band exactly [aboveHeight] tall; BELOW and ON_COLUMN too small for the stack. */
    private fun tightInput(aboveHeight: Float) =
        TodayColumnOverlayPlanner.Input(
            columnLeft = 0f,
            columnRight = 100f,
            graphTop = 0f,
            graphBottom = aboveHeight + 40f,
            barTop = aboveHeight,
            barBottom = aboveHeight + 35f,
            hardObstacles = emptyList(),
            horizontalPadding = 1f,
            padding = 0f,
            rowSpacing = 0f,
        )

    /** variant 0 = delta + temp/age (25+20 units); variant 1 = delta only. */
    private fun ladderLines(variant: Int): List<Line> =
        when (variant) {
            0 -> listOf(
                Line("delta", "+1.8", 40f, 25f),
                Line("dominant_temp_age", "65.4°", 40f, 20f),
            )
            else -> listOf(Line("delta", "+1.8", 40f, 25f))
        }
}
