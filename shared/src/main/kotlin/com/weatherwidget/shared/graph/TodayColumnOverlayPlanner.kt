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

    data class Line(
        val key: String,
        val text: String,
        val width: Float,
        val height: Float,
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

        val height = stackHeight(lines, input.rowSpacing)
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
            val headHeight = stackHeight(head, input.rowSpacing)
            val tailHeight = stackHeight(tail, input.rowSpacing)
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

    /** Centres the stack in [run] and lays the blocks out top-to-bottom in input order. */
    private fun layOut(
        lines: List<Line>,
        run: ClosedFloatingPointRange<Float>,
        height: Float,
        zone: Zone,
        input: Input,
    ): List<Placement> {
        val slack = ((run.endInclusive - run.start - height) / 2f).coerceAtLeast(0f)
        val top = run.start + slack
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

    private fun bandFor(zone: Zone, input: Input): Pair<Float, Float>? {
        val band =
            when (zone) {
                Zone.ABOVE -> (input.graphTop + input.padding) to (input.barTop - input.padding)
                Zone.BELOW -> (input.barBottom + input.padding) to (input.graphBottom - input.padding)
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
