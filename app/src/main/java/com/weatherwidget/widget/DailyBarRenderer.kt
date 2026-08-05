package com.weatherwidget.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.shared.graph.DualHighLabel
import com.weatherwidget.shared.graph.TodayColumnHighlight
import com.weatherwidget.util.WeatherConditionColors
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.DailyForecastGraphRenderer.BarDrawnDebug
import com.weatherwidget.widget.DailyForecastGraphRenderer.DayData
import com.weatherwidget.widget.DailyGraphLayoutInfo
import com.weatherwidget.widget.DailyGraphPaintSet
import kotlin.math.abs

/**
 * Draws the bar layer of the daily forecast graph, including today's highlighted triple bar,
 * adaptive mixed-weather segments, forecast overlays, and the high-temperature labels attached to
 * those bars. Layout, paint caching, and the icon/rain/day-label layer remain orchestrated by
 * [DailyForecastGraphRenderer].
 *
 * Extracted as split B of `plans/260728b-dailyforecastgraphrenderer-code-review.md`.
 */
internal object DailyBarRenderer {
    private const val TAG = "DailyGraphRenderer"

    internal const val FORECAST_BAR_WIDTH_DP = 9f
    internal const val TODAY_TRIPLE_BAR_WIDTH_DP = 8f
    internal const val TODAY_TRIPLE_BAR_WIDTH_DP_COMPACT = 6f
    internal const val GHOST_BAR_ALPHA = 75
    internal const val CLIMATE_OVERLAY_ALPHA = 80
    internal const val BULB_RADIUS_SCALE = 1.2f
    private const val BULB_VERTICAL_CENTER_FRACTION = 0.5f

    // These scales are independently tunable. They currently share 0.7 because that value happens
    // to suit history width, forecast-overlay width, and forecast offset; changing one need not move
    // the other two.
    internal const val HISTORY_BAR_WIDTH_SCALE = 0.7f
    internal const val FORECAST_OVERLAY_WIDTH_SCALE = 0.7f
    internal const val FORECAST_BAR_OFFSET_SCALE = 0.7f
    internal const val CLIMATE_OVERLAY_WIDTH_SCALE = 0.8f

    internal val COLOR_FORECAST = 0xFF5AC8FA.toInt()
    internal val COLOR_OBSERVED_RED = WeatherConditionColors.OBSERVED
    internal val COLOR_TODAY_HIGHLIGHT = 0xFFFFFF00.toInt()
    internal val COLOR_GAP_FALLBACK = 0xFF34C759.toInt()

    private inline fun debug(msg: () -> String) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, msg())
        }
    }

    /**
     * Draws the frosted-glass focal panel behind the today column. Spans the three bars horizontally
     * and the bar/icon/low-label area vertically. It is drawn separately from [drawDayBars] so the
     * caller can keep it behind every other element in the column.
     */
    internal fun drawTodayHighlightPanel(
        canvas: Canvas,
        centerX: Float,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
    ) {
        val density = layout.density
        val tripleBarWidth = todayTripleBarStrokeWidthPx(
            density = density,
            scaleFactor = layout.scaleFactor,
            bitmapScale = layout.bitmapScale,
            compact = layout.useCompactTodayBars,
        )
        val bounds = TodayColumnHighlight.panelBounds(
            centerXPx = centerX,
            tripleBarOffsetPx = layout.tripleBarOffset,
            flankBarWidthPx = tripleBarWidth,
            dayWidthPx =
                layout.todayColumnIndex?.let(layout::columnWidth) ?: layout.dayWidth,
            graphTopPx = layout.graphTop,
            canvasHeightPx = layout.heightPx.toFloat(),
            dayLabelBandPx = layout.dayLabelHeight,
            horizontalPaddingPx = TodayColumnHighlight.PANEL_HORIZONTAL_PADDING_DP.dp(density),
            topMarginPx = TodayColumnHighlight.PANEL_TOP_MARGIN_DP.dp(density),
        )
        val radius = TodayColumnHighlight.PANEL_CORNER_RADIUS_DP.dp(density)
        val rect = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
        canvas.drawRoundRect(rect, radius, radius, paints.todayPanelFillPaint)
    }

    internal fun drawDayBars(
        canvas: Canvas,
        day: DayData,
        centerX: Float,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
        onBarDrawn: ((BarDrawnDebug) -> Unit)?,
    ): List<RectF> {
        val highY = day.solidLineHigh?.let { layout.tempToY(it) }
        val lowY = day.solidLineLow?.let { layout.tempToY(it) }

        if (day.isToday) {
            drawTodayTripleBar(canvas, day, centerX, highY, lowY, layout, paints, onBarDrawn)
        } else if (highY != null || lowY != null) {
            val endpoints = resolveBarEndpoints(highY, lowY, layout.minBarHeightPx)
            if (endpoints == null) {
                Log.w(
                    TAG,
                    "drawDayBars: resolveBarEndpoints returned null despite non-null guard" +
                        " date=${day.date} highY=$highY lowY=$lowY",
                )
            } else {
                val (hY, effectiveLowY) = endpoints
                val condColor = WeatherConditionColors.forecastColor(
                    day.isSunny,
                    day.isRainy,
                    day.isMixed,
                    isNight = false,
                )
                val paint = when {
                    day.isPast -> paints.historyBarPaint
                    day.isSourceGapFallback -> paints.gapFallbackBarPaint
                    else -> paints.barForColor(condColor)
                }

                val usesAdaptiveSegments =
                    !day.isPast && day.iconRes != null && shouldUseAdaptiveSegments(day)
                debug {
                    "Bar color decision: date=${day.date}" +
                        " isPast=${day.isPast} isSunny=${day.isSunny} isRainy=${day.isRainy}" +
                        " isMixed=${day.isMixed} iconRes=${day.iconRes}" +
                        " color=${String.format("#%08X", paint.color)}" +
                        " gradient=$usesAdaptiveSegments" +
                        " cloudRatioOverride=${day.cloudCoverRatioOverride}"
                }
                drawWeatherAdaptiveBar(
                    canvas = canvas,
                    centerX = centerX,
                    topY = hY,
                    bottomY = effectiveLowY,
                    paint = paint,
                    day = day,
                    logPrefix = "primary",
                    allowAdaptiveSegments = !day.isPast,
                )
                onBarDrawn?.invoke(
                    BarDrawnDebug(
                        day.date,
                        if (day.isPast) "HISTORY" else "FUTURE",
                        hY,
                        effectiveLowY,
                        centerX,
                        paint.color,
                    ),
                )
            }
        }

        if (!day.isToday && day.dashedLineHigh != null && day.dashedLineLow != null) {
            val fHighY = layout.tempToY(day.dashedLineHigh)
            val fLowY = layout.tempToY(day.dashedLineLow)
            val effectiveFLowY = clampMinBarHeight(fHighY, fLowY, layout.minBarHeightPx)

            val forecastX = centerX + layout.forecastBarOffset
            val condColor = WeatherConditionColors.forecastColor(
                day.isSunny,
                day.isRainy,
                day.isMixed,
                isNight = false,
            )
            val overlayPaint = if (day.isClimateNormal) {
                paints.climateOverlayForColor(condColor)
            } else {
                paints.forecastForColor(condColor)
            }
            val overlayGradient = day.iconRes != null && shouldUseAdaptiveSegments(day)
            debug {
                "Overlay color decision: date=${day.date}" +
                    " isSunny=${day.isSunny} isRainy=${day.isRainy}" +
                    " isMixed=${day.isMixed} iconRes=${day.iconRes}" +
                    " color=${String.format("#%08X", condColor)} gradient=$overlayGradient" +
                    " cloudRatioOverride=${day.cloudCoverRatioOverride}" +
                    " isClimateNormal=${day.isClimateNormal}"
            }
            drawWeatherAdaptiveBar(
                canvas = canvas,
                centerX = forecastX,
                topY = fHighY,
                bottomY = effectiveFLowY,
                paint = overlayPaint,
                day = day,
                logPrefix = "overlay",
            )
            onBarDrawn?.invoke(
                BarDrawnDebug(
                    day.date,
                    "FORECAST_OVERLAY",
                    fHighY,
                    effectiveFLowY,
                    forecastX,
                    overlayPaint.color,
                ),
            )
        }

        return drawHighLabels(canvas, day, centerX, highY, layout, paints)
    }

    private fun drawHighLabels(
        canvas: Canvas,
        day: DayData,
        centerX: Float,
        highY: Float?,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
    ): List<RectF> {
        if (day.solidLineHigh == null) return emptyList()

        val basePaint = when {
            day.isToday -> paints.todayTempTextPaint
            day.isPast -> paints.pastTempTextPaint
            else -> paints.tempTextPaint
        }
        // History — and today once its high is settled (past the 5pm cutoff) — can label BOTH the
        // actual (thermostat color) and forecast when the forecast missed by enough and there is
        // vertical room. DailyHighLabelPlanner is shared with the rain-label resolver.
        val plan = DailyHighLabelPlanner.resolveHighLabelPlan(day, layout, paints)
        if (plan != null &&
            plan.showBoth &&
            plan.forecastHigh != null &&
            plan.forecastBaseline != null
        ) {
            val condColor = WeatherConditionColors.forecastColor(
                day.isSunny,
                day.isRainy,
                day.isMixed,
                isNight = false,
            )
            // Today centers both labels on the column; past forecast labels follow their overlay.
            val forecastLabelX =
                if (day.isToday) centerX else centerX + layout.forecastBarOffset
            val actualBounds = DailyTemperatureLabelRenderer.draw(
                canvas = canvas,
                text = DailyTemperatureLabelRenderer.format(
                    plan.actualHigh,
                    useCelsius = layout.useCelsius,
                ),
                centerX = centerX,
                baselineY = plan.actualBaseline,
                base = basePaint,
                extraScale = DualHighLabel.TWO_LABEL_FONT_SCALE,
                colorOverride = COLOR_OBSERVED_RED,
                drawOutline = true,
                maxWidthPx = layout.tempLabelMaxWidthPx,
            )
            // The forecast shrinks only when the planner found the two labels colliding at full size.
            val forecastBounds = DailyTemperatureLabelRenderer.draw(
                canvas = canvas,
                text = DailyTemperatureLabelRenderer.format(
                    plan.forecastHigh,
                    useCelsius = layout.useCelsius,
                ),
                centerX = forecastLabelX,
                baselineY = plan.forecastBaseline,
                base = basePaint,
                extraScale = DualHighLabel.TWO_LABEL_FONT_SCALE * plan.forecastFontScale,
                colorOverride = condColor,
                drawOutline = true,
                maxWidthPx = layout.tempLabelMaxWidthPx,
            )
            return listOf(actualBounds, forecastBounds)
        }

        // Reuse the plan's anchorHigh so today's cutoff resolution runs once per day per render.
        val displayHigh = plan?.anchorHigh ?: day.effectiveHigh() ?: day.solidLineHigh
        val highLabel = DailyTemperatureLabelRenderer.format(
            displayHigh,
            useCelsius = layout.useCelsius,
        )
        val labelBaseline = plan?.anchorBaseline ?: run {
            Log.w(
                TAG,
                "drawDayBars: high label plan null despite non-null solidLineHigh date=${day.date}",
            )
            val labelOffset =
                DailyHighLabelPlanner.HIGH_LABEL_OFFSET_DP *
                    layout.bitmapScale.coerceAtMost(1f) *
                    layout.density
            (highY ?: layout.graphTop) - labelOffset
        }
        val highColorOverride =
            if (plan?.todayHighSettled == true) COLOR_OBSERVED_RED else null
        return listOf(DailyTemperatureLabelRenderer.draw(
            canvas = canvas,
            text = highLabel,
            centerX = centerX,
            baselineY = labelBaseline,
            base = basePaint,
            colorOverride = highColorOverride,
            drawOutline = day.isPast || day.isToday,
            maxWidthPx = layout.tempLabelMaxWidthPx,
        ))
    }

    private fun drawTodayTripleBar(
        canvas: Canvas,
        day: DayData,
        centerX: Float,
        highY: Float?,
        lowY: Float?,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
        onBarDrawn: ((BarDrawnDebug) -> Unit)?,
    ) {
        val (obsHighY, effectiveObsLowY) =
            resolveBarEndpoints(highY, lowY, layout.minBarHeightPx) ?: return

        day.snapshotHigh?.let { sHigh ->
            day.snapshotLow?.let { sLow ->
                val sHighY = layout.tempToY(sHigh)
                val sLowY = layout.tempToY(sLow)
                val effectiveSLowY =
                    clampMinBarHeight(sHighY, sLowY, layout.minBarHeightPx)
                val snapshotX = centerX - layout.tripleBarOffset

                val sIsSunny =
                    day.snapshotIconRes?.let { WeatherIconMapper.isSunny(it) } ?: false
                val sIsRainy =
                    day.snapshotIconRes?.let { WeatherIconMapper.isPrecipitation(it) } ?: false
                val sIsMixed =
                    day.snapshotIconRes?.let { WeatherIconMapper.isMixed(it) } ?: false

                val sCondColor =
                    com.weatherwidget.shared.util.WeatherColors.snapshotBarOverrideArgb(sIsRainy)
                        ?: paints.todaySnapshotYellowPaint.color
                val sPaint = paints.todayForecastForColor(sCondColor)

                // Yesterday's forecast for today carries today's resolved cloud-cover ratio.
                val snapshotDay = day.copy(
                    iconRes = day.snapshotIconRes,
                    isSunny = sIsSunny,
                    isRainy = sIsRainy,
                    isMixed = sIsMixed,
                    cloudCoverRatioOverride = day.cloudCoverRatioOverride
                        ?: day.snapshotIconRes?.let { WeatherConditionColors.cloudRatio(it) },
                )

                drawWeatherAdaptiveBar(
                    canvas = canvas,
                    centerX = snapshotX,
                    topY = sHighY,
                    bottomY = effectiveSLowY,
                    paint = sPaint,
                    day = snapshotDay,
                    logPrefix = "today_snapshot",
                    allowAdaptiveSegments = true,
                )
                onBarDrawn?.invoke(
                    BarDrawnDebug(
                        day.date,
                        "TODAY_SNAPSHOT",
                        sHighY,
                        effectiveSLowY,
                        snapshotX,
                        sPaint.color,
                        adaptiveSegments = true,
                    ),
                )
            }
        }

        val fHigh = day.dashedLineHigh ?: day.solidLineHigh ?: return
        val fLow = day.dashedLineLow ?: day.solidLineLow ?: return
        val fHighY = layout.tempToY(fHigh)
        val fLowY = layout.tempToY(fLow)
        val effectiveFLowY = clampMinBarHeight(fHighY, fLowY, layout.minBarHeightPx)

        val condColor = WeatherConditionColors.forecastColor(
            day.isSunny,
            day.isRainy,
            day.isMixed,
            isNight = false,
        )
        val forecastPaint = paints.todayForecastForColor(condColor)
        val todayForecastX = centerX + layout.tripleBarOffset
        drawWeatherAdaptiveBar(
            canvas = canvas,
            centerX = todayForecastX,
            topY = fHighY,
            bottomY = effectiveFLowY,
            paint = forecastPaint,
            day = day,
            logPrefix = "today_forecast",
        )
        onBarDrawn?.invoke(
            BarDrawnDebug(
                day.date,
                "TODAY_FORECAST",
                fHighY,
                effectiveFLowY,
                todayForecastX,
                forecastPaint.color,
            ),
        )

        canvas.drawLine(
            centerX,
            obsHighY,
            centerX,
            effectiveObsLowY,
            paints.todayObservedRedPaint,
        )
        canvas.drawCircle(
            centerX,
            effectiveObsLowY + layout.bulbRadius * BULB_VERTICAL_CENTER_FRACTION,
            layout.bulbRadius,
            paints.todayObservedRedBulbPaint,
        )

        day.ghostLineHigh?.let { trueHigh ->
            val obsHighTemp = day.solidLineHigh ?: 0f
            if (trueHigh > obsHighTemp) {
                val ghostHighY = layout.tempToY(trueHigh)
                canvas.drawLine(
                    centerX,
                    ghostHighY,
                    centerX,
                    obsHighY,
                    paints.todayObservedGhostPaint,
                )
                onBarDrawn?.invoke(
                    BarDrawnDebug(
                        day.date,
                        "TODAY_GHOST",
                        ghostHighY,
                        obsHighY,
                        centerX,
                        paints.todayObservedGhostPaint.color,
                    ),
                )
            }
        }

        onBarDrawn?.invoke(
            BarDrawnDebug(
                day.date,
                "TODAY",
                obsHighY,
                effectiveObsLowY,
                centerX,
                paints.todayObservedRedPaint.color,
            ),
        )
    }

    private fun drawWeatherAdaptiveBar(
        canvas: Canvas,
        centerX: Float,
        topY: Float,
        bottomY: Float,
        paint: Paint,
        day: DayData,
        logPrefix: String,
        allowAdaptiveSegments: Boolean = true,
    ) {
        if (!allowAdaptiveSegments || !shouldUseAdaptiveSegments(day) || day.iconRes == null) {
            canvas.drawLine(centerX, topY, centerX, bottomY, paint)
            return
        }

        val split =
            WeatherConditionColors.resolveMixedBarSplit(day.iconRes, day.cloudCoverRatioOverride)
        if (split == null) {
            canvas.drawLine(centerX, topY, centerX, bottomY, paint)
            return
        }

        val barHeight = bottomY - topY
        if (barHeight <= 1f) {
            canvas.drawLine(centerX, topY, centerX, bottomY, paint)
            return
        }

        val topSegmentEndY = (topY + barHeight * split.topFraction).coerceIn(topY, bottomY)
        val bottomPaint = Paint(paint).apply {
            color = split.bottomColor
            shader = null
        }
        val topPaint = Paint(paint).apply {
            color = paint.color
            shader = null
        }

        canvas.drawLine(centerX, topY, centerX, bottomY, bottomPaint)
        if (abs(topSegmentEndY - topY) > 0.5f) {
            canvas.drawLine(centerX, topY, centerX, topSegmentEndY, topPaint)
        }

        debug {
            "$logPrefix mixed bar geometry: date=${day.date} centerX=$centerX" +
                " topY=$topY bottomY=$bottomY height=$barHeight" +
                " splitRatio=${split.ratio} topFraction=${split.topFraction}" +
                " topEndY=$topSegmentEndY" +
                " topColor=${String.format("#%08X", split.topColor)}" +
                " bottomColor=${String.format("#%08X", split.bottomColor)}"
        }
    }

    private fun shouldUseAdaptiveSegments(day: DayData): Boolean =
        day.isMixed || (day.cloudCoverRatioOverride ?: 0f) > 0f

    @VisibleForTesting
    internal fun dailyBarStrokeWidthPx(
        density: Float,
        scaleFactor: Float = 1f,
        bitmapScale: Float = 1f,
    ): Float {
        val labelScale = bitmapScale.coerceIn(0.5f, 1f)
        return FORECAST_BAR_WIDTH_DP * scaleFactor * labelScale * density
    }

    @VisibleForTesting
    internal fun todayTripleBarStrokeWidthPx(
        density: Float,
        scaleFactor: Float = 1f,
        bitmapScale: Float = 1f,
        compact: Boolean = false,
    ): Float {
        val labelScale = bitmapScale.coerceIn(0.5f, 1f)
        val widthDp =
            if (compact) TODAY_TRIPLE_BAR_WIDTH_DP_COMPACT else TODAY_TRIPLE_BAR_WIDTH_DP
        return widthDp * scaleFactor * labelScale * density
    }

    private fun clampMinBarHeight(
        highY: Float,
        lowY: Float,
        minBarHeight: Float,
    ): Float = if (abs(highY - lowY) < minBarHeight) highY + minBarHeight else lowY

    private fun resolveBarEndpoints(
        highY: Float?,
        lowY: Float?,
        minBarHeight: Float,
    ): Pair<Float, Float>? {
        val hY = highY ?: (lowY?.let { it - minBarHeight }) ?: return null
        val lY = lowY ?: (hY + minBarHeight)
        return hY to clampMinBarHeight(hY, lY, minBarHeight)
    }

    private fun Float.dp(density: Float): Float = this * density
}
