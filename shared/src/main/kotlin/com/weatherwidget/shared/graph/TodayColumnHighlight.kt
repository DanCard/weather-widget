package com.weatherwidget.shared.graph

/**
 * Shared geometry + styling for the "today" column emphasis in the daily forecast view. Pure math —
 * no platform graphics — so Android ([DailyForecastGraphRenderer]) and desktop ([DailyForecastGraph])
 * produce the same look and can't drift.
 *
 * The today column is a "triple bar": a centre thermostat bar flanked by a 24h-prior snapshot bar on
 * the left and the live-forecast bar on the right. This object owns two decisions:
 *  1. [tripleBarSpacing] — the centre-to-centre distance from the thermostat to each flanking bar.
 *  2. [panelBounds] — the frosted-glass focal panel drawn *behind* the three bars.
 *
 * Both platforms draw with their own APIs (Android `Canvas.drawRoundRect`, Compose
 * `DrawScope.drawRoundRect`); only the numbers live here.
 */
object TodayColumnHighlight {

    /**
     * Centre-to-centre spacing between the today column's thermostat bar and each flanking bar,
     * expressed via [spacingFactor] as a multiple of the point where the bars *touch*:
     *  - `1.0` → adjacent bars meet edge-to-edge
     *  - `< 1.0` → bars overlap
     *  - `> 1.0` → bars leave a gap
     *
     * Two bars touch when their centre-to-centre distance equals the average of their widths, so this
     * is width-agnostic: pass the real [centerBarWidthPx] (thermostat) and [flankBarWidthPx] (snapshot
     * / forecast). Android draws all three at the same width (both args equal); desktop draws thinner
     * flanking bars (flank < centre) — the same formula handles both.
     *
     * The result is clamped so a flanking bar's outer edge stays inside the half-column with
     * [columnEdgeMarginPx] to spare — prevents the widened today column bleeding into its neighbours
     * on narrow / many-column layouts.
     */
    fun tripleBarSpacing(
        centerBarWidthPx: Float,
        flankBarWidthPx: Float,
        dayWidthPx: Float,
        spacingFactor: Float = DEFAULT_SPACING_FACTOR,
        columnEdgeMarginPx: Float,
    ): Float {
        val touching = (centerBarWidthPx + flankBarWidthPx) / 2f
        val requested = touching * spacingFactor
        val maxOffset = (dayWidthPx / 2f - flankBarWidthPx / 2f - columnEdgeMarginPx).coerceAtLeast(0f)
        return requested.coerceAtMost(maxOffset)
    }

    /**
     * Bounds of the frosted-glass focal panel behind the today column. Spans the three bars
     * horizontally ([centerXPx] ± [tripleBarOffsetPx], plus [horizontalPaddingPx] and half a flanking
     * bar, clamped inside the column) and the bar/icon/low-label area vertically ([graphTopPx], lifted
     * by [topMarginPx], down to just above the day-label band).
     *
     * Purely decorative: it changes no bar/label geometry. Draw it with [PANEL_CORNER_RADIUS_DP]
     * (converted via the platform's dp→px) and a fill of [PANEL_FILL_ARGB]. Borderless by design — a
     * stroke would trace a crisp perimeter that reads stronger than the soft interior.
     */
    fun panelBounds(
        centerXPx: Float,
        tripleBarOffsetPx: Float,
        flankBarWidthPx: Float,
        dayWidthPx: Float,
        graphTopPx: Float,
        canvasHeightPx: Float,
        dayLabelBandPx: Float,
        horizontalPaddingPx: Float,
        topMarginPx: Float,
    ): GraphRect {
        val halfWidth = (tripleBarOffsetPx + flankBarWidthPx / 2f + horizontalPaddingPx)
            .coerceAtMost(dayWidthPx / 2f)
        val top = (graphTopPx - topMarginPx).coerceAtLeast(0f)
        val bottom = canvasHeightPx - dayLabelBandPx
        return GraphRect(
            left = centerXPx - halfWidth,
            top = top,
            right = centerXPx + halfWidth,
            bottom = bottom,
        )
    }

    /** `1.0` = flanking bars touch the thermostat edge-to-edge. */
    const val DEFAULT_SPACING_FACTOR = 1.0f

    /** Corner radius of the frosted panel, in dp. */
    const val PANEL_CORNER_RADIUS_DP = 12f

    /** Horizontal breathing room between the outer bars and the panel edge, in dp. */
    const val PANEL_HORIZONTAL_PADDING_DP = 9f

    /** How far above the bars' top the panel starts, in dp. */
    const val PANEL_TOP_MARGIN_DP = 4f

    /**
     * Panel fill as a 32-bit ARGB int (~12% white). Low alpha over the dark widget/tray background
     * reads as frosted glass. Android: use directly. Compose: `Color(PANEL_FILL_ARGB)`.
     */
    const val PANEL_FILL_ARGB = 0x1FFFFFFF
}
