package com.weatherwidget.shared.graph

/**
 * Where hourly-graph labels may be drawn — **one top limit for every placer**, so no label needs an
 * exception of its own (user, 2026-10-27; `plans/261027-shared-graph-label-top-limit.md`).
 *
 * The limit is the canvas edge ([TOP]), not the curve's plot top. The value-label placers
 * (`ValueLabelEngine`, `CurveFitPlacer`, `ActualExtremePlacers`, `ValleyCascade`) always used it; the
 * free-floating ones (`GhostLineLabel`, `GraphEmptySpaceFinder`) previously stopped at the plot top, so the
 * Fold's ghost-line peak at the top of the plot had no room above it while the HIGH label filled the
 * room below.
 */
object GraphLabelArea {
    /** The top limit every label obeys: the canvas edge. */
    const val TOP = 0f

    /** The free-label area: the canvas top down to [bottom] (the plot bottom, above the footer). */
    fun rect(width: Float, bottom: Float): GraphRect = GraphRect(0f, TOP, width, bottom)
}
