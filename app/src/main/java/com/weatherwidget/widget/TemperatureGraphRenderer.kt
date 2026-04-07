package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
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
import kotlin.math.round
import kotlin.math.max
import kotlin.math.roundToInt
import com.weatherwidget.util.WeatherConditionColors

object TemperatureGraphRenderer {
    private const val TAG = "TempGraphRenderer"
    private const val MAX_TEMP_LABEL_CANDIDATES = 6
    private val DENSE_TEMP_DIFF_THRESHOLDS = listOf(3, 4, 5) // Degrees

    private const val MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES = 2.5f
    private const val GRAPH_TOP_PADDING_DP = 8f
    private const val GRAPH_TO_FOOTER_GAP_DP = 1.8f
    private const val TOP_TEMP_BUFFER_RATIO = 0.1f
    private const val BOTTOM_TEMP_BUFFER_RATIO = 0.03f
    private const val MIN_TOP_TEMP_BUFFER_DEGREES = 3f
    private const val MIN_BOTTOM_TEMP_BUFFER_DEGREES = 2.5f
    private const val MIN_GHOST_LINE_DELTA = 0.1f
    private const val MINOR_OVERLAP_HEIGHT_RATIO = 0.45f

    data class HourData(
        val dateTime: LocalDateTime,
        val temperature: Float,          // Forecast temperature (drives the dashed forecast line)
        val label: String, // "12a", "1p", "2p"
        val iconRes: Int? = null,
        val isNight: Boolean = false,
        val isSunny: Boolean = false,
        val isRainy: Boolean = false,
        val isMixed: Boolean = false,
        val isCurrentHour: Boolean = false,
        val showLabel: Boolean = true, // Only at intervals
        val isActual: Boolean = false,           // True when actualTemperature is available
        val actualTemperature: Float? = null,    // Observed actual temp (past hours only)
        val isObservedActual: Boolean = false,   // Backed by a real blended/observed point, not carry-forward filler
    )

    private const val MAX_LEADER_DISPLACEMENT_STEPS = 3

    // Temperature-to-color thresholds
    private const val COLD_THRESHOLD = 50f
    private const val MILD_TEMP = 70f
    private const val HOT_THRESHOLD = 90f

    private val COLOR_COLD = Color.parseColor("#5AC8FA") // Blue
    private val COLOR_MILD = Color.parseColor("#E8A24E") // Golden amber
    private val COLOR_HOT = Color.parseColor("#FF6B35") // Warm orange
    private val COLOR_ACTUAL_LINE = WeatherConditionColors.OBSERVED  // Hot pink #FF3366
    private val COLOR_ACTUAL_LABEL = Color.parseColor("#FFB3C6")   // Light pink
    private val COLOR_FORECAST_LABEL = Color.parseColor("#C5DCFF")

    private fun tempToColor(temp: Float): Int {
        return when {
            temp <= COLD_THRESHOLD -> COLOR_COLD
            temp >= HOT_THRESHOLD -> COLOR_HOT
            temp <= MILD_TEMP -> blendColors(COLOR_COLD, COLOR_MILD, (temp - COLD_THRESHOLD) / (MILD_TEMP - COLD_THRESHOLD))
            else -> blendColors(COLOR_MILD, COLOR_HOT, (temp - MILD_TEMP) / (HOT_THRESHOLD - MILD_TEMP))
        }
    }

    private fun blendColors(
        c1: Int,
        c2: Int,
        fraction: Float,
    ): Int {
        val f = fraction.coerceIn(0f, 1f)
        val r = (Color.red(c1) * (1 - f) + Color.red(c2) * f).toInt()
        val g = (Color.green(c1) * (1 - f) + Color.green(c2) * f).toInt()
        val b = (Color.blue(c1) * (1 - f) + Color.blue(c2) * f).toInt()
        return Color.rgb(r, g, b)
    }

    private fun formatTemp(value: Float): String {
        val rounded = round(value * 10f) / 10f
        return if (rounded % 1f == 0f) {
            String.format("%.0f", rounded)
        } else {
            String.format("%.1f", rounded)
        }
    }

    private fun withAlpha(
        color: Int,
        alpha: Int,
    ): Int {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private class PaintSet(
        val density: Float,
        val labelScale: Float,
        val actualLinePaint: Paint,
        val forecastDashedPaint: Paint,
        val ghostPaint: Paint,
        val expectedFillPaint: Paint,
        val currentTimePaint: Paint,
        val hourLabelTextPaint: Paint,
        val actualTempLabelTextPaint: Paint,
        val forecastTempLabelTextPaint: Paint,
        val nowLabelTextPaint: Paint,
        val dayLabelTextPaint: Paint,
        val todayDayLabelPaint: Paint,
        val ringPaint: Paint,
        val outerRingPaint: Paint,
        val valueTextPaint: Paint,
        val stalenessTextPaint: Paint,
        val actualLeaderLinePaint: Paint,
        val forecastLeaderLinePaint: Paint,
    )

    private var cachedPaints: PaintSet? = null

    private fun ensurePaints(context: Context, labelScale: Float): PaintSet {
        val density = context.resources.displayMetrics.density
        val current = cachedPaints
        if (current != null && current.density == density && current.labelScale == labelScale) {
            return current
        }

        val actualLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = dpToPx(context, 1f)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = COLOR_ACTUAL_LINE
        }

        val forecastDashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = dpToPx(context, 1f)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = WeatherConditionColors.FORECAST_SUNNY // Default; overridden per-segment
            pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, 8f), dpToPx(context, 4f)), 0f)
        }

        val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 55
            strokeWidth = dpToPx(context, 1.2f)
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(0.1f, dpToPx(context, 4f)), 0f)
        }

        val expectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val currentTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9F0A")
            strokeWidth = dpToPx(context, 0.5f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, 4f), dpToPx(context, 3f)), 0f)
        }

        val hourLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#99FFFFFF")
            textSize = dpToPx(context, 19.5f * labelScale)
            textAlign = Paint.Align.CENTER
            setShadowLayer(dpToPx(context, 1f), 0f, dpToPx(context, 0.5f), Color.parseColor("#44000000"))
        }

        val actualTempLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACTUAL_LABEL
            textSize = dpToPx(context, 19.5f * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 2f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
        }

        val forecastTempLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_FORECAST_LABEL
            textSize = dpToPx(context, 19.5f * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 2f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
        }

        val nowLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BBFF9F0A")
            textSize = dpToPx(context, 15.5f * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 1f), 0f, 0f, Color.parseColor("#44000000"))
        }

        val dayLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#88FFFFFF")
            textSize = dpToPx(context, 19.5f * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val todayDayLabelPaint = Paint(dayLabelTextPaint).apply {
            color = Color.parseColor("#BBFF9F0A")
        }

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(context, 1.5f * labelScale)
        }

        val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44000000")
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(context, 0.5f * labelScale)
        }

        val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(COLOR_ACTUAL_LINE, 187) // BB alpha
            textSize = dpToPx(context, 19.5f * labelScale)
            textAlign = Paint.Align.LEFT
            setShadowLayer(dpToPx(context, 1f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
        }

        val stalenessTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(COLOR_ACTUAL_LINE, 136) // 88 alpha
            textSize = dpToPx(context, 12f * labelScale)
            textAlign = Paint.Align.CENTER
            setShadowLayer(dpToPx(context, 1f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
        }

        val actualLeaderLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(COLOR_ACTUAL_LABEL, 80)
            strokeWidth = dpToPx(context, 0.5f)
            style = Paint.Style.STROKE
        }

        val forecastLeaderLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(COLOR_FORECAST_LABEL, 80)
            strokeWidth = dpToPx(context, 0.5f)
            style = Paint.Style.STROKE
        }

        return PaintSet(
            density = density,
            labelScale = labelScale,
            actualLinePaint = actualLinePaint,
            forecastDashedPaint = forecastDashedPaint,
            ghostPaint = ghostPaint,
            expectedFillPaint = expectedFillPaint,
            currentTimePaint = currentTimePaint,
            hourLabelTextPaint = hourLabelTextPaint,
            actualTempLabelTextPaint = actualTempLabelTextPaint,
            forecastTempLabelTextPaint = forecastTempLabelTextPaint,
            nowLabelTextPaint = nowLabelTextPaint,
            dayLabelTextPaint = dayLabelTextPaint,
            todayDayLabelPaint = todayDayLabelPaint,
            ringPaint = ringPaint,
            outerRingPaint = outerRingPaint,
            valueTextPaint = valueTextPaint,
            stalenessTextPaint = stalenessTextPaint,
            actualLeaderLinePaint = actualLeaderLinePaint,
            forecastLeaderLinePaint = forecastLeaderLinePaint,
        ).also { cachedPaints = it }
    }

    /**
     * Build a vertical LinearGradient that maps Y positions to temperature colors.
     * graphTop = maxTemp, graphBottom = minTemp.
     */
    private fun buildTempGradient(
        graphTop: Float,
        graphBottom: Float,
        minTemp: Float,
        maxTemp: Float,
        tempRange: Float,
        alphaTop: Int = 255,
        alphaBottom: Int = 255,
    ): LinearGradient {
        // Map temperature thresholds to gradient positions (0.0 = graphTop/maxTemp, 1.0 = graphBottom/minTemp)
        fun tempToPos(t: Float): Float = ((maxTemp - t) / tempRange).coerceIn(0f, 1f)

        val stops = mutableListOf<Pair<Float, Int>>()

        // Always include endpoints
        stops.add(0f to tempToColor(maxTemp))
        stops.add(1f to tempToColor(minTemp))

        // Add intermediate stops at key thresholds if they fall within the temp range
        for (t in listOf(HOT_THRESHOLD, MILD_TEMP, COLD_THRESHOLD)) {
            if (t > minTemp && t < maxTemp) {
                stops.add(tempToPos(t) to tempToColor(t))
            }
        }

        // Sort by position and deduplicate
        stops.sortBy { it.first }
        val unique = stops.distinctBy { "%.4f".format(it.first) }

        val positions = unique.map { it.first }.toFloatArray()
        val colorsWithAlpha =
            unique.map { (pos, color) ->
                val alpha = (alphaTop + (alphaBottom - alphaTop) * pos).toInt().coerceIn(0, 255)
                withAlpha(color, alpha)
            }.toIntArray()

        return LinearGradient(
            0f,
            graphTop,
            0f,
            graphBottom,
            colorsWithAlpha,
            positions,
            Shader.TileMode.CLAMP,
        )
    }

    data class LabelPlacementDebug(
        val index: Int,
        val role: String,
        val temperature: Float,
        val rawTemperature: Float,
        val x: Float,
        val y: Float,
        val placedAbove: Boolean,
        val series: String = "",
        val colorFamily: String = "",
        val reason: String = "",
        val displacementSteps: Int = 0,
    )

    data class FetchDotDebug(
        val observedAt: Long,
        val fetchDotX: Float?,
        val fetchY: Float? = null,
        val withinWindow: Boolean,
        val ageText: String? = null,
        val valueColor: Int? = null,
        val stalenessColor: Int? = null,
    )

    data class GhostLineDebug(
        val startX: Float,
        val startY: Float,
    )

    data class ActualLineDebug(
        val endX: Float?,
        val endY: Float?,
        val pointCount: Int,
        val anchoredToFetchDot: Boolean,
    )

    data class DayLabelPlacementDebug(
        val side: String,       // "LEFT" or "RIGHT"
        val dayText: String,
        val date: LocalDate,
        val x: Float,
        val y: Float,
        val placement: String,  // "TOP", "MIDDLE", "BOTTOM"
        val isToday: Boolean,
    )

    data class PointsDebug(
        val original: List<Pair<Float, Float>>,
        val forecast: List<Pair<Float, Float>>,
        val expected: List<Pair<Float, Float>>,
    )

    private fun computeScaling(hours: List<HourData>): Triple<Float, Float, Float> {
        val allTemps = hours.map { it.temperature } + hours.mapNotNull { it.actualTemperature }
        val rawMin = allTemps.minOrNull() ?: 0f
        val rawMax = allTemps.maxOrNull() ?: 100f
        val rawRange = (rawMax - rawMin).coerceAtLeast(1f)

        val topBuffer = (rawRange * TOP_TEMP_BUFFER_RATIO).coerceAtLeast(MIN_TOP_TEMP_BUFFER_DEGREES)
        val bottomBuffer = (rawRange * BOTTOM_TEMP_BUFFER_RATIO).coerceAtLeast(MIN_BOTTOM_TEMP_BUFFER_DEGREES)
        val minTemp = rawMin - bottomBuffer
        val maxTemp = rawMax + topBuffer
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1f)
        Log.d(TAG, "Scaling: rawMin=$rawMin, rawMax=$rawMax, minTemp=$minTemp, maxTemp=$maxTemp, tempRange=$tempRange")
        return Triple(minTemp, maxTemp, tempRange)
    }

    private data class Layout(
        val topPadding: Float,
        val iconSize: Int,
        val footerTop: Float,
        val graphTop: Float,
        val graphBottom: Float,
        val graphHeight: Float,
        val iconTopPad: Float,
    )

    private fun computeLayout(context: Context, heightPx: Int, labelScale: Float): Layout {
        val topPadding = dpToPx(context, GRAPH_TOP_PADDING_DP)
        val iconSize = dpToPx(context, 16f).toInt()
        val labelHeight = dpToPx(context, 10f)
        val iconTopPad = dpToPx(context, 2f)
        val iconBottomPad = dpToPx(context, 1f)

        val graphTop = topPadding
        val footerTop = heightPx - labelHeight - iconBottomPad - iconSize - iconTopPad
        val graphBottom = (footerTop - dpToPx(context, GRAPH_TO_FOOTER_GAP_DP)).coerceAtLeast(graphTop + 1f)
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)
        Log.d(TAG, "Layout: heightPx=$heightPx, footerTop=$footerTop, graphTop=$graphTop, graphBottom=$graphBottom, graphHeight=$graphHeight")
        return Layout(topPadding, iconSize, footerTop, graphTop, graphBottom, graphHeight, iconTopPad)
    }

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
        onPointsResolved: ((PointsDebug) -> Unit)?,
    ): RenderContextUpdate {
        val effectiveDelta = appliedDelta ?: 0f
        val smoothedForecastTemps = hours.map { it.temperature }
        val actualTemps = hours.map { it.actualTemperature ?: (it.temperature + effectiveDelta) }

        val fetchTime = observedAt?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }
        val fetchIdx = fetchTime?.let { time -> hours.indexOfLast { !it.dateTime.isAfter(time) } } ?: -1
        val fetchFraction = if (fetchTime != null && fetchIdx != -1 && fetchIdx < smoothedForecastTemps.lastIndex) {
            Duration.between(hours[fetchIdx].dateTime, fetchTime).toMinutes() / 60f
        } else null

        val anchorDelta = effectiveDelta

        val smoothedExpectedTemps = smoothedForecastTemps.map { it + anchorDelta }

        val originalPoints = mutableListOf<Pair<Float, Float>>()
        val forecastPoints = mutableListOf<Pair<Float, Float>>()
        val expectedPoints = mutableListOf<Pair<Float, Float>>()

        hours.indices.forEach { index ->
            val pointEpoch = hours[index].dateTime.toEpochSecond(ZoneOffset.UTC)
            val x = ((pointEpoch - minTimeEpoch) / 3600f) * hourWidth
            val yTruth = graphTop + graphHeight * (1 - (actualTemps[index] - minTemp) / tempRange)
            originalPoints.add(x to yTruth)

            val yForecast = graphTop + graphHeight * (1 - (smoothedForecastTemps[index] - minTemp) / tempRange)
            forecastPoints.add(x to yForecast)

            val yExpected = graphTop + graphHeight * (1 - (smoothedExpectedTemps[index] - minTemp) / tempRange)
            expectedPoints.add(x to yExpected)
        }

        onPointsResolved?.invoke(PointsDebug(originalPoints, forecastPoints, expectedPoints))

        val tBezier0 = SystemClock.elapsedRealtime()
        val (originalPath, _) = GraphRenderUtils.buildSmoothCurveAndFillPaths(originalPoints, graphBottom)
        val tBezier1 = SystemClock.elapsedRealtime()
        val (expectedPath, expectedFillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(expectedPoints, graphBottom)
        val tBezier2 = SystemClock.elapsedRealtime()
        val (forecastPath, forecastFillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(forecastPoints, graphBottom)
        val forecastSegmentPaths = GraphRenderUtils.buildPerSegmentPaths(forecastPoints)
        val tBezier3 = SystemClock.elapsedRealtime()
        Log.d(TAG, "BEZIER_BREAKDOWN pts=${originalPoints.size}" +
            " actual=${tBezier1-tBezier0}ms expected=${tBezier2-tBezier1}ms forecast=${tBezier3-tBezier2}ms")

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

    private fun buildAnchoredActualPoints(
        originalPoints: List<Pair<Float, Float>>,
        transitionX: Float?,
        fetchDotX: Float?,
        lastObservedTemp: Float?,
        minTemp: Float,
        tempRange: Float,
        graphTop: Float,
        graphHeight: Float,
    ): List<Pair<Float, Float>> {
        if (transitionX == null || originalPoints.isEmpty()) return emptyList()

        val anchoredToFetchDot = fetchDotX != null && abs(fetchDotX - transitionX) <= 0.5f && lastObservedTemp != null
        val terminalY =
            if (anchoredToFetchDot) {
                graphTop + graphHeight * (1 - (lastObservedTemp!! - minTemp) / tempRange)
            } else {
                interpolateYAtX(originalPoints, transitionX)
            }

        val visible = originalPoints.filter { it.first < transitionX - 0.5f }.toMutableList()
        val terminalPoint = transitionX to terminalY
        if (visible.isEmpty()) {
            visible += terminalPoint
        } else {
            visible += terminalPoint
        }
        return visible
    }

    internal fun isMinorOverlapEligible(role: String): Boolean =
        role in setOf("LOW", "HIGH", "FORECAST_LOW", "FORECAST_HIGH", "ACTUAL_LOW", "ACTUAL_HIGH", "PAST_FORECAST_LOW", "PAST_FORECAST_HIGH", "START", "END", "LOCAL")

    internal fun shouldAllowMinorOverlap(
        role: String,
        overlapHeight: Float,
        labelHeight: Float,
    ): Boolean = isMinorOverlapEligible(role) && overlapHeight <= labelHeight * MINOR_OVERLAP_HEIGHT_RATIO

    internal fun maxVerticalOverlap(
        bounds: RectF,
        existingBounds: List<RectF>,
    ): Float {
        val intersect = RectF()
        return existingBounds.maxOfOrNull { existing ->
            if (intersect.setIntersect(existing, bounds)) intersect.height() else 0f
        } ?: 0f
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
            afterIndex == -1 -> points.last().second
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

        if (ctx.nowIndicatorVisible && ctx.appliedDelta != null && abs(ctx.appliedDelta) >= MIN_GHOST_LINE_DELTA && ctx.fetchDotX != null) {
            val expectedY = if (ctx.lastObservedTemp != null) {
                ctx.graphTop + ctx.graphHeight * (1 - (ctx.lastObservedTemp - ctx.minTemp) / ctx.tempRange)
            } else null
            if (expectedY != null) ctx.onGhostLineDebug?.invoke(GhostLineDebug(ctx.fetchDotX, expectedY))

            ctx.canvas.save()
            ctx.canvas.clipRect(ctx.fetchDotX, 0f, ctx.widthPx.toFloat(), ctx.heightPx.toFloat())
            ctx.canvas.drawPath(ctx.expectedPath, paints.ghostPaint)
            ctx.canvas.restore()
        }

        // Draw forecast line with per-hour weather-adaptive colors
        val segmentPaint = Paint(paints.forecastDashedPaint)
        for (i in ctx.forecastSegmentPaths.indices) {
            val hour = hours[i.coerceAtMost(hours.lastIndex)]
            segmentPaint.color = WeatherConditionColors.forecastColor(
                hour.isSunny, hour.isRainy, hour.isMixed, hour.isNight
            )
            ctx.canvas.drawPath(ctx.forecastSegmentPaths[i], segmentPaint)
        }

        if (ctx.transitionX != null) {
            ctx.canvas.save()
            ctx.canvas.clipRect(0f, 0f, ctx.transitionX + dpToPx(ctx.context, 1f), ctx.heightPx.toFloat())
            ctx.canvas.drawPath(ctx.actualPath, paints.actualLinePaint)
            ctx.canvas.restore()
        }
    }

    private fun drawHourLabelsAndIcons(
        ctx: RenderContext,
        hours: List<HourData>,
        drawnIconBounds: MutableList<RectF>
    ) {
        val minHourLabelSpacing = dpToPx(ctx.context, 42f * ctx.labelScale)
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
        val labelTemps = hours.map { it.temperature }
        val actualLabelTemps = hours.map { h ->
            if (h.isActual) h.actualTemperature ?: h.temperature else h.temperature
        }
        val dailyHighIndex = labelTemps.indices.maxByOrNull { labelTemps[it] } ?: -1
        val dailyLowIndex = labelTemps.indices.minByOrNull { labelTemps[it] } ?: -1

        val actualStartIndex = 0
        val actualEndIndex = if (ctx.transitionX != null) ctx.effectiveActualEndIndex else hours.lastIndex
        val actualIndices = (actualStartIndex..actualEndIndex).filter { it in actualLabelTemps.indices }
        val actualHighIndex = actualIndices.maxByOrNull { actualLabelTemps[it] } ?: -1
        val actualLowIndex = actualIndices.minByOrNull { actualLabelTemps[it] } ?: -1

        val forecastStartIndex = if (ctx.transitionX != null) ctx.effectiveActualEndIndex else 0
        val forecastEndIndex = hours.lastIndex
        val forecastIndices = (forecastStartIndex..forecastEndIndex).filter { it in labelTemps.indices }
        val forecastHighIndex = forecastIndices.maxByOrNull { labelTemps[it] } ?: -1
        val forecastLowIndex = forecastIndices.minByOrNull { labelTemps[it] } ?: -1

        val hasTransition = ctx.transitionX != null
        val pastForecastIndices = if (hasTransition) (0..actualEndIndex).filter { it in labelTemps.indices } else emptyList()
        val pastForecastHighIndex = if (hasTransition) pastForecastIndices.maxByOrNull { labelTemps[it] } ?: -1 else -1
        val pastForecastLowIndex = if (hasTransition) pastForecastIndices.minByOrNull { labelTemps[it] } ?: -1 else -1

        if (forecastHighIndex >= 0 && forecastLowIndex >= 0) {
            val forecastDates = forecastIndices.map { hours[it].dateTime.toLocalDate() }.distinct()
            Log.d(TAG, "FORECAST_EXTREMA highIdx=$forecastHighIndex highTemp=${labelTemps[forecastHighIndex]} " +
                "lowIdx=$forecastLowIndex lowTemp=${labelTemps[forecastLowIndex]} " +
                "forecastDates=$forecastDates forecastRange=$forecastStartIndex..$forecastEndIndex")
        }

        val localExtrema = findLocalExtremaIndices(labelTemps)
        val significantLocalExtrema = localExtrema.filter { index ->
            val prom = bilateralExtremaProminence(index, labelTemps, localExtrema)
            if (prom < MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES) {
                Log.d(TAG, "EXTREMUM_REJECTED idx=$index temp=${labelTemps[index]} prominence=$prom threshold=$MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES")
                false
            } else {
                Log.d(TAG, "SIGNIFICANT_EXTREMUM idx=$index temp=${labelTemps[index]} prominence=$prom")
                true
            }
        }

        val fetchIdx = ctx.fetchTime?.let { time -> hours.indexOfLast { !it.dateTime.isAfter(time) } } ?: -1

        // Collect all potential anchor candidates with their roles.
        // Priority is implicit in the order they are evaluated for deduplication.
        val potentialAnchors = mutableListOf<Pair<Int, String>>()
        if (dailyHighIndex >= 0) potentialAnchors.add(dailyHighIndex to "HIGH")
        if (dailyLowIndex >= 0) potentialAnchors.add(dailyLowIndex to "LOW")
        if (actualHighIndex >= 0) potentialAnchors.add(actualHighIndex to "ACTUAL_HIGH")
        if (actualLowIndex >= 0) potentialAnchors.add(actualLowIndex to "ACTUAL_LOW")
        if (forecastHighIndex >= 0) potentialAnchors.add(forecastHighIndex to "FORECAST_HIGH")
        if (forecastLowIndex >= 0) potentialAnchors.add(forecastLowIndex to "FORECAST_LOW")
        if (pastForecastHighIndex >= 0) potentialAnchors.add(pastForecastHighIndex to "PAST_FORECAST_HIGH")
        if (pastForecastLowIndex >= 0) potentialAnchors.add(pastForecastLowIndex to "PAST_FORECAST_LOW")
        potentialAnchors.add(0 to "START")
        if (hours.size > 1) potentialAnchors.add(hours.size - 1 to "END")
        
        // Also consider significant local extrema as potential candidates.
        // They have lower priority than explicitly named roles.
        significantLocalExtrema.forEach { potentialAnchors.add(it to "LOCAL") }

        // Group anchors by their visual "slot" (same rounded value and same plateau).
        // If multiple anchors land in the same slot, we only keep the one with the highest priority role.
        val slotToAnchor = mutableMapOf<Triple<String, Int, Int>, Int>()
        val rolePriority = listOf("HIGH", "LOW", "START", "END", "ACTUAL_HIGH", "ACTUAL_LOW", "FORECAST_HIGH", "FORECAST_LOW", "PAST_FORECAST_HIGH", "PAST_FORECAST_LOW", "LOCAL")
        
        for ((idx, role) in potentialAnchors) {
            val isActualRole = role in listOf("ACTUAL_HIGH", "ACTUAL_LOW")
            val temps = if (isActualRole) actualLabelTemps else labelTemps
            val v = temps[idx]
            val formattedValue = formatTemp(v)
            
            // Plateau boundaries
            var first = idx; var last = idx
            while (first > 0 && temps[first - 1] == v) first--
            while (last < temps.lastIndex && temps[last + 1] == v) last++
            
            val slotKey = Triple(formattedValue, first, last)
            val existingIdx = slotToAnchor[slotKey]
            if (existingIdx == null) {
                slotToAnchor[slotKey] = idx
            } else {
                val existingRole = potentialAnchors.find { it.first == existingIdx }?.second ?: "LOCAL"
                val existingPriority = rolePriority.indexOf(existingRole).let { if (it == -1) Int.MAX_VALUE else it }
                val currentPriority = rolePriority.indexOf(role).let { if (it == -1) Int.MAX_VALUE else it }
                if (currentPriority < existingPriority) {
                    slotToAnchor[slotKey] = idx
                }
            }
        }
        
        val deduplicatedIndices = slotToAnchor.values.toSet()
        val explicitAnchors = deduplicatedIndices.filter { idx ->
            potentialAnchors.any { it.first == idx && it.second != "LOCAL" }
        }.toSet()

        val filteredIndices = GraphLabelPlacementUtils.filterDenseLabelCandidates(
            items = labelTemps,
            candidates = deduplicatedIndices.toList(),
            globalMaxIdx = dailyHighIndex,
            globalMinIdx = dailyLowIndex,
            maxCandidates = MAX_TEMP_LABEL_CANDIDATES,
            diffThresholds = DENSE_TEMP_DIFF_THRESHOLDS,
            valueFunction = { it.roundToInt() },
            logTag = TAG,
            protectedIndices = deduplicatedIndices.filter { it in significantLocalExtrema && it > ctx.effectiveActualEndIndex }.toSet(),
            immovableIndices = explicitAnchors,
        )

        val suppressLeftEdgeLabel = GraphLabelPlacementUtils.shouldSuppressLeftEdgeLabel(
            items = labelTemps,
            candidates = filteredIndices,
            globalMaxIdx = dailyHighIndex,
            globalMinIdx = dailyLowIndex,
            valueFunction = { it.roundToInt() },
            nearbyWindow = 5
        )

        val specialCandidates = mutableListOf<TempLabelCandidate>()
        val filteredDistinct = filteredIndices.distinct()
        val suppressedIndices = mutableSetOf<Int>()
        for (idx in filteredDistinct) {
            var role = when (idx) {
                dailyHighIndex -> "HIGH"
                dailyLowIndex -> "LOW"
                0 -> "START"
                hours.lastIndex -> "END"
                actualHighIndex -> "ACTUAL_HIGH"
                actualLowIndex -> "ACTUAL_LOW"
                forecastHighIndex -> "FORECAST_HIGH"
                forecastLowIndex -> "FORECAST_LOW"
                pastForecastHighIndex -> "PAST_FORECAST_HIGH"
                pastForecastLowIndex -> "PAST_FORECAST_LOW"
                else -> "LOCAL"
            }

            // Boundary labels (START/END) are essential markers and should not be suppressed by proximity logic.
            val isBoundary = role == "START" || role == "END"
            if (idx == 0 && suppressLeftEdgeLabel && !isBoundary) {
                Log.d(TAG, "LABEL_CANDIDATE_SKIPPED idx=0 role=$role reason=suppressLeftEdgeLabel")
                suppressedIndices.add(idx)
                continue
            }

            // Suppress if this point is already being labeled by the Fetch Dot,
            // EXCEPT for endpoints (START/END) and global extrema (HIGH/LOW) which carry
            // distinct information from the fetch dot's observed value.
            if (idx == fetchIdx && ctx.observedAt != null) {
                if (idx == 0 || idx == hours.lastIndex) {
                    role = if (idx == 0) "START" else "END"
                } else if (role in listOf("HIGH", "LOW", "FORECAST_HIGH", "FORECAST_LOW")) {
                    // Global extrema labels show forecast peak/valley — different info from fetch dot's observed value.
                    Log.d(TAG, "LABEL_CANDIDATE_KEPT idx=$idx role=$role reason=EXTREMA_OVERRIDES_FETCH_DOT")
                } else {
                    Log.d(TAG, "LABEL_CANDIDATE_SKIPPED idx=$idx role=$role reason=FETCH_DOT_SUPPRESSED")
                    suppressedIndices.add(idx)
                    continue
                }
            }

            // Suppress ACTUAL extrema when near their global counterpart AND showing a similar value.
            // Skip suppression if the global HIGH/LOW was itself suppressed, or if the actual
            // and forecast values differ meaningfully (the label adds information).
            val redundantPairWindow = min(8, hours.lastIndex / 5)
            val redundantValueThreshold = 2f
            if (role == "ACTUAL_HIGH" && dailyHighIndex >= 0 && dailyHighIndex !in suppressedIndices && abs(idx - dailyHighIndex) <= redundantPairWindow) {
                val actualVal = actualLabelTemps[idx]
                val forecastVal = labelTemps[dailyHighIndex]
                if (abs(actualVal - forecastVal) < redundantValueThreshold) {
                    Log.d(TAG, "LABEL_CANDIDATE_SKIPPED idx=$idx role=$role reason=REDUNDANT_NEAR_HIGH dist=${abs(idx - dailyHighIndex)} valueDiff=${abs(actualVal - forecastVal)}")
                    suppressedIndices.add(idx)
                    continue
                }
            }
            if (role == "ACTUAL_LOW" && dailyLowIndex >= 0 && dailyLowIndex !in suppressedIndices && abs(idx - dailyLowIndex) <= redundantPairWindow) {
                val actualVal = actualLabelTemps[idx]
                val forecastVal = labelTemps[dailyLowIndex]
                if (abs(actualVal - forecastVal) < redundantValueThreshold) {
                    Log.d(TAG, "LABEL_CANDIDATE_SKIPPED idx=$idx role=$role reason=REDUNDANT_NEAR_LOW dist=${abs(idx - dailyLowIndex)} valueDiff=${abs(actualVal - forecastVal)}")
                    suppressedIndices.add(idx)
                    continue
                }
            }

            // Suppress extrema roles near graph edges — START/END labels already cover those values.
            // But don't suppress if this extrema IS the endpoint (since END/START are separate roles now).
            // Scale window proportionally so small graphs (tests, narrow widgets) aren't over-suppressed.
            // For longer graphs (100+ hours), use a tighter ratio to avoid over-suppressing distant labels.
            if (role in listOf("HIGH", "LOW", "FORECAST_HIGH", "FORECAST_LOW", "ACTUAL_HIGH", "ACTUAL_LOW", "PAST_FORECAST_HIGH", "PAST_FORECAST_LOW")) {
                val edgeWindow = if (hours.lastIndex > 50) min(5, hours.lastIndex / 15) else min(8, hours.lastIndex / 6)
                val edgeDist = min(idx, hours.lastIndex - idx)
                val isEndpoint = idx == 0 || idx == hours.lastIndex
                if (edgeDist <= edgeWindow && !isEndpoint) {
                    Log.d(TAG, "LABEL_CANDIDATE_SKIPPED idx=$idx role=$role reason=REDUNDANT_NEAR_ENDPOINT edgeDist=$edgeDist")
                    suppressedIndices.add(idx)
                    continue
                }
            }

            // END/START labels are essential boundary markers and should never be suppressed due to adjacency.
            // They pass through to the essential label placement logic below.

            val isActualRole = role == "ACTUAL_HIGH" || role == "ACTUAL_LOW"
            val forceForecast = role in listOf("HIGH", "LOW", "FORECAST_HIGH", "FORECAST_LOW", "PAST_FORECAST_HIGH", "PAST_FORECAST_LOW", "LOCAL", "START", "END")
            val temps = if (isActualRole) actualLabelTemps else labelTemps

            specialCandidates.add(TempLabelCandidate(idx, role, temps, hours[idx].temperature, forceForecast))
        }

        val drawnLabelBounds = mutableListOf<RectF>()
        val labelFontMetrics = ctx.paints.actualTempLabelTextPaint.fontMetrics
        val labelAscent = if (labelFontMetrics != null && labelFontMetrics.ascent != 0f) labelFontMetrics.ascent else (-ctx.paints.actualTempLabelTextPaint.textSize)
        val labelDescent = if (labelFontMetrics != null && labelFontMetrics.descent != 0f) labelFontMetrics.descent else (ctx.paints.actualTempLabelTextPaint.textSize * 0.2f)
        
        val gapDp = GraphLabelPlacementUtils.getLabelGapDp(isFallback = false)

        specialCandidates.sortWith(
            compareBy<TempLabelCandidate> {
                when (it.role) {
                    "HIGH", "LOW", "FORECAST_HIGH", "FORECAST_LOW", "PAST_FORECAST_LOW", "PAST_FORECAST_HIGH" -> 0
                    "LOCAL", "ACTUAL_END" -> 1
                    else -> 2 // START, END
                }
            }.thenBy {
                val leftVal = it.labelTemps.subList(0, it.index).findLast { v -> v != it.rawTemperature } ?: it.rawTemperature
                val rightVal = it.labelTemps.subList(it.index + 1, it.labelTemps.size).find { v -> v != it.rawTemperature } ?: it.rawTemperature
                val isPeak = it.role in listOf("HIGH", "FORECAST_HIGH", "ACTUAL_HIGH", "PAST_FORECAST_HIGH") || (it.role == "LOCAL" && it.rawTemperature > leftVal && it.rawTemperature > rightVal)
                if (isPeak) -it.rawTemperature else it.rawTemperature
            }
        )

        Log.d(TAG, "LABEL_CANDIDATES total=${specialCandidates.size} candidates=${specialCandidates.map { "idx=${it.index} role=${it.role} temp=${String.format("%.2f", it.labelTemps[it.index])}° (${formatTemp(it.labelTemps[it.index])}°) series=${if (it.forceForecastSeries) "forecast" else "actual"}" }}")

        for (candidate in specialCandidates) {
            val idx = candidate.index
            val temps = candidate.labelTemps
            val isFuture = candidate.forceForecastSeries || ctx.originalPoints[idx].first > (ctx.transitionX ?: -1f)
            val points = if (isFuture) ctx.forecastPoints else ctx.originalPoints
            println("DEBUG_SERIES: idx=$idx role=${candidate.role} forceForecast=${candidate.forceForecastSeries} isFuture=$isFuture transitionX=${ctx.transitionX} pointX=${ctx.originalPoints[idx].first}")
            val sx = if (candidate.role in listOf("LOW", "HIGH", "FORECAST_LOW", "FORECAST_HIGH", "PAST_FORECAST_LOW", "PAST_FORECAST_HIGH", "LOCAL")) {
                centerOfRun(idx, temps, candidate.forceForecastSeries, ctx.originalPoints, ctx.forecastPoints, ctx.transitionX).first
            } else points[idx].first
            val sy = ctx.graphTop + ctx.graphHeight * (1 - (temps[idx] - ctx.minTemp) / ctx.tempRange)

            val label = formatTemp(temps[idx]) + "°"
            val labelPaint = if (isFuture) {
                val hour = hours[idx.coerceAtMost(hours.lastIndex)]
                ctx.paints.forecastTempLabelTextPaint.also {
                    it.color = WeatherConditionColors.forecastColor(hour.isSunny, hour.isRainy, hour.isMixed, hour.isNight)
                }
            } else ctx.paints.actualTempLabelTextPaint
            val textWidth = labelPaint.measureText(label)
            val clampedX = sx.coerceIn(textWidth / 2f, ctx.widthPx - textWidth / 2f)

            val leftVal = temps.subList(0, idx).findLast { it != temps[idx] } ?: temps[idx]
            val rightVal = temps.subList(idx + 1, temps.size).find { it != temps[idx] } ?: temps[idx]
            val isValley = candidate.role in listOf("LOW", "FORECAST_LOW", "ACTUAL_LOW", "PAST_FORECAST_LOW") || (candidate.role == "LOCAL" && temps[idx] < leftVal && temps[idx] < rightVal)
            val isEssential = candidate.role in setOf("LOW", "HIGH", "FORECAST_LOW", "FORECAST_HIGH", "ACTUAL_LOW", "ACTUAL_HIGH", "PAST_FORECAST_LOW", "PAST_FORECAST_HIGH", "START", "END", "ACTUAL_END")

            val directions = if (isValley) listOf(false, true) else listOf(true, false) // placeAbove order: true=above, false=below
            val labelHeight = labelDescent - labelAscent
            val leaderLinePaint = if (isFuture) {
                ctx.paints.forecastLeaderLinePaint.also { it.color = withAlpha(labelPaint.color, 80) }
            } else ctx.paints.actualLeaderLinePaint
            val minorOverlapThreshold = if (isMinorOverlapEligible(candidate.role)) labelHeight * MINOR_OVERLAP_HEIGHT_RATIO else 0f
            var placed = false
            // Track last on-screen position as fallback for forced essential labels
            var forceBaselineY = Float.NaN
            var forceBounds: RectF? = null
            var forceDrawBelow = false
            var forceStep = 0
            
            outer@ for (step in 0..MAX_LEADER_DISPLACEMENT_STEPS) {
                for (placeAbove in directions) {
                    val currentGapPx = if (placeAbove) dpToPx(ctx.context, gapDp.aboveDp) else dpToPx(ctx.context, gapDp.belowDp)
                    val displacement = step * labelHeight
                    
                    val verticalPlacement = GraphLabelPlacementUtils.computeLabelVerticalPlacement(
                        pointY = sy,
                        placeAbove = placeAbove,
                        gapPx = currentGapPx + displacement,
                        textAscent = labelAscent,
                        textDescent = labelDescent
                    )
                    
                    val baselineY = verticalPlacement.baselineY
                    val bounds = RectF(clampedX - textWidth / 2f, verticalPlacement.top, clampedX + textWidth / 2f, verticalPlacement.bottom)
                    
                    val onScreen = bounds.top >= 0f && bounds.bottom <= ctx.heightPx
                    if (!onScreen) {
                        Log.d(
                            TAG,
                            "LABEL_PLACEMENT_REJECTED role=${candidate.role} idx=$idx text=$label step=$step " +
                                "preferred=${if (!placeAbove) "below" else "above"} reason=OFF_SCREEN " +
                                "bounds=$bounds size=${ctx.widthPx}x${ctx.heightPx}",
                        )
                        continue
                    }
                    val overlapsLabel = drawnLabelBounds.any { RectF.intersects(it, bounds) }
                    val overlapsIcon = drawnIconBounds.any { RectF.intersects(it, bounds) }
                    val labelOverlap = if (overlapsLabel) maxVerticalOverlap(bounds, drawnLabelBounds) else 0f
                    val iconOverlap = if (overlapsIcon) maxVerticalOverlap(bounds, drawnIconBounds) else 0f
                    val allowMinorLabelOverlap = overlapsLabel && shouldAllowMinorOverlap(candidate.role, labelOverlap, labelHeight)
                    val allowMinorIconOverlap = overlapsIcon && shouldAllowMinorOverlap(candidate.role, iconOverlap, labelHeight)
                    val hasCollision =
                        (overlapsLabel && !allowMinorLabelOverlap) ||
                            (overlapsIcon && !allowMinorIconOverlap)
                    
                    if (isEssential && forceBounds == null) { 
                        forceBaselineY = baselineY
                        forceBounds = bounds
                        forceDrawBelow = !placeAbove
                        forceStep = step 
                    }
                    
                    if (hasCollision) {
                        val collisionTarget = when {
                            overlapsLabel && overlapsIcon -> "LABEL+ICON"
                            overlapsLabel -> "LABEL"
                            overlapsIcon -> "ICON"
                            else -> "UNKNOWN"
                        }
                        Log.d(
                            TAG,
                            "LABEL_PLACEMENT_REJECTED role=${candidate.role} idx=$idx text=$label step=$step " +
                                "preferred=${if (!placeAbove) "below" else "above"} reason=COLLISION target=$collisionTarget " +
                                "labelOverlap=$labelOverlap iconOverlap=$iconOverlap threshold=$minorOverlapThreshold bounds=$bounds",
                        )
                    }
                    if (!hasCollision) {
                        if (allowMinorLabelOverlap || allowMinorIconOverlap) {
                            Log.d(
                                TAG,
                                "LABEL_PLACEMENT_ACCEPTED_WITH_MINOR_OVERLAP role=${candidate.role} idx=$idx text=$label step=$step " +
                                    "preferred=${if (!placeAbove) "below" else "above"} labelOverlap=$labelOverlap " +
                                    "iconOverlap=$iconOverlap threshold=$minorOverlapThreshold",
                            )
                        }
                        if (step > 0) {
                            val lineEndY = if (!placeAbove) bounds.top else bounds.bottom
                            ctx.canvas.drawLine(clampedX, sy, clampedX, lineEndY, leaderLinePaint)
                        }
                        ctx.canvas.drawText(label, clampedX, baselineY, labelPaint)
                        drawnLabelBounds.add(bounds)
                        val reasonBase = if (!placeAbove) "below" else "above"
                        val reason = if (step > 0) "$reasonBase+$step" else reasonBase
                        val seriesLabel = if (isFuture) "forecast" else "actual"
                        Log.d(
                            TAG,
                            "LABEL_PLACED role=${candidate.role} idx=$idx text=$label series=$seriesLabel " +
                                "placement=$reason forced=false bounds=$bounds",
                        )
                        ctx.onLabelPlaced?.invoke(LabelPlacementDebug(idx, candidate.role, temps[idx], candidate.rawTemperature, clampedX, baselineY, placeAbove, seriesLabel, seriesLabel, reason, step))
                        placed = true
                        break@outer
                    }
                }
            }
            // Essential labels always appear — force onto the last on-screen position if not placed
            if (!placed && isEssential && forceBounds != null) {
                if (forceStep > 0) {
                    val lineEndY = if (forceDrawBelow) forceBounds.top else forceBounds.bottom
                    ctx.canvas.drawLine(clampedX, sy, clampedX, lineEndY, leaderLinePaint)
                }
                ctx.canvas.drawText(label, clampedX, forceBaselineY, labelPaint)
                drawnLabelBounds.add(forceBounds)
                val seriesLabel = if (isFuture) "forecast" else "actual"
                Log.d(
                    TAG,
                    "LABEL_PLACED role=${candidate.role} idx=$idx text=$label series=$seriesLabel " +
                        "placement=${if (forceDrawBelow) "below" else "above"}+$forceStep forced=true bounds=$forceBounds",
                )
                ctx.onLabelPlaced?.invoke(LabelPlacementDebug(idx, candidate.role, temps[idx], candidate.rawTemperature, clampedX, forceBaselineY, !forceDrawBelow, seriesLabel, seriesLabel, "FORCED", forceStep))
            } else if (!placed) {
                val seriesLabel = if (isFuture) "forecast" else "actual"
                Log.d(
                    TAG,
                    "LABEL_NOT_PLACED role=${candidate.role} idx=$idx text=$label series=$seriesLabel",
                )
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
        val dayYBottom = ctx.heightPx - dpToPx(ctx.context, 14f)

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

    private fun drawFetchDot(ctx: RenderContext, hours: List<HourData>): List<RectF> {
        val drawnBounds = mutableListOf<RectF>()
        if (ctx.observedAt == null || ctx.fetchDotX == null || ctx.lastObservedTemp == null) return drawnBounds
        val fetchY = ctx.graphTop + ctx.graphHeight * (1 - (ctx.lastObservedTemp - ctx.minTemp) / ctx.tempRange)
        val dotRadius = dpToPx(ctx.context, 3.2f * ctx.labelScale)
        val clampedX = ctx.fetchDotX.coerceIn(dotRadius, ctx.widthPx - dotRadius)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tempToColor(ctx.lastObservedTemp); style = Paint.Style.FILL }
        ctx.canvas.drawCircle(clampedX, fetchY, dotRadius, dotPaint)
        ctx.canvas.drawCircle(clampedX, fetchY, dotRadius, ctx.paints.ringPaint)
        ctx.canvas.drawCircle(clampedX, fetchY, dotRadius + ctx.paints.ringPaint.strokeWidth / 2f, ctx.paints.outerRingPaint)

        val ageMinutes = ctx.fetchTime?.let { Duration.between(it, ctx.currentTime).toMinutes() } ?: 0L
        val ageLabel = if (ageMinutes >= 0 && Duration.between(hours.first().dateTime, hours.last().dateTime).toHours() <= 12) {
            if (ageMinutes >= 60) "${ageMinutes / 60}h${if (ageMinutes % 60 > 0) " ${ageMinutes % 60}m" else ""}" else "${ageMinutes}m"
        } else null

        if (ageLabel != null) {
            val ageY = fetchY + dotRadius + dpToPx(ctx.context, 4f * ctx.labelScale) - ctx.paints.stalenessTextPaint.ascent()
            if (ageY + ctx.paints.stalenessTextPaint.descent() <= ctx.heightPx) {
                val ageWidth = ctx.paints.stalenessTextPaint.measureText(ageLabel)
                val ageBounds = RectF(
                    clampedX - ageWidth / 2f,
                    ageY + ctx.paints.stalenessTextPaint.ascent(),
                    clampedX + ageWidth / 2f,
                    ageY + ctx.paints.stalenessTextPaint.descent()
                )
                ctx.canvas.drawText(ageLabel, clampedX, ageY, ctx.paints.stalenessTextPaint)
                drawnBounds.add(ageBounds)
            }
        }

        val valueLabel = formatTemp(ctx.lastObservedTemp) + "°"
        val valueWidth = ctx.paints.valueTextPaint.measureText(valueLabel)
        val sideGap = dpToPx(ctx.context, 4f * ctx.labelScale)
        var drawn = false

        val valueBaselineOffset = ctx.paints.valueTextPaint.textSize / 3f

        if (clampedX + dotRadius + sideGap + valueWidth <= ctx.widthPx) {
            ctx.paints.valueTextPaint.textAlign = Paint.Align.LEFT
            val x = clampedX + dotRadius + sideGap
            val y = fetchY + valueBaselineOffset
            ctx.canvas.drawText(valueLabel, x, y, ctx.paints.valueTextPaint)
            drawnBounds.add(RectF(x, y + ctx.paints.valueTextPaint.ascent(), x + valueWidth, y + ctx.paints.valueTextPaint.descent()))
            drawn = true
        }
        if (!drawn && clampedX - dotRadius - sideGap - valueWidth >= 0) {
            ctx.paints.valueTextPaint.textAlign = Paint.Align.RIGHT
            val x = clampedX - dotRadius - sideGap
            val y = fetchY + valueBaselineOffset
            ctx.canvas.drawText(valueLabel, x, y, ctx.paints.valueTextPaint)
            drawnBounds.add(RectF(x - valueWidth, y + ctx.paints.valueTextPaint.ascent(), x, y + ctx.paints.valueTextPaint.descent()))
            drawn = true
        }
        if (!drawn && fetchY - dotRadius - dpToPx(ctx.context, 2f * ctx.labelScale) + ctx.paints.valueTextPaint.ascent() >= 0) {
            ctx.paints.valueTextPaint.textAlign = Paint.Align.CENTER
            val x = clampedX
            val y = fetchY - dotRadius - dpToPx(ctx.context, 2f * ctx.labelScale)
            ctx.canvas.drawText(valueLabel, x, y, ctx.paints.valueTextPaint)
            drawnBounds.add(RectF(x - valueWidth / 2f, y + ctx.paints.valueTextPaint.ascent(), x + valueWidth / 2f, y + ctx.paints.valueTextPaint.descent()))
        }

        ctx.onFetchDotResolved?.invoke(FetchDotDebug(ctx.observedAt, clampedX, fetchY, true, if (ageLabel != null) "$valueLabel ($ageLabel)" else valueLabel, ctx.paints.valueTextPaint.color, if (ageLabel != null) ctx.paints.stalenessTextPaint.color else null))
        return drawnBounds
    }

    private fun findLocalExtremaIndices(temps: List<Float>): List<Int> {
        val extrema = mutableListOf<Int>()
        if (temps.size < 3) return extrema
        var i = 1
        while (i < temps.size - 1) {
            val current = temps[i]; val prev = temps[i - 1]; val next = temps[i + 1]
            if ((current > prev && current > next) || (current < prev && current < next)) extrema.add(i)
            else if (current == next && current != prev) {
                var j = i + 1
                while (j < temps.size - 1 && temps[j] == current) j++
                if (j < temps.size && ((current > prev && current > temps[j]) || (current < prev && current < temps[j]))) extrema.add((i + j) / 2)
                i = j - 1
            }
            i++
        }
        return extrema
    }

    private fun bilateralExtremaProminence(index: Int, temps: List<Float>, extrema: List<Int>): Float {
        val current = temps[index]; val extremaSet = extrema.toSet()
        fun maxDelta(step: Int): Float {
            var maxD = 0f; var cursor = index + step
            while (cursor in temps.indices) {
                maxD = max(maxD, abs(temps[cursor] - current))
                if (cursor != index + step && cursor in extremaSet) break
                cursor += step
            }
            return maxD
        }
        val left = maxDelta(-1); val right = maxDelta(1)
        return if (left == 0f || right == 0f) 0f else min(left, right)
    }

    private fun centerOfRun(idx: Int, temps: List<Float>, forceForecast: Boolean, original: List<Pair<Float, Float>>, forecast: List<Pair<Float, Float>>, transitionX: Float?): Pair<Float, Float> {
        val v = temps[idx]; var first = idx; var last = idx
        while (first > 0 && temps[first - 1] == v) first--
        while (last < temps.lastIndex && temps[last + 1] == v) last++
        val points = if (forceForecast || original[idx].first > (transitionX ?: -1f)) forecast else original
        return (points[first].first + points[last].first) / 2f to (points[first].second + points[last].second) / 2f
    }

    private data class TempLabelCandidate(val index: Int, val role: String, val labelTemps: List<Float>, val rawTemperature: Float, val forceForecastSeries: Boolean)

    private data class RenderContext(
        val context: Context,
        val canvas: Canvas,
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,
        val labelScale: Float,
        val minTemp: Float,
        val maxTemp: Float,
        val tempRange: Float,
        val graphTop: Float,
        val graphBottom: Float,
        val graphHeight: Float,
        val footerTop: Float,
        val hourWidth: Float,
        val minTimeEpoch: Long,
        val iconSize: Int,
        val iconTopPad: Float,
        val transitionX: Float?,
        val nowX: Float?,
        val nowIndicatorVisible: Boolean,
        val fetchTime: LocalDateTime?,
        val fetchDotX: Float?,
        val lastObservedTemp: Float?,
        val anchorDelta: Float,
        val smoothedForecastTemps: List<Float>,
        val smoothedExpectedTemps: List<Float>,
        val originalPoints: List<Pair<Float, Float>>,
        val forecastPoints: List<Pair<Float, Float>>,
        val expectedPoints: List<Pair<Float, Float>>,
        val originalPath: Path,
        val actualPath: Path,
        val actualVisiblePoints: List<Pair<Float, Float>>,
        val expectedPath: Path,
        val forecastPath: Path,
        val forecastFillPath: Path,
        val forecastSegmentPaths: List<Path>,
        val effectiveActualEndIndex: Int,
        val appliedDelta: Float?,
        val observedAt: Long?,
        val paints: PaintSet,
        val currentTime: LocalDateTime,
        val onGhostLineDebug: ((GhostLineDebug) -> Unit)?,
        val onActualLineResolved: ((ActualLineDebug) -> Unit)?,
        val onLabelPlaced: ((LabelPlacementDebug) -> Unit)?,
        val onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)?,
        val onFetchDotResolved: ((FetchDotDebug) -> Unit)?,
        val drawnLabelBounds: MutableList<RectF> = mutableListOf()
    )

    private data class RenderContextUpdate(
        val smoothedForecastTemps: List<Float>,
        val smoothedExpectedTemps: List<Float>,
        val originalPoints: List<Pair<Float, Float>>,
        val forecastPoints: List<Pair<Float, Float>>,
        val expectedPoints: List<Pair<Float, Float>>,
        val originalPath: Path,
        val actualPath: Path,
        val actualVisiblePoints: List<Pair<Float, Float>>,
        val expectedPath: Path,
        val expectedFillPath: Path,
        val forecastPath: Path,
        val forecastFillPath: Path,
        val forecastSegmentPaths: List<Path>,
        val nowX: Float?,
        val nowIndicatorVisible: Boolean,
        val fetchTime: LocalDateTime?,
        val fetchDotX: Float?,
        val anchorDelta: Float,
        val transitionX: Float?,
        val effectiveActualEndIndex: Int,
    )

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
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
        onFetchDotResolved: ((FetchDotDebug) -> Unit)? = null,
        onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)? = null,
        onGhostLineDebug: ((GhostLineDebug) -> Unit)? = null,
        onPointsResolved: ((PointsDebug) -> Unit)? = null,
        onActualLineResolved: ((ActualLineDebug) -> Unit)? = null,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (hours.isEmpty()) {
            Log.w(TAG, "renderGraph: empty hours list, returning blank bitmap (${widthPx}x${heightPx})")
            return bitmap
        }

        val t0 = SystemClock.elapsedRealtime()
        val labelScale = bitmapScale.coerceIn(0.5f, 1f)
        val paints = ensurePaints(context, labelScale)
        val density = context.resources.displayMetrics.density
        val t1 = SystemClock.elapsedRealtime()

        val (minTemp, maxTemp, tempRange) = computeScaling(hours)
        val layout = computeLayout(context, heightPx, labelScale)
        val t2 = SystemClock.elapsedRealtime()

        val minTimeEpoch = hours.firstOrNull()?.dateTime?.toEpochSecond(ZoneOffset.UTC) ?: 0L
        val maxTimeEpoch = hours.lastOrNull()?.dateTime?.toEpochSecond(ZoneOffset.UTC) ?: 0L
        val timeRangeHours = if (maxTimeEpoch > minTimeEpoch) (maxTimeEpoch - minTimeEpoch) / 3600f else hours.size.toFloat() - 1f
        val hourWidth = widthPx.toFloat() / timeRangeHours.coerceAtLeast(1f)

        val update = computePoints(
            hours, minTemp, tempRange, layout.graphTop, layout.graphHeight, layout.graphBottom,
            hourWidth, minTimeEpoch, currentTime, appliedDelta, observedAt, lastObservedTemp, widthPx, onPointsResolved
        )
        val t3 = SystemClock.elapsedRealtime()

        val ctx = RenderContext(
            context, canvas, widthPx, heightPx, density, labelScale, minTemp, maxTemp, tempRange,
            layout.graphTop, layout.graphBottom, layout.graphHeight, layout.footerTop, hourWidth, minTimeEpoch,
            layout.iconSize, layout.iconTopPad, update.transitionX, update.nowX, update.nowIndicatorVisible,
            update.fetchTime, update.fetchDotX, lastObservedTemp, update.anchorDelta,
            update.smoothedForecastTemps, update.smoothedExpectedTemps, update.originalPoints,
            update.forecastPoints, update.expectedPoints, update.originalPath, update.actualPath, update.actualVisiblePoints, update.expectedPath,
            update.forecastPath, update.forecastFillPath, update.forecastSegmentPaths, update.effectiveActualEndIndex,
            appliedDelta, observedAt, paints, currentTime, onGhostLineDebug, onActualLineResolved, onLabelPlaced,
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
        val t4 = SystemClock.elapsedRealtime()

        val drawnIconBounds = mutableListOf<RectF>()
        drawHourLabelsAndIcons(ctx, hours, drawnIconBounds)
        val t5 = SystemClock.elapsedRealtime()
        placeTemperatureLabels(ctx, hours, drawnIconBounds)
        placeDayLabels(ctx, hours, drawnIconBounds)
        val t6 = SystemClock.elapsedRealtime()

        val fetchDotBounds = drawFetchDot(ctx, hours)
        ctx.drawnLabelBounds.addAll(fetchDotBounds)

        GraphRenderUtils.drawNowIndicator(
            canvas, if (update.nowIndicatorVisible) update.nowX else null, ctx.graphTop, ctx.graphHeight,
            paints.currentTimePaint, paints.nowLabelTextPaint, ctx.drawnLabelBounds + drawnIconBounds
        ) { dpToPx(context, it) }
        val t7 = SystemClock.elapsedRealtime()

        Log.d(TAG, "RENDER_BREAKDOWN size=${widthPx}x${heightPx} hours=${hours.size}" +
            " paints=${t1-t0}ms layout=${t2-t1}ms points=${t3-t2}ms" +
            " curves=${t4-t3}ms icons=${t5-t4}ms labels=${t6-t5}ms decorations=${t7-t6}ms" +
            " total=${t7-t0}ms")

        return bitmap
    }

    private fun dpToPx(context: Context, dp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}
