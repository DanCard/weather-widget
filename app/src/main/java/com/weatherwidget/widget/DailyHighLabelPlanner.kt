package com.weatherwidget.widget

import com.weatherwidget.shared.graph.DualHighLabel
import com.weatherwidget.widget.DailyForecastGraphRenderer.DayData
import com.weatherwidget.widget.DailyForecastGraphRenderer.LayoutInfo
import com.weatherwidget.widget.DailyForecastGraphRenderer.PaintSet

/**
 * Resolves the day's high-label(s) for the daily forecast graph: the [HighLabelPlan] (shared with
 * the rain-label resolvers so the dual-high decision is made in exactly one place) and the drawn
 * high-baseline / fit-scale that the rain % anchors against. Extracted from
 * [DailyForecastGraphRenderer] as the high-label math is the densest piece of the file and benefits
 * from being isolatable — see `plans/260728b-dailyforecastgraphrenderer-code-review.md` split A.
 */
/** Today's effective (headline) high — observed / live-forecast / ghost blend by cutoff hour. */
internal fun DailyForecastGraphRenderer.DayData.effectiveHigh(): Float? =
    com.weatherwidget.shared.util.DailyDayValueResolver.effectiveHighForLabel(
        isToday = isToday,
        solidHigh = solidLineHigh,
        forecastHigh = dashedLineHigh,
        ghostHigh = ghostLineHigh,
        nowHour = nowHour,
    )

internal object DailyHighLabelPlanner {

    /** Gap in dp between a high-temp label and the top of its bar / the upper sibling in a dual. */
    internal const val HIGH_LABEL_OFFSET_DP = 8f

    /**
     * The high label(s) a column draws, and the anchor the daily rain % rides above. A past day (or
     * settled-today) can print BOTH the observed actual high and the forecast high (DualHighLabel);
     * when it does, the two labels sit at different heights and the rain % must clear the TOPMOST
     * (warmer) of the two — anchoring only to the actual wedges the % between them (see the "yesterday
     * 15%" report). Single-label days anchor to the headline effective high. Shared by
     * [DailyForecastGraphRenderer]'s bar drawer and the rain-label resolvers so the dual-high
     * decision is made in exactly one place.
     */
    internal data class HighLabelPlan(
        val showBoth: Boolean,
        val todayHighSettled: Boolean,
        /** Observed-actual (or headline) high, drawn at column center. */
        val actualHigh: Float,
        /** Forecast high, drawn offset to the side — only when [showBoth]. */
        val forecastHigh: Float?,
        val actualBaseline: Float,
        val forecastBaseline: Float?,
        /**
         * Extra scale for the forecast label: [DualHighLabel.DUAL_FORECAST_FONT_SCALE] only when the
         * two labels would collide at full size, else 1f (the shrink is collision-only by request).
         */
        val forecastFontScale: Float,
        /** Warmer of the drawn highs (== [actualHigh] when single) — the value the rain % sits above. */
        val anchorHigh: Float,
        /** Topmost drawn high-label baseline — the rain %'s vertical anchor. */
        val anchorBaseline: Float,
    )

    private fun Float.dp(density: Float): Float = this * density

    internal fun resolveHighLabelPlan(
        day: DayData,
        layout: LayoutInfo,
        paints: PaintSet,
    ): HighLabelPlan? {
        day.solidLineHigh ?: return null
        val effective = day.effectiveHigh() ?: return null
        val labelOffset = (HIGH_LABEL_OFFSET_DP * layout.bitmapScale.coerceAtMost(1f)).dp(layout.density)
        @Suppress("UNUSED_VARIABLE") // basePaint is read in resolveHighLabelDrawScale; kept here so the
        // paint-by-day-type rule is documented at the plan source.
        val basePaint = when {
            day.isToday -> paints.todayTempTextPaint
            day.isPast -> paints.pastTempTextPaint
            else -> paints.tempTextPaint
        }
        val todayHighSettled = com.weatherwidget.shared.util.DailyDayValueResolver.isHighTrackingActual(
            isToday = day.isToday,
            solidHigh = day.solidLineHigh,
            ghostHigh = day.ghostLineHigh,
            nowHour = day.nowHour,
        )
        // actualHigh == effective in every case (past: solid == effective; today: actual IS effective),
        // so the actual label baseline is always the headline (effective) baseline.
        val actualHigh = effective
        val effectiveBaseline = layout.tempToY(effective) - labelOffset
        val forecastHigh = if (day.isPast || todayHighSettled) day.dashedLineHigh else null
        // Two labels this close together (the gap IS the forecast miss, so a 2° miss is only a couple
        // of label-heights on a compressed graph) collide at their natural above-the-bar positions.
        // Move each away from the other before the room test, so the test measures what is drawn.
        // Digits have no descender, so a label's bottom edge is effectively its baseline.
        val offsets = forecastHigh?.let {
            DualHighLabel.bottomOffsetsDp(actualHigh, it, normalGapDp = HIGH_LABEL_OFFSET_DP)
        }
        val offsetScale = layout.bitmapScale.coerceAtMost(1f)
        fun offsetPx(dp: Float) = (dp * offsetScale).dp(layout.density)
        val nudgedActualBaseline = offsets?.let { layout.tempToY(effective) + offsetPx(it.actualDp) }
            ?: effectiveBaseline
        // offsets is non-null iff forecastHigh is non-null (both derive from the same source above),
        // so the chained ?.let is total. Avoid `offsets!!` so the invariant stays compiler-checkable.
        val forecastBaseline = forecastHigh?.let { high ->
            offsets?.let { layout.tempToY(high) + offsetPx(it.forecastDp) }
        }
        // Label height for the room test; fontMetrics is null under stubbed-Paint unit tests, so fall
        // back to textSize there. Only needed when a forecast label is in play. This is the FULL
        // (unshrunk) dual-label size — both the room test and the collision test below measure the
        // labels at the size they'd draw before any collision shrink.
        val twoLabelHeight = (basePaint.fontMetrics?.let { it.descent - it.ascent } ?: basePaint.textSize) *
            DualHighLabel.TWO_LABEL_FONT_SCALE
        val showBoth = forecastHigh != null && forecastBaseline != null &&
            DualHighLabel.showBoth(actualHigh, forecastHigh, nudgedActualBaseline, forecastBaseline, twoLabelHeight)
        // Shrink the forecast label only when it sits BELOW the actual and the two boxes would
        // collide at full size; a well-separated (or upper) forecast keeps the normal dual-label
        // font. Baselines are the labels' bottom edges (digits have no descenders) and don't move
        // with font size (bottom-pinned), so the full-size test needs no re-measure.
        val forecastFontScale = if (showBoth && forecastBaseline != null)
            DualHighLabel.forecastFontScale(nudgedActualBaseline, forecastBaseline, twoLabelHeight)
        else 1f
        // The nudge only applies when two labels actually render; a lone high label keeps its true
        // above-the-bar position.
        val actualBaseline = if (showBoth) nudgedActualBaseline else effectiveBaseline
        val anchorHigh = if (showBoth && forecastHigh != null) maxOf(actualHigh, forecastHigh) else effective
        val anchorBaseline = if (showBoth && forecastBaseline != null) minOf(actualBaseline, forecastBaseline) else actualBaseline
        return HighLabelPlan(
            showBoth = showBoth,
            todayHighSettled = todayHighSettled,
            actualHigh = actualHigh,
            forecastHigh = forecastHigh,
            actualBaseline = actualBaseline,
            forecastBaseline = forecastBaseline,
            forecastFontScale = forecastFontScale,
            anchorHigh = anchorHigh,
            anchorBaseline = anchorBaseline,
        )
    }

    internal fun resolveHighLabelBaseline(
        day: DayData,
        layout: LayoutInfo,
        paints: PaintSet,
    ): Float? = resolveHighLabelPlan(day, layout, paints)?.anchorBaseline

    /**
     * The fit-to-width font scale the anchored high-temp label is actually drawn at. The rain label
     * multiplies the full-size temp metrics by this so it anchors to that label's *rendered* top, not
     * its full-size top — otherwise a shrunk wide temp (e.g. dual-label "75.6°" in a narrow column)
     * leaves a large gap above the high. Uses the topmost (anchor) high's text so a dual-label day
     * measures the label the rain actually sits above. The 2% dual-label shrink is immaterial next to
     * the fit factor, so we pass extraScale = 1.
     */
    internal fun resolveHighLabelDrawScale(
        day: DayData,
        layout: LayoutInfo,
        paints: PaintSet,
    ): Float {
        val anchorHigh = resolveHighLabelPlan(day, layout, paints)?.anchorHigh
            ?: day.effectiveHigh() ?: day.solidLineHigh ?: return 1f
        val highText = DailyForecastGraphRenderer.formatTempLabel(anchorHigh, useCelsius = layout.useCelsius)
        val tempPaint = when {
            day.isToday -> paints.todayTempTextPaint
            day.isPast -> paints.pastTempTextPaint
            else -> paints.tempTextPaint
        }
        return DailyForecastGraphRenderer.tempLabelDrawScale(tempPaint, highText, extraScale = 1f, maxWidthPx = layout.tempLabelMaxWidthPx)
    }
}
