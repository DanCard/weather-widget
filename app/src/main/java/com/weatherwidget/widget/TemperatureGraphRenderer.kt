package com.weatherwidget.widget

import com.weatherwidget.shared.graph.*
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

    private const val YESTERDAY_DELTA_LABEL_PAD_DP = 6f
    private const val GHOST_LINE_LABEL_PAD_DP = 4f
    private const val GHOST_LINE_LABEL_GAP_DP = 2.5f
    // Ghost-like: faint, translucent white that echoes the ghost line, but opaque enough to read.
    private const val GHOST_LINE_LABEL_ALPHA = 110
    // Smaller than the forecast/actual temp labels so it reads as a wispier annotation.
    private const val GHOST_LINE_LABEL_SIZE_SCALE = 0.7f
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
        if (appliedDelta != null && abs(appliedDelta) >= MIN_GHOST_LINE_DELTA && fetchDotX != null) {
            val expectedY = lastObservedTemp?.let { ctx.tempToY(it) }
            if (expectedY != null) ctx.onGhostLineDebug?.invoke(GhostLineDebug(fetchDotX, expectedY))

            ctx.canvas.save()
            val clipStart = fetchDotX.coerceAtLeast(0f)
            ctx.canvas.clipRect(clipStart, 0f, ctx.widthPx.toFloat(), ctx.heightPx.toFloat())
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
            isDateLabel = { it.isDateLabel },
        ) { index, iconRect ->
            val hour = hours[index]
            hour.iconRes?.let { res ->
                androidx.core.content.ContextCompat.getDrawable(ctx.context, res)?.let { drawable ->
                    drawnIconBounds.add(iconRect)
                    drawable.setBounds(iconRect.left.toInt(), iconRect.top.toInt(), iconRect.right.toInt(), iconRect.bottom.toInt())
                    if (!hour.isRainy && !hour.isMixed) {
                        drawable.setTint(when {
                            hour.isNight -> HourlyGraphDefaults.ICON_TINT_NIGHT
                            hour.isTwilight -> HourlyGraphDefaults.ICON_TINT_TWILIGHT
                            hour.isSunny -> HourlyGraphDefaults.ICON_TINT_SUNNY
                            else -> HourlyGraphDefaults.ICON_TINT_DEFAULT
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
        fetchDotBounds: List<RectF>,
        numColumns: Int,
    ) {
        val paint = ctx.paints.actualTempLabelTextPaint
        val textMetrics = object : LabelTextMetrics {
            override fun width(text: String, isFuture: Boolean): Float {
                val measurePaint = if (isFuture) ctx.paints.forecastTempLabelTextPaint else ctx.paints.actualTempLabelTextPaint
                return measurePaint.measureText(text)
            }
            override val ascent: Float = fontAscent(paint)
            override val descent: Float = fontDescent(paint)
        }

        val neutralIconBounds = drawnIconBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) }
        // Fetch-dot value/age/ring rects are HARD obstacles so the engine flips a colliding label
        // (e.g. a valley forecast LOW) above the curve instead of drawing it over the pink dot value.
        val fetchDotHardBounds = fetchDotBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) }

        val placements = TemperatureLabelEngine.computePlacements(
            hours = hours,
            widthPx = ctx.widthPx,
            heightPx = ctx.heightPx,
            density = ctx.density,
            originalPoints = ctx.originalPoints,
            forecastPoints = ctx.forecastPoints,
            actualVisiblePoints = ctx.actualVisiblePoints,
            transitionX = ctx.transitionX,
            fetchDotX = ctx.fetchDotX,
            lastObservedTemp = ctx.lastObservedTemp,
            observedAt = ctx.observedAt,
            effectiveActualEndIndex = ctx.effectiveActualEndIndex,
            fetchTime = ctx.fetchTime,
            numColumns = numColumns,
            tempToY = { ctx.tempToY(it) },
            metrics = textMetrics,
            drawnIconBounds = neutralIconBounds,
            reservedHardBounds = fetchDotHardBounds
        )

        for (p in placements) {
            val isFuture = p.isFuture
            val labelPaint = if (isFuture) {
                val hour = hours[p.index.coerceAtMost(hours.lastIndex)]
                Paint(ctx.paints.forecastTempLabelTextPaint).also {
                    it.color = com.weatherwidget.util.WeatherConditionColors.forecastColor(hour.isSunny, hour.isRainy, hour.isMixed, hour.isNight, hour.isTwilight)
                }
            } else ctx.paints.actualTempLabelTextPaint

            val leaderLinePaint = if (isFuture) {
                Paint(ctx.paints.forecastLeaderLinePaint).also { it.color = TemperatureGraphStyle.withAlpha(labelPaint.color, 80) }
            } else ctx.paints.actualLeaderLinePaint

            if (p.drawLeaderLine) {
                ctx.canvas.drawLine(p.x, p.leaderFromY, p.x, p.leaderToY, leaderLinePaint)
            }
            ctx.canvas.drawText(p.text, p.x, p.baselineY, labelPaint)

            val seriesLabel = if (isFuture) "forecast" else "actual"
            val colorHex = TemperatureGraphStyle.formatColorHex(labelPaint.color)
            val debug = LabelPlacementDebug(
                index = p.index,
                role = p.role,
                temperature = p.displayTemperature,
                rawTemperature = p.rawTemperature,
                x = p.x,
                y = p.baselineY,
                placedAbove = p.placedAbove,
                series = seriesLabel,
                colorFamily = seriesLabel,
                hexColor = colorHex,
                reason = p.reason,
                displacementSteps = p.displacementSteps
            )
            if (shouldLogPlacement(p.role)) {
                Log.d(TAG, "LabelPlacementDebug: $debug")
            }
            ctx.onLabelPlaced?.invoke(debug)

            val tw = labelPaint.measureText(p.text)
            val b = RectF(p.x - tw / 2f, p.baselineY + textMetrics.ascent, p.x + tw / 2f, p.baselineY + textMetrics.descent)
            ctx.drawnLabelBounds.add(b)
        }
    }

    /**
     * Draws the "+0.4 from yesterday" delta into empty graph space, at the staleness/age font size and in
     * thermostat color. Formatting, the zoomed-in (≤12h span) gate, and empty-space placement all live in
     * shared [YesterdayDeltaLabel] so Android and desktop stay consistent; this only measures text, hands
     * over a visible-curve sampler, and draws. The number itself is computed upstream (the renderer's
     * windowed [hours] may not reach back 24h when zoomed in).
     */
    private fun placeYesterdayDeltaLabel(ctx: RenderContext, hours: List<HourData>, deltaFromYesterday: Float?) {
        if (deltaFromYesterday == null) return
        if (ctx.fetchDotX == null) return
        val currentTemp = ctx.lastObservedTemp ?: return
        if (hours.size < 2) return
        val spanHours = java.time.Duration.between(hours.first().dateTime, hours.last().dateTime).toHours()

        val paint = ctx.paints.stalenessTextPaint
        val text = YesterdayDeltaLabel.format(deltaFromYesterday)
        val metrics = YesterdayDeltaLabel.Metrics(
            width = paint.measureText(text),
            ascent = fontAscent(paint),
            descent = fontDescent(paint),
        )
        val obstacles = ctx.drawnLabelBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) }
        val placement = YesterdayDeltaLabel.place(
            delta = deltaFromYesterday,
            currentTemp = currentTemp,
            spanHours = spanHours,
            plot = GraphRect(0f, ctx.graphTop, ctx.widthPx.toFloat(), ctx.graphBottom),
            drawnBounds = obstacles,
            curveYAt = { x -> sampleVisibleCurveY(ctx, x) },
            metrics = metrics,
            padPx = dpToPx(ctx.context, YESTERDAY_DELTA_LABEL_PAD_DP),
        ) ?: return

        val labelPaint = Paint(paint).apply {
            color = placement.colorArgb
            textAlign = Paint.Align.CENTER
        }
        ctx.canvas.drawText(placement.text, placement.centerX, placement.baselineY, labelPaint)
        ctx.drawnLabelBounds.add(RectF(placement.box.left, placement.box.top, placement.box.right, placement.box.bottom))
    }

    /** Y of the *visible* curve at [x]: the actual line left of the transition, the forecast line right. */
    private fun sampleVisibleCurveY(ctx: RenderContext, x: Float): Float? {
        val transitionX = ctx.transitionX
        val useActual = transitionX != null && x <= transitionX && ctx.actualVisiblePoints.isNotEmpty()
        val points = if (useActual) ctx.actualVisiblePoints else ctx.forecastPoints
        if (points.isEmpty()) return null
        return interpolateYAtX(points, x)
    }

    /**
     * Labels the **ghost line** (forecast + observed delta) with its expected temperature, to one
     * decimal, snapped to an hour mark on the right half so it reads against the footer hour labels
     * ("at 6 PM → 69.4°"). Drawn only in the narrow view and only where there's free space — gating,
     * right-half/hour-mark selection, and empty-space placement all live in shared [GhostLineLabel].
     * Styled ghost-like: faint, translucent white that echoes the line it sits on.
     *
     * Gated identically to the ghost line itself ([drawFillAndCurves]) so the label never appears
     * without its line.
     */
    private fun placeGhostLineLabel(ctx: RenderContext, hours: List<HourData>) {
        val appliedDelta = ctx.appliedDelta
        val fetchDotX = ctx.fetchDotX
        if (!(appliedDelta != null && abs(appliedDelta) >= MIN_GHOST_LINE_DELTA && fetchDotX != null)) return
        if (hours.size < 2 || ctx.expectedPoints.size != hours.size) return
        val spanHours = java.time.Duration.between(hours.first().dateTime, hours.last().dateTime).toHours()

        // Only ghost-region hours (right of the fetch dot, where the line is actually drawn).
        // If fetchDotX is off left (<0), treat entire view as ghost region.
        val candidates = hours.indices.mapNotNull { i ->
            val (x, ghostY) = ctx.expectedPoints[i]
            if (x <= fetchDotX + X_COORDINATE_MATCH_TOLERANCE) return@mapNotNull null
            GhostLineLabel.Candidate(
                x = x,
                ghostY = ghostY,
                expectedTemp = ctx.smoothedExpectedTemps[i],
                hasHourLabel = hours[i].showLabel,
            )
        }
        if (candidates.isEmpty()) return

        val paint = Paint(ctx.paints.forecastTempLabelTextPaint).apply {
            color = withAlpha(Color.WHITE, GHOST_LINE_LABEL_ALPHA)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
            textSize *= GHOST_LINE_LABEL_SIZE_SCALE
        }
        val metrics = GhostLineLabel.Metrics(
            // Widest candidate so the recorded collision box always covers the drawn text.
            width = candidates.maxOf { paint.measureText(GhostLineLabel.format(it.expectedTemp)) },
            ascent = fontAscent(paint),
            descent = fontDescent(paint),
        )
        val obstacles = ctx.drawnLabelBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) }
        val placements = GhostLineLabel.placeAll(
            candidates = candidates,
            spanHours = spanHours,
            plot = GraphRect(0f, ctx.graphTop, ctx.widthPx.toFloat(), ctx.graphBottom),
            drawnBounds = obstacles,
            curveYAt = { x -> sampleVisibleCurveY(ctx, x) },
            metrics = metrics,
            padPx = dpToPx(ctx.context, GHOST_LINE_LABEL_PAD_DP),
            gapPx = dpToPx(ctx.context, GHOST_LINE_LABEL_GAP_DP),
        )

        // Permanent, logcat-only trace (Log.v never persists to app_logs) of the ghost-label decision:
        // every future-hour candidate (x@temp, * = has footer label) and which of them got a label.
        // These graph-label bugs recur — keep this; do not mark "remove after".
        Log.v(TAG, "GhostLineLabel: span=${spanHours}h candidates=[" +
            candidates.joinToString { "${it.x.roundToInt()}@${GhostLineLabel.format(it.expectedTemp)}${if (it.hasHourLabel) "*" else ""}" } +
            "] -> placed=${placements.size}: [" +
            placements.joinToString { "${it.centerX.roundToInt()}=${it.text}" } + "]")

        for (placement in placements) {
            ctx.canvas.drawText(placement.text, placement.centerX, placement.baselineY, paint)
            ctx.drawnLabelBounds.add(RectF(placement.box.left, placement.box.top, placement.box.right, placement.box.bottom))
        }
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
        val collision = GraphLabelPlacementUtils.maxVerticalOverlap(
            GraphRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
            existingBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) }
        ) > minorOverlapThreshold
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
        // Do not draw/fetch-dot when the anchor is off-screen (negative or beyond width).
        // This prevents the dot from "sticking" to the edge when scrolling the now dot out of view.
        if (fetchDotX < 0f || fetchDotX > ctx.widthPx) return null
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
            var collision = GraphLabelPlacementUtils.maxVerticalOverlap(GraphRect(bounds.left, bounds.top, bounds.right, bounds.bottom), allBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) }) > s.minorOverlapThreshold

            var step = 0
            while (collision && step < MAX_STALENESS_DISPLACEMENT_STEPS) {
                step++
                val bump = dpToPx(ctx.context, TemperatureGraphStyle.FETCH_DOT_ABOVE_GAP_DP * ctx.labelScale)
                ageBaselineY += if (placeAbove) -bump else bump
                bounds.offsetTo(layout.clampedX - s.ageWidth / 2f, ageBaselineY + s.sAscent)
                collision = GraphLabelPlacementUtils.maxVerticalOverlap(GraphRect(bounds.left, bounds.top, bounds.right, bounds.bottom), allBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) }) > s.minorOverlapThreshold
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
        deltaFromYesterday: Float? = null,
        numColumns: Int = 0,
        job: Job? = null,
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
        onFetchDotResolved: ((FetchDotDebug) -> Unit)? = null,
        onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)? = null,
        onGhostLineDebug: ((GhostLineDebug) -> Unit)? = null,
        onPointsResolved: ((PointsDebug) -> Unit)? = null,
        onActualLineResolved: ((ActualLineDebug) -> Unit)? = null,
        showErrorWatermark: Boolean = false,
        errorSourceLabel: String? = null,
        errorCode: String? = null,
        errorFailureTimeMs: Long? = null,
    ): Bitmap {
        job?.ensureActive()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (hours.isEmpty()) {
            Log.w(TAG, "renderGraph: empty hours list, returning blank bitmap (${widthPx}x${heightPx})")
            if (showErrorWatermark) {
                val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
                GraphRenderUtils.drawErrorWatermark(canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity, errorSourceLabel, errorCode, errorFailureTimeMs)
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

        // Draw Now Line early so it's behind all labels and curves (lowest z-order)
        GraphRenderUtils.drawNowLine(
            canvas, if (update.nowIndicatorVisible) update.nowX else null, ctx.graphTop, ctx.graphHeight,
            paints.currentTimePaint
        )

        drawFillAndCurves(ctx, update.expectedFillPath, hours)
        timings.mark("curves")

        val drawnIconBounds = drawHourLabelsAndIcons(ctx, hours, numColumns)
        timings.mark("icons")

        val fetchDotPreBounds = computeFetchDotBounds(ctx, hours)
        ctx.drawnLabelBounds.addAll(fetchDotPreBounds)
        placeTemperatureLabels(ctx, hours, drawnIconBounds, fetchDotPreBounds, numColumns)
        placeDayLabels(ctx, hours, drawnIconBounds)
        timings.mark("labels")

        val fetchDotBounds = drawFetchDot(ctx, hours)
        ctx.drawnLabelBounds.addAll(fetchDotBounds)

        placeYesterdayDeltaLabel(ctx, hours, deltaFromYesterday)
        placeGhostLineLabel(ctx, hours)

        GraphRenderUtils.drawNowIndicator(
            canvas = canvas,
            nowX = if (update.nowIndicatorVisible) update.nowX else null,
            graphTop = ctx.graphTop,
            graphHeight = ctx.graphHeight,
            currentTimePaint = paints.currentTimePaint,
            nowLabelTextPaint = paints.nowLabelTextPaint,
            drawnBounds = ctx.drawnLabelBounds + drawnIconBounds,
            drawLine = false,
            dpToPx = { dpToPx(context, it) }
        )
        timings.mark("decorations")

        timings.log(widthPx, heightPx, hours.size, TAG)

        if (showErrorWatermark) {
            val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
            GraphRenderUtils.drawErrorWatermark(canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity, errorSourceLabel, errorCode, errorFailureTimeMs)
        }

        return bitmap
    }
}
