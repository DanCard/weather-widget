package com.weatherwidget.widget

import android.graphics.*
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.shared.graph.DailyRainLabelPlanner
import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.widget.DailyGraphLayoutInfo
import com.weatherwidget.widget.DailyGraphPaintSet
import com.weatherwidget.widget.DailyForecastGraphRenderer.DayData
import com.weatherwidget.widget.DailyForecastGraphRenderer.DailyRainLabelPlacement
import com.weatherwidget.widget.DailyForecastGraphRenderer.RainLabelKind

internal object DailyForecastRainLabelRenderer {
    private const val TAG = "DailyRainLabelRenderer"
    // Placement + font-scale constants live in :shared so Android and desktop stay identical.
    private const val RAIN_HIGH_TEMP_GAP_DP = DailyRainLabels.RAIN_HIGH_TEMP_GAP_DP
    private const val RAIN_LABEL_EDGE_MARGIN_DP = DailyRainLabels.RAIN_LABEL_EDGE_MARGIN_DP
    private const val NIGHT_SCALE = DailyRainLabels.NIGHT_SCALE
    private const val NIGHT_TUCK_ROOM_MIN_DP = DailyRainLabels.NIGHT_TUCK_ROOM_MIN_DP
    private const val NIGHT_TUCK_ROOM_MAX_DP = DailyRainLabels.NIGHT_TUCK_ROOM_MAX_DP
    private const val NIGHT_TUCK_OVERLAP_BASE_DP = DailyRainLabels.NIGHT_TUCK_OVERLAP_BASE_DP
    private const val NIGHT_TUCK_ROOMY_DOWN_DP = DailyRainLabels.NIGHT_TUCK_ROOMY_DOWN_DP

    internal enum class RainLabelType {
        DAY, NIGHT
    }

    internal data class NightTuckParams(
        val anchorBaseline: Float,
        val leftBaseline: Float,
        val rightNeighborBaseline: Float?,
        val dynamicOverlapDp: Float,
        val tightFraction: Float,
        val roomBelowDp: Float,
        val tempMetrics: Paint.FontMetrics,
    )

    internal data class NightHorizontalFit(
        val centerX: Float,
        val paint: Paint,
        val placementType: String,
    )

    internal data class ResolvedDailyRainLabel(
        val debug: DailyRainLabelPlacement,
        val fitsPreferredTopMargin: Boolean,
    )

    @VisibleForTesting
    internal data class RainAboveHighPlacement(
        val baseline: Float,
        val top: Float,
        val bottom: Float,
        val highLabelTop: Float,
        val fits: Boolean,
    )

    @VisibleForTesting
    internal data class TextMetrics(
        val ascent: Float,
        val descent: Float,
    )

    fun drawDailyRainLabel(
        day: DayData,
        centerX: Float,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
        onRainLabelDrawn: ((DailyRainLabelPlacement) -> Unit)?,
        canvas: Canvas,
    ) {
        val placement = resolveDailyRainLabelPlacement(day, centerX, layout, paints) ?: run {
            if (day.rainData.dailyRainLabelText != null) {
                Log.d(TAG, "rainLabel skipped: no high baseline (null high temp): date=${day.date} solidLineHigh=${day.solidLineHigh}")
            }
            return
        }

        val rainText = placement.debug.text
        val localRainPaint = createScaledRainPaint(day, day.rainData.dailyPrecipProbability, RainLabelType.DAY, layout.density, paints)
        val topMargin = layout.graphTop * 0.2f

        if (!placement.fitsPreferredTopMargin) {
            Log.d(
                TAG,
                "rainLabel forced despite above-high top margin: date=${day.date} label=\"$rainText\"" +
                    " baseline=${placement.debug.baselineY} top=${placement.debug.topY} topMargin=$topMargin" +
                    " highBaseline=${placement.debug.anchorBaselineY} highTop=${placement.debug.anchorTopY}" +
                    " overflow=${topMargin - placement.debug.topY}px",
            )
        }

        // Permanent placement trace (logcat-only; VERBOSE never persists to app_logs). The day rain
        // label is anchored above the high-temp label: rain bottom should sit `gap` dp from the high
        // label's top. `delta` is the actual pixel distance between the high label's top and the rain
        // label's bottom — negative = overlap, large positive = the unexpected gap we are chasing.
        val gapPx = (RAIN_HIGH_TEMP_GAP_DP * layout.bitmapScale.coerceAtMost(1f)).toPx(layout.density)
        val delta = placement.debug.anchorTopY - placement.debug.bottomY
        Log.v(
            TAG,
            "dayRainLabel placement: date=${day.date} isToday=${day.isToday} text=\"$rainText\"" +
                " solidLineHigh=${day.solidLineHigh}" +
                " highBaseline=${placement.debug.anchorBaselineY} highTop=${placement.debug.anchorTopY}" +
                " rainBaseline=${placement.debug.baselineY} rainTop=${placement.debug.topY} rainBottom=${placement.debug.bottomY}" +
                " gapPx=$gapPx delta(highTop-rainBottom)=$delta fits=${placement.fitsPreferredTopMargin}",
        )

        canvas.drawText(rainText, centerX, placement.debug.baselineY, localRainPaint)
        onRainLabelDrawn?.invoke(placement.debug)
    }

    internal fun resolveDailyRainLabelPlacement(
        day: DayData,
        centerX: Float,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
    ): ResolvedDailyRainLabel? {
        val rainText = day.rainData.dailyRainLabelText ?: return null
        val localRainPaint = createScaledRainPaint(day, day.rainData.dailyPrecipProbability, RainLabelType.DAY, layout.density, paints)
        val textWidth = localRainPaint.measureText(rainText)
        val highBaseline = DailyHighLabelPlanner.resolveHighLabelBaseline(day, layout, paints) ?: return null
        val tempPaint = when {
            day.isToday -> paints.todayTempTextPaint
            day.isPast -> paints.pastTempTextPaint
            else -> paints.tempTextPaint
        }
        // The high label is shrink-to-fit when its temp is wide for the column (e.g. dual-label
        // "75.6°"), so its rendered top is lower than the full-size metrics imply. Anchor the rain
        // label to the high label *as drawn* by scaling the high metrics by that same draw scale —
        // otherwise the percentage floats well above the temperature with a large gap.
        val highScale = DailyHighLabelPlanner.resolveHighLabelDrawScale(day, layout, paints)
        val fullHighMetrics = textMetrics(tempPaint)
        val drawnHighMetrics = TextMetrics(fullHighMetrics.ascent * highScale, fullHighMetrics.descent * highScale)
        // The triple bar's left slot (today's, or a past day's thinner one) can rise above the
        // high label; the rain % must clear its cap. Drawn only as a pair.
        val snapshotBarTop = if ((day.isToday || day.isPast) && day.snapshotHigh != null && day.snapshotLow != null) {
            val capRadius =
                (if (day.isToday) paints.todaySnapshotYellowPaint else paints.pastTripleBarPaint).strokeWidth / 2f
            layout.tempToY(day.snapshotHigh) - capRadius
        } else null
        val placement = resolveRainAboveHighPlacement(
            highBaseline = highBaseline,
            highMetrics = drawnHighMetrics,
            rainMetrics = textMetrics(localRainPaint),
            topMargin = layout.graphTop * 0.2f,
            gap = (RAIN_HIGH_TEMP_GAP_DP * layout.bitmapScale.coerceAtMost(1f)).toPx(layout.density),
            snapshotBarTop = snapshotBarTop,
        )

        return ResolvedDailyRainLabel(
            debug = DailyRainLabelPlacement(
                date = day.date,
                text = rainText,
                placement = "ABOVE_HIGH",
                kind = RainLabelKind.DAY,
                centerX = centerX,
                leftX = centerX - textWidth / 2f,
                rightX = centerX + textWidth / 2f,
                baselineY = placement.baseline,
                topY = placement.top,
                bottomY = placement.bottom,
                anchorTopY = placement.highLabelTop,
                anchorBaselineY = highBaseline,
            ),
            fitsPreferredTopMargin = placement.fits,
        )
    }

    /**
     * Second pass of the daily graph: draws every night rain label once all columns are drawn, so
     * both neighbours' low labels and icons are known obstacles.
     */
    fun drawNightRainLabels(
        days: List<Pair<Int, DayData>>,
        daysByColumn: Map<Int, DayData>,
        boundsByColumn: Map<Int, DailyColumnRenderer.DrawnBounds>,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
        onRainLabelDrawn: ((DailyRainLabelPlacement) -> Unit)?,
        canvas: Canvas,
    ) {
        days.forEach { (column, day) ->
            val rightNeighbor = daysByColumn[column + 1]
            val obstacles = listOfNotNull(boundsByColumn[column], boundsByColumn[column + 1])
                .flatMap { it.nightObstacles }
            val centerX = layout.columnCenter(column)
            val gapCenterX =
                if (column + 1 < layout.columnLefts.size) {
                    (centerX + layout.columnCenter(column + 1)) / 2f
                } else {
                    centerX + layout.columnWidth(column) / 2f
                }
            drawNightRainLabel(
                day, rightNeighbor, centerX, gapCenterX, layout.columnWidth(column),
                layout, paints, obstacles, onRainLabelDrawn, canvas,
            )
        }
    }

    fun drawNightRainLabel(
        day: DayData,
        rightNeighbor: DayData?,
        centerX: Float,
        gapCenterX: Float,
        columnWidth: Float,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
        obstacles: List<DailyRainLabelPlanner.Box>,
        onRainLabelDrawn: ((DailyRainLabelPlacement) -> Unit)?,
        canvas: Canvas,
    ) {
        val rainText = day.rainData.nightRainLabelText ?: return

        val tuck = resolveNightAnchorBaseline(day, rightNeighbor, layout, paints) ?: return
        // When roomy, drop the label a couple px below its snug tuck; collapses to 0 when cramped.
        val roomyDownPx = (NIGHT_TUCK_ROOMY_DOWN_DP * (1f - tuck.tightFraction)).toPx(layout.density)
        val fit = resolveNightHorizontalFit(day, rainText, centerX, gapCenterX, layout, paints) ?: return

        val metrics = fit.paint.fontMetrics
        val hardBottomLimit = layout.heightPx - (DailyColumnRenderer.DAY_LABEL_BOTTOM_MARGIN_DP * layout.density)

        val finalTopOverlap = (tuck.dynamicOverlapDp).toPx(layout.density)
        val homeTop = tuck.anchorBaseline + tuck.tempMetrics.descent - finalTopOverlap + roomyDownPx

        // Start centred in the gap under the low temps; nudge left, right or down only when a
        // neighbour's low label or icon is in the way (slight overlap is fine).
        val nightTextWidth = fit.paint.measureText(rainText)
        val nightHalfWidth = nightTextWidth / 2f
        val textHeight = metrics.descent - metrics.ascent
        val edgeMargin = RAIN_LABEL_EDGE_MARGIN_DP.toPx(layout.density)
        val placed = DailyRainLabelPlanner.placeNightLabel(
            homeCenterX = fit.centerX,
            homeTop = homeTop,
            width = nightTextWidth,
            height = textHeight,
            obstacles = obstacles,
            maxShiftPx = columnWidth * DailyRainLabels.NIGHT_NUDGE_MAX_SHIFT_FRACTION,
            maxBottom = hardBottomLimit,
            minLeft = edgeMargin,
            maxRight = layout.widthPx - edgeMargin,
            density = layout.density,
        )
        val resolvedCenterX = placed.centerX
        val resolvedBaseline = placed.top - metrics.ascent
        val resolvedBottom = resolvedBaseline + metrics.descent

        Log.v(TAG, "nightRainLabel position: date=${day.date} text=\"$rainText\"" +
            " anchorBaseline=${tuck.anchorBaseline} tightFraction=${tuck.tightFraction}" +
            " dynamicOverlapDp=${tuck.dynamicOverlapDp} gapCenterX=$gapCenterX homeTop=$homeTop" +
            " resolvedCenterX=$resolvedCenterX resolvedBaseline=$resolvedBaseline" +
            " placement=${placed.placement}" +
            " offsetDp=(${placed.dxPx / layout.density},${placed.dyPx / layout.density})" +
            " overlapDp=${placed.overlapPx / layout.density} obstacles=${obstacles.size}" +
            " finalBottom=$resolvedBottom hardBottomLimit=$hardBottomLimit roomBelowDp=${tuck.roomBelowDp}")

        if (resolvedBottom <= hardBottomLimit) {
            canvas.drawText(rainText, resolvedCenterX, resolvedBaseline, fit.paint)
            onRainLabelDrawn?.invoke(
                DailyRainLabelPlacement(
                    date = day.date,
                    text = rainText,
                    placement = fit.placementType,
                    kind = RainLabelKind.NIGHT,
                    centerX = resolvedCenterX,
                    leftX = resolvedCenterX - nightHalfWidth,
                    rightX = resolvedCenterX + nightHalfWidth,
                    baselineY = resolvedBaseline,
                    topY = resolvedBaseline + metrics.ascent,
                    bottomY = resolvedBottom,
                    anchorBaselineY = tuck.anchorBaseline,
                )
            )
            return
        }

        Log.d(TAG, "nightRainLabel skipped: bottom overflow: date=${day.date} baseline=$resolvedBaseline")
    }

    internal fun resolveRainAboveHighPlacement(
        highBaseline: Float,
        highMetrics: TextMetrics,
        rainMetrics: TextMetrics,
        topMargin: Float,
        gap: Float,
        snapshotBarTop: Float? = null,
    ): RainAboveHighPlacement {
        val result = DailyRainLabelPlanner.resolveRainAboveHighPlacement(
            highBaseline = highBaseline,
            highMetrics = DailyRainLabelPlanner.TextMetrics(highMetrics.ascent, highMetrics.descent),
            rainMetrics = DailyRainLabelPlanner.TextMetrics(rainMetrics.ascent, rainMetrics.descent),
            topMargin = topMargin,
            gap = gap,
            snapshotBarTop = snapshotBarTop,
        )
        return RainAboveHighPlacement(
            baseline = result.baseline,
            top = result.top,
            bottom = result.bottom,
            highLabelTop = result.anchorTop,
            fits = result.fits,
        )
    }

    internal fun textMetrics(paint: Paint): TextMetrics {
        val metrics = paint.fontMetrics
        if (metrics.ascent != 0f || metrics.descent != 0f) {
            return TextMetrics(metrics.ascent, metrics.descent)
        }
        if (paint.textSize <= 0f) {
            return TextMetrics(0f, 0f)
        }
        return TextMetrics(
            ascent = -paint.textSize * 0.8f,
            descent = paint.textSize * 0.2f,
        )
    }

    internal fun resolveNightAnchorBaseline(
        day: DayData,
        rightNeighbor: DayData?,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
    ): NightTuckParams? {
        val leftBaseline = DailyColumnRenderer.resolveLowLabelBaseline(day, layout) ?: return null
        val rightNeighborBaseline = rightNeighbor?.let { DailyColumnRenderer.resolveLowLabelBaseline(it, layout) }
        val anchorBaseline = if (rightNeighborBaseline != null) {
            minOf(leftBaseline, rightNeighborBaseline)
        } else {
            leftBaseline
        }

        val tempPaint = if (day.isToday) paints.todayTempTextPaint else paints.tempTextPaint
        val tempMetrics = tempPaint.fontMetrics

        val hardBottomLimit = layout.heightPx - (DailyColumnRenderer.DAY_LABEL_BOTTOM_MARGIN_DP * layout.density)
        val roomBelowPx = (hardBottomLimit - anchorBaseline).coerceAtLeast(0f)
        val roomBelowDp = roomBelowPx / layout.density

        val tuck = DailyRainLabelPlanner.calculateNightTuck(roomBelowDp = roomBelowDp)

        return NightTuckParams(
            anchorBaseline = anchorBaseline,
            leftBaseline = leftBaseline,
            rightNeighborBaseline = rightNeighborBaseline,
            dynamicOverlapDp = tuck.dynamicOverlapDp,
            tightFraction = tuck.tightFraction,
            roomBelowDp = roomBelowDp,
            tempMetrics = tempMetrics,
        )
    }

    internal fun resolveNightHorizontalFit(
        day: DayData,
        rainText: String,
        centerX: Float,
        gapCenterX: Float,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
    ): NightHorizontalFit? {
        val currentPaint = createScaledRainPaint(day, day.rainData.nighttimePrecipProbability, RainLabelType.NIGHT, layout.density, paints)
        val textWidth = currentPaint.measureText(rainText)
        val shiftedCenterX = gapCenterX
        val halfWidth = textWidth / 2f
        val edgeMargin = (RAIN_LABEL_EDGE_MARGIN_DP).toPx(layout.density)
        val canShiftStandard = (shiftedCenterX + halfWidth <= layout.widthPx - edgeMargin) && (shiftedCenterX - halfWidth >= edgeMargin)

        if (canShiftStandard) {
            return NightHorizontalFit(shiftedCenterX, currentPaint, "NIGHT_SHIFTED_LEFT")
        }

        val reducedPaint = createScaledRainPaint(day, day.rainData.nighttimePrecipProbability, RainLabelType.NIGHT, layout.density, paints, extraScale = 0.85f)
        val reducedWidth = reducedPaint.measureText(rainText)
        val reducedHalfWidth = reducedWidth / 2f

        if (shiftedCenterX + reducedHalfWidth <= layout.widthPx - edgeMargin && shiftedCenterX - reducedHalfWidth >= edgeMargin) {
            return NightHorizontalFit(shiftedCenterX, reducedPaint, "NIGHT_SHIFTED_SCALED")
        }

        return NightHorizontalFit(centerX, currentPaint, "NIGHT_CENTERED")
    }

    internal fun createScaledRainPaint(
        day: DayData,
        probability: Int?,
        labelType: RainLabelType,
        density: Float,
        paints: DailyGraphPaintSet,
        extraScale: Float = 1.0f,
    ): Paint {
        val nightScale = if (labelType == RainLabelType.NIGHT) NIGHT_SCALE else 1.0f
        // Probability-weighted for every day; future/today additionally distance-weighted. History
        // uses the same probability scaling as future minus the distance term (shared rule) — so a
        // history chance% sizes like the equivalent near-term forecast chance%, not a flat size.
        val combinedScale = DailyRainLabels.rainLabelFontScale(day.isPast, probability, day.daysFromToday)
        val finalTextSize = paints.rainTextPaint.textSize * combinedScale * extraScale * nightScale

        return Paint(paints.rainTextPaint).apply {
            textSize = finalTextSize
        }
    }

    private fun Float.toPx(density: Float): Float = this * density
}
