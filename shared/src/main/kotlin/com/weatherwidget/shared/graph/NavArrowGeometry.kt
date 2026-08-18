package com.weatherwidget.shared.graph

/**
 * Where the hourly graph's left/right navigation arrows sit **relative to the plot**, so the
 * free-floating label searches stop drawing on top of them.
 *
 * The arrows are the second obstacle discovered to live outside [GraphEmptySpaceFinder]'s view of
 * the world, and they are further out than the first. The NOW line at least shares the canvas — it
 * is merely invisible to `curveYsAt`, which models horizontal curves and cannot express a vertical.
 * The arrows are not on the canvas **at all**: on Android they are `ImageButton`s in the RemoteViews
 * layout that the *launcher* composites over the graph bitmap, and on desktop they are Compose
 * `IconButton`s overlaid on the graph. Nothing the renderer draws can see them, so to the finder
 * that strip of plot reads as wide-open space — and `DominantStationLabel.X_FRACTIONS` leads with a
 * left-edge anchor, so it is the *first* place the search looks. Observed on both the emulator and
 * the Samsung Fold, 2026-08-18: `knuq 64.4° @ 10:10 am` centred on the left chevron with most of the
 * plot empty.
 *
 * Pass [arrowBounds] as `vetoBounds`, never `drawnBounds` — see [GraphEmptySpaceFinder.find]. The
 * arrow band is only ~6% of the plot width; scoring its distance would cost a full line height of
 * clearance and retire the edge anchors that exist precisely so a NOW-split plot still has somewhere
 * to go. Blocking overlap without repelling lets the label keep hugging the edge and simply step up
 * or down out of the arrow's band.
 *
 * Pure geometry, no platform graphics, so Android and desktop cannot drift.
 */
object NavArrowGeometry {

    /**
     * Arrow button width. Mirrors `nav_left`/`nav_right` `android:layout_width` in
     * `widget_weather.xml`; `NavTouchZoneRoboTest` inflates the real layout and asserts they match,
     * because a silent drift here would leave the label dodging a rectangle that is not the chevron.
     */
    const val ARROW_WIDTH_DP = 40f

    /**
     * `graph_view`'s `layout_marginStart`/`layout_marginEnd`. The bitmap is inset from the widget
     * edge by this much, so only `ARROW_WIDTH_DP - GRAPH_INSET_DP` of the arrow actually overlaps
     * the plot. Dropping this term would over-reserve by 4dp on both sides.
     */
    const val GRAPH_INSET_DP = 4f

    /**
     * Arrow button height. Mirrors `android:minHeight` on `nav_left`/`nav_right`, which is what
     * actually decides the button's height — the layout says `wrap_content`, and the chevron
     * drawable is shorter than the minimum. The band is centred vertically, so a label in the top or
     * bottom third clears it at any x; a full-height rect would wrongly evict those.
     */
    const val ARROW_HEIGHT_DP = 80f

    /** Which arrows are actually on screen. Both are hidden together on the daily/error paths. */
    data class Visibility(val left: Boolean, val right: Boolean) {
        companion object {
            val NONE = Visibility(left = false, right = false)
            val BOTH = Visibility(left = true, right = true)
        }
    }

    /**
     * The width of arrow that overlaps the plot, in pixels. Public because desktop's arrow is a
     * different width and supplies its own.
     */
    fun overlapWidthPx(density: Float): Float = (ARROW_WIDTH_DP - GRAPH_INSET_DP) * density

    /**
     * The visible arrows as plot-local veto rectangles, or an empty list when neither is shown.
     *
     * [heightPx] and [widthPx] default to the Android layout's dp constants; desktop passes its own
     * (its arrow is a 28dp column, but only the ~24dp icon is inked — vetoing the full-height button
     * would evict every edge anchor and be worse than the bug).
     */
    fun arrowBounds(
        plot: GraphRect,
        density: Float,
        visibility: Visibility,
        widthPx: Float = overlapWidthPx(density),
        heightPx: Float = ARROW_HEIGHT_DP * density,
    ): List<GraphRect> {
        if (!visibility.left && !visibility.right) return emptyList()
        if (widthPx <= 0f || heightPx <= 0f) return emptyList()

        // Centred vertically, and never taller than the plot itself — on a short widget the arrow's
        // 80dp minimum can exceed the plot, and a rect hanging outside it would veto every candidate.
        val bandHeight = heightPx.coerceAtMost(plot.height)
        val top = plot.top + (plot.height - bandHeight) / 2f
        val bottom = top + bandHeight

        return buildList {
            if (visibility.left) {
                add(GraphRect(plot.left, top, plot.left + widthPx, bottom))
            }
            if (visibility.right) {
                add(GraphRect(plot.right - widthPx, top, plot.right, bottom))
            }
        }
    }
}
