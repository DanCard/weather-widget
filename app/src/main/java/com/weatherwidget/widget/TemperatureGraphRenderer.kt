package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.LocalDateTime
import java.time.Instant
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import com.weatherwidget.util.WeatherConditionColors
import com.weatherwidget.BuildConfig

object TemperatureGraphRenderer {
    private const val TAG = "TempGraphRenderer"

    private inline fun debug(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg())
    }

    private const val MIN_GHOST_LINE_DELTA = 0.1f
    private const val MAX_LEADER_DISPLACEMENT_STEPS = 3

    private fun formatAgeLabel(ageMinutes: Long, hoursSpanHours: Long): String? = TemperatureGraphStyle.formatAgeLabel(ageMinutes, hoursSpanHours)
    private fun withAlpha(color: Int, alpha: Int): Int = TemperatureGraphStyle.withAlpha(color, alpha)
    private fun fontAscent(paint: Paint): Float = TemperatureGraphStyle.fontAscent(paint)
    private fun fontDescent(paint: Paint): Float = TemperatureGraphStyle.fontDescent(paint)
    private fun ensurePaints(context: Context, labelScale: Float): PaintSet = TemperatureGraphStyle.ensurePaints(context, labelScale)
    private fun dpToPx(context: Context, dp: Float): Float = TemperatureGraphStyle.dpToPx(context, dp)

    private fun buildTempGradient(
        graphTop: Float,
        graphBottom: Float,
        minTemp: Float,
        maxTemp: Float,
        tempRange: Float,
        alphaTop: Int = 255,
        alphaBottom: Int = 255,
    ): LinearGradient = TemperatureGraphStyle.buildTempGradient(graphTop, graphBottom, minTemp, maxTemp, tempRange, alphaTop, alphaBottom)

    private suspend fun computePoints(
        hours: List<HourData>,
        minTemp: Float,
        tempRange: Float,
        graphTop: Float,
        graphHeight: Float,
        graphBottom: Float,
        hourWidth: Float,
        minTimeEpoch: Long,
        currentTime: LocalDateTime,
        appliedDelta: Float?,
        observedAt: Long?,
        lastObservedTemp: Float?,
        widthPx: Int,
        onPointsResolved: ((PointsDebug) -> Unit)?,
    ): RenderContextUpdate {
        currentCoroutineContext().ensureActive()
        val effectiveDelta = appliedDelta ?: 0f
        val smoothedForecastTemps = hours.map { it.temperature }
        val actualTemps = hours.map { it.actualTemperature ?: (it.temperature + effectiveDelta) }

        val fetchTime = observedAt?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }
        val fetchIdx = fetchTime?.let { time -> hours.indexOfLast { !it.dateTime.isAfter(time) } } ?: -1

        val anchorDelta = effectiveDelta
        val smoothedExpectedTemps = smoothedForecastTemps.map { it + anchorDelta }

        val originalPoints = mutableListOf<Pair<Float, Float>>()
        val forecastPoints = mutableListOf<Pair<Float, Float>>()
        val expectedPoints = mutableListOf<Pair<Float, Float>>()

        hours.indices.forEach { index ->
            currentCoroutineContext().ensureActive()
            val pointEpoch = hours[index].dateTime.toEpochSecond(ZoneOffset.UTC)
            val x = ((pointEpoch - minTimeEpoch) / 3600f) * hourWidth
            val yTruth = TemperatureGraphStyle.tempToY(actualTemps[index], graphTop, graphHeight, minTemp, tempRange)
            originalPoints.add(x to yTruth)

            val yForecast = TemperatureGraphStyle.tempToY(smoothedForecastTemps[index], graphTop, graphHeight, minTemp, tempRange)
            forecastPoints.add(x to yForecast)

            val yExpected = TemperatureGraphStyle.tempToY(smoothedExpectedTemps[index], graphTop, graphHeight, minTemp, tempRange)
            expectedPoints.add(x to yExpected)
        }

        onPointsResolved?.invoke(PointsDebug(originalPoints, forecastPoints, expectedPoints))

        val (originalPath, _) = GraphRenderUtils.buildSmoothCurveAndFillPaths(originalPoints, graphBottom)
        val (expectedPath, expectedFillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(expectedPoints, graphBottom)
        val (forecastPath, forecastFillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(forecastPoints, graphBottom)
        val forecastSegmentPaths = GraphRenderUtils.buildPerSegmentPaths(forecastPoints)

        val nowX = GraphRenderUtils.computeNowX(hours, originalPoints, currentTime, hourWidth, { it.isCurrentHour }, { it.dateTime })
        val nowIndicatorVisible = nowX != null && nowX in 0f..widthPx.toFloat()

        val fetchDotX: Float? = if (observedAt != null && fetchTime != null) {
            GraphRenderUtils.computeXForTime(fetchTime, hours, originalPoints, hourWidth) { it.dateTime }
        } else null
        val lastObservedActualIndex = hours.indexOfLast { it.isObservedActual }
        val lastObservedActualX: Float? = if (lastObservedActualIndex >= 0) originalPoints[lastObservedActualIndex].first else null
        val lastActualIndex = hours.indexOfLast { it.isActual }
        val rawTransitionX: Float? =
            when {
                fetchDotX != null -> fetchDotX
                lastObservedActualX != null -> lastObservedActualX
                lastActualIndex >= 0 -> originalPoints[lastActualIndex].first
                else -> null
            }

        val transitionX: Float? = rawTransitionX?.let { raw ->
            listOfNotNull(raw, nowX, fetchDotX).min()
        }
        val effectiveActualEndIndex = if (transitionX != null) {
            val idx = originalPoints.indexOfLast { it.first <= transitionX + 1f }
            if (idx >= 0) idx else lastActualIndex
        } else -1

        val anchoredActualPoints =
            buildAnchoredActualPoints(
                originalPoints = originalPoints,
                transitionX = transitionX,
                fetchDotX = fetchDotX,
                lastObservedTemp = lastObservedTemp,
                minTemp = minTemp,
                tempRange = tempRange,
                graphTop = graphTop,
                graphHeight = graphHeight,
            )
        val (actualPath, _) = GraphRenderUtils.buildSmoothCurveAndFillPaths(anchoredActualPoints, graphBottom)

        return RenderContextUpdate(
            smoothedForecastTemps, smoothedExpectedTemps, originalPoints, forecastPoints, expectedPoints,
            originalPath, actualPath, anchoredActualPoints, expectedPath, expectedFillPath, forecastPath, forecastFillPath, forecastSegmentPaths,
            nowX, nowIndicatorVisible, fetchTime, fetchDotX,
            anchorDelta, transitionX, effectiveActualEndIndex
        )
    }

    private suspend fun buildAnchoredActualPoints(
        originalPoints: List<Pair<Float, Float>>,
        transitionX: Float?,
        fetchDotX: Float?,
        lastObservedTemp: Float?,
        minTemp: Float,
        tempRange: Float,
        graphTop: Float,
        graphHeight: Float,
    ): List<Pair<Float, Float>> {
        currentCoroutineContext().ensureActive()
        if (transitionX == null || originalPoints.isEmpty()) return emptyList()

        val anchoredToFetchDot = fetchDotX != null && abs(fetchDotX - transitionX) <= 0.5f && lastObservedTemp != null
        val terminalY =
            if (anchoredToFetchDot) {
                TemperatureGraphStyle.tempToY(lastObservedTemp, graphTop, graphHeight, minTemp, tempRange)
            } else {
                interpolateYAtX(originalPoints, transitionX)
            }

        val visible = originalPoints.filter { it.first < transitionX - 0.5f }.toMutableList()
        val terminalPoint = transitionX to terminalY
        visible += terminalPoint
        return visible
    }

    private fun interpolateYAtX(
        points: List<Pair<Float, Float>>,
        targetX: Float,
    ): Float {
        val exact = points.firstOrNull { abs(it.first - targetX) <= 0.5f }
        if (exact != null) return exact.second

        val afterIndex = points.indexOfFirst { it.first > targetX }
        return when {
            afterIndex <= 0 -> points.first().second
            else -> {
                val before = points[afterIndex - 1]
                val after = points[afterIndex]
                val span = (after.first - before.first).coerceAtLeast(0.0001f)
                val fraction = ((targetX - before.first) / span).coerceIn(0f, 1f)
                before.second + (after.second - before.second) * fraction
            }
        }
    }

    private fun drawFillAndCurves(ctx: RenderContext, expectedFillPath: Path, hours: List<HourData>) {
        val paints = ctx.paints
        paints.expectedFillPaint.shader = buildTempGradient(
            ctx.graphTop, ctx.graphBottom, ctx.minTemp, ctx.maxTemp, ctx.tempRange, alphaTop = 68, alphaBottom = 0
        )
        ctx.canvas.drawPath(expectedFillPath, paints.expectedFillPaint)

        val appliedDelta = ctx.appliedDelta
        val fetchDotX = ctx.fetchDotX
        val lastObservedTemp = ctx.lastObservedTemp
        if (ctx.nowIndicatorVisible && appliedDelta != null && abs(appliedDelta) >= MIN_GHOST_LINE_DELTA && fetchDotX != null) {
            val expectedY = lastObservedTemp?.let { ctx.tempToY(it) }
            if (expectedY != null) ctx.onGhostLineDebug?.invoke(GhostLineDebug(fetchDotX, expectedY))

            ctx.canvas.save()
            ctx.canvas.clipRect(fetchDotX, 0f, ctx.widthPx.toFloat(), ctx.heightPx.toFloat())
            ctx.canvas.drawPath(ctx.expectedPath, paints.ghostPaint)
            ctx.canvas.restore()
        }

        val dashOn = dpToPx(ctx.context, 8f)
        val dashOff = dpToPx(ctx.context, 4f)
        val dashPattern = floatArrayOf(dashOn, dashOff)
        val segmentPaint = Paint(paints.forecastDashedPaint)
        val pathMeasure = PathMeasure()
        var cumulativeLength = 0f
        for (i in ctx.forecastSegmentPaths.indices) {
            val hour = hours[i + 1]
            segmentPaint.color = WeatherConditionColors.forecastColor(
                hour.isSunny, hour.isRainy, hour.isMixed, hour.isNight, hour.isTwilight
            )
            segmentPaint.pathEffect = DashPathEffect(dashPattern, cumulativeLength)
            ctx.canvas.drawPath(ctx.forecastSegmentPaths[i], segmentPaint)
            pathMeasure.setPath(ctx.forecastSegmentPaths[i], false)
            cumulativeLength += pathMeasure.length
        }

        val transitionX = ctx.transitionX
        if (transitionX != null) {
            ctx.canvas.save()
            ctx.canvas.clipRect(0f, 0f, transitionX + dpToPx(ctx.context, 1f), ctx.heightPx.toFloat())
            ctx.canvas.drawPath(ctx.actualPath, paints.actualLinePaint)
            ctx.canvas.restore()
        }
    }

    private fun drawHourLabelsAndIcons(
        ctx: RenderContext,
        hours: List<HourData>,
        drawnIconBounds: MutableList<RectF>
    ) {
        val minHourLabelSpacing = dpToPx(ctx.context, TemperatureGraphStyle.HOUR_LABEL_SPACING_DP * ctx.labelScale)
        GraphRenderUtils.drawHourLabels(
            canvas = ctx.canvas,
            items = hours,
            points = ctx.originalPoints,
            widthPx = ctx.widthPx,
            heightPx = ctx.heightPx,
            minHourLabelSpacing = minHourLabelSpacing,
            hourLabelTextPaint = ctx.paints.hourLabelTextPaint,
            dpToPx = { dpToPx(ctx.context, it) },
            showLabel = { it.showLabel },
            labelText = { it.label },
        ) { index, clampedX ->
            val hour = hours[index]
            hour.iconRes?.let { res ->
                androidx.core.content.ContextCompat.getDrawable(ctx.context, res)?.let { drawable ->
                    val iconY = ctx.footerTop + ctx.iconTopPad
                    val iconX = clampedX - ctx.iconSize / 2f
                    val iconRect = RectF(iconX, iconY, iconX + ctx.iconSize, iconY + ctx.iconSize)
                    drawnIconBounds.add(iconRect)
                    drawable.setBounds(iconRect.left.toInt(), iconRect.top.toInt(), iconRect.right.toInt(), iconRect.bottom.toInt())
                    if (!hour.isRainy && !hour.isMixed) {
                        drawable.setTint(when {
                            hour.isNight -> Color.parseColor("#BBBBBB")
                            hour.isTwilight -> Color.parseColor("#FFA726")
                            hour.isSunny -> Color.parseColor("#FFD60A")
                            else -> Color.parseColor("#BBBBBB")
                        })
                    }
                    drawable.draw(ctx.canvas)
                }
            }
        }
    }

    private fun placeTemperatureLabels(
        ctx: RenderContext,
        hours: List<HourData>,
        drawnIconBounds: List<RectF>
    ) {
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, ctx.transitionX, ctx.effectiveActualEndIndex, ctx.fetchTime)
        val specialCandidates = TemperatureLabelResolver.collectLabelCandidates(hours, extrema, ctx.effectiveActualEndIndex, ctx.transitionX, ctx.observedAt).toMutableList()

        val drawnLabelBounds = mutableListOf<RectF>()
        val labelAscent = fontAscent(ctx.paints.actualTempLabelTextPaint)
        val labelDescent = fontDescent(ctx.paints.actualTempLabelTextPaint)
        val labelHeight = labelDescent - labelAscent
        
        val gapDp = GraphLabelPlacementUtils.getLabelGapDp(isFallback = false)

        TemperatureLabelResolver.sortLabelCandidates(specialCandidates)

        for (candidate in specialCandidates) {
            val idx = candidate.index
            val temps = candidate.labelTemps
            val placement = TemperatureLabelResolver.resolveCandidatePlacement(ctx, hours, candidate)
            if (placement == null) continue

            val directions = if (placement.isValley) listOf(false, true) else listOf(true, false)
            val minorOverlapThreshold = if (GraphLabelPlacementUtils.isMinorOverlapEligible(candidate.role)) labelHeight * GraphLabelPlacementUtils.MINOR_OVERLAP_HEIGHT_RATIO else 0f
            var placed = false
            var forceBaselineY = Float.NaN
            var forceBounds: RectF? = null
            var forceDrawBelow = false
            var forceStep = 0
            
            outer@ for (step in 0..MAX_LEADER_DISPLACEMENT_STEPS) {
                for (placeAbove in directions) {
                    val currentGapPx = if (placeAbove) dpToPx(ctx.context, gapDp.aboveDp) else dpToPx(ctx.context, gapDp.belowDp)
                    val displacement = step * labelHeight
                    
                    val verticalPlacement = GraphLabelPlacementUtils.computeLabelVerticalPlacement(
                        pointY = placement.sy,
                        placeAbove = placeAbove,
                        gapPx = currentGapPx + displacement,
                        textAscent = labelAscent,
                        textDescent = labelDescent
                    )
                    
                    val baselineY = verticalPlacement.baselineY
                    val bounds = RectF(placement.clampedX - placement.textWidth / 2f, verticalPlacement.top, placement.clampedX + placement.textWidth / 2f, verticalPlacement.bottom)
                    
                    val onScreen = bounds.top >= 0f && bounds.bottom <= ctx.heightPx
                    if (!onScreen) continue

                    val overlapsLabel = drawnLabelBounds.any { RectF.intersects(it, bounds) }
                    val overlapsIcon = drawnIconBounds.any { RectF.intersects(it, bounds) }
                    val labelOverlap = if (overlapsLabel) GraphLabelPlacementUtils.maxVerticalOverlap(bounds, drawnLabelBounds) else 0f
                    val iconOverlap = if (overlapsIcon) GraphLabelPlacementUtils.maxVerticalOverlap(bounds, drawnIconBounds) else 0f
                    val allowMinorLabelOverlap = overlapsLabel && GraphLabelPlacementUtils.shouldAllowMinorOverlap(candidate.role, labelOverlap, labelHeight)
                    val allowMinorIconOverlap = overlapsIcon && GraphLabelPlacementUtils.shouldAllowMinorOverlap(candidate.role, iconOverlap, labelHeight)
                    val hasCollision = (overlapsLabel && !allowMinorLabelOverlap) || (overlapsIcon && !allowMinorIconOverlap)
                    
                    if (placement.isEssential && forceBounds == null) { 
                        forceBaselineY = baselineY
                        forceBounds = bounds
                        forceDrawBelow = !placeAbove
                        forceStep = step 
                    }
                    
                    if (!hasCollision) {
                        if (step > 0) {
                            val lineEndY = if (!placeAbove) bounds.top else bounds.bottom
                            ctx.canvas.drawLine(placement.clampedX, placement.sy, placement.clampedX, lineEndY, placement.leaderLinePaint)
                        }
                        ctx.canvas.drawText(placement.label, placement.clampedX, baselineY, placement.labelPaint)
                        drawnLabelBounds.add(bounds)
                        val seriesLabel = if (placement.isFuture) "forecast" else "actual"
                        val reasonBase = if (!placeAbove) "below" else "above"
                        val reason = if (step > 0) "$reasonBase+$step" else reasonBase
                        ctx.onLabelPlaced?.invoke(LabelPlacementDebug(idx, candidate.role, temps[idx], candidate.rawTemperature, placement.clampedX, baselineY, placeAbove, seriesLabel, seriesLabel, reason, step))
                        placed = true
                        break@outer
                    }
                }
            }
            if (!placed && placement.isEssential && forceBounds != null) {
                if (forceStep > 0) {
                    val lineEndY = if (forceDrawBelow) forceBounds.top else forceBounds.bottom
                    ctx.canvas.drawLine(placement.clampedX, placement.sy, placement.clampedX, lineEndY, placement.leaderLinePaint)
                }
                ctx.canvas.drawText(placement.label, placement.clampedX, forceBaselineY, placement.labelPaint)
                drawnLabelBounds.add(forceBounds)
                val seriesLabel = if (placement.isFuture) "forecast" else "actual"
                ctx.onLabelPlaced?.invoke(LabelPlacementDebug(idx, candidate.role, temps[idx], candidate.rawTemperature, placement.clampedX, forceBaselineY, !forceDrawBelow, seriesLabel, seriesLabel, "FORCED", forceStep))
            }
        }
        ctx.drawnLabelBounds.addAll(drawnLabelBounds)
    }

    private fun placeDayLabels(
        ctx: RenderContext,
        hours: List<HourData>,
        drawnIconBounds: List<RectF>
    ) {
        val fm = ctx.paints.dayLabelTextPaint.fontMetrics ?: Paint.FontMetrics()
        val dayLabelTextHeight = fm.descent - fm.ascent
        val dayYTop = ctx.graphTop + dayLabelTextHeight
        val dayYMid = (ctx.graphTop + ctx.graphBottom) / 2f
        val dayYBottom = ctx.heightPx - dpToPx(ctx.context, TemperatureGraphStyle.DAY_LABEL_BOTTOM_PADDING_DP)

        fun collides(bounds: RectF): Boolean =
            ctx.drawnLabelBounds.any { RectF.intersects(it, bounds) } ||
            drawnIconBounds.any { RectF.intersects(it, bounds) }

        val today = ctx.currentTime.toLocalDate()
        val leftDate = hours.first().dateTime.toLocalDate()
        val rightDate = hours.last().dateTime.toLocalDate()
        val leftText = hours.first().dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val rightText = hours.last().dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        
        data class DayCandidate(val date: LocalDate, val x: Float, val text: String)
        val leftWidth = (if (leftDate == today) ctx.paints.todayDayLabelPaint else ctx.paints.dayLabelTextPaint).measureText(leftText)
        val rightWidth = (if (rightDate == today) ctx.paints.todayDayLabelPaint else ctx.paints.dayLabelTextPaint).measureText(rightText)
        
        val candidates = listOf(DayCandidate(leftDate, leftWidth / 2f, leftText), DayCandidate(rightDate, ctx.widthPx - rightWidth / 2f, rightText))
        val drawnDayBounds = mutableListOf<RectF>()

        for ((idx, candidate) in candidates.withIndex()) {
            val isToday = candidate.date == today
            val paint = if (isToday) ctx.paints.todayDayLabelPaint else ctx.paints.dayLabelTextPaint
            val tw = paint.measureText(candidate.text)
            fun bounds(y: Float) = RectF(candidate.x - tw / 2f, y + fm.ascent, candidate.x + tw / 2f, y + fm.descent)

            val topB = bounds(dayYTop)
            if (!collides(topB) && !drawnDayBounds.any { RectF.intersects(it, topB) }) {
                ctx.canvas.drawText(candidate.text, candidate.x, dayYTop, paint)
                drawnDayBounds.add(topB)
                ctx.onDayLabelPlaced?.invoke(DayLabelPlacementDebug(if (idx == 0) "LEFT" else "RIGHT", candidate.text, candidate.date, candidate.x, dayYTop, "TOP", isToday))
                continue
            }
            val midB = bounds(dayYMid)
            if (!collides(midB) && !drawnDayBounds.any { RectF.intersects(it, midB) }) {
                ctx.canvas.drawText(candidate.text, candidate.x, dayYMid, paint)
                drawnDayBounds.add(midB)
                ctx.onDayLabelPlaced?.invoke(DayLabelPlacementDebug(if (idx == 0) "LEFT" else "RIGHT", candidate.text, candidate.date, candidate.x, dayYMid, "MIDDLE", isToday))
                continue
            }
            ctx.canvas.drawText(candidate.text, candidate.x, dayYBottom, paint)
            ctx.onDayLabelPlaced?.invoke(DayLabelPlacementDebug(if (idx == 0) "LEFT" else "RIGHT", candidate.text, candidate.date, candidate.x, dayYBottom, "BOTTOM", isToday))
        }
    }

    private fun resolveValueLabelLayout(
        clampedX: Float,
        fetchY: Float,
        dotRadius: Float,
        valueWidth: Float,
        sideGap: Float,
        aboveGap: Float,
        widthPx: Int,
        baselineOffset: Float,
        ascent: Float,
        descent: Float,
    ): ValueLabelLayout? {
        if (clampedX + dotRadius + sideGap + valueWidth <= widthPx) {
            val x = clampedX + dotRadius + sideGap
            val y = fetchY + baselineOffset
            return ValueLabelLayout(x, y, RectF(x, y + ascent, x + valueWidth, y + descent), Paint.Align.LEFT)
        }
        if (clampedX - dotRadius - sideGap - valueWidth >= 0) {
            val x = clampedX - dotRadius - sideGap
            val y = fetchY + baselineOffset
            return ValueLabelLayout(x, y, RectF(x - valueWidth, y + ascent, x, y + descent), Paint.Align.RIGHT)
        }
        if (fetchY - dotRadius - aboveGap + ascent >= 0) {
            val x = clampedX
            val y = fetchY - dotRadius - aboveGap
            return ValueLabelLayout(x, y, RectF(x - valueWidth / 2f, y + ascent, x + valueWidth / 2f, y + descent), Paint.Align.CENTER)
        }
        return null
    }

    private fun resolveStalenessInitialLayout(
        clampedX: Float,
        fetchY: Float,
        dotRadius: Float,
        padding: Float,
        ageWidth: Float,
        ascent: Float,
        descent: Float,
        heightPx: Int,
        existingBounds: List<RectF>,
        minorOverlapThreshold: Float,
    ): StalenessInitialLayout {
        var placeAbove = false
        var baselineY = fetchY + dotRadius + padding - ascent
        var bounds = RectF(
            clampedX - ageWidth / 2f,
            baselineY + ascent,
            clampedX + ageWidth / 2f,
            baselineY + descent
        )
        val collision = GraphLabelPlacementUtils.maxVerticalOverlap(bounds, existingBounds) > minorOverlapThreshold
        if (collision || bounds.bottom > heightPx) {
            placeAbove = true
            baselineY = fetchY - dotRadius - padding - descent
            bounds.offsetTo(clampedX - ageWidth / 2f, baselineY + ascent)
        }
        return StalenessInitialLayout(baselineY, bounds, placeAbove)
    }

    private fun computeFetchDotBounds(ctx: RenderContext, hours: List<HourData>): List<RectF> {
        val bounds = mutableListOf<RectF>()
        val observedAt = ctx.observedAt
        val fetchDotX = ctx.fetchDotX
        val lastObservedTemp = ctx.lastObservedTemp
        if (observedAt == null || fetchDotX == null || lastObservedTemp == null) return bounds
        val fetchY = ctx.tempToY(lastObservedTemp)
        val dotRadius = dpToPx(ctx.context, TemperatureGraphStyle.DOT_RADIUS_DP * ctx.labelScale)
        val clampedX = fetchDotX.coerceIn(dotRadius, ctx.widthPx - dotRadius)

        val outerRadius = dotRadius + ctx.paints.ringPaint.strokeWidth / 2f
        bounds.add(RectF(clampedX - outerRadius, fetchY - outerRadius, clampedX + outerRadius, fetchY + outerRadius))

        val valueLabel = TemperatureGraphStyle.formatTemp(lastObservedTemp) + "°"
        val valueWidth = ctx.paints.valueTextPaint.measureText(valueLabel)
        val sideGap = dpToPx(ctx.context, TemperatureGraphStyle.FETCH_DOT_SIDE_GAP_DP * ctx.labelScale)
        val aboveGap = dpToPx(ctx.context, TemperatureGraphStyle.FETCH_DOT_ABOVE_GAP_DP * ctx.labelScale)
        val baselineOffset = ctx.paints.valueTextPaint.textSize / 3f
        val vAscent = fontAscent(ctx.paints.valueTextPaint)
        val vDescent = fontDescent(ctx.paints.valueTextPaint)

        resolveValueLabelLayout(clampedX, fetchY, dotRadius, valueWidth, sideGap, aboveGap, ctx.widthPx, baselineOffset, vAscent, vDescent)?.let {
            bounds.add(it.bounds)
        }

        val ageMinutes = ctx.fetchTime?.let { Duration.between(it, ctx.currentTime).toMinutes() } ?: 0L
        val ageLabel = formatAgeLabel(ageMinutes, Duration.between(hours.first().dateTime, hours.last().dateTime).toHours())
        if (ageLabel != null) {
            val sAscent = fontAscent(ctx.paints.stalenessTextPaint)
            val sDescent = fontDescent(ctx.paints.stalenessTextPaint)
            val ageWidth = ctx.paints.stalenessTextPaint.measureText(ageLabel)
            val padding = dpToPx(ctx.context, TemperatureGraphStyle.FETCH_DOT_SIDE_GAP_DP * ctx.labelScale)
            val minorOverlapThreshold = ctx.paints.stalenessTextPaint.textSize * 0.40f
            bounds.add(resolveStalenessInitialLayout(clampedX, fetchY, dotRadius, padding, ageWidth, sAscent, sDescent, ctx.heightPx, bounds, minorOverlapThreshold).bounds)
        }

        return bounds
    }

    private fun drawFetchDot(ctx: RenderContext, hours: List<HourData>): List<RectF> {
        val drawnBounds = mutableListOf<RectF>()
        val observedAt = ctx.observedAt
        val fetchDotX = ctx.fetchDotX
        val lastObservedTemp = ctx.lastObservedTemp
        if (observedAt == null || fetchDotX == null || lastObservedTemp == null) return drawnBounds
        val fetchY = ctx.tempToY(lastObservedTemp)
        val dotRadius = dpToPx(ctx.context, TemperatureGraphStyle.DOT_RADIUS_DP * ctx.labelScale)
        val clampedX = fetchDotX.coerceIn(dotRadius, ctx.widthPx - dotRadius)

        val localDotPaint = Paint(ctx.paints.dotPaint).apply { color = TemperatureGraphStyle.tempToColor(lastObservedTemp) }
        ctx.canvas.drawCircle(clampedX, fetchY, dotRadius, localDotPaint)
        ctx.canvas.drawCircle(clampedX, fetchY, dotRadius, ctx.paints.ringPaint)
        ctx.canvas.drawCircle(clampedX, fetchY, dotRadius + ctx.paints.ringPaint.strokeWidth / 2f, ctx.paints.outerRingPaint)

        val valueLabel = TemperatureGraphStyle.formatTemp(lastObservedTemp) + "°"
        val valueWidth = ctx.paints.valueTextPaint.measureText(valueLabel)
        val sideGap = dpToPx(ctx.context, TemperatureGraphStyle.FETCH_DOT_SIDE_GAP_DP * ctx.labelScale)
        val aboveGap = dpToPx(ctx.context, TemperatureGraphStyle.FETCH_DOT_ABOVE_GAP_DP * ctx.labelScale)
        val baselineOffset = ctx.paints.valueTextPaint.textSize / 3f
        val vAscent = fontAscent(ctx.paints.valueTextPaint)
        val vDescent = fontDescent(ctx.paints.valueTextPaint)
        val valueLayout = resolveValueLabelLayout(clampedX, fetchY, dotRadius, valueWidth, sideGap, aboveGap, ctx.widthPx, baselineOffset, vAscent, vDescent)

        if (valueLayout != null) {
            val localValuePaint = Paint(ctx.paints.valueTextPaint).apply { textAlign = valueLayout.align }
            ctx.canvas.drawText(valueLabel, valueLayout.x, valueLayout.y, localValuePaint)
            drawnBounds.add(valueLayout.bounds)
        }

        val ageMinutes = ctx.fetchTime?.let { Duration.between(it, ctx.currentTime).toMinutes() } ?: 0L
        val ageLabel = formatAgeLabel(ageMinutes, Duration.between(hours.first().dateTime, hours.last().dateTime).toHours())

        var finalAgeY: Float? = null
        if (ageLabel != null) {
            val sAscent = fontAscent(ctx.paints.stalenessTextPaint)
            val sDescent = fontDescent(ctx.paints.stalenessTextPaint)
            val ageWidth = ctx.paints.stalenessTextPaint.measureText(ageLabel)
            val padding = dpToPx(ctx.context, TemperatureGraphStyle.FETCH_DOT_SIDE_GAP_DP * ctx.labelScale)
            val leaderLinePaint = ctx.paints.actualLeaderLinePaint
            val allBounds = ctx.drawnLabelBounds + drawnBounds
            val minorOverlapThreshold = ctx.paints.stalenessTextPaint.textSize * 0.40f

            val initial = resolveStalenessInitialLayout(clampedX, fetchY, dotRadius, padding, ageWidth, sAscent, sDescent, ctx.heightPx, allBounds, minorOverlapThreshold)
            var placeAbove = initial.placeAbove
            var ageBaselineY = initial.baselineY
            var bounds = initial.bounds
            var collision = GraphLabelPlacementUtils.maxVerticalOverlap(bounds, allBounds) > minorOverlapThreshold

            var step = 0
            while (collision && step < 15) {
                step++
                val bump = dpToPx(ctx.context, TemperatureGraphStyle.FETCH_DOT_ABOVE_GAP_DP * ctx.labelScale)
                ageBaselineY += if (placeAbove) -bump else bump
                bounds.offsetTo(clampedX - ageWidth / 2f, ageBaselineY + sAscent)
                collision = GraphLabelPlacementUtils.maxVerticalOverlap(bounds, allBounds) > minorOverlapThreshold
            }

            if (step > 2) {
                val lineEndY = if (placeAbove) bounds.bottom else bounds.top
                val lineStartY = if (placeAbove) fetchY - dotRadius else fetchY + dotRadius
                ctx.canvas.drawLine(clampedX, lineStartY, clampedX, lineEndY, leaderLinePaint)
            }

            ctx.canvas.drawText(ageLabel, clampedX, ageBaselineY, ctx.paints.stalenessTextPaint)
            drawnBounds.add(bounds)
            finalAgeY = ageBaselineY
        }

        ctx.onFetchDotResolved?.invoke(FetchDotDebug(observedAt, clampedX, fetchY, true, if (ageLabel != null) "$valueLabel ($ageLabel)" else valueLabel, ctx.paints.valueTextPaint.color, if (ageLabel != null) ctx.paints.stalenessTextPaint.color else null, finalAgeY))
        return drawnBounds
    }

    suspend fun renderGraph(
        context: Context,
        hours: List<HourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        appliedDelta: Float? = null,
        observedAt: Long? = null,
        lastObservedTemp: Float? = null,
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
        onFetchDotResolved: ((FetchDotDebug) -> Unit)? = null,
        onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)? = null,
        onGhostLineDebug: ((GhostLineDebug) -> Unit)? = null,
        onPointsResolved: ((PointsDebug) -> Unit)? = null,
        onActualLineResolved: ((ActualLineDebug) -> Unit)? = null,
    ): Bitmap {
        currentCoroutineContext().ensureActive()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (hours.isEmpty()) {
            Log.w(TAG, "renderGraph: empty hours list, returning blank bitmap (${widthPx}x${heightPx})")
            return bitmap
        }

        val timings = RenderTimings()
        timings.mark("start")

        val labelScale = bitmapScale.coerceIn(0.5f, 1f)
        val paints = ensurePaints(context, labelScale)
        val density = context.resources.displayMetrics.density
        timings.mark("paints")

        val (minTemp, maxTemp, tempRange) = GraphLayout.computeScaling(hours)
        val layout = GraphLayout.computeLayout(context, heightPx, labelScale)
        timings.mark("layout")

        val minTimeEpoch = hours.firstOrNull()?.dateTime?.toEpochSecond(ZoneOffset.UTC) ?: 0L
        val maxTimeEpoch = hours.lastOrNull()?.dateTime?.toEpochSecond(ZoneOffset.UTC) ?: 0L
        val timeRangeHours = if (maxTimeEpoch > minTimeEpoch) (maxTimeEpoch - minTimeEpoch) / 3600f else hours.size.toFloat() - 1f
        val hourWidth = widthPx.toFloat() / timeRangeHours.coerceAtLeast(1f)

        val update = computePoints(
            hours, minTemp, tempRange, layout.graphTop, layout.graphHeight, layout.graphBottom,
            hourWidth, minTimeEpoch, currentTime, appliedDelta, observedAt, lastObservedTemp, widthPx, onPointsResolved
        )
        timings.mark("points")

        currentCoroutineContext().ensureActive()
        val ctx = RenderContext.create(
            context, canvas, widthPx, heightPx, density, labelScale, minTemp, maxTemp, tempRange,
            layout, hourWidth, minTimeEpoch, update, lastObservedTemp, appliedDelta, observedAt,
            paints, currentTime, onGhostLineDebug, onActualLineResolved, onLabelPlaced,
            onDayLabelPlaced, onFetchDotResolved
        )

        onActualLineResolved?.invoke(
            ActualLineDebug(
                endX = update.actualVisiblePoints.lastOrNull()?.first,
                endY = update.actualVisiblePoints.lastOrNull()?.second,
                pointCount = update.actualVisiblePoints.size,
                anchoredToFetchDot = update.fetchDotX != null &&
                    update.actualVisiblePoints.lastOrNull()?.first?.let { abs(it - update.fetchDotX) <= 0.5f } == true,
            ),
        )

        drawFillAndCurves(ctx, update.expectedFillPath, hours)
        timings.mark("curves")

        val drawnIconBounds = mutableListOf<RectF>()
        drawHourLabelsAndIcons(ctx, hours, drawnIconBounds)
        timings.mark("icons")

        val fetchDotPreBounds = computeFetchDotBounds(ctx, hours)
        ctx.drawnLabelBounds.addAll(fetchDotPreBounds)
        placeTemperatureLabels(ctx, hours, drawnIconBounds)
        placeDayLabels(ctx, hours, drawnIconBounds)
        timings.mark("labels")

        val fetchDotBounds = drawFetchDot(ctx, hours)
        ctx.drawnLabelBounds.addAll(fetchDotBounds)

        GraphRenderUtils.drawNowIndicator(
            canvas, if (update.nowIndicatorVisible) update.nowX else null, ctx.graphTop, ctx.graphHeight,
            paints.currentTimePaint, paints.nowLabelTextPaint, ctx.drawnLabelBounds + drawnIconBounds
        ) { dpToPx(context, it) }
        timings.mark("decorations")

        timings.log(widthPx, heightPx, hours.size, TAG)

        return bitmap
    }
}
