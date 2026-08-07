package com.weatherwidget.shared.graph

import kotlin.math.max
import kotlin.math.min

/**
 * Vertical layout for the large daily Today-column annotations, shared by the Android
 * (`TodayColumnOverlayRenderer`) and desktop (`DailyForecastGraph`) renderers.
 *
 * The overlay is treated as **one ordered stack**, not as N independently-placed blocks. The
 * previous implementation maximized each block's *clearance* — its distance to the nearest obstacle
 * — over a dense grid of candidate positions, one block at a time. Maximizing clearance parks a
 * block in the MIDDLE of a free run, which is the only placement that turns one usable gap into two
 * unusable ones: a 26 px delta centred in an 86 px run left 26 px and 33 px fragments for a 54 px
 * temp/age block that would have fitted against either edge, so it fell through to being drawn over
 * the bars. See plans/260806-today-overlay-placement-rewrite-maximal.md.
 *
 * Clearance is kept, but demoted to the final tie-break: once the stack is placed as a unit there is
 * nothing left to fragment, so centring it in the chosen run is free and preserves the original
 * look in roomy columns.
 *
 * Candidates are searched in strict cost order and the first success wins, which makes the ordering
 * lexicographic by construction (no weighted sum, no magic penalty constant):
 *
 *  1. fewest content rows dropped (per-variant ladder)
 *  2. clean single-zone
 *  3. clean split
 *  4. split with bars
 *  5. whole stack on bars
 *  6. zone preference (ABOVE before BELOW)
 *  7. greatest clearance
 *
 * Ranks 2-5 are supplied by candidate search order per variant; hysteresis still only overrides
 * the weak terms (zone preference and clearance).
 *
 * Ranks 1-5 are supplied by the caller: the planner never learns what an "age row" is, and it cannot
 * measure text (Android uses `Paint`, desktop uses `TextMeasurer`), so content variants and
 * measurement both arrive through [layout]'s callback.
 *
 * Text is always measured and drawn at one fixed size — there is deliberately no font-shrink
 * dimension (removed at user request): a tighter column degrades by dropping content rows, never
 * by changing the rendered text size.
 */
object TodayColumnOverlayPlanner {
    enum class Zone {
        ABOVE,
        BELOW,
        ON_COLUMN,
    }

    data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        fun intersects(other: Bounds): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }

    /**
     * One block of the stack. [height] is the full font box (ascent..descent) the renderer will
     * advance by, so blocks keep their normal rhythm when drawn.
     *
     * [topLeading] and [bottomLeading] are the parts of that box which contain no ink — the gap
     * between the box edge and the actual glyphs. They are measured by the renderer (the planner
     * cannot measure text) and exist because **fit is decided on ink, not on boxes**. Packing boxes
     * against obstacles that are themselves boxes double-counts the leading where they meet: on the
     * reported Samsung layout, ~12 px of visibly empty space between `0m` and the `74.4°` label was
     * counted as solid, which is what made a stack with 10 px of real headroom read as a 1.6 px
     * overflow. Default 0 keeps callers that do not measure leading on the old box-packing
     * behaviour.
     */
    data class Line(
        val key: String,
        val text: String,
        val width: Float,
        val height: Float,
        val topLeading: Float = 0f,
        val bottomLeading: Float = 0f,
    )

    data class Placement(
        val key: String,
        val text: String,
        val zone: Zone,
        val bounds: Bounds,
        val score: Float,
    )

    data class Input(
        val columnLeft: Float,
        val columnRight: Float,
        val graphTop: Float,
        val graphBottom: Float,
        val barTop: Float,
        val barBottom: Float,
        val hardObstacles: List<Bounds>,
        val horizontalPadding: Float,
        val padding: Float,
        /** Retained for call-site compatibility; the interval search is continuous and does not step. */
        val verticalStep: Float = 2f,
        /** Gap inserted between blocks of one stack. */
        val rowSpacing: Float = 0f,
        /**
         * Zone each block occupied on the previous render, keyed by [Line.key]. When a layout that
         * reproduces these zones is available at the SAME strength (same rows dropped,
         * same group count), it is preferred over a marginally better one — so label jitter cannot
         * migrate text between zones between renders. Hysteresis only ever overrides the weak terms
         * (zone preference and clearance); it can never retain a materially worse layout.
         */
        val previousZones: Map<String, Zone> = emptyMap(),
        /**
         * Inset from the graph's OUTER edge ([graphTop] / [graphBottom]), as distinct from
         * [padding], which is clearance from the bar cap. Defaults to [padding] for callers that do
         * not distinguish the two.
         *
         * Both renderers pass 0. [graphTop] and [graphBottom] are already computed margins — on
         * Android `graphTop` IS `TOP_PADDING_DP` (39dp) of reserved header band — so a second inset
         * there is pure lost headroom. It cost the reported layout its fit: an ABOVE free run of
         * 79.16 px against an 81.07 px stack, short by 1.91 px, which dropped the temp/age block
         * onto the forecast bars while 7.875 px of usable space sat unused at the top of the graph.
         */
        val edgeInset: Float = padding,
        /**
         * Topmost y the ABOVE zone may use. Defaults to the graph edge, but callers may raise the
         * ceiling into space the graph reserves and does not draw in — on Android [graphTop] is a
         * 50dp header band whose text occupies well under half of it, and that unused remainder is
         * the only place the stack can gain real distance from the bar cap's temperature label.
         * Raising it here rather than lowering [graphTop] leaves the temperature scale, the bar
         * heights, and every other column untouched.
         *
         * Android passes the header's MEASURED ink bottom plus [padding]
         * (`DailyForecastHeaderRenderer.resolveHeaderInkBottom`). It passed a fraction of the band
         * until 2026-08-07; on the Samsung fold that guess reserved ~17 px of empty band and left
         * every overlay row drawn across the forecast bars. Desktop still passes the graph edge.
         */
        val aboveCeiling: Float = graphTop + edgeInset,
    )

    /** Chosen layout plus the content variant the renderer must paint. */
    data class Layout(
        val placements: List<Placement>,
        val variantIndex: Int,
        val fromLastResort: Boolean = false,
    ) {
        val isEmpty: Boolean get() = placements.isEmpty()
    }

    private val ZONE_ORDER = listOf(Zone.ABOVE, Zone.BELOW, Zone.ON_COLUMN)

    /**
     * Convenience entry point: one content variant, no hysteresis. Equivalent to the old
     * `place(lines, input)` and used where the caller has nothing to degrade.
     */
    fun place(lines: List<Line>, input: Input): List<Placement> =
        layout(
            variantCount = 1,
            measureAt = { lines },
            input = input,
        ).placements

    /**
     * Searches `variant x grouping x zone` in cost order and returns the first layout that
     * fits. [measureAt] is called lazily and memoized, so the common roomy case costs exactly one
     * measurement pass — the ladder only pays for extra measurement in genuinely cramped columns.
     */
    fun layout(
        variantCount: Int,
        measureAt: (variantIndex: Int) -> List<Line>,
        input: Input,
    ): Layout {
        val empty = Layout(emptyList(), 0, fromLastResort = false)
        if (input.columnRight <= input.columnLeft || input.graphBottom <= input.graphTop) return empty
        if (variantCount <= 0) return empty

        val measured = HashMap<Int, List<Line>>()
        // Zero-size lines are KEPT: a line with no measurable height still has text to draw, and a
        // caller whose font metrics are unavailable (Robolectric supplies none, so a one-row block
        // measures 0 high) must not silently lose a row. Only nonsense values are rejected. The old
        // planner dropped zero-height lines and relied on the renderer's `combined` retry to recover
        // them — that retry is gone, so the tolerance has to live here.
        fun linesFor(variant: Int): List<Line> =
            measured.getOrPut(variant) { measureAt(variant) }
                .filter { it.width.isFinite() && it.height.isFinite() && it.width >= 0f && it.height >= 0f }

        val candidates = sequence {
            for (variant in 0 until variantCount) {
                val lines = linesFor(variant)
                if (lines.isEmpty()) continue

                // 1. Same-zone clean: ABOVE 1-group, BELOW 1-group, ABOVE 2-group, BELOW 2-group
                fitGroupInZone(lines, Zone.ABOVE, input)?.let {
                    yield(Candidate(it, variant, groups = 1, onBars = false))
                }
                fitGroupInZone(lines, Zone.BELOW, input)?.let {
                    yield(Candidate(it, variant, groups = 1, onBars = false))
                }
                if (lines.size >= 2) {
                    fitSameZoneTwoGroups(lines, Zone.ABOVE, input)?.let {
                        yield(Candidate(it, variant, groups = 2, onBars = false))
                    }
                    fitSameZoneTwoGroups(lines, Zone.BELOW, input)?.let {
                        yield(Candidate(it, variant, groups = 2, onBars = false))
                    }

                    // 2. Cross-zone clean split (ABOVE, BELOW)
                    fitSplitForZonePair(lines, Zone.ABOVE, Zone.BELOW, input)?.let {
                        yield(Candidate(it, variant, groups = 2, onBars = false))
                    }

                    // 3. Cross-zone split (ABOVE, ON_COLUMN)
                    fitSplitForZonePair(lines, Zone.ABOVE, Zone.ON_COLUMN, input)?.let {
                        yield(Candidate(it, variant, groups = 2, onBars = true))
                    }

                    // 4. Cross-zone split (ON_COLUMN, BELOW)
                    fitSplitForZonePair(lines, Zone.ON_COLUMN, Zone.BELOW, input)?.let {
                        yield(Candidate(it, variant, groups = 2, onBars = true))
                    }
                }

                // 5. Same-zone ON_COLUMN, 1-group then 2-group
                fitGroupInZone(lines, Zone.ON_COLUMN, input)?.let {
                    yield(Candidate(it, variant, groups = 1, onBars = true))
                }
                if (lines.size >= 2) {
                    fitSameZoneTwoGroups(lines, Zone.ON_COLUMN, input)?.let {
                        yield(Candidate(it, variant, groups = 2, onBars = true))
                    }
                }
            }
        }

        // Ranks are honoured by iteration order, so the first candidate is the optimum. Hysteresis
        // may substitute a same-strength candidate that reproduces the previous zones.
        val best = candidates.firstOrNull() ?: return lastResort(variantCount, ::linesFor, input, empty)
        val chosen =
            if (input.previousZones.isEmpty()) {
                best
            } else {
                candidates
                    .takeWhile { it.sameStrengthAs(best) }
                    .firstOrNull { it.reproduces(input.previousZones) }
                    ?: best
            }
        return Layout(chosen.placements, chosen.variantIndex, fromLastResort = false)
    }

    private const val FIT_EPSILON = 0.01f

    private fun ClosedFloatingPointRange<Float>.fits(height: Float): Boolean =
        endInclusive - start >= height - FIT_EPSILON

    private data class Candidate(
        val placements: List<Placement>,
        val variantIndex: Int,
        val groups: Int,
        val onBars: Boolean,
    ) {
        /** Same rank 1-3 strength: differs only in zone preference and clearance. */
        fun sameStrengthAs(other: Candidate): Boolean =
            onBars == other.onBars &&
                variantIndex == other.variantIndex &&
                groups == other.groups

        fun reproduces(previous: Map<String, Zone>): Boolean =
            placements.all { previous[it.key] == null || previous[it.key] == it.zone }
    }

    /**
     * Final floor: place whatever blocks can be placed individually, so a column too short for any
     * whole-stack layout still shows something rather than nothing.
     */
    private fun lastResort(
        variantCount: Int,
        linesFor: (variant: Int) -> List<Line>,
        input: Input,
        empty: Layout,
    ): Layout {
        for (variant in 0 until variantCount) {
            val lines = linesFor(variant)
            if (lines.isEmpty()) continue
            val placements = mutableListOf<Placement>()
            val taken = mutableListOf<Bounds>()
            lines.forEach { line ->
                ZONE_ORDER.firstNotNullOfOrNull { zone ->
                    fitGroupInZone(listOf(line), zone, input, extraObstacles = taken)
                }?.let {
                    placements += it
                    taken += it.map(Placement::bounds)
                }
            }
            if (placements.isNotEmpty()) return Layout(placements, variant, fromLastResort = true)
        }
        return empty
    }

    private fun fitGroupInZone(
        lines: List<Line>,
        zone: Zone,
        input: Input,
        extraObstacles: List<Bounds> = emptyList(),
    ): List<Placement>? {
        if (lines.isEmpty()) return null
        val band = bandFor(zone, input) ?: return null
        val stackWidth = lines.maxOf { it.width }
        val left = (input.columnLeft + input.columnRight - stackWidth) / 2f
        val right = left + stackWidth
        val runs = freeRuns(band.first, band.second, input.hardObstacles + extraObstacles, left, right)
        if (runs.isEmpty()) return null

        val height = inkHeight(lines, input.rowSpacing)
        val run = runs.filter { it.fits(height) }
            .maxByOrNull { it.endInclusive - it.start } ?: return null
        return layOut(lines, run, height, zone, input)
    }

    private fun fitSameZoneTwoGroups(
        lines: List<Line>,
        zone: Zone,
        input: Input,
    ): List<Placement>? {
        if (lines.size < 2) return null
        val band = bandFor(zone, input) ?: return null
        val stackWidth = lines.maxOf { it.width }
        val left = (input.columnLeft + input.columnRight - stackWidth) / 2f
        val right = left + stackWidth
        val runs = freeRuns(band.first, band.second, input.hardObstacles, left, right)
        if (runs.isEmpty()) return null

        var best: List<Placement>? = null
        var bestClearance = Float.NEGATIVE_INFINITY
        for (seam in 1 until lines.size) {
            val head = lines.subList(0, seam)
            val tail = lines.subList(seam, lines.size)
            val headHeight = inkHeight(head, input.rowSpacing)
            val tailHeight = inkHeight(tail, input.rowSpacing)
            for (i in runs.indices) {
                if (!runs[i].fits(headHeight)) continue
                for (j in i + 1 until runs.size) {
                    if (!runs[j].fits(tailHeight)) continue
                    val placed =
                        layOut(head, runs[i], headHeight, zone, input) +
                            layOut(tail, runs[j], tailHeight, zone, input)
                    val clearance = placed.minOf { it.score }
                    if (clearance > bestClearance) {
                        bestClearance = clearance
                        best = placed
                    }
                }
            }
        }
        return best
    }

    private fun fitSplitForZonePair(
        lines: List<Line>,
        headZone: Zone,
        tailZone: Zone,
        input: Input,
    ): List<Placement>? {
        if (lines.size < 2) return null
        var bestPlaced: List<Placement>? = null
        var minOnBarsCount = Int.MAX_VALUE

        for (seam in 1 until lines.size) {
            val head = lines.subList(0, seam)
            val tail = lines.subList(seam, lines.size)
            val headPlaced = fitGroupInZone(head, headZone, input) ?: continue
            val tailPlaced = fitGroupInZone(tail, tailZone, input) ?: continue

            val onBarsCount = when {
                headZone == Zone.ON_COLUMN -> head.size
                tailZone == Zone.ON_COLUMN -> tail.size
                else -> 0
            }

            if (onBarsCount < minOnBarsCount) {
                minOnBarsCount = onBarsCount
                bestPlaced = headPlaced + tailPlaced
            }
        }
        return bestPlaced
    }

    /**
     * Centres the stack's INK in [run] and lays the blocks out top-to-bottom in input order.
     *
     * [height] is the ink height, so the returned boxes may extend past the run by each end's
     * leading — deliberately, since that leading is blank. Drawing is unchanged: blocks still
     * advance by their full box height, so the stack's internal rhythm is identical.
     */
    private fun layOut(
        lines: List<Line>,
        run: ClosedFloatingPointRange<Float>,
        height: Float,
        zone: Zone,
        input: Input,
    ): List<Placement> {
        val free = (run.endInclusive - run.start - height).coerceAtLeast(0f)
        // ABOVE sits at the BOTTOM of its run, backed off by [Input.padding] — as close to the column
        // it annotates as that clearance allows. It hugged the TOP until 2026-08-07, which was
        // indistinguishable while the run was a few px roomier than the stack, but the Android
        // ceiling is now the header's MEASURED ink rather than a fraction of the reserved band, so a
        // top-hugging stack floats up under the header instead of staying with its column. BELOW
        // still hugs its far edge — that edge is away from both the bars and the header — and
        // ON_COLUMN has bars on both sides, so it still centres.
        val offset =
            when (zone) {
                Zone.ABOVE -> (free - input.padding).coerceAtLeast(0f)
                Zone.BELOW -> free
                Zone.ON_COLUMN -> free / 2f
            }
        val slack = free / 2f
        val top = run.start + offset - lines.first().topLeading
        var cursor = top
        return lines.map { line ->
            val left = (input.columnLeft + input.columnRight - line.width) / 2f
            val bounds = Bounds(left, cursor, left + line.width, cursor + line.height)
            cursor += line.height + input.rowSpacing
            Placement(line.key, line.text, zone, bounds, slack)
        }
    }

    private fun stackHeight(lines: List<Line>, rowSpacing: Float): Float =
        lines.sumOf { it.height.toDouble() }.toFloat() + rowSpacing * (lines.size - 1)

    /**
     * The stack's height measured from the first row's ink to the last row's — what actually has to
     * clear the obstacles. Only the OUTER leading is trimmed: leading between blocks is real
     * spacing that keeps the rows legible, and trimming it would let neighbours visually collide.
     */
    private fun inkHeight(lines: List<Line>, rowSpacing: Float): Float =
        (
            stackHeight(lines, rowSpacing) -
                lines.first().topLeading -
                lines.last().bottomLeading
            ).coerceAtLeast(0f)

    /**
     * The two outer bands are bounded by different things at each end: the graph edge takes
     * [Input.edgeInset] (zero for the real renderers — the edge is already a computed margin), the
     * bar cap takes [Input.padding]. Conflating them into one constant meant tuning bar clearance
     * silently shrank the usable band by the same amount at the graph edge, where nothing needed it.
     */
    private fun bandFor(zone: Zone, input: Input): Pair<Float, Float>? {
        val band =
            when (zone) {
                Zone.ABOVE -> input.aboveCeiling to (input.barTop - input.padding)
                Zone.BELOW -> (input.barBottom + input.padding) to (input.graphBottom - input.edgeInset)
                Zone.ON_COLUMN -> (input.barTop + input.padding) to (input.barBottom - input.padding)
            }
        return band.takeIf { it.second > it.first }
    }

    /**
     * Exact free intervals of `[bandStart, bandEnd]` after removing every obstacle that overlaps the
     * horizontal extent `[xLeft, xRight]`. Replaces the old `candidateTops` grid: continuous, so a
     * slot cannot be missed because it fell between two sampled positions, and `O(n log n)` rather
     * than `O(band / step * obstacles)`.
     *
     * Obstacles that do not overlap horizontally are irrelevant and are dropped, matching
     * [Bounds.intersects] semantics (strict inequality, so merely touching does not block).
     */
    internal fun freeRuns(
        bandStart: Float,
        bandEnd: Float,
        obstacles: List<Bounds>,
        xLeft: Float,
        xRight: Float,
    ): List<ClosedFloatingPointRange<Float>> {
        if (bandEnd <= bandStart) return emptyList()
        val blocked =
            obstacles
                .filter { it.left < xRight && xLeft < it.right }
                .map { max(it.top, bandStart) to min(it.bottom, bandEnd) }
                .filter { it.second > it.first }
                .sortedBy { it.first }

        val runs = mutableListOf<ClosedFloatingPointRange<Float>>()
        var cursor = bandStart
        blocked.forEach { (top, bottom) ->
            if (top > cursor) runs += cursor..top
            cursor = max(cursor, bottom)
        }
        if (cursor < bandEnd) runs += cursor..bandEnd
        return runs
    }
}
