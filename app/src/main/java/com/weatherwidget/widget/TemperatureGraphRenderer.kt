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
import com.weatherwidget.BuildConfig

object TemperatureGraphRenderer {
    private const val TAG = "TempGraphRenderer"
    private const val MAX_TEMP_LABEL_CANDIDATES = 6
    private val DENSE_TEMP_DIFF_THRESHOLDS = listOf(3, 4, 5)

    private inline fun debug(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg())
    }

    private const val MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES = 2.5f
    private const val MIN_GHOST_LINE_DELTA = 0.1f

    private const val MAX_LEADER_DISPLACEMENT_STEPS = 3

    private const val TEMP_LABEL_SIZE_DP = 19.5f
    private const val NOW_LABEL_SIZE_DP = 15.5f
    private const val DAY_LABEL_SIZE_DP = 19.5f
    private const val VALUE_LABEL_SIZE_DP = 19.5f
    private const val STALENESS_LABEL_SIZE_DP = 12f
    private const val DOT_RADIUS_DP = 3.2f
    private const val RING_STROKE_DP = 1.5f
    private const val OUTER_RING_STROKE_DP = 0.5f
    private const val HOUR_LABEL_SPACING_DP = 42f
    private const val FETCH_DOT_SIDE_GAP_DP = 4f
    private const val FETCH_DOT_ABOVE_GAP_DP = 2f
    private const val DAY_LABEL_BOTTOM_PADDING_DP = 14f

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

    // Temperature-to-color thresholds
    private const val COLD_THRESHOLD = 50f
    private const val MILD_TEMP = 70f
    private const val HOT_THRESHOLD = 90f

    private val COLOR_COLD = Color.parseColor("#5AC8FA") // Blue
    private val COLOR_MILD = Color.parseColor("#E8A24E") // Golden amber
    private val COLOR_HOT = Color.parseColor("#FF6B35") // Warm orange
    private val COLOR_ACTUAL_LINE = WeatherConditionColors.OBSERVED  // Hot pink #FF3366
    private val COLOR_ACTUAL_LABEL = WeatherConditionColors.OBSERVED
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

    private fun formatAgeLabel(ageMinutes: Long, hoursSpanHours: Long): String? {
        if (ageMinutes < 0) {
            Log.w(TAG, "formatAgeLabel: negative ageMinutes=$ageMinutes, possible clock skew")
            return null
        }
        if (hoursSpanHours > 12) return null
        return if (ageMinutes >= 60) "${ageMinutes / 60}h${if (ageMinutes % 60 > 0) " ${ageMinutes % 60}m" else ""}" else "${ageMinutes}m"
    }

    private fun withAlpha(
        color: Int,
        alpha: Int,
    ): Int {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun fontAscent(paint: Paint): Float {
        val fm = paint.fontMetrics
        return if (fm != null && fm.ascent != 0f) fm.ascent else -paint.textSize
    }

    private fun fontDescent(paint: Paint): Float {
        val fm = paint.fontMetrics
        return if (fm != null && fm.descent != 0f) fm.descent else paint.textSize * 0.2f
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
        val dotPaint: Paint,
    )

    @Volatile
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
            textSize = dpToPx(context, TEMP_LABEL_SIZE_DP * labelScale)
            textAlign = Paint.Align.CENTER
            setShadowLayer(dpToPx(context, 1f), 0f, dpToPx(context, 0.5f), Color.parseColor("#44000000"))
        }

        val actualTempLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACTUAL_LABEL
            textSize = dpToPx(context, TEMP_LABEL_SIZE_DP * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 2f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
        }

        val forecastTempLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_FORECAST_LABEL
            textSize = dpToPx(context, TEMP_LABEL_SIZE_DP * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 2f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
        }

        val nowLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BBFF9F0A")
            textSize = dpToPx(context, NOW_LABEL_SIZE_DP * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 1f), 0f, 0f, Color.parseColor("#44000000"))
        }

        val dayLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#88FFFFFF")
            textSize = dpToPx(context, DAY_LABEL_SIZE_DP * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val todayDayLabelPaint = Paint(dayLabelTextPaint).apply {
            color = Color.parseColor("#BBFF9F0A")
        }

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(context, RING_STROKE_DP * labelScale)
        }

        val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44000000")
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(context, OUTER_RING_STROKE_DP * labelScale)
        }

        val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACTUAL_LINE
            textSize = dpToPx(context, VALUE_LABEL_SIZE_DP * labelScale)
            textAlign = Paint.Align.LEFT
            setShadowLayer(dpToPx(context, 1f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
        }

        val stalenessTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACTUAL_LINE
            textSize = dpToPx(context, STALENESS_LABEL_SIZE_DP * labelScale)
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

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
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
            dotPaint = dotPaint,
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

    enum class TemperatureRole {
        HIGH, LOW, START, END, ACTUAL_HIGH, ACTUAL_LOW, FORECAST_HIGH, FORECAST_LOW, PAST_FORECAST_HIGH, PAST_FORECAST_LOW, LOCAL, ACTUAL_END
    }

    data class LabelPlacementDebug(
        val index: Int,
        val role: TemperatureRole,
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
        val stalenessLabelY: Float? = null,
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
            val yTruth = tempToY(actualTemps[index], graphTop, graphHeight, minTemp, tempRange)
            originalPoints.add(x to yTruth)

            val yForecast = tempToY(smoothedForecastTemps[index], graphTop, graphHeight, minTemp, tempRange)
            forecastPoints.add(x to yForecast)

            val yExpected = tempToY(smoothedExpectedTemps[index], graphTop, graphHeight, minTemp, tempRange)
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
        debug { "BEZIER_BREAKDOWN pts=${originalPoints.size}" +
            " actual=${tBezier1-tBezier0}ms expected=${tBezier2-tBezier1}ms forecast=${tBezier3-tBezier2}ms" }

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
                tempToY(lastObservedTemp, graphTop, graphHeight, minTemp, tempRange)
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

        // Draw forecast line with per-hour weather-adaptive colors.
        // Each segment is a separate Path, so DashPathEffect phase resets to 0.
        // Advance the phase by cumulative path length so dashes flow continuously.
        val dashOn = dpToPx(ctx.context, 8f)
        val dashOff = dpToPx(ctx.context, 4f)
        val dashPattern = floatArrayOf(dashOn, dashOff)
        val segmentPaint = Paint(paints.forecastDashedPaint)
        val pathMeasure = PathMeasure()
        var cumulativeLength = 0f
        for (i in ctx.forecastSegmentPaths.indices) {
            val hour = hours[i + 1]
            segmentPaint.color = WeatherConditionColors.forecastColor(
                hour.isSunny, hour.isRainy, hour.isMixed, hour.isNight
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
        val minHourLabelSpacing = dpToPx(ctx.context, HOUR_LABEL_SPACING_DP * ctx.labelScale)
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


    private typealias ExtremaIndices = TemperatureExtrema.ExtremaIndices

    private fun computeExtremaIndices(
        hours: List<HourData>,
        transitionX: Float?,
        effectiveActualEndIndex: Int,
        fetchTime: LocalDateTime?,
    ): ExtremaIndices {
        val prominenceThreshold = when {
            hours.size <= 10 -> MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES
            hours.size <= 24 -> 1.5f // Narrow zoom: more detail, but still reject minor noise
            else -> MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES
        }
        return TemperatureExtrema.compute(hours, transitionX, effectiveActualEndIndex, fetchTime, prominenceThreshold)
    }

    /**
     * Checks if a label at [idx] with [role] is redundant because it's too close to [targetIdx]
     * (which has a different role) and their temperature values are nearly identical.
     */
    private fun findPrevDifferent(temps: List<Float>, idx: Int): Float {
        val target = temps[idx]
        for (i in idx - 1 downTo 0) {
            if (temps[i] != target) return temps[i]
        }
        return target
    }

    private fun findNextDifferent(temps: List<Float>, idx: Int): Float {
        val target = temps[idx]
        for (i in idx + 1..temps.lastIndex) {
            if (temps[i] != target) return temps[i]
        }
        return target
    }

    private fun isRedundantNear(
        idx: Int,
        role: TemperatureRole,
        targetIdx: Int,
        suppressedIndices: Set<Int>,
        currentVal: Float,
        targetVal: Float,
        window: Int,
        threshold: Float,
        reasonSuffix: String
    ): Boolean {
        if (targetIdx >= 0 && targetIdx !in suppressedIndices && abs(idx - targetIdx) <= window) {
            if (abs(currentVal - targetVal) < threshold) {
                debug { "LABEL_CANDIDATE_SKIPPED idx=$idx role=$role reason=REDUNDANT_NEAR_$reasonSuffix dist=${abs(idx - targetIdx)} valueDiff=${abs(currentVal - targetVal)}" }
                return true
            }
        }
        return false
    }

    private data class SuppressionResult(
        val suppressed: Boolean,
        val overriddenRole: TemperatureRole? = null,
    )

    private fun checkLeftEdgeSuppression(
        idx: Int,
        role: TemperatureRole,
        suppressLeftEdgeLabel: Boolean,
    ): SuppressionResult {
        val isBoundary = role == TemperatureRole.START || role == TemperatureRole.END
        if (idx == 0 && suppressLeftEdgeLabel && !isBoundary) {
            debug { "LABEL_CANDIDATE_SKIPPED idx=0 role=$role reason=suppressLeftEdgeLabel" }
            return SuppressionResult(true)
        }
        return SuppressionResult(false)
    }

    private fun checkFetchDotSuppression(
        idx: Int,
        role: TemperatureRole,
        extrema: ExtremaIndices,
        observedAt: Long?,
        hours: List<HourData>,
    ): SuppressionResult {
        if (idx != extrema.fetchIdx || observedAt == null) return SuppressionResult(false)
        if (idx == 0 || idx == hours.lastIndex) {
            return SuppressionResult(false, overriddenRole = if (idx == 0) TemperatureRole.START else TemperatureRole.END)
        }
        if (role in listOf(TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW)) {
            debug { "LABEL_CANDIDATE_KEPT idx=$idx role=$role reason=EXTREMA_OVERRIDES_FETCH_DOT" }
            return SuppressionResult(false)
        }
        debug { "LABEL_CANDIDATE_SKIPPED idx=$idx role=$role reason=FETCH_DOT_SUPPRESSED" }
        return SuppressionResult(true)
    }

    private fun checkRedundantPairSuppression(
        idx: Int,
        role: TemperatureRole,
        extrema: ExtremaIndices,
        suppressedIndices: Set<Int>,
        labelTemps: List<Float>,
        actualLabelTemps: List<Float>,
    ): Boolean {
        val redundantPairWindow = min(8, labelTemps.lastIndex / 5)
        val redundantValueThreshold = 2f
        return when (role) {
            TemperatureRole.ACTUAL_HIGH -> isRedundantNear(idx, role, extrema.dailyHighIndex, suppressedIndices, actualLabelTemps[idx], labelTemps[extrema.dailyHighIndex], redundantPairWindow, redundantValueThreshold, "HIGH")
            TemperatureRole.ACTUAL_LOW -> isRedundantNear(idx, role, extrema.dailyLowIndex, suppressedIndices, actualLabelTemps[idx], labelTemps[extrema.dailyLowIndex], redundantPairWindow, redundantValueThreshold, "LOW")
            TemperatureRole.FORECAST_HIGH, TemperatureRole.PAST_FORECAST_HIGH -> isRedundantNear(idx, role, extrema.actualHighIndex, suppressedIndices, labelTemps[idx], actualLabelTemps[extrema.actualHighIndex], redundantPairWindow, redundantValueThreshold, "ACTUAL_HIGH")
            TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_LOW -> isRedundantNear(idx, role, extrema.actualLowIndex, suppressedIndices, labelTemps[idx], actualLabelTemps[extrema.actualLowIndex], redundantPairWindow, redundantValueThreshold, "ACTUAL_LOW")
            else -> false
        }
    }

    private fun checkTransitionBoundarySuppression(
        idx: Int,
        role: TemperatureRole,
        effectiveActualEndIndex: Int,
        transitionX: Float?,
        hours: List<HourData>,
    ): Boolean {
        if (role !in listOf(TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW)) return false
        if (transitionX == null) return false
        val boundaryIdx = effectiveActualEndIndex
        val transitionWindow = min(3, hours.lastIndex / 20)
        if (boundaryIdx >= 0 && abs(idx - boundaryIdx) <= transitionWindow) {
            debug { "LABEL_CANDIDATE_SKIPPED idx=$idx role=$role reason=TRANSITION_BOUNDARY_SUPPRESSED dist=${abs(idx - boundaryIdx)} boundaryIdx=$boundaryIdx" }
            return true
        }
        return false
    }

    private fun checkEndpointSuppression(
        idx: Int,
        role: TemperatureRole,
        hours: List<HourData>,
    ): Boolean {
        val extremaRoles = listOf(TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW)
        if (role !in extremaRoles) return false
        val edgeWindow = when {
            hours.lastIndex > 50 -> min(5, hours.lastIndex / 15)
            hours.lastIndex > 24 -> min(8, hours.lastIndex / 6)
            hours.lastIndex > 10 -> 1
            else -> 0
        }
        val edgeDist = min(idx, hours.lastIndex - idx)
        val isEndpoint = idx == 0 || idx == hours.lastIndex
        if (edgeDist <= edgeWindow && !isEndpoint) {
            debug { "LABEL_CANDIDATE_SKIPPED idx=$idx role=$role reason=REDUNDANT_NEAR_ENDPOINT edgeDist=$edgeDist" }
            return true
        }
        return false
    }

    private fun buildPotentialAnchors(
        extrema: ExtremaIndices,
        hoursCount: Int,
    ): MutableList<Pair<Int, TemperatureRole>> {
        val anchors = mutableListOf<Pair<Int, TemperatureRole>>()
        if (extrema.dailyHighIndex >= 0) anchors.add(extrema.dailyHighIndex to TemperatureRole.HIGH)
        if (extrema.dailyLowIndex >= 0) anchors.add(extrema.dailyLowIndex to TemperatureRole.LOW)
        if (extrema.actualHighIndex >= 0) anchors.add(extrema.actualHighIndex to TemperatureRole.ACTUAL_HIGH)
        if (extrema.actualLowIndex >= 0) anchors.add(extrema.actualLowIndex to TemperatureRole.ACTUAL_LOW)
        if (extrema.forecastHighIndex >= 0) anchors.add(extrema.forecastHighIndex to TemperatureRole.FORECAST_HIGH)
        if (extrema.forecastLowIndex >= 0) anchors.add(extrema.forecastLowIndex to TemperatureRole.FORECAST_LOW)
        if (extrema.pastForecastHighIndex >= 0) anchors.add(extrema.pastForecastHighIndex to TemperatureRole.PAST_FORECAST_HIGH)
        if (extrema.pastForecastLowIndex >= 0) anchors.add(extrema.pastForecastLowIndex to TemperatureRole.PAST_FORECAST_LOW)
        anchors.add(0 to TemperatureRole.START)
        if (hoursCount > 1) anchors.add(hoursCount - 1 to TemperatureRole.END)
        return anchors
    }

    private fun deduplicateAnchors(
        potentialAnchors: List<Pair<Int, TemperatureRole>>,
        labelTemps: List<Float>,
        actualLabelTemps: List<Float>,
    ): Set<Int> {
        val rolePriority = listOf(
            TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.START, TemperatureRole.END,
            TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_LOW, TemperatureRole.FORECAST_HIGH,
            TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW,
            TemperatureRole.LOCAL
        )
        val slotToAnchor = mutableMapOf<Triple<String, Int, Int>, Int>()
        for ((idx, role) in potentialAnchors) {
            val isActualRole = role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.ACTUAL_LOW
            val temps = if (isActualRole) actualLabelTemps else labelTemps
            val v = temps[idx]
            val formattedValue = formatTemp(v)
            var first = idx; var last = idx
            while (first > 0 && temps[first - 1] == v) first--
            while (last < temps.lastIndex && temps[last + 1] == v) last++
            val slotKey = Triple(formattedValue, first, last)
            val existingIdx = slotToAnchor[slotKey]
            if (existingIdx == null) {
                slotToAnchor[slotKey] = idx
            } else {
                val existingRole = potentialAnchors.find { it.first == existingIdx }?.second ?: TemperatureRole.LOCAL
                val existingPriority = rolePriority.indexOf(existingRole).let { if (it == -1) Int.MAX_VALUE else it }
                val currentPriority = rolePriority.indexOf(role).let { if (it == -1) Int.MAX_VALUE else it }
                if (currentPriority < existingPriority) {
                    slotToAnchor[slotKey] = idx
                }
            }
        }
        return slotToAnchor.values.toSet()
    }

    private fun collectLabelCandidates(
        hours: List<HourData>,
        extrema: ExtremaIndices,
        effectiveActualEndIndex: Int,
        transitionX: Float?,
        observedAt: Long?,
    ): List<TempLabelCandidate> {
        val labelTemps = extrema.labelTemps
        val actualLabelTemps = extrema.actualLabelTemps

        val potentialAnchors = buildPotentialAnchors(extrema, hours.size)
        debug { "POTENTIAL_ANCHORS: $potentialAnchors" }
        extrema.significantLocalExtrema.forEach { potentialAnchors.add(it to TemperatureRole.LOCAL) }

        val deduplicatedIndices = deduplicateAnchors(potentialAnchors, labelTemps, actualLabelTemps)
        debug { "DEDUPLICATED_INDICES: $deduplicatedIndices" }
        val explicitAnchors = deduplicatedIndices.filter { idx ->
            potentialAnchors.any { it.first == idx && it.second != TemperatureRole.LOCAL }
        }.toSet()
        debug { "EXPLICIT_ANCHORS: $explicitAnchors" }

        val filteredIndices = GraphLabelPlacementUtils.filterDenseLabelCandidates(
            items = labelTemps,
            candidates = deduplicatedIndices.toList(),
            globalMaxIdx = extrema.dailyHighIndex,
            globalMinIdx = extrema.dailyLowIndex,
            maxCandidates = MAX_TEMP_LABEL_CANDIDATES,
            diffThresholds = DENSE_TEMP_DIFF_THRESHOLDS,
            valueFunction = { it.roundToInt() },
            logTag = TAG,
            protectedIndices = deduplicatedIndices.filter { it in extrema.significantLocalExtrema && it > effectiveActualEndIndex }.toSet(),
            immovableIndices = explicitAnchors,
        )

        val suppressLeftEdgeLabel = GraphLabelPlacementUtils.shouldSuppressLeftEdgeLabel(
            items = labelTemps,
            candidates = filteredIndices,
            globalMaxIdx = extrema.dailyHighIndex,
            globalMinIdx = extrema.dailyLowIndex,
            valueFunction = { it.roundToInt() },
            nearbyWindow = min(5, (hours.lastIndex / 3).coerceAtLeast(1))
        )

        val specialCandidates = mutableListOf<TempLabelCandidate>()
        val suppressedIndices = mutableSetOf<Int>()
        for (idx in filteredIndices.distinct()) {
            var role = resolveExtremaRole(idx, extrema, hours)

            if (checkLeftEdgeSuppression(idx, role, suppressLeftEdgeLabel).suppressed) {
                suppressedIndices.add(idx)
                continue
            }

            val fetchResult = checkFetchDotSuppression(idx, role, extrema, observedAt, hours)
            if (fetchResult.suppressed) {
                suppressedIndices.add(idx)
                continue
            }
            fetchResult.overriddenRole?.let { role = it }

            if (checkRedundantPairSuppression(idx, role, extrema, suppressedIndices, labelTemps, actualLabelTemps)) {
                suppressedIndices.add(idx)
                continue
            }

            if (checkTransitionBoundarySuppression(idx, role, effectiveActualEndIndex, transitionX, hours)) {
                suppressedIndices.add(idx)
                continue
            }

            if (checkEndpointSuppression(idx, role, hours)) {
                suppressedIndices.add(idx)
                continue
            }

            val isActualRole = role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.ACTUAL_LOW
            val forceForecast = role in listOf(TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW, TemperatureRole.LOCAL, TemperatureRole.START, TemperatureRole.END)
            val temps = if (isActualRole) actualLabelTemps else labelTemps

            debug { "ADDING_CANDIDATE idx=$idx role=$role temp=${temps[idx]} series=${if (forceForecast) "forecast" else "actual"}" }

            specialCandidates.add(TempLabelCandidate(idx, role, temps, hours[idx].temperature, forceForecast))
        }
        return specialCandidates
    }

    private fun resolveExtremaRole(
        idx: Int,
        extrema: ExtremaIndices,
        hours: List<HourData>,
    ): TemperatureRole = when (idx) {
        extrema.dailyHighIndex -> TemperatureRole.HIGH
        extrema.dailyLowIndex -> TemperatureRole.LOW
        0 -> TemperatureRole.START
        hours.lastIndex -> TemperatureRole.END
        extrema.actualHighIndex -> TemperatureRole.ACTUAL_HIGH
        extrema.actualLowIndex -> TemperatureRole.ACTUAL_LOW
        extrema.forecastHighIndex -> TemperatureRole.FORECAST_HIGH
        extrema.forecastLowIndex -> TemperatureRole.FORECAST_LOW
        extrema.pastForecastHighIndex -> TemperatureRole.PAST_FORECAST_HIGH
        extrema.pastForecastLowIndex -> TemperatureRole.PAST_FORECAST_LOW
        else -> TemperatureRole.LOCAL
    }

    private data class CandidatePlacement(
        val sx: Float,
        val sy: Float,
        val label: String,
        val labelPaint: Paint,
        val textWidth: Float,
        val clampedX: Float,
        val isFuture: Boolean,
        val isValley: Boolean,
        val isEssential: Boolean,
        val leaderLinePaint: Paint,
    )

    private fun resolveCandidatePlacement(
        ctx: RenderContext,
        hours: List<HourData>,
        candidate: TempLabelCandidate,
    ): CandidatePlacement? {
        val idx = candidate.index
        val temps = candidate.labelTemps
        val isFuture = candidate.forceForecastSeries || ctx.originalPoints[idx].first > (ctx.transitionX ?: -1f)
        val points = if (isFuture) ctx.forecastPoints else ctx.originalPoints
        val sx = if (candidate.role in listOf(TemperatureRole.LOW, TemperatureRole.HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.LOCAL)) {
            centerOfRun(idx, temps, candidate.forceForecastSeries, ctx.originalPoints, ctx.forecastPoints, ctx.transitionX).first
        } else points[idx].first
        val sy = ctx.tempToY(temps[idx])

        val label = formatTemp(temps[idx]) + "°"
        val labelPaint = if (isFuture) {
            val hour = hours[idx.coerceAtMost(hours.lastIndex)]
            Paint(ctx.paints.forecastTempLabelTextPaint).also {
                it.color = WeatherConditionColors.forecastColor(hour.isSunny, hour.isRainy, hour.isMixed, hour.isNight)
            }
        } else ctx.paints.actualTempLabelTextPaint
        val textWidth = labelPaint.measureText(label)
        val clampedX = sx.coerceIn(textWidth / 2f, ctx.widthPx - textWidth / 2f)

        val fetchDotX = ctx.fetchDotX
        val lastObservedTemp = ctx.lastObservedTemp
        if (fetchDotX != null && lastObservedTemp != null && candidate.role !in setOf(TemperatureRole.START, TemperatureRole.END)) {
            val fetchDotLabel = formatTemp(lastObservedTemp) + "°"
            val dist = kotlin.math.abs(clampedX - fetchDotX)
            if (label == fetchDotLabel && dist < dpToPx(ctx.context, 12f)) {
                debug { "LABEL_CANDIDATE_SKIPPED idx=$idx role=${candidate.role} reason=REDUNDANT_WITH_FETCH_DOT dist=$dist" }
                return null
            }
        }

        val leftVal = findPrevDifferent(temps, idx)
        val rightVal = findNextDifferent(temps, idx)
        val isValley = candidate.role in listOf(TemperatureRole.LOW, TemperatureRole.FORECAST_LOW, TemperatureRole.ACTUAL_LOW, TemperatureRole.PAST_FORECAST_LOW) || (candidate.role == TemperatureRole.LOCAL && temps[idx] < leftVal && temps[idx] < rightVal)
        val isEssential = candidate.role in setOf(TemperatureRole.LOW, TemperatureRole.HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.ACTUAL_LOW, TemperatureRole.ACTUAL_HIGH, TemperatureRole.PAST_FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.START, TemperatureRole.END, TemperatureRole.ACTUAL_END)

        val leaderLinePaint = if (isFuture) {
            Paint(ctx.paints.forecastLeaderLinePaint).also { it.color = withAlpha(labelPaint.color, 80) }
        } else ctx.paints.actualLeaderLinePaint

        return CandidatePlacement(sx, sy, label, labelPaint, textWidth, clampedX, isFuture, isValley, isEssential, leaderLinePaint)
    }

    private fun sortLabelCandidates(candidates: MutableList<TempLabelCandidate>) {
        candidates.sortWith(
            compareBy<TempLabelCandidate> {
                val displayTemp = it.labelTemps[it.index]
                val leftVal = findPrevDifferent(it.labelTemps, it.index)
                val rightVal = findNextDifferent(it.labelTemps, it.index)
                val isPeak = it.role in listOf(TemperatureRole.HIGH, TemperatureRole.FORECAST_HIGH, TemperatureRole.ACTUAL_HIGH, TemperatureRole.PAST_FORECAST_HIGH) || (it.role == TemperatureRole.LOCAL && displayTemp > leftVal && displayTemp > rightVal)
                if (isPeak) -displayTemp else displayTemp
            }.thenBy {
                when (it.role) {
                    TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_LOW -> 0
                    TemperatureRole.LOCAL, TemperatureRole.ACTUAL_END -> 1
                    else -> 2 // START, END
                }
            }
        )
    }

    private fun placeTemperatureLabels(
        ctx: RenderContext,
        hours: List<HourData>,
        drawnIconBounds: List<RectF>
    ) {
        val extrema = computeExtremaIndices(hours, ctx.transitionX, ctx.effectiveActualEndIndex, ctx.fetchTime)
        val specialCandidates = collectLabelCandidates(hours, extrema, ctx.effectiveActualEndIndex, ctx.transitionX, ctx.observedAt).toMutableList()

        val drawnLabelBounds = mutableListOf<RectF>()
        val labelAscent = fontAscent(ctx.paints.actualTempLabelTextPaint)
        val labelDescent = fontDescent(ctx.paints.actualTempLabelTextPaint)
        val labelHeight = labelDescent - labelAscent
        
        val gapDp = GraphLabelPlacementUtils.getLabelGapDp(isFallback = false)

        sortLabelCandidates(specialCandidates)

        debug { "LABEL_CANDIDATES total=${specialCandidates.size} candidates=${specialCandidates.map { "idx=${it.index} role=${it.role} temp=${String.format("%.2f", it.labelTemps[it.index])}° rawTemp=${String.format("%.2f", it.rawTemperature)}° series=${if (it.forceForecastSeries) "forecast" else "actual"}" }}" }

        for (candidate in specialCandidates) {
            val idx = candidate.index
            val temps = candidate.labelTemps
            val placement = resolveCandidatePlacement(ctx, hours, candidate)
            if (placement == null) continue

            debug { "DEBUG_SERIES: idx=$idx role=${candidate.role} forceForecast=${candidate.forceForecastSeries} isFuture=${placement.isFuture} transitionX=${ctx.transitionX} pointX=${ctx.originalPoints[idx].first}" }

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
                    if (!onScreen) {
                        Log.d(
                            TAG,
                            "LABEL_PLACEMENT_REJECTED role=${candidate.role} idx=$idx text=${placement.label} step=$step " +
                                "preferred=${if (!placeAbove) "below" else "above"} reason=OFF_SCREEN " +
                                "bounds=$bounds size=${ctx.widthPx}x${ctx.heightPx}",
                        )
                        continue
                    }
                    val overlapsLabel = drawnLabelBounds.any { RectF.intersects(it, bounds) }
                    val overlapsIcon = drawnIconBounds.any { RectF.intersects(it, bounds) }
                    val labelOverlap = if (overlapsLabel) GraphLabelPlacementUtils.maxVerticalOverlap(bounds, drawnLabelBounds) else 0f
                    val iconOverlap = if (overlapsIcon) GraphLabelPlacementUtils.maxVerticalOverlap(bounds, drawnIconBounds) else 0f
                    val allowMinorLabelOverlap = overlapsLabel && GraphLabelPlacementUtils.shouldAllowMinorOverlap(candidate.role, labelOverlap, labelHeight)
                    val allowMinorIconOverlap = overlapsIcon && GraphLabelPlacementUtils.shouldAllowMinorOverlap(candidate.role, iconOverlap, labelHeight)
                    val hasCollision =
                        (overlapsLabel && !allowMinorLabelOverlap) ||
                            (overlapsIcon && !allowMinorIconOverlap)
                    
                    if (placement.isEssential && forceBounds == null) { 
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
                            "LABEL_PLACEMENT_REJECTED role=${candidate.role} idx=$idx text=${placement.label} step=$step " +
                                "preferred=${if (!placeAbove) "below" else "above"} reason=COLLISION target=$collisionTarget " +
                                "labelOverlap=$labelOverlap iconOverlap=$iconOverlap threshold=$minorOverlapThreshold bounds=$bounds",
                        )
                    }
                    if (!hasCollision) {
                        if (allowMinorLabelOverlap || allowMinorIconOverlap) {
                            Log.d(
                                TAG,
                                "LABEL_PLACEMENT_ACCEPTED_WITH_MINOR_OVERLAP role=${candidate.role} idx=$idx text=${placement.label} step=$step " +
                                    "preferred=${if (!placeAbove) "below" else "above"} labelOverlap=$labelOverlap " +
                                    "iconOverlap=$iconOverlap threshold=$minorOverlapThreshold",
                            )
                        }
                        if (step > 0) {
                            val lineEndY = if (!placeAbove) bounds.top else bounds.bottom
                            ctx.canvas.drawLine(placement.clampedX, placement.sy, placement.clampedX, lineEndY, placement.leaderLinePaint)
                        }
                        ctx.canvas.drawText(placement.label, placement.clampedX, baselineY, placement.labelPaint)
                        drawnLabelBounds.add(bounds)
                        val reasonBase = if (!placeAbove) "below" else "above"
                        val reason = if (step > 0) "$reasonBase+$step" else reasonBase
                        val seriesLabel = if (placement.isFuture) "forecast" else "actual"
                        Log.d(
                            TAG,
                            "LABEL_PLACED role=${candidate.role} idx=$idx text=${placement.label} series=$seriesLabel " +
                                "placement=$reason forced=false bounds=$bounds",
                        )
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
                Log.d(
                    TAG,
                    "LABEL_PLACED role=${candidate.role} idx=$idx text=${placement.label} series=$seriesLabel " +
                        "placement=${if (forceDrawBelow) "below" else "above"}+$forceStep forced=true bounds=$forceBounds",
                )
                ctx.onLabelPlaced?.invoke(LabelPlacementDebug(idx, candidate.role, temps[idx], candidate.rawTemperature, placement.clampedX, forceBaselineY, !forceDrawBelow, seriesLabel, seriesLabel, "FORCED", forceStep))
            } else if (!placed) {
                val seriesLabel = if (placement.isFuture) "forecast" else "actual"
                Log.d(
                    TAG,
                    "LABEL_NOT_PLACED role=${candidate.role} idx=$idx text=${placement.label} series=$seriesLabel",
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
        val dayYBottom = ctx.heightPx - dpToPx(ctx.context, DAY_LABEL_BOTTOM_PADDING_DP)

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

    private data class ValueLabelLayout(
        val x: Float,
        val y: Float,
        val bounds: RectF,
        val align: Paint.Align,
    )

    private data class StalenessInitialLayout(
        val baselineY: Float,
        val bounds: RectF,
        val placeAbove: Boolean,
    )

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

    /**
     * Pre-compute the bounding rectangles that [drawFetchDot] will occupy,
     * so temperature labels can treat them as collision obstacles.
     */
    private fun computeFetchDotBounds(ctx: RenderContext, hours: List<HourData>): List<RectF> {
        val bounds = mutableListOf<RectF>()
        val observedAt = ctx.observedAt
        val fetchDotX = ctx.fetchDotX
        val lastObservedTemp = ctx.lastObservedTemp
        if (observedAt == null || fetchDotX == null || lastObservedTemp == null) return bounds
        val fetchY = ctx.tempToY(lastObservedTemp)
        val dotRadius = dpToPx(ctx.context, DOT_RADIUS_DP * ctx.labelScale)
        val clampedX = fetchDotX.coerceIn(dotRadius, ctx.widthPx - dotRadius)

        val outerRadius = dotRadius + ctx.paints.ringPaint.strokeWidth / 2f
        bounds.add(RectF(clampedX - outerRadius, fetchY - outerRadius, clampedX + outerRadius, fetchY + outerRadius))

        val valueLabel = formatTemp(lastObservedTemp) + "°"
        val valueWidth = ctx.paints.valueTextPaint.measureText(valueLabel)
        val sideGap = dpToPx(ctx.context, FETCH_DOT_SIDE_GAP_DP * ctx.labelScale)
        val aboveGap = dpToPx(ctx.context, FETCH_DOT_ABOVE_GAP_DP * ctx.labelScale)
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
            val padding = dpToPx(ctx.context, FETCH_DOT_SIDE_GAP_DP * ctx.labelScale)
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
        val dotRadius = dpToPx(ctx.context, DOT_RADIUS_DP * ctx.labelScale)
        val clampedX = fetchDotX.coerceIn(dotRadius, ctx.widthPx - dotRadius)

        val localDotPaint = Paint(ctx.paints.dotPaint).apply { color = tempToColor(lastObservedTemp) }
        ctx.canvas.drawCircle(clampedX, fetchY, dotRadius, localDotPaint)
        ctx.canvas.drawCircle(clampedX, fetchY, dotRadius, ctx.paints.ringPaint)
        ctx.canvas.drawCircle(clampedX, fetchY, dotRadius + ctx.paints.ringPaint.strokeWidth / 2f, ctx.paints.outerRingPaint)

        val valueLabel = formatTemp(lastObservedTemp) + "°"
        val valueWidth = ctx.paints.valueTextPaint.measureText(valueLabel)
        val sideGap = dpToPx(ctx.context, FETCH_DOT_SIDE_GAP_DP * ctx.labelScale)
        val aboveGap = dpToPx(ctx.context, FETCH_DOT_ABOVE_GAP_DP * ctx.labelScale)
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
            val padding = dpToPx(ctx.context, FETCH_DOT_SIDE_GAP_DP * ctx.labelScale)
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
                val bump = dpToPx(ctx.context, FETCH_DOT_ABOVE_GAP_DP * ctx.labelScale)
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

    private fun tempToY(temp: Float, graphTop: Float, graphHeight: Float, minTemp: Float, tempRange: Float): Float {
        return graphTop + graphHeight * (1 - (temp - minTemp) / tempRange)
    }

    private fun centerOfRun(idx: Int, temps: List<Float>, forceForecast: Boolean, original: List<Pair<Float, Float>>, forecast: List<Pair<Float, Float>>, transitionX: Float?): Pair<Float, Float> {
        val v = temps[idx]; var first = idx; var last = idx
        while (first > 0 && abs(temps[first - 1] - v) < 0.01f) first--
        while (last < temps.lastIndex && abs(temps[last + 1] - v) < 0.01f) last++
        val points = if (forceForecast || original[idx].first > (transitionX ?: -1f)) forecast else original
        return (points[first].first + points[last].first) / 2f to (points[first].second + points[last].second) / 2f
    }

    private data class Geometry(
        val graphTop: Float,
        val graphBottom: Float,
        val graphHeight: Float,
        val footerTop: Float,
        val hourWidth: Float,
        val minTimeEpoch: Long,
        val iconSize: Int,
        val iconTopPad: Float,
        val minTemp: Float,
        val maxTemp: Float,
        val tempRange: Float,
    )

    private data class GraphData(
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
    )

    private data class DebugCallbacks(
        val onGhostLineDebug: ((GhostLineDebug) -> Unit)?,
        val onActualLineResolved: ((ActualLineDebug) -> Unit)?,
        val onLabelPlaced: ((LabelPlacementDebug) -> Unit)?,
        val onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)?,
        val onFetchDotResolved: ((FetchDotDebug) -> Unit)?,
    )

    private data class RenderContext(
        val context: Context,
        val canvas: Canvas,
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,
        val labelScale: Float,
        val geo: Geometry,
        val data: GraphData,
        val paints: PaintSet,
        val currentTime: LocalDateTime,
        val debug: DebugCallbacks,
        val drawnLabelBounds: MutableList<RectF> = mutableListOf()
    ) {
        val graphTop get() = geo.graphTop
        val graphBottom get() = geo.graphBottom
        val graphHeight get() = geo.graphHeight
        val footerTop get() = geo.footerTop
        val minTemp get() = geo.minTemp
        val maxTemp get() = geo.maxTemp
        val tempRange get() = geo.tempRange
        val transitionX get() = data.transitionX
        val nowX get() = data.nowX
        val nowIndicatorVisible get() = data.nowIndicatorVisible
        val fetchTime get() = data.fetchTime
        val fetchDotX get() = data.fetchDotX
        val lastObservedTemp get() = data.lastObservedTemp
        val anchorDelta get() = data.anchorDelta
        val originalPoints get() = data.originalPoints
        val forecastPoints get() = data.forecastPoints
        val expectedPath get() = data.expectedPath
        val actualPath get() = data.actualPath
        val actualVisiblePoints get() = data.actualVisiblePoints
        val forecastPath get() = data.forecastPath
        val forecastFillPath get() = data.forecastFillPath
        val forecastSegmentPaths get() = data.forecastSegmentPaths
        val effectiveActualEndIndex get() = data.effectiveActualEndIndex
        val appliedDelta get() = data.appliedDelta
        val observedAt get() = data.observedAt
        val iconSize get() = geo.iconSize
        val iconTopPad get() = geo.iconTopPad
        val hourWidth get() = geo.hourWidth
        val smoothedForecastTemps get() = data.smoothedForecastTemps
        val smoothedExpectedTemps get() = data.smoothedExpectedTemps
        val expectedPoints get() = data.expectedPoints
        val onGhostLineDebug get() = debug.onGhostLineDebug
        val onActualLineResolved get() = debug.onActualLineResolved
        val onLabelPlaced get() = debug.onLabelPlaced
        val onDayLabelPlaced get() = debug.onDayLabelPlaced
        val onFetchDotResolved get() = debug.onFetchDotResolved

        companion object {
            fun create(
                context: Context,
                canvas: Canvas,
                widthPx: Int,
                heightPx: Int,
                density: Float,
                labelScale: Float,
                minTemp: Float,
                maxTemp: Float,
                tempRange: Float,
                layout: GraphLayout.Layout,
                hourWidth: Float,
                minTimeEpoch: Long,
                update: RenderContextUpdate,
                lastObservedTemp: Float?,
                appliedDelta: Float?,
                observedAt: Long?,
                paints: PaintSet,
                currentTime: LocalDateTime,
                onGhostLineDebug: ((GhostLineDebug) -> Unit)?,
                onActualLineResolved: ((ActualLineDebug) -> Unit)?,
                onLabelPlaced: ((LabelPlacementDebug) -> Unit)?,
                onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)?,
                onFetchDotResolved: ((FetchDotDebug) -> Unit)?,
            ): RenderContext = RenderContext(
                context = context,
                canvas = canvas,
                widthPx = widthPx,
                heightPx = heightPx,
                density = density,
                labelScale = labelScale,
                geo = Geometry(
                    graphTop = layout.graphTop,
                    graphBottom = layout.graphBottom,
                    graphHeight = layout.graphHeight,
                    footerTop = layout.footerTop,
                    hourWidth = hourWidth,
                    minTimeEpoch = minTimeEpoch,
                    iconSize = layout.iconSize,
                    iconTopPad = layout.iconTopPad,
                    minTemp = minTemp,
                    maxTemp = maxTemp,
                    tempRange = tempRange,
                ),
                data = GraphData(
                    transitionX = update.transitionX,
                    nowX = update.nowX,
                    nowIndicatorVisible = update.nowIndicatorVisible,
                    fetchTime = update.fetchTime,
                    fetchDotX = update.fetchDotX,
                    lastObservedTemp = lastObservedTemp,
                    anchorDelta = update.anchorDelta,
                    smoothedForecastTemps = update.smoothedForecastTemps,
                    smoothedExpectedTemps = update.smoothedExpectedTemps,
                    originalPoints = update.originalPoints,
                    forecastPoints = update.forecastPoints,
                    expectedPoints = update.expectedPoints,
                    originalPath = update.originalPath,
                    actualPath = update.actualPath,
                    actualVisiblePoints = update.actualVisiblePoints,
                    expectedPath = update.expectedPath,
                    forecastPath = update.forecastPath,
                    forecastFillPath = update.forecastFillPath,
                    forecastSegmentPaths = update.forecastSegmentPaths,
                    effectiveActualEndIndex = update.effectiveActualEndIndex,
                    appliedDelta = appliedDelta,
                    observedAt = observedAt,
                ),
                paints = paints,
                currentTime = currentTime,
                debug = DebugCallbacks(
                    onGhostLineDebug = onGhostLineDebug,
                    onActualLineResolved = onActualLineResolved,
                    onLabelPlaced = onLabelPlaced,
                    onDayLabelPlaced = onDayLabelPlaced,
                    onFetchDotResolved = onFetchDotResolved,
                ),
            )
        }
    }

    private fun RenderContext.tempToY(temp: Float): Float =
        this@TemperatureGraphRenderer.tempToY(temp, graphTop, graphHeight, minTemp, tempRange)

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

    private class RenderTimings {
        private val marks = mutableListOf<Pair<String, Long>>()
        fun mark(label: String) { marks.add(label to SystemClock.elapsedRealtime()) }
        fun log(widthPx: Int, heightPx: Int, hoursSize: Int) {
            if (!BuildConfig.DEBUG || marks.size < 2) return
            val parts = marks.zipWithNext().map { (a, b) -> "${a.first}=${b.second - a.second}ms" }
            debug { "RENDER_BREAKDOWN size=${widthPx}x${heightPx} hours=$hoursSize ${parts.joinToString(" ")} total=${marks.last().second - marks.first().second}ms" }
        }
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
        debug { "FETCH_DOT_PRE_BOUNDS count=${fetchDotPreBounds.size} bounds=$fetchDotPreBounds" }
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

        timings.log(widthPx, heightPx, hours.size)

        return bitmap
    }

    private fun dpToPx(context: Context, dp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}
