package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the property the overlay must hold: the delta row and the station reading are one overlay and
 * belong together, so a layout that puts them at opposite ends of the column is wrong even when both
 * are technically drawn.
 *
 * **Honest limitation: this does NOT reproduce the reported failure.** On the Samsung the planner
 * emitted `[delta:ABOVE, dominant_temp_age:BELOW]` — y≈30 against y≈347 — and the geometry below is
 * taken verbatim from that render's `TodayColumnOverlay` VERBOSE line (column, graph, bars,
 * aboveCeiling, block heights, rowSpacing, obstacles), with density 3.03125 derived from
 * `aboveCeiling = headerInkBottom + 3dp`. It still will not split under the pre-fix ladder, so some
 * input the log does not print differs. The device's reported bounds are themselves out of band
 * (delta's top 30.30 sits above its own aboveCeiling of 35.20, which `bandFor` should make
 * impossible), so the reconstruction is incomplete in a way worth resolving before trusting this
 * file as a regression guard.
 *
 * What it does do: fail if a future change lets a stack split when a contiguous placement exists,
 * and fail if contiguity is bought by dropping a row. `lastResort` is now in the log line, so the
 * next occurrence will say whether the ladder chose the split or nothing whole-stack fit at all.
 */
@Category(ShortDuration::class)
class TodayColumnOverlayStackContiguityTest {

    private val delta = TodayColumnOverlayPlanner.Line("delta", "+0.4 fcst", 78.60719f, 30.194092f)
    private val dominant = TodayColumnOverlayPlanner.Line("dominant_temp_age", "62.6°", 58.0f, 30.194092f)

    /** The device's own numbers, including the obstacles it reported. */
    private fun deviceInput() = TodayColumnOverlayPlanner.Input(
        columnLeft = 61.2973f,
        columnRight = 137.91891f,
        graphTop = 51.55125f,
        graphBottom = 372.2317f,
        barTop = 93.98078f,
        barBottom = 280.0677f,
        hardObstacles = listOf(
            bounds(67.10811f, 59.22714f, 132.10811f, 93.15643f),   // today's high label
            bounds(81.10811f, 272.7927f, 118.10811f, 309.7927f),   // condition icon
            bounds(60.60811f, 309.1798f, 138.60811f, 348.3966f),   // today's low label
            bounds(70.60811f, 373.98752f, 128.60811f, 399.22696f), // "Today" day label
        ),
        // Density 3.03125, derived from the same log line: aboveCeiling 35.202034 is
        // headerInkBottom 26.108282 plus VERTICAL_PADDING_DP (3dp). Guessing 2f for these instead of
        // deriving them was why the first version of this test could not reproduce the split at all:
        // `padding` is clearance from the bar cap, so understating it hands the ON_COLUMN band ~14px
        // it does not really have, and the whole stack then "fits" there under either ladder.
        horizontalPadding = 3.03125f,
        padding = 9.09375f,
        verticalStep = 3.03125f,
        rowSpacing = 1.515625f,
        // Reported by the device. Android measures this per column from the header's ink bottom, so
        // it is well above graphTop here — leaving out the real value is what made the first version
        // of this test fail to reproduce the split at all.
        aboveCeiling = 35.202034f,
    )

    @Test
    fun `the stack stays in one zone rather than splitting across the column`() {
        val placements = TodayColumnOverlayPlanner.place(listOf(delta, dominant), deviceInput())

        assertEquals("both blocks must be placed", 2, placements.size)
        val zones = placements.associate { it.key to it.zone }
        assertEquals(
            "delta and the station reading belong to one overlay and must share a zone; " +
                "they were placed as $zones",
            zones["delta"],
            zones["dominant_temp_age"],
        )
    }

    @Test
    fun `the two blocks end up vertically adjacent`() {
        val placements = TodayColumnOverlayPlanner.place(listOf(delta, dominant), deviceInput())
        val top = placements.minByOrNull { it.bounds.top }!!
        val bottom = placements.maxByOrNull { it.bounds.top }!!

        val gap = bottom.bounds.top - top.bounds.bottom
        assertTrue(
            "blocks of one stack must sit within a row spacing of each other, not $gap px apart",
            gap <= 4f,
        )
    }

    /**
     * Contiguity must not be bought by silently dropping the reading. The whole point of the report
     * was that the value had gone missing.
     */
    @Test
    fun `no content row is dropped to achieve contiguity`() {
        val placements = TodayColumnOverlayPlanner.place(listOf(delta, dominant), deviceInput())
        assertTrue(placements.any { it.key == "delta" })
        assertTrue(placements.any { it.key == "dominant_temp_age" })
    }

    /**
     * A roomy column must be unaffected — it already grouped correctly ABOVE, and this change must
     * not push those layouts onto the bars.
     */
    @Test
    fun `a column with room still places the stack cleanly above the bars`() {
        val roomy = deviceInput().copy(
            barTop = 200f,
            hardObstacles = listOf(bounds(70.60811f, 373.98752f, 128.60811f, 399.22696f)),
        )
        val placements = TodayColumnOverlayPlanner.place(listOf(delta, dominant), roomy)

        assertEquals(2, placements.size)
        assertTrue(
            "a tall ABOVE run must still win over the bars",
            placements.all { it.zone == TodayColumnOverlayPlanner.Zone.ABOVE },
        )
    }

    private fun bounds(left: Float, top: Float, right: Float, bottom: Float) =
        TodayColumnOverlayPlanner.Bounds(left, top, right, bottom)
}
