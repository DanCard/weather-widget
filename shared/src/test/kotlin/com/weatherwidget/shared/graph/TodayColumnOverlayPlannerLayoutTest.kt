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

    // ---- The redundant graph-edge inset ----------------------------------------------------------

    /**
     * Captured 2026-08-07 06:07:39 from emulator-5554 (density 2.625, labelScale 0.5), the render
     * that showed `+0.0 fcst` above the column with `58.7°`/`0m` drawn across the forecast bars.
     *
     * Today's own high label caps the ABOVE free run at 138.23, so with the band starting at
     * `graphTop + padding` = 59.07 the run is 79.16 px against an 81.07 px stack — short by 1.91 px,
     * i.e. 2.4%. The 7.875 px inset below `graphTop` was buying nothing: `graphTop` is already
     * `TOP_PADDING_DP` (39dp) of reserved header band. See
     * plans/260807-today-overlay-graph-edge-inset-opus.md.
     */
    private val edgeInsetInput =
        TodayColumnOverlayPlanner.Input(
            columnLeft = 126.27027f,
            columnRight = 205.1892f,
            graphTop = 51.196808f,
            graphBottom = 359.5991f,
            barTop = 161.65521f,
            barBottom = 290.52335f,
            // The device's full obstacle list: high label, weather icon, low label, day label.
            // Omitting the lower three leaves BELOW spuriously free and the fixture stops
            // reproducing the reported layout.
            hardObstacles =
                listOf(
                    Bounds(134.72974f, 138.23172f, 196.72974f, 170.49461f),
                    Bounds(147.72974f, 298.39835f, 183.72974f, 334.39835f),
                    Bounds(133.22974f, 333.8676f, 198.22974f, 367.82855f),
                    Bounds(136.22974f, 361.88345f, 195.22974f, 387.76752f),
                ),
            horizontalPadding = 2.625f,
            padding = 7.875f,
            rowSpacing = 1.3125f,
            edgeInset = 0f,
        )

    private val edgeInsetLines =
        listOf(
            Line("delta", "+0.0 fcst", 68.12375f, 26.14746f),
            Line("dominant_temp_age", "58.7°\n0m", 50.0f, 53.60742f),
        )

    @Test
    fun `zero edge inset lets the whole stack fit above the bars`() {
        val zones =
            TodayColumnOverlayPlanner.place(edgeInsetLines, edgeInsetInput)
                .associate { it.key to it.zone }

        assertEquals("both blocks must be placed; got $zones", 2, zones.size)
        assertEquals(
            "temp/age was pushed onto the bars again; zones=$zones",
            Zone.ABOVE,
            zones["dominant_temp_age"],
        )
        assertEquals(Zone.ABOVE, zones["delta"])
    }

    @Test
    fun `the graph-edge inset is what cost the fit`() {
        // Guards the diagnosis, not just the outcome: re-conflating edgeInset with padding must
        // reproduce the reported split, so a future "simplification" back to one constant fails
        // here rather than silently regressing the layout on a device.
        // `aboveCeiling` defaults to `graphTop + edgeInset` at construction, so restoring the old
        // behaviour means restoring the ceiling it produced — copying `edgeInset` alone would not.
        val zones =
            TodayColumnOverlayPlanner
                .place(
                    edgeInsetLines,
                    edgeInsetInput.copy(
                        edgeInset = edgeInsetInput.padding,
                        aboveCeiling = edgeInsetInput.graphTop + edgeInsetInput.padding,
                    ),
                )
                .associate { it.key to it.zone }

        // The control's job is to show the old inset denies the stack its ABOVE fit. That is still
        // exactly what it shows; the consequence is now "contiguous on the bars" rather than a split.
        assertEquals(
            "the fixture no longer reproduces the reported bug, so the test above proves nothing",
            Zone.ON_COLUMN,
            zones["dominant_temp_age"],
        )
        assertEquals("the stack must stay contiguous; zones=$zones", zones["delta"], zones["dominant_temp_age"])
    }

    @Test
    fun `edge inset defaults to padding for callers that do not distinguish them`() {
        val defaulted =
            TodayColumnOverlayPlanner.Input(
                columnLeft = 0f,
                columnRight = 100f,
                graphTop = 0f,
                graphBottom = 120f,
                barTop = 40f,
                barBottom = 80f,
                hardObstacles = emptyList(),
                horizontalPadding = 2f,
                padding = 5f,
            )
        assertEquals(defaulted.padding, defaulted.edgeInset, 0f)
    }

    // ---- Fit is decided on ink, not on font boxes ------------------------------------------------

    /**
     * Captured 2026-08-07 from the Samsung fold (SM-F936U1). Even with `edgeInset = 0` the ABOVE run
     * is 92.02 px against a 93.61 px box stack — 1.60 px short. But the rendered gap between the
     * last row's ink (`0m`, no descenders) and the `74.4°` label's ink measured 27 device px ≈ 10.5
     * bitmap px: the shortfall was entirely font-box leading counted as solid at both ends.
     *
     * Leading values are Roboto's for this size (25.77 px text): 5.4 px above digits, 6.29 px of
     * unused descent below them.
     */
    private val samsungInput =
        TodayColumnOverlayPlanner.Input(
            columnLeft = 122.5946f,
            columnRight = 199.21622f,
            graphTop = 51.55125f,
            graphBottom = 372.2317f,
            barTop = 178.67683f,
            barBottom = 297.64258f,
            hardObstacles =
                listOf(
                    Bounds(128.40541f, 143.56795f, 193.40541f, 177.49724f),
                    Bounds(142.40541f, 306.73633f, 179.40541f, 343.73633f),
                    Bounds(121.90541f, 343.12344f, 199.90541f, 382.34024f),
                    Bounds(131.90541f, 373.98752f, 189.90541f, 399.22696f),
                ),
            horizontalPadding = 3.03125f,
            padding = 9.09375f,
            rowSpacing = 1.515625f,
            edgeInset = 0f,
        )

    private val samsungLines =
        listOf(
            Line("delta", "+0.0 fcst", 78.60719f, 30.194092f, topLeading = 5.4f, bottomLeading = 6.29f),
            Line("dominant_temp_age", "60.8°\n0m", 58.0f, 61.90381f, topLeading = 5.4f, bottomLeading = 6.29f),
        )

    @Test
    fun `ink fitting lands the Samsung stack above the bars`() {
        val zones =
            TodayColumnOverlayPlanner.place(samsungLines, samsungInput)
                .associate { it.key to it.zone }

        assertEquals("both blocks must be placed; got $zones", 2, zones.size)
        assertEquals(Zone.ABOVE, zones["delta"])
        assertEquals(
            "temp/age fell to the bars again; zones=$zones",
            Zone.ABOVE,
            zones["dominant_temp_age"],
        )
    }

    @Test
    fun `box packing is what rejected the Samsung stack`() {
        // Same geometry with the leading unreported (the old box-packing behaviour) must still fail
        // to fit ABOVE, so the test above is proving the ink trim rather than some other slack.
        // What that failure now LOOKS like changed on 2026-08-24: it used to split the stack
        // (delta ABOVE, temp/age on the bars); it now keeps the stack contiguous on the bars. The
        // control still discriminates — the trimmed version above fits both blocks ABOVE, this one
        // reaches ABOVE with neither.
        val zones =
            TodayColumnOverlayPlanner
                .place(samsungLines.map { it.copy(topLeading = 0f, bottomLeading = 0f) }, samsungInput)
                .associate { it.key to it.zone }

        assertEquals("the stack must stay contiguous; zones=$zones", zones["delta"], zones["dominant_temp_age"])
        assertEquals(Zone.ON_COLUMN, zones["delta"])
    }

    @Test
    fun `only the stack's outer leading is trimmed`() {
        // Interior leading is real spacing between blocks. If it were trimmed too, the stack would
        // gain 2x more room and the blocks would visually close up.
        val placements = TodayColumnOverlayPlanner.place(samsungLines, samsungInput)
        assertEquals(
            "blocks stay exactly rowSpacing apart regardless of leading",
            samsungInput.rowSpacing,
            placements[1].bounds.top - placements[0].bounds.bottom,
            0.01f,
        )
    }

    @Test
    fun `trimmed leading hangs outside the run rather than shifting ink into obstacles`() {
        val placements = TodayColumnOverlayPlanner.place(samsungLines, samsungInput)
        val obstacleTop = samsungInput.hardObstacles.first().top // the high label caps ABOVE
        val lastInkBottom = placements.last().bounds.bottom - samsungLines.last().bottomLeading

        assertTrue(
            "ink must clear the high label: inkBottom=$lastInkBottom obstacleTop=$obstacleTop",
            lastInkBottom <= obstacleTop + 0.01f,
        )
        // ABOVE hugs the bottom of its run less `padding`, so it is the blank DESCENT that hangs out
        // of the run — the last row's ink stops a clear `padding` short of the high label while its
        // box extends past that.
        assertEquals(
            "last row's ink should sit `padding` above the high label",
            obstacleTop - samsungInput.padding,
            lastInkBottom,
            0.01f,
        )
        assertTrue(
            "the box itself overhangs the ink by the blank descent",
            placements.last().bounds.bottom > lastInkBottom,
        )
    }

    // ---- The ABOVE ceiling is the header's measured ink, not a fraction of the band ---------------

    /**
     * Captured 2026-08-07 from the Samsung fold (SM-F936U1) after the user reported that *every*
     * overlay row rendered across the forecast bars while a row's worth of empty band sat above the
     * column. `graphTop` is 51.55 (50dp of reserved header band) and the old ceiling took a fixed
     * quarter of it, so the ABOVE run was 41.24..57.89 — 16.65 px against an 18.5 px delta row.
     *
     * The run ends at the `1%` rain chip (57.89), not the `82°` high label (63.17): the chip is 9 px
     * wide and dead centre, so a 71.6 px-wide stack cannot pass it.
     */
    private val foldHeaderBandInput =
        TodayColumnOverlayPlanner.Input(
            columnLeft = 122.5946f,
            columnRight = 199.21622f,
            graphTop = 51.55125f,
            graphBottom = 372.2317f,
            barTop = 85.49489f,
            barBottom = 297.64258f,
            hardObstacles =
                listOf(
                    Bounds(135.90541f, 63.17186f, 185.90541f, 102.38866f), // 82° high label
                    Bounds(142.40541f, 306.73633f, 179.40541f, 343.73633f), // weather icon
                    Bounds(121.90541f, 343.12344f, 199.90541f, 382.34024f), // "Today"
                    Bounds(131.90541f, 373.98752f, 189.90541f, 399.22696f), // low label
                    Bounds(156.40541f, 57.89468f, 165.40541f, 66.26494f), // 1% rain chip
                    Bounds(191.08531f, 369.39902f, 198.08531f, 375.4256f),
                ),
            horizontalPadding = 3.03125f,
            padding = 9.09375f,
            rowSpacing = 1.515625f,
            edgeInset = 0f,
            // Header ink bottoms out at the 24dp weather icon (22.7) plus the same clearance the bar
            // cap gets. The old value was graphTop * 0.75 = 38.66 (0.8 in the build that shipped it).
            aboveCeiling = 22.7f + 9.09375f,
        )

    private val foldHeaderBandLines =
        listOf(
            Line("delta", "-2.5 fcst", 71.60719f, 30.194092f, topLeading = 5.4f, bottomLeading = 6.29f),
            Line("dominant_temp_age", "66.2°\n5m", 58.0f, 61.90381f, topLeading = 5.4f, bottomLeading = 6.29f),
        )

    @Test
    fun `the fold's stack stays contiguous rather than splitting to reach ABOVE`() {
        val zones =
            TodayColumnOverlayPlanner.place(foldHeaderBandLines, foldHeaderBandInput)
                .associate { it.key to it.zone }

        assertEquals("both blocks must be placed; got $zones", 2, zones.size)
        // SUPERSEDED 2026-08-24. This asserted delta:ABOVE + temp/age:ON_COLUMN — lifting one row
        // off the bars and leaving the other behind. The measured ceiling still does its job (it is
        // what makes the ABOVE run real at all), but splitting the stack to exploit it is no longer
        // the preferred outcome: on the Samsung the two rows landed at opposite ends of the column
        // and the station reading read as missing. Contiguity now outranks getting off the bars.
        assertEquals("the stack must not split to reach ABOVE; zones=$zones", zones["delta"], zones["dominant_temp_age"])
    }

    @Test
    fun `the guessed band fraction is what kept the fold's delta on the bars`() {
        // Paired control: same geometry at the old ceiling must still put everything on the bars, so
        // the test above cannot pass on some other slack.
        val zones =
            TodayColumnOverlayPlanner
                .place(
                    foldHeaderBandLines,
                    foldHeaderBandInput.copy(aboveCeiling = foldHeaderBandInput.graphTop * 0.8f),
                )
                .associate { it.key to it.zone }

        assertEquals(Zone.ON_COLUMN, zones["delta"])
        assertEquals(Zone.ON_COLUMN, zones["dominant_temp_age"])
    }

    @Test
    fun `a lifted ceiling keeps the stack off the ceiling by a share of the slack`() {
        // The ABOVE bias is a fraction of the slack, not a hard hug, so an open ceiling cannot pull
        // the stack onto it. Taken to the limit — no ceiling at all — the delta still keeps a quarter
        // of the run's spare room between itself and y=0, where the header text lives.
        val runEnd = 57.89468f // the 1% rain chip caps the ABOVE run
        // A ONE-block stack, so the ABOVE zone is genuinely chosen and this test keeps measuring
        // slack distribution rather than split policy. The two-block stack is 81.9 px of ink against
        // a 26.1 px run and now stays contiguous on the bars, which says nothing about the bias.
        val delta =
            TodayColumnOverlayPlanner
                .place(foldHeaderBandLines.take(1), foldHeaderBandInput.copy(aboveCeiling = 0f))
                .single { it.key == "delta" }
        val inkTop = delta.bounds.top + foldHeaderBandLines.first().topLeading
        val inkBottom = delta.bounds.bottom - foldHeaderBandLines.first().bottomLeading
        val slack = inkTop + (runEnd - inkBottom)

        assertEquals(
            "delta should take a quarter of the slack above: top gap $inkTop of $slack",
            slack * TodayColumnOverlayPlanner.ABOVE_SLACK_ABOVE_FRACTION,
            inkTop,
            0.01f,
        )
        assertTrue(
            "the bias must still leave at least `padding` off the chip",
            inkBottom <= runEnd - foldHeaderBandInput.padding + 0.01f,
        )
        // A hard top hug would have put the box at `0 - topLeading`, ink flush with the header text.
        val topHuggingTop = 0f - foldHeaderBandLines.first().topLeading
        assertTrue(
            "stack hugged the ceiling: top=${delta.bounds.top}, top-hugging=$topHuggingTop",
            delta.bounds.top > topHuggingTop + slack * 0.2f,
        )
    }

    @Test
    fun `a ceiling tighter than the clearance spends every pixel it has`() {
        // The real fold ceiling leaves 7.6 px of slack against 9.09 px of padding, so ABOVE cannot
        // have its full clearance. It must use all of the slack rather than none of it.
        // One block, for the same reason as the slack-fraction test above: the subject is where
        // within the ABOVE run the stack sits, not whether a two-block stack reaches ABOVE at all.
        val delta =
            TodayColumnOverlayPlanner.place(foldHeaderBandLines.take(1), foldHeaderBandInput)
                .single { it.key == "delta" }
        val inkTop = delta.bounds.top + foldHeaderBandLines.first().topLeading

        assertEquals(
            "the stack should start at the ceiling when slack < padding",
            foldHeaderBandInput.aboveCeiling,
            inkTop,
            0.01f,
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
    fun `dropping a row is preferred over drawing across the bars when no split fits`() {
        // No font-shrink ladder (removed at user request): a column too tight for the richest
        // variant at the fixed text size degrades to a poorer variant, never to smaller text.
        val input = tightInputNoBarsFit(aboveHeight = 44f)
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
    fun `content completeness via cross-zone split is preferred over dropping a row`() {
        val input = tightInput(aboveHeight = 44f)
        val result =
            TodayColumnOverlayPlanner.layout(
                variantCount = 2,
                measureAt = { variant -> ladderLines(variant) },
                input = input,
            )
        assertEquals("richest variant should be chosen via split", 0, result.variantIndex)
        val zones = result.placements.associate { it.key to it.zone }
        assertEquals(Zone.ABOVE, zones["delta"])
        assertEquals(Zone.ON_COLUMN, zones["dominant_temp_age"])
    }

    @Test
    fun `desktop geometry keeps all 3 rows contiguous rather than splitting them`() {
        val desktopInput = TodayColumnOverlayPlanner.Input(
            columnLeft = 255.47f,
            columnRight = 415.13f,
            graphTop = 6.0f,
            graphBottom = 813.5f,
            barTop = 171.70f,
            barBottom = 648.06f,
            hardObstacles = listOf(
                Bounds(302.8f, 100.7f, 367.8f, 156.7f),
                Bounds(287.8f, 225.7f, 382.8f, 278.7f),
                Bounds(297.0f, 629.7f, 373.6f, 706.4f),
                Bounds(286.8f, 712.4f, 383.8f, 766.4f),
                Bounds(289.3f, 818.0f, 381.3f, 863.0f),
                Bounds(408.8f, 762.1f, 420.8f, 773.1f),
            ),
            horizontalPadding = 2f,
            padding = 3f,
            rowSpacing = 3f,
        )
        val desktopLines = listOf(
            Line("delta", "-1.2 fcst", 108f, 49f),
            Line("dominant_temp_age", "64.4°\n5m", 100f, 100f),
        )

        val result = TodayColumnOverlayPlanner.layout(
            variantCount = 1,
            measureAt = { desktopLines },
            input = desktopInput,
        )

        assertEquals(0, result.variantIndex)
        val zones = result.placements.associate { it.key to it.zone }
        // SUPERSEDED 2026-08-24: previously delta ABOVE + temp/age ON_COLUMN. No row is dropped
        // (variantIndex is still 0) and nothing falls to the last resort — the stack simply stays
        // together now instead of straddling two zones.
        assertEquals("the stack must stay contiguous; zones=$zones", zones["delta"], zones["dominant_temp_age"])
        assertEquals(Zone.ON_COLUMN, zones["delta"])
        assertFalse(result.fromLastResort)
    }

    @Test
    fun `a contiguous stack on the bars beats an ABOVE and BELOW split`() {
        val lines = listOf(
            Line("delta", "+1.8", 40f, 25f),
            Line("dominant_temp_age", "65.4°", 40f, 20f),
        )
        val input = TodayColumnOverlayPlanner.Input(
            columnLeft = 0f,
            columnRight = 100f,
            graphTop = 0f,
            graphBottom = 130f,
            barTop = 30f,
            barBottom = 100f,
            hardObstacles = emptyList(),
            horizontalPadding = 1f,
            padding = 0f,
            rowSpacing = 0f,
        )
        val placements = TodayColumnOverlayPlanner.place(lines, input)
        val zones = placements.associate { it.key to it.zone }
        // SUPERSEDED 2026-08-24: this named the ABOVE/BELOW split as preferred over the bars. It is
        // now the other way round — ABOVE and BELOW are opposite ends of the column, and a reader
        // does not connect two labels that far apart. A contiguous group over the bars wins.
        assertEquals("the stack must stay contiguous; zones=$zones", zones["delta"], zones["dominant_temp_age"])
        assertEquals(Zone.ON_COLUMN, zones["delta"])
    }

    @Test
    fun `inverted pairs are never emitted in split candidates`() {
        val lines = listOf(
            Line("head", "Head text", 40f, 25f),
            Line("tail", "Tail text", 40f, 25f),
        )
        val input = TodayColumnOverlayPlanner.Input(
            columnLeft = 0f,
            columnRight = 100f,
            graphTop = 0f,
            graphBottom = 150f,
            barTop = 50f,
            barBottom = 100f,
            hardObstacles = emptyList(),
            horizontalPadding = 1f,
            padding = 0f,
            rowSpacing = 0f,
        )
        val placements = TodayColumnOverlayPlanner.place(lines, input)
        if (placements.size == 2) {
            assertTrue(
                "head must sit above or at same top as tail: ${placements.map { it.bounds.top }}",
                placements[0].bounds.top <= placements[1].bounds.top,
            )
            val headZone = placements[0].zone
            val tailZone = placements[1].zone
            val order = listOf(Zone.ABOVE, Zone.ON_COLUMN, Zone.BELOW)
            assertTrue("head zone must be above or equal to tail zone", order.indexOf(headZone) <= order.indexOf(tailZone))
        }
    }

    @Test
    fun `fromLastResort is false for search layouts and true for last-resort layouts`() {
        val normalLayout = TodayColumnOverlayPlanner.layout(
            variantCount = 1,
            measureAt = { emulatorLines },
            input = emulatorInput,
        )
        assertFalse(normalLayout.fromLastResort)

        val tightInput = TodayColumnOverlayPlanner.Input(
            columnLeft = 0f,
            columnRight = 100f,
            graphTop = 0f,
            graphBottom = 100f,
            barTop = 40f,
            barBottom = 60f,
            hardObstacles = listOf(
                Bounds(0f, 0f, 100f, 30f),
                Bounds(0f, 60f, 100f, 100f),
            ),
            horizontalPadding = 0f,
            padding = 0f,
            rowSpacing = 0f,
        )
        val bigLines = listOf(
            Line("line1", "L1", 40f, 15f),
            Line("line2", "L2", 40f, 15f),
        )
        val lastResortLayout = TodayColumnOverlayPlanner.layout(
            variantCount = 1,
            measureAt = { bigLines },
            input = tightInput,
        )
        assertTrue("last-resort layout must have fromLastResort set to true", lastResortLayout.fromLastResort)
    }

    @Test
    fun `ABOVE splits a roomy run with a top bias while BELOW hugs the edge away from the bars`() {
        // BELOW's spare room becomes distance from the bar cap. ABOVE instead keeps a quarter of the
        // slack above and three quarters below (see layOut): banking every spare pixel at the bottom
        // left a visibly empty band under the header, and banking it all at the top would strand the
        // text at the widget edge on a column the header does not reach over.
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
        // 80 px of slack in the 0..100 band: a quarter of it above the 20 px row.
        val bounds = TodayColumnOverlayPlanner.place(line, input).single().bounds
        assertEquals("a quarter of the slack sits above the stack", 20f, bounds.top, 0.01f)
        assertEquals("the other three quarters sit below it", 40f, bounds.bottom, 0.01f)

        // Same split once there is a padding, just within the shorter 0..88 band (68 px of slack).
        val padded = TodayColumnOverlayPlanner.place(line, input.copy(padding = 12f)).single().bounds
        assertEquals("split within the 0..(barTop - padding) band", 17f, padded.top, 0.01f)

        // ...but the bias must never buy less bar-cap clearance than the old bottom-hug did: in a run
        // tighter than `padding / (1 - fraction)` it degrades to sitting `padding` clear of the end.
        // Band 0..34 against a 20 px row is 14 px of slack, under the 16 px where the bias takes over.
        val tight =
            TodayColumnOverlayPlanner
                .place(line, input.copy(barTop = 46f, padding = 12f))
                .single()
                .bounds
        assertEquals("tight ABOVE run keeps a full `padding` off the band end", 22f, tight.bottom, 0.01f)

        // ...and the mirror image below the bars, so neither zone drifts toward the bar cap.
        val below =
            TodayColumnOverlayPlanner
                .place(line, input.copy(barTop = 0f, barBottom = 100f, graphBottom = 200f))
                .single()
                .bounds
        assertEquals(Zone.BELOW, TodayColumnOverlayPlanner.place(line, input.copy(barTop = 0f, barBottom = 100f)).single().zone)
        assertEquals("BELOW should sit at the bottom of the 100..200 band", 200f, below.bottom, 0.01f)
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

    private fun tightInputNoBarsFit(aboveHeight: Float) =
        TodayColumnOverlayPlanner.Input(
            columnLeft = 0f,
            columnRight = 100f,
            graphTop = 0f,
            graphBottom = aboveHeight + 20f,
            barTop = aboveHeight,
            barBottom = aboveHeight + 15f,
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
