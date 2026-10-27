package com.weatherwidget.shared.graph

/**
 * Where hourly-graph labels may be drawn — **one top limit for every placer**, so no label needs an
 * exception of its own (user, 2026-10-27; `plans/261027-shared-graph-label-top-limit.md`).
 *
 * The limit is the canvas edge ([TOP]), not the curve's plot top. The value-label placers
 * (`ValueLabelEngine`, `CurveFitPlacer`, `ActualExtremePlacers`, `ValleyCascade`) always used it; the
 * free-floating ones (`GhostLineLabel`, `GraphEmptySpaceFinder`) stopped at the plot top, so the
 * Fold's ghost-line peak at the top of the plot had no room above it while the HIGH label filled the
 * room below.
 *
 * What protects the strip above the plot is the header, as **obstacles** rather than a ceiling:
 * Android lays its RemoteViews header over the bitmap's top padding, so [headerObstacles] maps the
 * header's drawn items into graph coordinates and every placer avoids exactly those, leaving the
 * gaps between them usable. Desktop's header is a row above the canvas, so it has none.
 */
object GraphLabelArea {
    /** The top limit every label obeys: the canvas edge. */
    const val TOP = 0f

    /** The free-label area: the canvas top down to [bottom] (the plot bottom, above the footer). */
    fun rect(width: Float, bottom: Float): GraphRect = GraphRect(0f, TOP, width, bottom)

    /**
     * [spans] — header items' horizontal extents in header units (`HeaderFit.occupiedSpans`) — as
     * graph rects across [bandTop]..[bandBottom] (graph px). [originX] is the graph's left edge in
     * header units and [scale] converts header units to graph px. Spans entirely off the graph are
     * dropped; the rest are clipped to `0..[graphWidth]`.
     */
    fun headerObstacles(
        spans: List<Pair<Float, Float>>,
        originX: Float,
        scale: Float,
        bandTop: Float,
        bandBottom: Float,
        graphWidth: Float,
    ): List<GraphRect> {
        if (bandBottom <= bandTop || scale <= 0f) return emptyList()
        return spans.mapNotNull { (start, end) ->
            val left = ((start - originX) * scale).coerceAtLeast(0f)
            val right = ((end - originX) * scale).coerceAtMost(graphWidth)
            if (right <= left) null else GraphRect(left, bandTop, right, bandBottom)
        }
    }
}
