package com.weatherwidget.widget

import android.graphics.*
import android.util.Log
import com.weatherwidget.shared.util.DailyRainLabels
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
    // Placement constants live in :shared so Android and desktop stay identical (single tweak point).
    private const val RAIN_HIGH_TEMP_GAP_DP = DailyRainLabels.RAIN_HIGH_TEMP_GAP_DP
    private const val RAIN_LABEL_EDGE_MARGIN_DP = DailyRainLabels.RAIN_LABEL_EDGE_MARGIN_DP
    private const val NIGHT_SCALE = DailyRainLabels.NIGHT_SCALE
    private const val NIGHT_TUCK_ROOM_MIN_DP = DailyRainLabels.NIGHT_TUCK_ROOM_MIN_DP
    private const val NIGHT_TUCK_ROOM_MAX_DP = DailyRainLabels.NIGHT_TUCK_ROOM_MAX_DP
    private const val NIGHT_TUCK_OVERLAP_BASE_DP = DailyRainLabels.NIGHT_TUCK_OVERLAP_BASE_DP
    private const val NIGHT_TUCK_NUDGE_BASE_DP = DailyRainLabels.NIGHT_TUCK_NUDGE_BASE_DP
    private const val NIGHT_TUCK_NUDGE_RANGE_DP = DailyRainLabels.NIGHT_TUCK_NUDGE_RANGE_DP

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

    internal data class NightCollisionResult(
        val centerX: Float,
        val baseline: Float,
        val resolution: String, // "none" | "down"
    )

    /** This day's drawn low-temp label, used as an obstacle for the night rain label. */
    internal data class LowLabelBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val baseline: Float,
    )

    internal data class DailyRainLabelPlacement(
        val debug: RainLabelDrawnDebug,
        val fitsPreferredTopMargin: Boolean,
    )

    fun drawDailyRainLabel(
        day: DayData,
        centerX: Float,
        layout: LayoutInfo,
        paints: PaintSet,
        onRainLabelDrawn: ((RainLabelDrawnDebug) -> Unit)?,
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
        layout: LayoutInfo,
        paints: PaintSet,
    ): DailyRainLabelPlacement? {
        val rainText = day.rainData.dailyRainLabelText ?: return null
        val localRainPaint = createScaledRainPaint(day, day.rainData.dailyPrecipProbability, RainLabelType.DAY, layout.density, paints)
        val textWidth = localRainPaint.measureText(rainText)
        val highBaseline = DailyForecastGraphRenderer.resolveHighLabelBaseline(day, layout) ?: return null
        val tempPaint = when {
            day.isToday -> paints.todayTempTextPaint
            day.isPast -> paints.pastTempTextPaint
            else -> paints.tempTextPaint
        }
        // The high label is shrink-to-fit when its temp is wide for the column (e.g. dual-label
        // "75.6°"), so its rendered top is lower than the full-size metrics imply. Anchor the rain
        // label to the high label *as drawn* by scaling the high metrics by that same draw scale —
        // otherwise the percentage floats well above the temperature with a large gap.
        val highScale = DailyForecastGraphRenderer.resolveHighLabelDrawScale(day, layout, paints)
        val fullHighMetrics = textMetrics(tempPaint)
        val drawnHighMetrics = TextMetrics(fullHighMetrics.ascent * highScale, fullHighMetrics.descent * highScale)
        val placement = resolveRainAboveHighPlacement(
            highBaseline = highBaseline,
            highMetrics = drawnHighMetrics,
            rainMetrics = textMetrics(localRainPaint),
            topMargin = layout.graphTop * 0.2f,
            gap = (RAIN_HIGH_TEMP_GAP_DP * layout.bitmapScale.coerceAtMost(1f)).toPx(layout.density),
        )

        return DailyRainLabelPlacement(
            debug = RainLabelDrawnDebug(
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
            fitsPreferredTopMargin = placement.fits,
        )
    }

    fun drawNightRainLabel(
        day: DayData,
        rightNeighbor: DayData?,
        centerX: Float,
        layout: LayoutInfo,
        paints: PaintSet,
        ownLowLabel: LowLabelBox?,
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

        // The interstitial anchor (min of this day's and the neighbor's low baseline) can land the
        // label right next to this day's own low label, clipping its degree symbol. When that
        // happens, nudge the label straight DOWN to share the low label's baseline so it sits beside
        // the number (e.g. "56° 12%"), clear of the degree symbol above. No horizontal move; the
        // interstitial anchor in resolveNightAnchorBaseline is intentionally untouched.
        val nightTextWidth = fit.paint.measureText(rainText)
        val nightHalfWidth = nightTextWidth / 2f
        var resolvedCenterX = fit.centerX
        var resolvedBaseline = finalBaseline
        var resolution = "none"

        if (ownLowLabel != null) {
            val collision = resolveNightCollision(
                nightCenterX = resolvedCenterX,
                nightBaseline = resolvedBaseline,
                nightHalfWidth = nightHalfWidth,
                ascent = metrics.ascent,
                descent = metrics.descent,
                ownLeft = ownLowLabel.left,
                ownTop = ownLowLabel.top,
                ownRight = ownLowLabel.right,
                ownBottom = ownLowLabel.bottom,
                ownBaseline = ownLowLabel.baseline,
            )
            resolvedCenterX = collision.centerX
            resolvedBaseline = collision.baseline
            resolution = collision.resolution
        }

        val resolvedBottom = resolvedBaseline + metrics.descent

        Log.d(TAG, "nightRainLabel position: date=${day.date} text=\"$rainText\"" +
            " anchorBaseline=${tuck.anchorBaseline} tightFraction=${tuck.tightFraction}" +
            " dynamicOverlapDp=${tuck.dynamicOverlapDp} finalTopOverlap=${finalTopOverlap}px" +
            " finalTopY=$finalTopY finalBaseline=$finalBaseline resolvedBaseline=$resolvedBaseline" +
            " resolvedCenterX=$resolvedCenterX resolution=$resolution finalBottom=$resolvedBottom" +
            " hardBottomLimit=$hardBottomLimit roomBelowDp=${tuck.roomBelowDp}" +
            " tempDescent=${tuck.tempMetrics.descent}")

        if (resolvedBottom <= hardBottomLimit) {
            canvas.drawText(rainText, resolvedCenterX, resolvedBaseline, fit.paint)
            onRainLabelDrawn?.invoke(
                RainLabelDrawnDebug(
                    date = day.date,
                    text = rainText,
                    placement = fit.placementType,
                    centerX = resolvedCenterX,
                    leftX = resolvedCenterX - nightHalfWidth,
                    rightX = resolvedCenterX + nightHalfWidth,
                    baselineY = resolvedBaseline,
                    topY = resolvedBaseline + metrics.ascent,
                    bottomY = resolvedBottom,
                    anchorBaselineY = tuck.anchorBaseline,
                    isNightLabel = true,
                )
            )
            return
        }

        Log.d(TAG, "nightRainLabel skipped: bottom overflow: date=${day.date} baseline=$resolvedBaseline")
    }

    /**
     * Resolves a collision between the night rain label and this day's own low-temp label
     * (degree symbol included). When the two overlap, nudge the rain label straight DOWN so its
     * baseline matches the low label's — it then sits beside the number ("56° 12%") and clears the
     * degree symbol above. Only ever moves down, and never further than the shared baseline, so the
     * label stays by the side of the temperature rather than dropping into the empty space below.
     * Pure float math (no Paint/RectF) so it is unit-testable in a renderer environment where font
     * metrics are stubbed to zero.
     */
    internal fun resolveNightCollision(
        nightCenterX: Float,
        nightBaseline: Float,
        nightHalfWidth: Float,
        ascent: Float,
        descent: Float,
        ownLeft: Float,
        ownTop: Float,
        ownRight: Float,
        ownBottom: Float,
        ownBaseline: Float,
    ): NightCollisionResult {
        val nightLeft = nightCenterX - nightHalfWidth
        val nightRight = nightCenterX + nightHalfWidth
        val nightTop = nightBaseline + ascent
        val nightBottom = nightBaseline + descent
        val intersects = nightLeft < ownRight && ownLeft < nightRight && nightTop < ownBottom && ownTop < nightBottom
        if (!intersects || ownBaseline <= nightBaseline) {
            return NightCollisionResult(nightCenterX, nightBaseline, "none")
        }
        return NightCollisionResult(nightCenterX, ownBaseline, "down")
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
        val shiftedCenterX = centerX + layout.dayWidth / 2f - hNudgePx + (1.0f).toPx(layout.density)
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
        paints: PaintSet,
        extraScale: Float = 1.0f,
    ): Paint {
        val nightScale = if (labelType == RainLabelType.NIGHT) NIGHT_SCALE else 1.0f
        // Past days show observed amounts, not probability-weighted forecasts.
        // Skip probability and distance scaling so actuals render at base size.
        val combinedScale = if (day.isPast) {
            0.85f
        } else {
            val probFraction = (probability ?: 0).toFloat() / 100f
            val probScale = HeaderPrecipCalculator.getPrecipScaleFactor(probability ?: 0)
            val effectiveDays = day.daysFromToday.toFloat().coerceAtLeast(1.5f)
            val distanceScale = 1.0f - RAIN_FONT_SCALE_K * (1.0f - probFraction) * (effectiveDays / RAIN_FONT_SCALE_MAX_DAYS)
            probScale * distanceScale
        }
        val finalTextSize = paints.rainTextPaint.textSize * combinedScale * extraScale * nightScale

        return Paint(paints.rainTextPaint).apply {
            textSize = finalTextSize
        }
    }

    private fun Float.toPx(density: Float): Float = this * density
}
