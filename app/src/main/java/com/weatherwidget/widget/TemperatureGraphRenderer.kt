package com.weatherwidget.widget

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import android.content.Context
import android.graphics.*
import android.util.Log
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
import kotlin.math.roundToInt
import com.weatherwidget.util.WeatherConditionColors
import com.weatherwidget.BuildConfig

object TemperatureGraphRenderer {
    private const val TAG = "TempGraphRenderer"

    private inline fun debug(msg: () -> String) {
        Log.d(TAG, msg())
    }

    private const val MIN_GHOST_LINE_DELTA = 0.1f
    private const val MAX_LEADER_DISPLACEMENT_STEPS = 3
    private const val X_COORDINATE_MATCH_TOLERANCE = 0.5f
    private const val TRANSITION_CLIP_EXTRA_DP = 1f
    private const val MIN_INTERPOLATION_SPAN = 0.0001f
    private const val CURVE_AVOIDANCE_MARGIN_PX = 0.5f
    private const val CURVE_AVOIDANCE_CLEAR_PX = 1.5f
    private const val CURVE_AVOIDANCE_ALLOWED_DIP_DP = 5f

    private val CURVE_AVOIDANCE_ROLES: Set<TemperatureRole> = setOf(
        TemperatureRole.ACTUAL_END,
        TemperatureRole.ACTUAL_HIGH,
        TemperatureRole.ACTUAL_LOW,
        TemperatureRole.HIGH,
        TemperatureRole.LOW,
        TemperatureRole.LOCAL,
        TemperatureRole.START,
        TemperatureRole.END,
    )

    private val LOGGED_ROLES: Set<TemperatureRole> = setOf(
        TemperatureRole.ACTUAL_LOW, TemperatureRole.LOW,
        TemperatureRole.ACTUAL_HIGH, TemperatureRole.HIGH,
        TemperatureRole.ACTUAL_END, TemperatureRole.LOCAL,
        TemperatureRole.START, TemperatureRole.END,
    )
    private fun shouldLogPlacement(role: TemperatureRole): Boolean = role in LOGGED_ROLES

    private const val VALUE_NEIGHBOR_WINDOW = 5
    private const val SIGNIFICANT_MAX_GAP = 1.0f

    private fun prefersAbovePlacement(candidate: TempLabelCandidate): Boolean {
        val temps = candidate.labelTemps
        val i = candidate.index
        if (i !in temps.indices) return true
        val v = temps[i]
        val lo = kotlin.math.max(0, i - VALUE_NEIGHBOR_WINDOW)
        val hi = min(temps.lastIndex, i + VALUE_NEIGHBOR_WINDOW)
        var nearMax = v
        for (k in lo..hi) {
            val t = temps[k]
            if (t > nearMax) nearMax = t
        }
        return (nearMax - v) < SIGNIFICANT_MAX_GAP
    }

    private data class CurveIntrusion(val minY: Float, val maxY: Float) {
        val isEmpty: Boolean get() = minY > maxY
        companion object {
            val NONE = CurveIntrusion(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
            fun merge(a: CurveIntrusion, b: CurveIntrusion): CurveIntrusion = when {
                a.isEmpty -> b
                b.isEmpty -> a
                else -> CurveIntrusion(min(a.minY, b.minY), kotlin.math.max(a.maxY, b.maxY))
            }
        }
    }

    private fun curveIntrusionInLabel(
        points: List<Pair<Float, Float>>,
        bounds: RectF,
    ): CurveIntrusion {
        if (points.size < 2) return CurveIntrusion.NONE
        val left = bounds.left - CURVE_AVOIDANCE_MARGIN_PX
        val right = bounds.right + CURVE_AVOIDANCE_MARGIN_PX
        val top = bounds.top - CURVE_AVOIDANCE_MARGIN_PX
        val bottom = bounds.bottom + CURVE_AVOIDANCE_MARGIN_PX
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val segMinX = min(a.first, b.first)
            val segMaxX = kotlin.math.max(a.first, b.first)
            if (segMaxX < left || segMinX > right) continue
            val span = (b.first - a.first)
            val ySegMin: Float
            val ySegMax: Float
            if (abs(span) < MIN_INTERPOLATION_SPAN) {
                ySegMin = a.second
                ySegMax = a.second
            } else {
                val xL = kotlin.math.max(segMinX, left)
                val xR = min(segMaxX, right)
                val tL = ((xL - a.first) / span).coerceIn(0f, 1f)
                val tR = ((xR - a.first) / span).coerceIn(0f, 1f)
                val yL = a.second + (b.second - a.second) * tL
                val yR = a.second + (b.second - a.second) * tR
                ySegMin = min(yL, yR)
                ySegMax = kotlin.math.max(yL, yR)
            }
            if (ySegMax < top || ySegMin > bottom) continue
            val clipMin = kotlin.math.max(ySegMin, top)
            val clipMax = min(ySegMax, bottom)
            if (clipMin < minY) minY = clipMin
            if (clipMax > maxY) maxY = clipMax
        }
        return CurveIntrusion(minY, maxY)
    }

    private fun combinedCurveIntrusion(ctx: RenderContext, bounds: RectF): CurveIntrusion {
        val a = curveIntrusionInLabel(ctx.actualVisiblePoints, bounds)
        val f = curveIntrusionInLabel(ctx.forecastPoints, bounds)
        return CurveIntrusion.merge(a, f)
    }

    private fun isCurveGrazeWithinAllowedDip(
        bounds: RectF,
        intrusion: CurveIntrusion,
        placeAbove: Boolean,
        allowedDipPx: Float,
    ): Boolean {
        if (intrusion.isEmpty) return true
        val intrusionDepth =
            if (placeAbove) {
                bounds.bottom - intrusion.minY
            } else {
                intrusion.maxY - bounds.top
            }
        return intrusionDepth <= allowedDipPx
    }

    private const val STALENESS_MINOR_OVERLAP_RATIO = 0.40f
    private const val MAX_STALENESS_DISPLACEMENT_STEPS = 15
    private const val STALENESS_LEADER_LINE_MIN_STEPS = 2
    private const val VALUE_LABEL_BASELINE_DIVISOR = 3f
    private const val SECONDS_PER_HOUR = 3600f

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

    private fun computePoints(
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
        job: Job? = null,
        onPointsResolved: ((PointsDebug) -> Unit)?,
    ): RenderContextUpdate {
        job?.ensureActive()
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
            job?.ensureActive()
            val pointEpoch = hours[index].dateTime.toEpochSecond(ZoneOffset.UTC)
            val x = ((pointEpoch - minTimeEpoch) / SECONDS_PER_HOUR) * hourWidth
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
            val idx = originalPoints.indexOfLast { it.first <= transitionX + TRANSITION_CLIP_EXTRA_DP }
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
                job = job,
            )
        val (actualPath, _) = GraphRenderUtils.buildSmoothCurveAndFillPaths(anchoredActualPoints, graphBottom)

        return RenderContextUpdate(
            smoothedForecastTemps, smoothedExpectedTemps, originalPoints, forecastPoints, expectedPoints,
            originalPath, actualPath, anchoredActualPoints, expectedPath, expectedFillPath, forecastPath, forecastFillPath, forecastSegmentPaths,
            nowX, nowIndicatorVisible, fetchTime, fetchDotX,
            anchorDelta, transitionX, effectiveActualEndIndex
        )
    }

    private fun buildAnchoredActualPoints(
        originalPoints: List<Pair<Float, Float>>,
        transitionX: Float?,
        fetchDotX: Float?,
        lastObservedTemp: Float?,
        minTemp: Float,
        tempRange: Float,
        graphTop: Float,
        graphHeight: Float,
        job: Job? = null,
    ): List<Pair<Float, Float>> {
        job?.ensureActive()
        if (transitionX == null || originalPoints.isEmpty()) return emptyList()

        val anchoredToFetchDot = fetchDotX != null && abs(fetchDotX - transitionX) <= X_COORDINATE_MATCH_TOLERANCE && lastObservedTemp != null
        val terminalY =
            if (anchoredToFetchDot) {
                TemperatureGraphStyle.tempToY(lastObservedTemp, graphTop, graphHeight, minTemp, tempRange)
            } else {
                interpolateYAtX(originalPoints, transitionX)
            }

        val visible = originalPoints.filter { it.first < transitionX - X_COORDINATE_MATCH_TOLERANCE }.toMutableList()
        val terminalPoint = transitionX to terminalY
        visible += terminalPoint
        return visible
    }

    private fun interpolateYAtX(
        points: List<Pair<Float, Float>>,
        targetX: Float,
    ): Float {
        val exact = points.firstOrNull { abs(it.first - targetX) <= X_COORDINATE_MATCH_TOLERANCE }
        if (exact != null) return exact.second

        val afterIndex = points.indexOfFirst { it.first > targetX }
        return when {
            afterIndex <= 0 -> points.first().second
            else -> {
                val before = points[afterIndex - 1]
                val after = points[afterIndex]
                val span = (after.first - before.first).coerceAtLeast(MIN_INTERPOLATION_SPAN)
                val fraction = ((targetX - before.first) / span).coerceIn(0f, 1f)
                before.second + (after.second - before.second) * fraction
            }
        }
    }

    private fun drawFillAndCurves(ctx: RenderContext, expectedFillPath: Path, hours: List<HourData>) {
        val paints = ctx.paints
        paints.expectedFillPaint.shader = buildTempGradient(
            ctx.graphTop, ctx.graphBottom, ctx.minTemp, ctx.maxTemp, ctx.tempRange, alphaTop = TemperatureGraphStyle.EXPECTED_FILL_ALPHA_TOP, alphaBottom = TemperatureGraphStyle.EXPECTED_FILL_ALPHA_BOTTOM
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

        val dashOn = dpToPx(ctx.context, TemperatureGraphStyle.FORECAST_DASH_ON_DP)
        val dashOff = dpToPx(ctx.context, TemperatureGraphStyle.FORECAST_DASH_OFF_DP)
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
            ctx.canvas.clipRect(0f, 0f, transitionX + dpToPx(ctx.context, TRANSITION_CLIP_EXTRA_DP), ctx.heightPx.toFloat())
            ctx.canvas.drawPath(ctx.actualPath, paints.actualLinePaint)
            ctx.canvas.restore()
        }
    }

    private fun drawHourLabelsAndIcons(
        ctx: RenderContext,
        hours: List<HourData>,
        numColumns: Int,
    ): List<RectF> {
        val drawnIconBounds = mutableListOf<RectF>()
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
            iconSize = ctx.iconSize.toFloat(),
            iconTextGapDp = GraphRenderUtils.footerIconGapDp(numColumns),
            hasIcon = { it.iconRes != null },
        ) { index, iconRect ->
            val hour = hours[index]
            hour.iconRes?.let { res ->
                androidx.core.content.ContextCompat.getDrawable(ctx.context, res)?.let { drawable ->
                    drawnIconBounds.add(iconRect)
                    drawable.setBounds(iconRect.left.toInt(), iconRect.top.toInt(), iconRect.right.toInt(), iconRect.bottom.toInt())
                    if (!hour.isRainy && !hour.isMixed) {
                        drawable.setTint(when {
                            hour.isNight -> Color.parseColor(HourlyGraphDefaults.ICON_TINT_NIGHT)
                            hour.isTwilight -> Color.parseColor(HourlyGraphDefaults.ICON_TINT_TWILIGHT)
                            hour.isSunny -> Color.parseColor(HourlyGraphDefaults.ICON_TINT_SUNNY)
                            else -> Color.parseColor(HourlyGraphDefaults.ICON_TINT_DEFAULT)
                        })
                    }
                    drawable.draw(ctx.canvas)
                }
            }
        }
        return drawnIconBounds
    }

    private fun placeTemperatureLabels(
        ctx: RenderContext,
        hours: List<HourData>,
        drawnIconBounds: List<RectF>,
        numColumns: Int,
    ) {
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, ctx.transitionX, ctx.effectiveActualEndIndex, ctx.fetchTime)
        val specialCandidates = TemperatureLabelResolver.collectLabelCandidates(hours, extrema, ctx.effectiveActualEndIndex, ctx.transitionX, ctx.observedAt, numColumns).toMutableList()

        val drawnLabelMetas = mutableListOf<PlacedLabelMeta>()
        val labelAscent = fontAscent(ctx.paints.actualTempLabelTextPaint)
        val labelDescent = fontDescent(ctx.paints.actualTempLabelTextPaint)
        val labelHeight = labelDescent - labelAscent
        
        val gapDp = GraphLabelPlacementUtils.getLabelGapDp(isFallback = false)

        TemperatureLabelResolver.sortLabelCandidates(specialCandidates)

        val forcedAboveLows = computeForcedAboveLowIndices(specialCandidates)

        for (candidate in specialCandidates) {
            placeSingleLabel(ctx, candidate, hours, drawnLabelMetas, drawnIconBounds, gapDp, labelAscent, labelDescent, labelHeight, forceAbove = candidate.index in forcedAboveLows)
        }
        ctx.drawnLabelBounds.addAll(drawnLabelMetas.map { it.bounds })
    }

    /**
     * Identifies the actual (observed) low when it should flip to ABOVE placement so it stacks in
     * value order over a colder nearby low: warmer actual low above, colder forecast/daily low
     * below (matching the points' vertical order). Only [TemperatureRole.ACTUAL_LOW] is lifted —
     * forecast/daily lows keep the default below-cascade, so the existing valley de-collision still
     * applies when no actual low is involved. The flip fires only if another low-role candidate
     * sits within [GraphLabelPlacementUtils.NEARBY_LABEL_WINDOW] indices with a strictly lower
     * *rounded* value; equal rounded values do not flip.
     */
    private fun computeForcedAboveLowIndices(candidates: List<TempLabelCandidate>): Set<Int> {
        val lowRoles = setOf(
            TemperatureRole.LOW, TemperatureRole.ACTUAL_LOW,
            TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_LOW,
        )
        val lows = candidates.filter { it.role in lowRoles }
        if (lows.size < 2) return emptySet()
        val window = GraphLabelPlacementUtils.NEARBY_LABEL_WINDOW
        val forced = mutableSetOf<Int>()
        for (c in lows) {
            if (c.role != TemperatureRole.ACTUAL_LOW) continue
            val cVal = c.labelTemps[c.index].roundToInt()
            val hasLowerNeighbor = lows.any { other ->
                other.index != c.index &&
                    abs(other.index - c.index) <= window &&
                    other.labelTemps[other.index].roundToInt() < cVal
            }
            if (hasLowerNeighbor) forced.add(c.index)
        }
        return forced
    }

    private fun placeSingleLabel(
        ctx: RenderContext,
        candidate: TempLabelCandidate,
        hours: List<HourData>,
        drawnLabelMetas: MutableList<PlacedLabelMeta>,
        drawnIconBounds: List<RectF>,
        gapDp: GraphLabelPlacementUtils.LabelGapDp,
        labelAscent: Float,
        labelDescent: Float,
        labelHeight: Float,
        forceAbove: Boolean = false,
    ) {
        val idx = candidate.index
        val temps = candidate.labelTemps
        val placement = TemperatureLabelResolver.resolveCandidatePlacement(ctx, hours, candidate)
        if (placement == null) return

        val valueBasedRoles = candidate.role == TemperatureRole.ACTUAL_END ||
            candidate.role == TemperatureRole.LOCAL ||
            candidate.role == TemperatureRole.START ||
            candidate.role == TemperatureRole.END
        val preferAbove = when {
            forceAbove -> true
            valueBasedRoles -> prefersAbovePlacement(candidate)
            else -> !placement.isValley
        }
        val directions = if (preferAbove) listOf(true, false) else listOf(false, true)
        var placed = false
        var forceBaselineY = Float.NaN
        var forceBounds: RectF? = null
        var forceX = placement.clampedX
        var forceDrawBelow = false
        var forceStep = 0
        // Set once the below-cascade decides this (warmer) valley label should be lifted above a
        // colder one instead of stacking on it. Stops the cascade from re-firing and steers the
        // remaining loop iterations toward the above-placement path.
        var flipDecided = false

        if (candidate.role in CURVE_AVOIDANCE_ROLES) {
            placed = tryExactFitCurveAvoidance(
                ctx = ctx,
                candidate = candidate,
                placement = placement,
                directions = directions,
                gapDp = gapDp,
                labelAscent = labelAscent,
                labelDescent = labelDescent,
                drawnLabelMetas = drawnLabelMetas,
                drawnIconBounds = drawnIconBounds,
                idx = idx,
                temps = temps,
            )
            if (placed) return
        }

        val gapAbovePx = dpToPx(ctx.context, gapDp.aboveDp)
        val gapBelowPx = dpToPx(ctx.context, gapDp.belowDp)
        val allowedCurveDipPx = dpToPx(ctx.context, CURVE_AVOIDANCE_ALLOWED_DIP_DP)

        outer@ for (step in 0..MAX_LEADER_DISPLACEMENT_STEPS) {
            for (placeAbove in directions) {
                if (flipDecided && !placeAbove) continue

                val currentGapPx = if (placeAbove) gapAbovePx else gapBelowPx
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

                val drawnBoundsList = drawnLabelMetas.map { it.bounds }
                val overlapsLabel = drawnBoundsList.any { RectF.intersects(it, bounds) }
                val overlapsIcon = drawnIconBounds.any { RectF.intersects(it, bounds) }
                val labelOverlap = if (overlapsLabel) GraphLabelPlacementUtils.maxVerticalOverlap(bounds, drawnBoundsList) else 0f
                val iconOverlap = if (overlapsIcon) GraphLabelPlacementUtils.maxVerticalOverlap(bounds, drawnIconBounds) else 0f

                val currentIconRatio = if (!placeAbove && placement.isValley) GraphLabelPlacementUtils.MINOR_OVERLAP_ICON_RATIO else GraphLabelPlacementUtils.MINOR_OVERLAP_HEIGHT_RATIO

                val allowMinorLabelOverlap = overlapsLabel && GraphLabelPlacementUtils.shouldAllowMinorOverlap(candidate.role, labelOverlap, labelHeight)
                val allowMinorIconOverlap = overlapsIcon && GraphLabelPlacementUtils.isMinorOverlapEligible(candidate.role) && iconOverlap <= labelHeight * currentIconRatio

                val curveAvoidanceEligible = candidate.role in CURVE_AVOIDANCE_ROLES
                val curveIntrusion = if (curveAvoidanceEligible) combinedCurveIntrusion(ctx, bounds) else CurveIntrusion.NONE
                val allowFlippedAboveCurveGraze =
                    flipDecided &&
                        placeAbove &&
                        curveAvoidanceEligible &&
                        isCurveGrazeWithinAllowedDip(bounds, curveIntrusion, placeAbove = true, allowedDipPx = allowedCurveDipPx)
                val overlapsCurve = curveAvoidanceEligible && !curveIntrusion.isEmpty && !allowFlippedAboveCurveGraze

                val hasCollision = (overlapsLabel && !allowMinorLabelOverlap) || (overlapsIcon && !allowMinorIconOverlap) || overlapsCurve

                if (hasCollision && !placeAbove && placement.isValley && step == 0 && !flipDecided) {
                    val outcome = tryValleyBelowCascade(
                        ctx = ctx,
                        candidate = candidate,
                        placement = placement,
                        verticalPlacement = verticalPlacement,
                        drawnLabelMetas = drawnLabelMetas,
                        drawnIconBounds = drawnIconBounds,
                        labelHeight = labelHeight,
                    )
                    when (outcome) {
                        is ValleyCascadeOutcome.Below -> {
                            val cascadeResult = outcome.result
                            ctx.canvas.drawText(placement.label, cascadeResult.x, cascadeResult.baselineY, placement.labelPaint)
                            drawnLabelMetas.add(PlacedLabelMeta(cascadeResult.bounds, isValleyBelow = true, role = candidate.role, temperature = temps[idx]))
                            val seriesLabel = if (placement.isFuture) "forecast" else "actual"
                            val debug = LabelPlacementDebug(idx, candidate.role, temps[idx], candidate.rawTemperature, cascadeResult.x, cascadeResult.baselineY, false, seriesLabel, seriesLabel, TemperatureGraphStyle.formatColorHex(placement.labelPaint.color), cascadeResult.reason, 0)
                            if (shouldLogPlacement(candidate.role)) {
                                Log.d(TAG, "LabelPlacementDebug: $debug")
                            }
                            ctx.onLabelPlaced?.invoke(debug)
                            placed = true
                            break@outer
                        }
                        ValleyCascadeOutcome.FlipAbove -> {
                            // Don't place (or record a forced-below fallback) here; steer to the
                            // above iteration so this warmer label stacks on top of the colder one.
                            flipDecided = true
                            continue
                        }
                        ValleyCascadeOutcome.None -> Unit // fall through to normal rejection handling
                    }
                }

                if (shouldLogPlacement(candidate.role)) {
                    val rejectReason = when {
                        !onScreen -> "offscreen(top=${String.format("%.1f", bounds.top)}, bot=${String.format("%.1f", bounds.bottom)}, hPx=${ctx.heightPx})"
                        overlapsLabel && !allowMinorLabelOverlap -> "label-collision(overlap=${String.format("%.1f", labelOverlap)}, h=${String.format("%.1f", labelHeight)}, ratio=${String.format("%.2f", labelOverlap / labelHeight)})"
                        overlapsIcon && !allowMinorIconOverlap -> "icon-collision(overlap=${String.format("%.1f", iconOverlap)}, h=${String.format("%.1f", labelHeight)}, ratio=${String.format("%.2f", iconOverlap / labelHeight)}, iconRatio=${String.format("%.2f", currentIconRatio)})"
                        overlapsCurve -> "curve-collision(bounds=${String.format("%.1f,%.1f,%.1f,%.1f", bounds.left, bounds.top, bounds.right, bounds.bottom)})"
                        else -> null
                    }
                    if (rejectReason != null) {
                        Log.d(TAG, "LabelRejected: role=${candidate.role} idx=$idx above=$placeAbove step=$step pointY=${String.format("%.1f", placement.sy)} reason=$rejectReason")
                    }
                }

                if (placement.isEssential && forceBounds == null) { 
                    forceBaselineY = baselineY
                    forceBounds = bounds
                    forceX = placement.clampedX
                    forceDrawBelow = !placeAbove
                    forceStep = step 
                }
                
                if (!hasCollision) {
                    if (step > 0) {
                        val lineEndY = if (!placeAbove) bounds.top else bounds.bottom
                        ctx.canvas.drawLine(placement.clampedX, placement.sy, placement.clampedX, lineEndY, placement.leaderLinePaint)
                    }
                    ctx.canvas.drawText(placement.label, placement.clampedX, baselineY, placement.labelPaint)
                    drawnLabelMetas.add(PlacedLabelMeta(bounds, isValleyBelow = !placeAbove && placement.isValley, role = candidate.role, temperature = temps[idx]))
                    val seriesLabel = if (placement.isFuture) "forecast" else "actual"
                    val reasonBase = if (!placeAbove) "below" else "above"
                    val reason = if (step > 0) "$reasonBase+$step" else reasonBase
                    val debug = LabelPlacementDebug(idx, candidate.role, temps[idx], candidate.rawTemperature, placement.clampedX, baselineY, placeAbove, seriesLabel, seriesLabel, TemperatureGraphStyle.formatColorHex(placement.labelPaint.color), reason, step)
                    if (shouldLogPlacement(candidate.role)) {
                        Log.d(TAG, "LabelPlacementDebug: $debug")
                    }
                    ctx.onLabelPlaced?.invoke(debug)
                    placed = true
                    break@outer
                }
            }
        }
        if (!placed && placement.isEssential && forceBounds != null) {
            if (forceStep > 0) {
                val lineEndY = if (forceDrawBelow) forceBounds.top else forceBounds.bottom
                ctx.canvas.drawLine(forceX, placement.sy, forceX, lineEndY, placement.leaderLinePaint)
            }
            ctx.canvas.drawText(placement.label, forceX, forceBaselineY, placement.labelPaint)
            drawnLabelMetas.add(PlacedLabelMeta(forceBounds, isValleyBelow = forceDrawBelow, role = candidate.role, temperature = temps[idx]))
            val seriesLabel = if (placement.isFuture) "forecast" else "actual"
            val debugForced = LabelPlacementDebug(idx, candidate.role, temps[idx], candidate.rawTemperature, forceX, forceBaselineY, !forceDrawBelow, seriesLabel, seriesLabel, TemperatureGraphStyle.formatColorHex(placement.labelPaint.color), "FORCED", forceStep)
            if (shouldLogPlacement(candidate.role)) {
                Log.d(TAG, "LabelPlacementDebug: $debugForced")
            }
            ctx.onLabelPlaced?.invoke(debugForced)
        }
    }

    private fun tryExactFitCurveAvoidance(
        ctx: RenderContext,
        candidate: TempLabelCandidate,
        placement: TemperatureLabelResolver.CandidatePlacement,
        directions: List<Boolean>,
        gapDp: GraphLabelPlacementUtils.LabelGapDp,
        labelAscent: Float,
        labelDescent: Float,
        drawnLabelMetas: MutableList<PlacedLabelMeta>,
        drawnIconBounds: List<RectF>,
        idx: Int,
        temps: List<Float>,
    ): Boolean {
        val allowedDipPx = dpToPx(ctx.context, CURVE_AVOIDANCE_ALLOWED_DIP_DP)
        // Walk directions in preference order. At each one, decide which blocker is active:
        //   - clean       → return false so main loop places at the natural position (no leader)
        //   - label/icon  → exact-fit can't help here; try the next direction
        //   - curve only  → run exact-fit (this is the case we're optimizing)
        for (placeAbove in directions) {
            val outcome = tryExactFitForDirection(
                ctx = ctx,
                candidate = candidate,
                placement = placement,
                placeAbove = placeAbove,
                gapDp = gapDp,
                labelAscent = labelAscent,
                labelDescent = labelDescent,
                drawnLabelMetas = drawnLabelMetas,
                drawnIconBounds = drawnIconBounds,
                idx = idx,
                temps = temps,
                allowedDipPx = allowedDipPx,
            )
            when (outcome) {
                ExactFitOutcome.NATURAL_FITS -> return false
                ExactFitOutcome.PLACED -> return true
                ExactFitOutcome.LABEL_OR_ICON_BLOCKED -> continue
                ExactFitOutcome.GAVE_UP -> return false
            }
        }
        return false
    }

    private enum class ExactFitOutcome { NATURAL_FITS, PLACED, LABEL_OR_ICON_BLOCKED, GAVE_UP }

    private sealed class ExactFitBlockerResult {
        object NaturalFits : ExactFitBlockerResult()
        object LabelOrIconBlocked : ExactFitBlockerResult()
        data class CurveOnly(val intrusion: CurveIntrusion, val baseBounds: RectF, val baseGapPx: Float) : ExactFitBlockerResult()
    }

    private fun checkExactFitBlockers(
        ctx: RenderContext,
        candidate: TempLabelCandidate,
        placement: TemperatureLabelResolver.CandidatePlacement,
        placeAbove: Boolean,
        gapDp: GraphLabelPlacementUtils.LabelGapDp,
        labelAscent: Float,
        labelDescent: Float,
        drawnLabelMetas: MutableList<PlacedLabelMeta>,
        drawnIconBounds: List<RectF>,
        idx: Int,
    ): ExactFitBlockerResult {
        val baseGapPx = if (placeAbove) dpToPx(ctx.context, gapDp.aboveDp) else dpToPx(ctx.context, gapDp.belowDp)
        val baseV = GraphLabelPlacementUtils.computeLabelVerticalPlacement(
            pointY = placement.sy, placeAbove = placeAbove,
            gapPx = baseGapPx, textAscent = labelAscent, textDescent = labelDescent
        )
        val baseBounds = RectF(
            placement.clampedX - placement.textWidth / 2f, baseV.top,
            placement.clampedX + placement.textWidth / 2f, baseV.bottom
        )
        val intrusion = combinedCurveIntrusion(ctx, baseBounds)
        val baseOverlapsLabel = drawnLabelMetas.any { RectF.intersects(it.bounds, baseBounds) }
        val baseOverlapsIcon = drawnIconBounds.any { RectF.intersects(it, baseBounds) }

        val labelHeight = labelDescent - labelAscent
        val drawnLabelBoundsList = drawnLabelMetas.map { it.bounds }
        val labelOverlapPx = if (baseOverlapsLabel) GraphLabelPlacementUtils.maxVerticalOverlap(baseBounds, drawnLabelBoundsList) else 0f
        val iconOverlapPx = if (baseOverlapsIcon) GraphLabelPlacementUtils.maxVerticalOverlap(baseBounds, drawnIconBounds) else 0f
        val iconOverlapRatio = if (!placeAbove && placement.isValley) GraphLabelPlacementUtils.MINOR_OVERLAP_ICON_RATIO else GraphLabelPlacementUtils.MINOR_OVERLAP_HEIGHT_RATIO
        val allowMinorLabelOverlap = baseOverlapsLabel && GraphLabelPlacementUtils.shouldAllowMinorOverlap(candidate.role, labelOverlapPx, labelHeight)
        val allowMinorIconOverlap = baseOverlapsIcon && GraphLabelPlacementUtils.isMinorOverlapEligible(candidate.role) && iconOverlapPx <= labelHeight * iconOverlapRatio
        val effectiveLabelBlocker = baseOverlapsLabel && !allowMinorLabelOverlap
        val effectiveIconBlocker = baseOverlapsIcon && !allowMinorIconOverlap

        Log.d(TAG, "ExactFitPreCheck: role=${candidate.role} idx=$idx placeAbove=$placeAbove anchorY=${String.format("%.1f", placement.sy)} baseBounds=${baseBounds.toShortString()} intrusion=${if (intrusion.isEmpty) "none" else "minY=${String.format("%.1f", intrusion.minY)} maxY=${String.format("%.1f", intrusion.maxY)}"} labelBlocker=$effectiveLabelBlocker iconBlocker=$effectiveIconBlocker allowedDip=${String.format("%.1f", dpToPx(ctx.context, CURVE_AVOIDANCE_ALLOWED_DIP_DP))}")

        if (intrusion.isEmpty && !effectiveLabelBlocker && !effectiveIconBlocker) {
            return ExactFitBlockerResult.NaturalFits
        }
        if (effectiveLabelBlocker || effectiveIconBlocker) {
            return ExactFitBlockerResult.LabelOrIconBlocked
        }
        return ExactFitBlockerResult.CurveOnly(intrusion, baseBounds, baseGapPx)
    }

    private fun tryExactFitForDirection(
        ctx: RenderContext,
        candidate: TempLabelCandidate,
        placement: TemperatureLabelResolver.CandidatePlacement,
        placeAbove: Boolean,
        gapDp: GraphLabelPlacementUtils.LabelGapDp,
        labelAscent: Float,
        labelDescent: Float,
        drawnLabelMetas: MutableList<PlacedLabelMeta>,
        drawnIconBounds: List<RectF>,
        idx: Int,
        temps: List<Float>,
        allowedDipPx: Float,
    ): ExactFitOutcome {
        val blockerResult = checkExactFitBlockers(ctx, candidate, placement, placeAbove, gapDp, labelAscent, labelDescent, drawnLabelMetas, drawnIconBounds, idx)
        when (blockerResult) {
            is ExactFitBlockerResult.NaturalFits -> return ExactFitOutcome.NATURAL_FITS
            is ExactFitBlockerResult.LabelOrIconBlocked -> return ExactFitOutcome.LABEL_OR_ICON_BLOCKED
            is ExactFitBlockerResult.CurveOnly -> {
                val extra = if (placeAbove) {
                    blockerResult.baseBounds.bottom - blockerResult.intrusion.minY + CURVE_AVOIDANCE_CLEAR_PX - allowedDipPx
                } else {
                    blockerResult.intrusion.maxY + CURVE_AVOIDANCE_CLEAR_PX - allowedDipPx - blockerResult.baseBounds.top
                }
                if (extra <= 0f) return ExactFitOutcome.GAVE_UP

                val newGapPx = blockerResult.baseGapPx + extra
                val newV = GraphLabelPlacementUtils.computeLabelVerticalPlacement(
                    pointY = placement.sy, placeAbove = placeAbove,
                    gapPx = newGapPx, textAscent = labelAscent, textDescent = labelDescent
                )
                val newBounds = RectF(
                    placement.clampedX - placement.textWidth / 2f, newV.top,
                    placement.clampedX + placement.textWidth / 2f, newV.bottom
                )
                if (newBounds.top < 0f || newBounds.bottom > ctx.heightPx) {
                    Log.d(TAG, "ExactFitPreCheck: role=${candidate.role} idx=$idx FAILED offscreen newBounds=${newBounds.toShortString()}")
                    return ExactFitOutcome.GAVE_UP
                }
                val overlapsLabel = drawnLabelMetas.any { RectF.intersects(it.bounds, newBounds) }
                val overlapsIcon = drawnIconBounds.any { RectF.intersects(it, newBounds) }
                if (overlapsLabel || overlapsIcon) {
                    Log.d(TAG, "ExactFitPreCheck: role=${candidate.role} idx=$idx FAILED overlapsLabel=$overlapsLabel overlapsIcon=$overlapsIcon")
                    return ExactFitOutcome.GAVE_UP
                }
                val residual = combinedCurveIntrusion(ctx, newBounds)
                if (!residual.isEmpty) {
                    val residualDepth = if (placeAbove) newBounds.bottom - residual.maxY else residual.minY - newBounds.top
                    if (residualDepth > allowedDipPx + 1f) {
                        Log.d(TAG, "ExactFitPreCheck: role=${candidate.role} idx=$idx FAILED residualCurveIntrusion depth=${String.format("%.1f", residualDepth)} allowedDip=${String.format("%.1f", allowedDipPx)}")
                        return ExactFitOutcome.GAVE_UP
                    }
                }

                val lineEndY = if (placeAbove) newBounds.bottom else newBounds.top
                ctx.canvas.drawLine(placement.clampedX, placement.sy, placement.clampedX, lineEndY, placement.leaderLinePaint)
                ctx.canvas.drawText(placement.label, placement.clampedX, newV.baselineY, placement.labelPaint)
                drawnLabelMetas.add(PlacedLabelMeta(newBounds, isValleyBelow = !placeAbove && placement.isValley, role = candidate.role, temperature = temps[idx]))

                val seriesLabel = if (placement.isFuture) "forecast" else "actual"
                val reasonBase = if (placeAbove) "above" else "below"
                val reason = "$reasonBase+curveFit(${String.format("%.1f", extra)}px)"
                val debug = LabelPlacementDebug(idx, candidate.role, temps[idx], candidate.rawTemperature, placement.clampedX, newV.baselineY, placeAbove, seriesLabel, seriesLabel, TemperatureGraphStyle.formatColorHex(placement.labelPaint.color), reason, 1)
                Log.d(TAG, "LabelPlacementDebug: $debug")
                ctx.onLabelPlaced?.invoke(debug)
                return ExactFitOutcome.PLACED
            }
        }
    }

    private data class CascadeResult(
        val x: Float,
        val baselineY: Float,
        val bounds: RectF,
        val reason: String,
    )

    /**
     * Outcome of attempting to place a colliding valley label below the line.
     *  - [Below]     → place at the returned position (horizontal shift or accepted minor overlap).
     *  - [FlipAbove] → the only below resolution left is a heavy overlap on a *colder* low, and
     *                  this label is strictly warmer; lift it above so warmer-on-top matches the
     *                  points' vertical order. Caller re-routes it to the above-placement path.
     *  - [None]      → no below resolution found (equivalent to the old `null`).
     */
    private sealed class ValleyCascadeOutcome {
        data class Below(val result: CascadeResult) : ValleyCascadeOutcome()
        object FlipAbove : ValleyCascadeOutcome()
        object None : ValleyCascadeOutcome()
    }

    private fun tryValleyBelowCascade(
        ctx: RenderContext,
        candidate: TempLabelCandidate,
        placement: TemperatureLabelResolver.CandidatePlacement,
        verticalPlacement: GraphLabelPlacementUtils.LabelVerticalPlacement,
        drawnLabelMetas: List<PlacedLabelMeta>,
        drawnIconBounds: List<RectF>,
        labelHeight: Float,
    ): ValleyCascadeOutcome {
        val centerX = placement.clampedX
        val halfWidth = placement.textWidth / 2f

        val centeredBounds = RectF(centerX - halfWidth, verticalPlacement.top, centerX + halfWidth, verticalPlacement.bottom)
        val drawnBoundsList = drawnLabelMetas.map { it.bounds }

        val collidingMeta = drawnLabelMetas.firstOrNull { RectF.intersects(it.bounds, centeredBounds) }
        if (collidingMeta == null) return ValleyCascadeOutcome.None

        val horizontalOverlap = maxOf(0f, minOf(centeredBounds.right, collidingMeta.bounds.right) - maxOf(centeredBounds.left, collidingMeta.bounds.left))
        val verticalOverlap = maxOf(0f, minOf(centeredBounds.bottom, collidingMeta.bounds.bottom) - maxOf(centeredBounds.top, collidingMeta.bounds.top))

        val shiftAmount = horizontalOverlap * GraphLabelPlacementUtils.VALLEY_HORIZONTAL_SHIFT_FRACTION
        for (shiftSign in listOf(-1, 1)) {
            val shiftedX = centerX + shiftSign * shiftAmount
            val shiftedBounds = RectF(shiftedX - halfWidth, verticalPlacement.top, shiftedX + halfWidth, verticalPlacement.bottom)
            val onScreen = shiftedBounds.top >= 0f && shiftedBounds.bottom <= ctx.heightPx &&
                shiftedBounds.left >= 0f && shiftedBounds.right <= ctx.widthPx
            if (!onScreen) continue

            val overlapsLabel = drawnBoundsList.any { RectF.intersects(it, shiftedBounds) }
            val overlapsIcon = drawnIconBounds.any { RectF.intersects(it, shiftedBounds) }
            if (!overlapsLabel && !overlapsIcon) {
                return ValleyCascadeOutcome.Below(
                    CascadeResult(
                        x = shiftedX,
                        baselineY = verticalPlacement.baselineY,
                        bounds = shiftedBounds,
                        reason = "below-shifted",
                    )
                )
            }
        }

        val overlapRatio = verticalOverlap / labelHeight
        if (overlapRatio <= GraphLabelPlacementUtils.VALLEY_BELOW_LABEL_OVERLAP_RATIO) {
            if (shouldLogPlacement(candidate.role)) {
                Log.d(TAG, "LabelCascade: role=${candidate.role} option2-accepted ratio=${String.format("%.2f", overlapRatio)} threshold=${GraphLabelPlacementUtils.VALLEY_BELOW_LABEL_OVERLAP_RATIO}")
            }
            return ValleyCascadeOutcome.Below(
                CascadeResult(
                    x = centerX,
                    baselineY = verticalPlacement.baselineY,
                    bounds = centeredBounds,
                    reason = "below-relaxed",
                )
            )
        }

        if (collidingMeta.isValleyBelow && overlapRatio <= GraphLabelPlacementUtils.VALLEY_VS_VALLEY_OVERLAP_RATIO) {
            // Last resort: a heavy (>option2) overlap stacked on another below valley. Rather than
            // accept it, lift this label above the line when it is strictly warmer than the one it
            // collides with — warmer-on-top matches the points' vertical order. Equal rounded values
            // (and colder labels) keep the existing below-overlap behavior.
            val currentVal = candidate.labelTemps[candidate.index].roundToInt()
            val collidingVal = collidingMeta.temperature.roundToInt()
            if (currentVal > collidingVal) {
                if (shouldLogPlacement(candidate.role)) {
                    Log.d(TAG, "LabelCascade: role=${candidate.role} flip-above-warmer current=$currentVal colliding=$collidingVal collidingRole=${collidingMeta.role} ratio=${String.format("%.2f", overlapRatio)}")
                }
                return ValleyCascadeOutcome.FlipAbove
            }
            if (shouldLogPlacement(candidate.role)) {
                Log.d(TAG, "LabelCascade: role=${candidate.role} option1-accepted ratio=${String.format("%.2f", overlapRatio)} threshold=${GraphLabelPlacementUtils.VALLEY_VS_VALLEY_OVERLAP_RATIO} collidingRole=${collidingMeta.role}")
            }
            return ValleyCascadeOutcome.Below(
                CascadeResult(
                    x = centerX,
                    baselineY = verticalPlacement.baselineY,
                    bounds = centeredBounds,
                    reason = "below-valley-overlap",
                )
            )
        }

        if (shouldLogPlacement(candidate.role)) {
            Log.d(TAG, "LabelCascade: role=${candidate.role} all-options-exhausted ratio=${String.format("%.2f", overlapRatio)} collidingIsValleyBelow=${collidingMeta.isValleyBelow}")
        }

        return ValleyCascadeOutcome.None
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
        // clampedX is already screen-clamped by the caller, so horizontal bounds checks are not needed
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

    private data class StalenessPrecompute(
        val ageLabel: String,
        val ageWidth: Float,
        val sAscent: Float,
        val sDescent: Float,
        val padding: Float,
        val minorOverlapThreshold: Float,
    )

    private data class FetchDotLayout(
        val observedAt: Long,
        val clampedX: Float,
        val fetchY: Float,
        val dotRadius: Float,
        val outerRadius: Float,
        val lastObservedTemp: Float,
        val valueLabel: String,
        val valueLayout: ValueLabelLayout?,
        val staleness: StalenessPrecompute?,
    )

    private fun resolveFetchDotLayout(ctx: RenderContext, hours: List<HourData>): FetchDotLayout? {
        val observedAt = ctx.observedAt ?: return null
        val fetchDotX = ctx.fetchDotX ?: return null
        val lastObservedTemp = ctx.lastObservedTemp ?: return null
        val fetchY = ctx.tempToY(lastObservedTemp)
        val dotRadius = dpToPx(ctx.context, TemperatureGraphStyle.DOT_RADIUS_DP * ctx.labelScale)
        val clampedX = fetchDotX.coerceIn(dotRadius, ctx.widthPx - dotRadius)
        val outerRadius = dotRadius + ctx.paints.ringPaint.strokeWidth / 2f

        val valueLabel = TemperatureGraphStyle.formatTemp(lastObservedTemp) + "°"
        val valueWidth = ctx.paints.valueTextPaint.measureText(valueLabel)
        val sideGap = dpToPx(ctx.context, TemperatureGraphStyle.FETCH_DOT_SIDE_GAP_DP * ctx.labelScale)
        val aboveGap = dpToPx(ctx.context, TemperatureGraphStyle.FETCH_DOT_ABOVE_GAP_DP * ctx.labelScale)
        val baselineOffset = ctx.paints.valueTextPaint.textSize / VALUE_LABEL_BASELINE_DIVISOR
        val vAscent = fontAscent(ctx.paints.valueTextPaint)
        val vDescent = fontDescent(ctx.paints.valueTextPaint)
        val valueLayout = resolveValueLabelLayout(clampedX, fetchY, dotRadius, valueWidth, sideGap, aboveGap, ctx.widthPx, baselineOffset, vAscent, vDescent)

        val ageMinutes = ctx.fetchTime?.let { Duration.between(it, ctx.currentTime).toMinutes() } ?: 0L
        val ageLabel = formatAgeLabel(ageMinutes, Duration.between(hours.first().dateTime, hours.last().dateTime).toHours())
        val staleness = if (ageLabel != null) {
            StalenessPrecompute(
                ageLabel = ageLabel,
                ageWidth = ctx.paints.stalenessTextPaint.measureText(ageLabel),
                sAscent = fontAscent(ctx.paints.stalenessTextPaint),
                sDescent = fontDescent(ctx.paints.stalenessTextPaint),
                padding = dpToPx(ctx.context, TemperatureGraphStyle.FETCH_DOT_SIDE_GAP_DP * ctx.labelScale),
                minorOverlapThreshold = ctx.paints.stalenessTextPaint.textSize * STALENESS_MINOR_OVERLAP_RATIO,
            )
        } else null

        return FetchDotLayout(observedAt, clampedX, fetchY, dotRadius, outerRadius, lastObservedTemp, valueLabel, valueLayout, staleness)
    }

    private fun computeFetchDotBounds(ctx: RenderContext, hours: List<HourData>): List<RectF> {
        val layout = resolveFetchDotLayout(ctx, hours) ?: return emptyList()
        val bounds = mutableListOf<RectF>()
        bounds.add(RectF(layout.clampedX - layout.outerRadius, layout.fetchY - layout.outerRadius, layout.clampedX + layout.outerRadius, layout.fetchY + layout.outerRadius))
        layout.valueLayout?.let { bounds.add(it.bounds) }
        layout.staleness?.let { s ->
            bounds.add(resolveStalenessInitialLayout(layout.clampedX, layout.fetchY, layout.dotRadius, s.padding, s.ageWidth, s.sAscent, s.sDescent, ctx.heightPx, bounds, s.minorOverlapThreshold).bounds)
        }
        return bounds
    }

    private fun drawFetchDot(ctx: RenderContext, hours: List<HourData>): List<RectF> {
        val layout = resolveFetchDotLayout(ctx, hours) ?: return emptyList()
        val drawnBounds = mutableListOf<RectF>()

        val localDotPaint = Paint(ctx.paints.dotPaint).apply { color = TemperatureGraphStyle.tempToColor(layout.lastObservedTemp) }
        ctx.canvas.drawCircle(layout.clampedX, layout.fetchY, layout.dotRadius, localDotPaint)
        ctx.canvas.drawCircle(layout.clampedX, layout.fetchY, layout.dotRadius, ctx.paints.ringPaint)
        ctx.canvas.drawCircle(layout.clampedX, layout.fetchY, layout.outerRadius, ctx.paints.outerRingPaint)

        if (layout.valueLayout != null) {
            val localValuePaint = Paint(ctx.paints.valueTextPaint).apply { textAlign = layout.valueLayout.align }
            ctx.canvas.drawText(layout.valueLabel, layout.valueLayout.x, layout.valueLayout.y, localValuePaint)
            drawnBounds.add(layout.valueLayout.bounds)
        }

        var finalAgeY: Float? = null
        layout.staleness?.let { s ->
            val allBounds = ctx.drawnLabelBounds + drawnBounds
            val initial = resolveStalenessInitialLayout(layout.clampedX, layout.fetchY, layout.dotRadius, s.padding, s.ageWidth, s.sAscent, s.sDescent, ctx.heightPx, allBounds, s.minorOverlapThreshold)
            var placeAbove = initial.placeAbove
            var ageBaselineY = initial.baselineY
            var bounds = initial.bounds
            var collision = GraphLabelPlacementUtils.maxVerticalOverlap(bounds, allBounds) > s.minorOverlapThreshold

            var step = 0
            while (collision && step < MAX_STALENESS_DISPLACEMENT_STEPS) {
                step++
                val bump = dpToPx(ctx.context, TemperatureGraphStyle.FETCH_DOT_ABOVE_GAP_DP * ctx.labelScale)
                ageBaselineY += if (placeAbove) -bump else bump
                bounds.offsetTo(layout.clampedX - s.ageWidth / 2f, ageBaselineY + s.sAscent)
                collision = GraphLabelPlacementUtils.maxVerticalOverlap(bounds, allBounds) > s.minorOverlapThreshold
            }

            if (step > STALENESS_LEADER_LINE_MIN_STEPS) {
                val lineEndY = if (placeAbove) bounds.bottom else bounds.top
                val lineStartY = if (placeAbove) layout.fetchY - layout.dotRadius else layout.fetchY + layout.dotRadius
                ctx.canvas.drawLine(layout.clampedX, lineStartY, layout.clampedX, lineEndY, ctx.paints.actualLeaderLinePaint)
            }

            ctx.canvas.drawText(s.ageLabel, layout.clampedX, ageBaselineY, ctx.paints.stalenessTextPaint)
            drawnBounds.add(bounds)
            finalAgeY = ageBaselineY
        }

        ctx.onFetchDotResolved?.invoke(FetchDotDebug(layout.observedAt, layout.clampedX, layout.fetchY, true, if (layout.staleness != null) "${layout.valueLabel} (${layout.staleness.ageLabel})" else layout.valueLabel, ctx.paints.valueTextPaint.color, if (layout.staleness != null) ctx.paints.stalenessTextPaint.color else null, finalAgeY))
        return drawnBounds
    }

    fun renderGraph(
        context: Context,
        hours: List<HourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        appliedDelta: Float? = null,
        observedAt: Long? = null,
        lastObservedTemp: Float? = null,
        numColumns: Int = 0,
        job: Job? = null,
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
        onFetchDotResolved: ((FetchDotDebug) -> Unit)? = null,
        onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)? = null,
        onGhostLineDebug: ((GhostLineDebug) -> Unit)? = null,
        onPointsResolved: ((PointsDebug) -> Unit)? = null,
        onActualLineResolved: ((ActualLineDebug) -> Unit)? = null,
        showErrorWatermark: Boolean = false,
    ): Bitmap {
        job?.ensureActive()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (hours.isEmpty()) {
            Log.w(TAG, "renderGraph: empty hours list, returning blank bitmap (${widthPx}x${heightPx})")
            if (showErrorWatermark) {
                val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
                GraphRenderUtils.drawErrorWatermark(canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity)
            }
            return bitmap
        }

        val timings = RenderTimings()
        timings.mark("start")

        val labelScale = bitmapScale.coerceAtMost(1f)
        val paints = ensurePaints(context, labelScale)
        val density = context.resources.displayMetrics.density
        timings.mark("paints")

        val (minTemp, maxTemp, tempRange) = GraphLayout.computeScaling(hours)
        val footerIconSize = GraphRenderUtils.footerIconSize(paints.hourLabelTextPaint)
        val layout = GraphLayout.computeLayout(context, heightPx, labelScale, footerIconSize)
        timings.mark("layout")

        val minTimeEpoch = hours.firstOrNull()?.dateTime?.toEpochSecond(ZoneOffset.UTC) ?: 0L
        val maxTimeEpoch = hours.lastOrNull()?.dateTime?.toEpochSecond(ZoneOffset.UTC) ?: 0L
        val timeRangeHours = if (maxTimeEpoch > minTimeEpoch) (maxTimeEpoch - minTimeEpoch) / SECONDS_PER_HOUR else hours.size.toFloat() - 1f
        val hourWidth = widthPx.toFloat() / timeRangeHours.coerceAtLeast(1f)

        val update = computePoints(
            hours, minTemp, tempRange, layout.graphTop, layout.graphHeight, layout.graphBottom,
            hourWidth, minTimeEpoch, currentTime, appliedDelta, observedAt, lastObservedTemp, widthPx, job, onPointsResolved
        )
        timings.mark("points")

        job?.ensureActive()
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
                    update.actualVisiblePoints.lastOrNull()?.first?.let { abs(it - update.fetchDotX) <= X_COORDINATE_MATCH_TOLERANCE } == true,
            ),
        )

        drawFillAndCurves(ctx, update.expectedFillPath, hours)
        timings.mark("curves")

        val drawnIconBounds = drawHourLabelsAndIcons(ctx, hours, numColumns)
        timings.mark("icons")

        val fetchDotPreBounds = computeFetchDotBounds(ctx, hours)
        ctx.drawnLabelBounds.addAll(fetchDotPreBounds)
        placeTemperatureLabels(ctx, hours, drawnIconBounds, numColumns)
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

        if (showErrorWatermark) {
            val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
            GraphRenderUtils.drawErrorWatermark(canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity)
        }

        return bitmap
    }
}
