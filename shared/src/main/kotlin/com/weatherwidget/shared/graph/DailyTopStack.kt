package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.Log

/**
 * Room above the daily bars, shared by the Android widget and desktop: no top label (high, dual
 * high, day rain %, peak hour) may rise more than [HEADER_OVERLAP_FRACTION] of the header's height
 * into the header. The graph top is solved from what each column actually stacks above its bars, so
 * the column that needs the most room sets the bar height and nothing is reserved for labels that
 * are not drawn (plans/261029-shared-daily-top-stack.md).
 *
 * Each platform measures its columns' needs by laying the labels out once and reporting how far
 * each column's topmost label reaches above its tallest bar ([headroom]); this type owns the ceiling
 * and the solve.
 */
data class DailyTopStack(
    /** Y of the header's bottom edge in graph-canvas coordinates (negative when it sits above the canvas). */
    val headerBottomPx: Float,
    val headerHeightPx: Float,
) {
    /** No top label goes above this. */
    val labelCeiling: Float get() = headerBottomPx - HEADER_OVERLAP_FRACTION * headerHeightPx

    /**
     * One need: a bar top at [barTopFraction] of the axis range (0 = axis minimum, 1 = maximum) with
     * labels reaching [aboveBarPx] above it.
     */
    data class ColumnHeadroom(val label: String, val barTopFraction: Float, val aboveBarPx: Float)

    data class Solution(val graphTop: Float, val bindingColumn: String?)

    /**
     * The highest graph top that keeps every column's labels below [labelCeiling]. A bar top sits at
     * `graphBottom − H·f`, so each column allows `H ≤ (graphBottom − labelCeiling − above) / f`; the
     * smallest bound wins. The graph never starts above the ceiling itself and keeps at least 1 px of
     * height.
     */
    fun solve(graphBottom: Float, columns: List<ColumnHeadroom>): Solution {
        var height = graphBottom - labelCeiling
        var binding: String? = null
        columns.forEach { c ->
            if (c.barTopFraction <= 0f) return@forEach
            val bound = (graphBottom - labelCeiling - c.aboveBarPx) / c.barTopFraction
            if (bound < height) {
                height = bound
                binding = c.label
            }
        }
        return Solution(graphBottom - height.coerceAtLeast(1f), binding)
    }

    companion object {
        private const val TAG = "DailyTopStack"

        /** How far (fraction of the header's height) top labels may rise into the header. */
        const val HEADER_OVERLAP_FRACTION = 0.20f

        /** A column's need from its laid-out geometry: tallest bar top and topmost label top (px). */
        fun headroom(
            label: String,
            barTopTemp: Float,
            minTemp: Float,
            tempRange: Float,
            barTopY: Float,
            topmostLabelTop: Float,
        ): ColumnHeadroom =
            ColumnHeadroom(
                label = label,
                barTopFraction = ((barTopTemp - minTemp) / tempRange.coerceAtLeast(1e-3f)).coerceIn(0f, 1f),
                aboveBarPx = (barTopY - topmostLabelTop).coerceAtLeast(0f),
            )

        /** Builds the stack, solves the graph top, and traces it (VERBOSE: once per daily render). */
        fun resolve(
            platform: String,
            headerBottomPx: Float,
            headerHeightPx: Float,
            graphBottom: Float,
            columns: List<ColumnHeadroom>,
        ): Pair<DailyTopStack, Solution> {
            val stack = DailyTopStack(headerBottomPx, headerHeightPx)
            val solution = stack.solve(graphBottom, columns)
            Log.v(TAG) {
                "$platform headerBottom=${headerBottomPx.r()} headerH=${headerHeightPx.r()} " +
                    "ceiling=${stack.labelCeiling.r()} graphTop=${solution.graphTop.r()} " +
                    "barH=${(graphBottom - solution.graphTop).r()} binding=${solution.bindingColumn} " +
                    "needs=" + columns.joinToString(",") { "${it.label}:${it.aboveBarPx.r()}@${"%.2f".format(it.barTopFraction)}" }
            }
            return stack to solution
        }

        private fun Float.r(): String = "%.1f".format(this)
    }
}
