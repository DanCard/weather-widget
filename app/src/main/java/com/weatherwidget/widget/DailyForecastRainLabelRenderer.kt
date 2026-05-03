package com.weatherwidget.widget

import android.graphics.*
import android.util.Log
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.widget.DailyForecastGraphRenderer.LayoutInfo
import com.weatherwidget.widget.DailyForecastGraphRenderer.PaintSet
import com.weatherwidget.widget.DailyForecastGraphRenderer.DayData
import com.weatherwidget.widget.DailyForecastGraphRenderer.RainLabelDrawnDebug
import com.weatherwidget.widget.DailyForecastGraphRenderer.TextMetrics
import com.weatherwidget.widget.DailyForecastGraphRenderer.RainAboveHighPlacement

internal object DailyForecastRainLabelRenderer {
    private const val TAG = "DailyRainLabelRenderer"
    private const val RAIN_FONT_SCALE_K = 0.6f
    private const val RAIN_FONT_SCALE_MAX_DAYS = 7f
    private const val RAIN_TEXT_MARGIN_DP = 4f
    private const val RAIN_HIGH_TEMP_GAP_DP = -2f
    private const val RAIN_LABEL_EDGE_MARGIN_DP = 4f
    private const val NIGHT_SCALE = 0.72f
    private const val NIGHT_TUCK_ROOM_MIN_DP = 10f
    private const val NIGHT_TUCK_ROOM_MAX_DP = 22f
    private const val NIGHT_TUCK_OVERLAP_BASE_DP = 5.0f
    private const val NIGHT_TUCK_NUDGE_BASE_DP = 1.5f
    private const val NIGHT_TUCK_NUDGE_RANGE_DP = 1.5f

    internal enum class RainLabelType {
        DAY, NIGHT
    }

    internal data class NightTuckParams(
        val anchorBaseline: Float,
        val leftBaseline: Float,
        val rightNeighborBaseline: Float?,
        val dynamicOverlapDp: Float,
        val effectiveNudgeDp: Float,
        val tightFraction: Float,
        val roomBelowDp: Float,
        val isLeftTempLower: Boolean,
        val tempMetrics: Paint.FontMetrics,
    )

    internal data class NightHorizontalFit(
        val centerX: Float,
        val paint: Paint,
        val placementType: String,
    )

    fun drawDailyRainLabel(
        day: DayData,
        centerX: Float,
        layout: LayoutInfo,
        paints: PaintSet,
        onRainLabelDrawn: ((RainLabelDrawnDebug) -> Unit)?,
        canvas: Canvas,
    ) {
        val rainText = day.rainData.dailyRainLabelText ?: return
        val localRainPaint = createScaledRainPaint(day, day.rainData.dailyPrecipProbability, RainLabelType.DAY, layout.density, paints)

        val textWidth = localRainPaint.measureText(rainText)
        val maxTextWidth = layout.dayWidth - (RAIN_TEXT_MARGIN_DP * layout.scaleFactor).toPx(layout.density)
        if (textWidth > maxTextWidth) {
            Log.d(TAG, "rainLabel skipped: text too wide: date=${day.date} textWidth=${textWidth}px maxWidth=${maxTextWidth}px dayWidth=${layout.dayWidth}px label=\"$rainText\"")
            return
        }

        val highBaseline = DailyForecastGraphRenderer.resolveHighLabelBaseline(day, layout)
        if (highBaseline == null) {
            Log.d(TAG, "rainLabel skipped: no high baseline (null high temp): date=${day.date} high=${day.high}")
            return
        }

        val metrics = textMetrics(localRainPaint)
        val tempPaint = when {
            day.isToday -> paints.todayTempTextPaint
            day.isPast -> paints.pastTempTextPaint
            else -> paints.tempTextPaint
        }
        val tempMetrics = textMetrics(tempPaint)
        val topMargin = layout.graphTop * 0.2f
        val gap = (RAIN_HIGH_TEMP_GAP_DP * layout.bitmapScale.coerceAtMost(1f)).toPx(layout.density)
        val placement = resolveRainAboveHighPlacement(
            highBaseline = highBaseline,
            highMetrics = tempMetrics,
            rainMetrics = metrics,
            topMargin = topMargin,
            gap = gap,
        )

        if (placement.fits) {
            canvas.drawText(rainText, centerX, placement.baseline, localRainPaint)
            onRainLabelDrawn?.invoke(
                RainLabelDrawnDebug(
                    date = day.date,
                    text = rainText,
                    placement = "ABOVE_HIGH",
                    centerX = centerX,
                    leftX = centerX - textWidth / 2f,
                    rightX = centerX + textWidth / 2f,
                    baselineY = placement.baseline,
                    topY = placement.top,
                    bottomY = placement.bottom,
                    anchorTopY = placement.highLabelTop,
                    anchorBaselineY = highBaseline,
                    isNightLabel = false,
                ),
            )
            return
        }

        Log.d(
            TAG,
            "rainLabel skipped: above-high insufficient space: date=${day.date} label=\"$rainText\"" +
                " baseline=${placement.baseline} top=${placement.top} topMargin=$topMargin ascent=${metrics.ascent}" +
                " descent=${metrics.descent} highBaseline=$highBaseline highTop=${placement.highLabelTop}" +
                " gap=$gap overflow=${topMargin - placement.top}px",
        )
    }

    fun drawNightRainLabel(
        day: DayData,
        rightNeighbor: DayData?,
        centerX: Float,
        layout: LayoutInfo,
        paints: PaintSet,
        onRainLabelDrawn: ((RainLabelDrawnDebug) -> Unit)?,
        canvas: Canvas,
    ) {
        val rainText = day.rainData.nightRainLabelText ?: return

        val tuck = resolveNightAnchorBaseline(day, rightNeighbor, layout, paints) ?: return
        val hNudgePx = (tuck.effectiveNudgeDp).toPx(layout.density)
        val fit = resolveNightHorizontalFit(day, rainText, centerX, layout, hNudgePx, paints) ?: return

        val metrics = fit.paint.fontMetrics
        val hardBottomLimit = layout.heightPx - (DailyForecastGraphRenderer.DAY_LABEL_BOTTOM_MARGIN_DP * layout.density)

        val finalTopOverlap = (tuck.dynamicOverlapDp).toPx(layout.density)
        val finalTopY = tuck.anchorBaseline + tuck.tempMetrics.descent - finalTopOverlap
        val finalBaseline = finalTopY - metrics.ascent
        val finalBottomWithMargin = finalBaseline + metrics.descent

        Log.d(TAG, "nightRainLabel position: date=${day.date} text=\"$rainText\"" +
            " anchorBaseline=${tuck.anchorBaseline} tightFraction=${tuck.tightFraction}" +
            " dynamicOverlapDp=${tuck.dynamicOverlapDp} finalTopOverlap=${finalTopOverlap}px" +
            " finalTopY=$finalTopY finalBaseline=$finalBaseline finalBottom=$finalBottomWithMargin" +
            " hardBottomLimit=$hardBottomLimit roomBelowDp=${tuck.roomBelowDp}" +
            " tempDescent=${tuck.tempMetrics.descent}")

        if (finalBottomWithMargin <= hardBottomLimit) {
            canvas.drawText(rainText, fit.centerX, finalBaseline, fit.paint)
            val nightTextWidth = fit.paint.measureText(rainText)
            onRainLabelDrawn?.invoke(
                RainLabelDrawnDebug(
                    date = day.date,
                    text = rainText,
                    placement = fit.placementType,
                    centerX = fit.centerX,
                    leftX = fit.centerX - nightTextWidth / 2f,
                    rightX = fit.centerX + nightTextWidth / 2f,
                    baselineY = finalBaseline,
                    topY = finalBaseline + metrics.ascent,
                    bottomY = finalBaseline + metrics.descent,
                    anchorBaselineY = tuck.anchorBaseline,
                    isNightLabel = true,
                )
            )
            return
        }

        Log.d(TAG, "nightRainLabel skipped: bottom overflow: date=${day.date} baseline=$finalBaseline")
    }

    internal fun resolveRainAboveHighPlacement(
        highBaseline: Float,
        highMetrics: TextMetrics,
        rainMetrics: TextMetrics,
        topMargin: Float,
        gap: Float,
    ): RainAboveHighPlacement {
        val highLabelTop = highBaseline + highMetrics.ascent
        val baseline = highLabelTop - gap - rainMetrics.descent
        val top = baseline + rainMetrics.ascent
        val bottom = baseline + rainMetrics.descent
        return RainAboveHighPlacement(
            baseline = baseline,
            top = top,
            bottom = bottom,
            highLabelTop = highLabelTop,
            fits = top >= topMargin,
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
        layout: LayoutInfo,
        paints: PaintSet,
    ): NightTuckParams? {
        val leftBaseline = DailyForecastGraphRenderer.resolveLowLabelBaseline(day, layout) ?: return null
        val rightNeighborBaseline = rightNeighbor?.let { DailyForecastGraphRenderer.resolveLowLabelBaseline(it, layout) }
        val anchorBaseline = if (rightNeighborBaseline != null) {
            minOf(leftBaseline, rightNeighborBaseline)
        } else {
            leftBaseline
        }

        val tempPaint = if (day.isToday) paints.todayTempTextPaint else paints.tempTextPaint
        val tempMetrics = tempPaint.fontMetrics

        val hardBottomLimit = layout.heightPx - (DailyForecastGraphRenderer.DAY_LABEL_BOTTOM_MARGIN_DP * layout.density)
        val roomBelowPx = (hardBottomLimit - anchorBaseline).coerceAtLeast(0f)
        val roomBelowDp = roomBelowPx / layout.density

        val tightFraction = (1f - (roomBelowDp - NIGHT_TUCK_ROOM_MIN_DP) / (NIGHT_TUCK_ROOM_MAX_DP - NIGHT_TUCK_ROOM_MIN_DP)).coerceIn(0f, 1f)
        val dynamicOverlapDp = NIGHT_TUCK_OVERLAP_BASE_DP * tightFraction
        val dynamicNudgeDp = NIGHT_TUCK_NUDGE_BASE_DP + (NIGHT_TUCK_NUDGE_RANGE_DP * tightFraction)

        val isLeftTempLower = rightNeighborBaseline != null && leftBaseline > rightNeighborBaseline
        val effectiveNudgeDp = if (isLeftTempLower) dynamicNudgeDp * 0.0f else dynamicNudgeDp

        return NightTuckParams(
            anchorBaseline = anchorBaseline,
            leftBaseline = leftBaseline,
            rightNeighborBaseline = rightNeighborBaseline,
            dynamicOverlapDp = dynamicOverlapDp,
            effectiveNudgeDp = effectiveNudgeDp,
            tightFraction = tightFraction,
            roomBelowDp = roomBelowDp,
            isLeftTempLower = isLeftTempLower,
            tempMetrics = tempMetrics,
        )
    }

    internal fun resolveNightHorizontalFit(
        day: DayData,
        rainText: String,
        centerX: Float,
        layout: LayoutInfo,
        hNudgePx: Float,
        paints: PaintSet,
    ): NightHorizontalFit? {
        val currentPaint = createScaledRainPaint(day, day.rainData.nighttimePrecipProbability, RainLabelType.NIGHT, layout.density, paints)
        val textWidth = currentPaint.measureText(rainText)
        val shiftedCenterX = centerX + layout.dayWidth / 2f - hNudgePx
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

        val maxTextWidth = layout.dayWidth - (RAIN_TEXT_MARGIN_DP * layout.scaleFactor).toPx(layout.density)
        if (textWidth > maxTextWidth) {
            if (reducedWidth <= maxTextWidth) {
                return NightHorizontalFit(centerX, reducedPaint, "NIGHT_CENTERED_SCALED")
            }
            return null
        }

        return NightHorizontalFit(centerX, currentPaint, "NIGHT_CENTERED")
    }

    internal fun createScaledRainPaint(
        day: DayData,
        probability: Int?,
        labelType: RainLabelType,
        density: Float,
        paints: PaintSet,
        extraScale: Float = 1.0f,
    ): Paint {
        val probFraction = (probability ?: 0).toFloat() / 100f
        val probScale = HeaderPrecipCalculator.getPrecipScaleFactor(probability ?: 0)
        val distanceScale = 1.0f - RAIN_FONT_SCALE_K * (1.0f - probFraction) * (day.daysFromToday / RAIN_FONT_SCALE_MAX_DAYS)
        val nightScale = if (labelType == RainLabelType.NIGHT) NIGHT_SCALE else 1.0f
        val combinedScale = probScale * distanceScale
        val finalTextSize = paints.rainTextPaint.textSize * combinedScale * extraScale * nightScale

        return Paint(paints.rainTextPaint).apply {
            textSize = finalTextSize
        }
    }

    private fun Float.toPx(density: Float): Float = this * density
}
