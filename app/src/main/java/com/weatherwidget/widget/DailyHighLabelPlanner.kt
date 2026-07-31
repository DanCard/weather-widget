package com.weatherwidget.widget

import android.util.Log
import com.weatherwidget.shared.graph.DualHighLabel
import kotlin.math.abs
import com.weatherwidget.widget.DailyForecastGraphRenderer.DayData
import com.weatherwidget.widget.DailyGraphLayoutInfo
import com.weatherwidget.widget.DailyGraphPaintSet

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

    private const val TAG = "DailyHighLabel"

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
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
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
        // Label height for the room test, measured at the size each label is actually DRAWN — the
        // two can differ (a wide "75.9°" takes WIDE_LABEL_FONT_SCALE, a plain "77°" does not, and
        // either may be fit-shrunk to the column), and drawScale folds all of that in. The taller
        // of the two boxes governs, matching the desktop renderer's maxOf(aH, fH).
        // See [[label_redundancy_measured_where_drawn]]: measure where the label lands.
        // fontMetrics is null under stubbed-Paint unit tests, so fall back to textSize there.
        val fullFontHeight = basePaint.fontMetrics?.let { it.descent - it.ascent } ?: basePaint.textSize
        fun drawnScale(high: Float) = DailyTemperatureLabelRenderer.drawScale(
            base = basePaint,
            text = DailyTemperatureLabelRenderer.format(high, useCelsius = layout.useCelsius),
            // The FULL (unshrunk) dual size: DUAL_FORECAST_FONT_SCALE is decided below, from this.
            extraScale = DualHighLabel.TWO_LABEL_FONT_SCALE,
            maxWidthPx = layout.tempLabelMaxWidthPx,
        )
        val twoLabelHeight = fullFontHeight *
            maxOf(drawnScale(actualHigh), forecastHigh?.let { drawnScale(it) } ?: 0f)
        // A small miss leaves the pair nearly on top of each other however the fixed nudges are
        // tuned — the gap IS the miss. Spend the empty space directly above the upper label to
        // reach a clean separation, instead of dropping the forecast label or printing the two
        // numbers over each other. Only the upper label moves, so they can never cross.
        val extraUpperPush = if (forecastHigh != null && forecastBaseline != null) {
            val upperBaseline = minOf(nudgedActualBaseline, forecastBaseline)
            DualHighLabel.extraUpperPushPx(
                currentGapPx = abs(nudgedActualBaseline - forecastBaseline),
                labelHeightPx = twoLabelHeight,
                // Never raise the upper label off the top of the bitmap: its top edge is roughly a
                // label height above its baseline, so that much headroom is the real ceiling.
                maxExtraPushPx = minOf(
                    twoLabelHeight * DualHighLabel.DUAL_UPPER_MAX_EXTRA_PUSH_FRACTION,
                    (upperBaseline - twoLabelHeight).coerceAtLeast(0f),
                ),
            )
        } else {
            0f
        }
        // Y grows downward, so raising the upper label means subtracting.
        val actualIsUpper = forecastHigh != null && actualHigh >= forecastHigh
        val pushedActualBaseline = if (actualIsUpper) nudgedActualBaseline - extraUpperPush else nudgedActualBaseline
        val pushedForecastBaseline = forecastBaseline?.let { if (actualIsUpper) it else it - extraUpperPush }
        val showBoth = forecastHigh != null && pushedForecastBaseline != null &&
            DualHighLabel.showBoth(actualHigh, forecastHigh, pushedActualBaseline, pushedForecastBaseline, twoLabelHeight)
        if (forecastHigh != null && pushedForecastBaseline != null) {
            // Permanent VERBOSE breadcrumb — the dual-high decision had no logging at all, so every
            // "why is there no forecast label" report cost a screenshot-measuring session. Log.v is
            // never persisted to app_logs, so this stays off the widget's log budget.
            Log.v(
                TAG,
                "dualHigh date=${day.date} actual=$actualHigh forecast=$forecastHigh " +
                    "gap=${abs(pushedActualBaseline - pushedForecastBaseline)} " +
                    "(prePush=${abs(nudgedActualBaseline - (forecastBaseline ?: 0f))} push=$extraUpperPush) " +
                    "labelH=$twoLabelHeight (full=$fullFontHeight) " +
                    "needGap=${twoLabelHeight * (1f - DualHighLabel.MAX_OVERLAP_FRACTION)} showBoth=$showBoth",
            )
        }
        // Shrink the forecast label only when it sits BELOW the actual and the two boxes would
        // collide before that shrink; a well-separated (or upper) forecast keeps the normal
        // dual-label font. Baselines are the labels' bottom edges (digits have no descenders) and
        // don't move with font size (bottom-pinned), so no re-measure is needed here.
        val forecastFontScale = if (showBoth && pushedForecastBaseline != null)
            DualHighLabel.forecastFontScale(pushedActualBaseline, pushedForecastBaseline, twoLabelHeight)
        else 1f
        // The nudge (and the extra push) only apply when two labels actually render; a lone high
        // label keeps its true above-the-bar position.
        val actualBaseline = if (showBoth) pushedActualBaseline else effectiveBaseline
        val forecastLabelBaseline = if (showBoth) pushedForecastBaseline else forecastBaseline
        val anchorHigh = if (showBoth && forecastHigh != null) maxOf(actualHigh, forecastHigh) else effective
        val anchorBaseline = if (showBoth && forecastLabelBaseline != null) {
            minOf(actualBaseline, forecastLabelBaseline)
        } else {
            actualBaseline
        }
        return HighLabelPlan(
            showBoth = showBoth,
            todayHighSettled = todayHighSettled,
            actualHigh = actualHigh,
            forecastHigh = forecastHigh,
            actualBaseline = actualBaseline,
            forecastBaseline = forecastLabelBaseline,
            forecastFontScale = forecastFontScale,
            anchorHigh = anchorHigh,
            anchorBaseline = anchorBaseline,
        )
    }

    internal fun resolveHighLabelBaseline(
        day: DayData,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
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
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
    ): Float {
        val anchorHigh = resolveHighLabelPlan(day, layout, paints)?.anchorHigh
            ?: day.effectiveHigh() ?: day.solidLineHigh ?: return 1f
        val highText = DailyTemperatureLabelRenderer.format(anchorHigh, useCelsius = layout.useCelsius)
        val tempPaint = when {
            day.isToday -> paints.todayTempTextPaint
            day.isPast -> paints.pastTempTextPaint
            else -> paints.tempTextPaint
        }
        return DailyTemperatureLabelRenderer.drawScale(tempPaint, highText, extraScale = 1f, maxWidthPx = layout.tempLabelMaxWidthPx)
    }
}
