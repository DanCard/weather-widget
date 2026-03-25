package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
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

object TemperatureGraphRenderer {
    private const val TAG = "TempGraphRenderer"

    private const val MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES = 1.5f
    private const val GRAPH_TOP_PADDING_DP = 8f
    private const val GRAPH_BOTTOM_OVERLAP_DP = 10f
    private const val TOP_TEMP_BUFFER_RATIO = 0.1f
    private const val BOTTOM_TEMP_BUFFER_RATIO = 0.03f
    private const val MIN_TOP_TEMP_BUFFER_DEGREES = 3f
    private const val MIN_BOTTOM_TEMP_BUFFER_DEGREES = 1.5f
    private const val MIN_GHOST_LINE_DELTA = 0.1f

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
    private val COLOR_ACTUAL_LINE = Color.parseColor("#F4C542")
    private val COLOR_FORECAST_LINE = Color.parseColor("#8FB7FF")
    private val COLOR_ACTUAL_LABEL = Color.parseColor("#FFF1A8")
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
            color = COLOR_FORECAST_LINE
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
            color = Color.parseColor("#BBF4C542")
            textSize = dpToPx(context, 19.5f * labelScale)
            textAlign = Paint.Align.LEFT
            setShadowLayer(dpToPx(context, 1f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
        }

        val stalenessTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#88F4C542")
            textSize = dpToPx(context, 12f * labelScale)
            textAlign = Paint.Align.CENTER
            setShadowLayer(dpToPx(context, 1f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
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
        val graphBottom = (footerTop + dpToPx(context, GRAPH_BOTTOM_OVERLAP_DP)).coerceAtMost(heightPx.toFloat() - labelHeight)
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
        widthPx: Int,
        onPointsResolved: ((PointsDebug) -> Unit)?,
    ): RenderContextUpdate {
        val effectiveDelta = appliedDelta ?: 0f
        val rawForecastTemps = hours.map { it.temperature }
        val smoothedForecastTemps = GraphRenderUtils.smoothValuesPreservingGlobalExtrema(rawForecastTemps, iterations = 1)
        val actualTemps = hours.map { it.actualTemperature ?: (it.temperature + effectiveDelta) }

        val fetchTime = observedAt?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }
        val fetchIdx = fetchTime?.let { time -> hours.indexOfLast { !it.dateTime.isAfter(time) } } ?: -1
        val fetchFraction = if (fetchTime != null && fetchIdx != -1 && fetchIdx < smoothedForecastTemps.lastIndex) {
            Duration.between(hours[fetchIdx].dateTime, fetchTime).toMinutes() / 60f
        } else null

        val interpolatedForecastAtFetch = if (fetchFraction != null && fetchIdx != -1) {
            val t = GraphRenderUtils.computeTangents(hours.indices.map { 0f to smoothedForecastTemps[it] })
            GraphRenderUtils.evaluateCubicY(smoothedForecastTemps[fetchIdx], t[fetchIdx].second, smoothedForecastTemps[fetchIdx + 1], t[fetchIdx + 1].second, fetchFraction)
        } else null

        val interpolatedTruthAtFetch = if (fetchFraction != null && fetchIdx != -1) {
            actualTemps[fetchIdx] + (actualTemps[fetchIdx + 1] - actualTemps[fetchIdx]) * fetchFraction
        } else null

        val anchorDelta = if (interpolatedForecastAtFetch != null && interpolatedTruthAtFetch != null) {
            interpolatedTruthAtFetch - interpolatedForecastAtFetch
        } else effectiveDelta

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

        val (originalPath, _) = GraphRenderUtils.buildSmoothCurveAndFillPaths(originalPoints, graphBottom)
        val (expectedPath, expectedFillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(expectedPoints, graphBottom)
        val (forecastPath, forecastFillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(forecastPoints, graphBottom)

        val nowX = GraphRenderUtils.computeNowX(hours, originalPoints, currentTime, hourWidth, { it.isCurrentHour }, { it.dateTime })
        val nowIndicatorVisible = nowX != null && nowX in 0f..widthPx.toFloat()

        val lastActualIndex = hours.indexOfLast { it.isActual }
        val rawTransitionX: Float? = if (lastActualIndex >= 0) originalPoints[lastActualIndex].first else null
        val fetchDotX: Float? = if (observedAt != null && fetchTime != null) {
            GraphRenderUtils.computeXForTime(fetchTime, hours, originalPoints, hourWidth) { it.dateTime }
        } else null

        val transitionX: Float? = rawTransitionX?.let { raw ->
            listOfNotNull(raw, nowX, fetchDotX).min()
        }
        val effectiveActualEndIndex = if (transitionX != null) {
            val idx = originalPoints.indexOfLast { it.first <= transitionX + 1f }
            if (idx >= 0) idx else lastActualIndex
        } else -1

        return RenderContextUpdate(
            smoothedForecastTemps, smoothedExpectedTemps, originalPoints, forecastPoints, expectedPoints,
            originalPath, expectedPath, expectedFillPath, forecastPath, forecastFillPath,
            nowX, nowIndicatorVisible, fetchTime, fetchDotX, interpolatedTruthAtFetch,
            anchorDelta, transitionX, effectiveActualEndIndex
        )
    }

    private fun drawFillAndCurves(ctx: RenderContext, expectedFillPath: Path) {
        val paints = ctx.paints
        paints.expectedFillPaint.shader = buildTempGradient(
            ctx.graphTop, ctx.graphBottom, ctx.minTemp, ctx.maxTemp, ctx.tempRange, alphaTop = 68, alphaBottom = 0
        )
        ctx.canvas.drawPath(expectedFillPath, paints.expectedFillPaint)

        if (ctx.nowIndicatorVisible && ctx.appliedDelta != null && abs(ctx.appliedDelta) >= MIN_GHOST_LINE_DELTA && ctx.fetchDotX != null) {
            val expectedY = if (ctx.interpolatedTruthAtFetch != null) {
                ctx.graphTop + ctx.graphHeight * (1 - (ctx.interpolatedTruthAtFetch - ctx.minTemp) / ctx.tempRange)
            } else null
            if (expectedY != null) ctx.onGhostLineDebug?.invoke(GhostLineDebug(ctx.fetchDotX, expectedY))

            ctx.canvas.save()
            ctx.canvas.clipRect(ctx.fetchDotX, 0f, ctx.widthPx.toFloat(), ctx.heightPx.toFloat())
            ctx.canvas.drawPath(ctx.expectedPath, paints.ghostPaint)
            ctx.canvas.restore()
        }

        ctx.canvas.drawPath(ctx.forecastPath, paints.forecastDashedPaint)

        if (ctx.transitionX != null) {
            ctx.canvas.save()
            ctx.canvas.clipRect(0f, 0f, ctx.transitionX + dpToPx(ctx.context, 1f), ctx.heightPx.toFloat())
            ctx.canvas.drawPath(ctx.originalPath, paints.actualLinePaint)
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
        val labelTemps = hours.map { it.actualTemperature ?: it.temperature }
        val forecastLabelTemps = hours.map { it.temperature }
        val dailyHighIndex = labelTemps.indices.maxByOrNull { labelTemps[it] } ?: -1
        val dailyLowIndex = labelTemps.indices.minByOrNull { labelTemps[it] } ?: -1
        val forecastHighIndex = forecastLabelTemps.indices.maxByOrNull { forecastLabelTemps[it] } ?: -1
        val forecastLowIndex = forecastLabelTemps.indices.minByOrNull { forecastLabelTemps[it] } ?: -1

        val localExtrema = findLocalExtremaIndices(labelTemps)
        val significantLocalExtrema = localExtrema.filter { index ->
            bilateralExtremaProminence(index, labelTemps, localExtrema) >= MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES
        }

        val specialCandidates = mutableListOf<TempLabelCandidate>()
        fun addCandidate(index: Int, role: String, temps: List<Float>, forceForecast: Boolean = false) {
            if (index !in temps.indices) return
            val text = formatTemp(temps[index])
            if (specialCandidates.none { it.index == index || (abs(it.index - index) <= 3 && formatTemp(it.labelTemps[it.index]) == text) }) {
                specialCandidates.add(TempLabelCandidate(index, role, temps, hours[index].temperature, forceForecast))
            }
        }

        if (dailyLowIndex >= 0) addCandidate(dailyLowIndex, "LOW", labelTemps)
        if (dailyHighIndex >= 0 && dailyHighIndex != dailyLowIndex) addCandidate(dailyHighIndex, "HIGH", labelTemps)
        if (forecastLowIndex >= 0) addCandidate(forecastLowIndex, "FORECAST_LOW", forecastLabelTemps, true)
        if (forecastHighIndex >= 0) addCandidate(forecastHighIndex, "FORECAST_HIGH", forecastLabelTemps, true)

        significantLocalExtrema.forEach { idx ->
            if (!hours[idx].isActual && specialCandidates.none { it.index == idx }) {
                val text = formatTemp(labelTemps[idx])
                if (specialCandidates.none { abs(idx - it.index) <= 3 && formatTemp(it.labelTemps[it.index]) == text }) {
                    addCandidate(idx, "LOCAL", labelTemps)
                }
            }
        }
        if (ctx.effectiveActualEndIndex > 0 && ctx.effectiveActualEndIndex < hours.size - 1) {
            val isFetchDotPoint = ctx.fetchDotX != null && abs(ctx.originalPoints[ctx.effectiveActualEndIndex].first - ctx.fetchDotX) < 1f
            if (specialCandidates.none { it.index == ctx.effectiveActualEndIndex } && !isFetchDotPoint) {
                val text = formatTemp(labelTemps[ctx.effectiveActualEndIndex])
                if (specialCandidates.none { abs(ctx.effectiveActualEndIndex - it.index) <= 3 && formatTemp(it.labelTemps[it.index]) == text }) {
                    addCandidate(ctx.effectiveActualEndIndex, "ACTUAL_END", labelTemps)
                }
            }
        }
        if (specialCandidates.none { it.index == 0 }) addCandidate(0, "START", labelTemps)
        if (hours.size > 1 && specialCandidates.none { it.index == hours.size - 1 }) addCandidate(hours.size - 1, "END", labelTemps)

        val drawnLabelBounds = mutableListOf<RectF>()
        val labelFontMetrics = ctx.paints.actualTempLabelTextPaint.fontMetrics
        val labelAscent = if (labelFontMetrics != null && labelFontMetrics.ascent != 0f) labelFontMetrics.ascent else (-ctx.paints.actualTempLabelTextPaint.textSize)
        val labelDescent = if (labelFontMetrics != null && labelFontMetrics.descent != 0f) labelFontMetrics.descent else (ctx.paints.actualTempLabelTextPaint.textSize * 0.2f)
        val aboveGap = dpToPx(ctx.context, -0.1f)
        val belowGap = dpToPx(ctx.context, -0.1f)

        for (candidate in specialCandidates) {
            val idx = candidate.index
            val temps = candidate.labelTemps
            val isFuture = candidate.forceForecastSeries || ctx.originalPoints[idx].first > (ctx.transitionX ?: -1f)
            val points = if (isFuture) ctx.forecastPoints else ctx.originalPoints
            val (sx, sy) = if (candidate.role == "LOW" || candidate.role == "HIGH" || candidate.role == "FORECAST_HIGH") {
                centerOfRun(idx, temps, candidate.forceForecastSeries, ctx.originalPoints, ctx.forecastPoints, ctx.transitionX)
            } else points[idx].first to points[idx].second

            val label = formatTemp(temps[idx]) + "°"
            val labelPaint = if (isFuture) ctx.paints.forecastTempLabelTextPaint else ctx.paints.actualTempLabelTextPaint
            val textWidth = labelPaint.measureText(label)
            val clampedX = sx.coerceIn(textWidth / 2f, ctx.widthPx - textWidth / 2f)

            val leftVal = temps.subList(0, idx).findLast { it != temps[idx] } ?: 0f
            val isValley = candidate.role == "LOW" || candidate.role == "FORECAST_LOW" || (candidate.role == "LOCAL" && idx in significantLocalExtrema && temps[idx] < leftVal)
            val isEssential = candidate.role in listOf("LOW", "HIGH", "FORECAST_LOW", "FORECAST_HIGH", "START", "END", "ACTUAL_END")
            
            val attempts = if (isValley) listOf(true, false) else listOf(false, true)
            for (attemptIdx in attempts.indices) {
                val drawBelow = attempts[attemptIdx]
                val baselineY = if (drawBelow) sy + belowGap - labelAscent else sy - aboveGap - labelDescent
                val bounds = RectF(clampedX - textWidth / 2f, baselineY + labelAscent, clampedX + textWidth / 2f, baselineY + labelDescent)
                val onScreen = bounds.top >= 0f && bounds.bottom <= ctx.heightPx
                val hasCollision = drawnLabelBounds.any { RectF.intersects(it, bounds) } || drawnIconBounds.any { RectF.intersects(it, bounds) }

                if (onScreen && (!hasCollision || (isEssential && attemptIdx > 0))) {
                    ctx.canvas.drawText(label, clampedX, baselineY, labelPaint)
                    drawnLabelBounds.add(bounds)
                    ctx.onLabelPlaced?.invoke(LabelPlacementDebug(idx, candidate.role, temps[idx], candidate.rawTemperature, clampedX, baselineY, !drawBelow, if (isFuture) "forecast" else "actual", if (isFuture) "forecast" else "actual", if (hasCollision) "FORCED" else if (drawBelow) "below" else "above"))
                    break
                }
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

    private fun drawFetchDot(ctx: RenderContext, hours: List<HourData>) {
        if (ctx.observedAt == null || ctx.fetchDotX == null || ctx.interpolatedTruthAtFetch == null) return
        val fetchY = ctx.graphTop + ctx.graphHeight * (1 - (ctx.interpolatedTruthAtFetch - ctx.minTemp) / ctx.tempRange)
        val dotRadius = dpToPx(ctx.context, 3.2f * ctx.labelScale)
        val clampedX = ctx.fetchDotX.coerceIn(dotRadius, ctx.widthPx - dotRadius)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tempToColor(ctx.interpolatedTruthAtFetch); style = Paint.Style.FILL }
        ctx.canvas.drawCircle(clampedX, fetchY, dotRadius, dotPaint)
        ctx.canvas.drawCircle(clampedX, fetchY, dotRadius, ctx.paints.ringPaint)
        ctx.canvas.drawCircle(clampedX, fetchY, dotRadius + ctx.paints.ringPaint.strokeWidth / 2f, ctx.paints.outerRingPaint)

        val ageMinutes = ctx.fetchTime?.let { Duration.between(it, ctx.currentTime).toMinutes() } ?: 0L
        val ageLabel = if (ageMinutes >= 0 && Duration.between(hours.first().dateTime, hours.last().dateTime).toHours() <= 12) {
            if (ageMinutes >= 60) "${ageMinutes / 60}h${if (ageMinutes % 60 > 0) " ${ageMinutes % 60}m" else ""}" else "${ageMinutes}m"
        } else null

        if (ageLabel != null) {
            val ageY = fetchY + dotRadius + dpToPx(ctx.context, 4f * ctx.labelScale) - ctx.paints.stalenessTextPaint.ascent()
            if (ageY + ctx.paints.stalenessTextPaint.descent() <= ctx.heightPx) ctx.canvas.drawText(ageLabel, clampedX, ageY, ctx.paints.stalenessTextPaint)
        }

        val valueLabel = formatTemp(ctx.interpolatedTruthAtFetch) + "°"
        val valueWidth = ctx.paints.valueTextPaint.measureText(valueLabel)
        val sideGap = dpToPx(ctx.context, 4f * ctx.labelScale)
        var drawn = false

        if (clampedX + dotRadius + sideGap + valueWidth <= ctx.widthPx) {
            ctx.paints.valueTextPaint.textAlign = Paint.Align.LEFT
            ctx.canvas.drawText(valueLabel, clampedX + dotRadius + sideGap, fetchY + ctx.paints.valueTextPaint.textSize / 3f, ctx.paints.valueTextPaint)
            drawn = true
        }
        if (!drawn && clampedX - dotRadius - sideGap - valueWidth >= 0) {
            ctx.paints.valueTextPaint.textAlign = Paint.Align.RIGHT
            ctx.canvas.drawText(valueLabel, clampedX - dotRadius - sideGap, fetchY + ctx.paints.valueTextPaint.textSize / 3f, ctx.paints.valueTextPaint)
            drawn = true
        }
        if (!drawn && fetchY - dotRadius - dpToPx(ctx.context, 2f * ctx.labelScale) + ctx.paints.valueTextPaint.ascent() >= 0) {
            ctx.paints.valueTextPaint.textAlign = Paint.Align.CENTER
            ctx.canvas.drawText(valueLabel, clampedX, fetchY - dotRadius - dpToPx(ctx.context, 2f * ctx.labelScale), ctx.paints.valueTextPaint)
        }

        ctx.onFetchDotResolved?.invoke(FetchDotDebug(ctx.observedAt, clampedX, fetchY, true, if (ageLabel != null) "$valueLabel ($ageLabel)" else valueLabel, ctx.paints.valueTextPaint.color, if (ageLabel != null) ctx.paints.stalenessTextPaint.color else null))
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
        val interpolatedTruthAtFetch: Float?,
        val anchorDelta: Float,
        val smoothedForecastTemps: List<Float>,
        val smoothedExpectedTemps: List<Float>,
        val originalPoints: List<Pair<Float, Float>>,
        val forecastPoints: List<Pair<Float, Float>>,
        val expectedPoints: List<Pair<Float, Float>>,
        val originalPath: Path,
        val expectedPath: Path,
        val forecastPath: Path,
        val forecastFillPath: Path,
        val effectiveActualEndIndex: Int,
        val appliedDelta: Float?,
        val observedAt: Long?,
        val paints: PaintSet,
        val currentTime: LocalDateTime,
        val onGhostLineDebug: ((GhostLineDebug) -> Unit)?,
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
        val expectedPath: Path,
        val expectedFillPath: Path,
        val forecastPath: Path,
        val forecastFillPath: Path,
        val nowX: Float?,
        val nowIndicatorVisible: Boolean,
        val fetchTime: LocalDateTime?,
        val fetchDotX: Float?,
        val interpolatedTruthAtFetch: Float?,
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
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
        onFetchDotResolved: ((FetchDotDebug) -> Unit)? = null,
        onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)? = null,
        onGhostLineDebug: ((GhostLineDebug) -> Unit)? = null,
        onPointsResolved: ((PointsDebug) -> Unit)? = null,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (hours.isEmpty()) return bitmap

        val labelScale = bitmapScale.coerceIn(0.5f, 1f)
        val paints = ensurePaints(context, labelScale)
        val density = context.resources.displayMetrics.density

        val (minTemp, maxTemp, tempRange) = computeScaling(hours)
        val layout = computeLayout(context, heightPx, labelScale)

        val minTimeEpoch = hours.firstOrNull()?.dateTime?.toEpochSecond(ZoneOffset.UTC) ?: 0L
        val maxTimeEpoch = hours.lastOrNull()?.dateTime?.toEpochSecond(ZoneOffset.UTC) ?: 0L
        val timeRangeHours = if (maxTimeEpoch > minTimeEpoch) (maxTimeEpoch - minTimeEpoch) / 3600f else hours.size.toFloat() - 1f
        val hourWidth = widthPx.toFloat() / timeRangeHours.coerceAtLeast(1f)

        val update = computePoints(
            hours, minTemp, tempRange, layout.graphTop, layout.graphHeight, layout.graphBottom,
            hourWidth, minTimeEpoch, currentTime, appliedDelta, observedAt, widthPx, onPointsResolved
        )

        val ctx = RenderContext(
            context, canvas, widthPx, heightPx, density, labelScale, minTemp, maxTemp, tempRange,
            layout.graphTop, layout.graphBottom, layout.graphHeight, layout.footerTop, hourWidth, minTimeEpoch,
            layout.iconSize, layout.iconTopPad, update.transitionX, update.nowX, update.nowIndicatorVisible,
            update.fetchTime, update.fetchDotX, update.interpolatedTruthAtFetch, update.anchorDelta,
            update.smoothedForecastTemps, update.smoothedExpectedTemps, update.originalPoints,
            update.forecastPoints, update.expectedPoints, update.originalPath, update.expectedPath,
            update.forecastPath, update.forecastFillPath, update.effectiveActualEndIndex,
            appliedDelta, observedAt, paints, currentTime, onGhostLineDebug, onLabelPlaced,
            onDayLabelPlaced, onFetchDotResolved
        )

        drawFillAndCurves(ctx, update.expectedFillPath)
        
        val drawnIconBounds = mutableListOf<RectF>()
        drawHourLabelsAndIcons(ctx, hours, drawnIconBounds)
        placeTemperatureLabels(ctx, hours, drawnIconBounds)
        placeDayLabels(ctx, hours, drawnIconBounds)

        GraphRenderUtils.drawNowIndicator(
            canvas, if (update.nowIndicatorVisible) update.nowX else null, ctx.graphTop, ctx.graphHeight,
            paints.currentTimePaint, paints.nowLabelTextPaint
        ) { dpToPx(context, it) }

        drawFetchDot(ctx, hours)

        return bitmap
    }

    private fun dpToPx(context: Context, dp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}
