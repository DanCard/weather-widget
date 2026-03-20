package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.Log
import android.util.TypedValue
import java.time.LocalDateTime

object TemperatureGraphRenderer {

    private const val MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES = 1.5f
    private const val GRAPH_TOP_PADDING_DP = 8f
    private const val GRAPH_BOTTOM_OVERLAP_DP = 10f
    private const val TOP_TEMP_BUFFER_RATIO = 0.1f
    private const val BOTTOM_TEMP_BUFFER_RATIO = 0.03f
    private const val MIN_TOP_TEMP_BUFFER_DEGREES = 3f
    private const val MIN_BOTTOM_TEMP_BUFFER_DEGREES = 1.5f

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
    )

    // Temperature-to-color thresholds
    private const val COLD_THRESHOLD = 50f
    private const val MILD_TEMP = 70f
    private const val HOT_THRESHOLD = 90f

    private val COLOR_COLD = Color.parseColor("#5AC8FA") // Blue
    private val COLOR_MILD = Color.parseColor("#E8A24E") // Golden amber
    private val COLOR_HOT = Color.parseColor("#FF6B35") // Warm orange

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

    private fun withAlpha(
        color: Int,
        alpha: Int,
    ): Int {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
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
        val reason: String = "",
    )

    data class FetchDotDebug(
        val actualSeriesAnchorAt: Long,
        val fetchDotX: Float?,
        val fetchY: Float? = null,
        val withinWindow: Boolean,
    )

    data class GhostLineDebug(
        val startX: Float,
        val startY: Float,
    )

    data class DayLabelPlacementDebug(
        val side: String,       // "LEFT" or "RIGHT"
        val dayText: String,
        val date: java.time.LocalDate,
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

    fun renderGraph(
        context: Context,
        hours: List<HourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        appliedDelta: Float? = null,
        actualSeriesAnchorAt: Long? = null,
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
        onFetchDotResolved: ((FetchDotDebug) -> Unit)? = null,
        onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)? = null,
        onGhostLineDebug: ((GhostLineDebug) -> Unit)? = null,
        onPointsResolved: ((PointsDebug) -> Unit)? = null,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (hours.isEmpty()) return bitmap

        // Expected (Corrected) Hours — delta applied to forecast for ghost line range calculation
        val expectedHours = hours.map { it.copy(temperature = it.temperature + (appliedDelta ?: 0f)) }

        // Find temperature range for scaling (forecast + actuals + expected)
        val allTemps = hours.map { it.temperature } +
            hours.mapNotNull { it.actualTemperature } +
            expectedHours.map { it.temperature }
        val rawMin = (allTemps.minOrNull() ?: 0f)
        val rawMax = (allTemps.maxOrNull() ?: 100f)
        val rawRange = (rawMax - rawMin).coerceAtLeast(1f)

        // Keep more headroom above peaks for labels, but use a smaller bottom buffer so the
        // graph can visually consume more of the footer area while footer touch targets stay on top.
        val topBuffer = (rawRange * TOP_TEMP_BUFFER_RATIO).coerceAtLeast(MIN_TOP_TEMP_BUFFER_DEGREES)
        val bottomBuffer = (rawRange * BOTTOM_TEMP_BUFFER_RATIO).coerceAtLeast(MIN_BOTTOM_TEMP_BUFFER_DEGREES)
        val minTemp = rawMin - bottomBuffer
        val maxTemp = rawMax + topBuffer
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1f)

        val density = context.resources.displayMetrics.density
        
        // Layout zones
        val topPadding = dpToPx(context, GRAPH_TOP_PADDING_DP)
        val iconSizeDp = 16f
        val iconSize = dpToPx(context, iconSizeDp).toInt()
        val labelHeight = dpToPx(context, 10f)
        val iconTopPad = dpToPx(context, 2f)
        val iconBottomPad = dpToPx(context, 1f)

        val graphTop = topPadding
        val footerTop = heightPx - labelHeight - iconBottomPad - iconSize - iconTopPad
        val graphBottom = (footerTop + dpToPx(context, GRAPH_BOTTOM_OVERLAP_DP)).coerceAtMost(heightPx.toFloat() - labelHeight)
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        android.util.Log.d("TempGraphRenderer", "Layout: widthPx=$widthPx, heightPx=$heightPx, topPadding=$topPadding, footerTop=$footerTop, graphTop=$graphTop, graphBottom=$graphBottom, graphHeight=$graphHeight")

        val minTimeEpoch = hours.firstOrNull()?.dateTime?.toEpochSecond(java.time.ZoneOffset.UTC) ?: 0L
        val maxTimeEpoch = hours.lastOrNull()?.dateTime?.toEpochSecond(java.time.ZoneOffset.UTC) ?: 0L
        val timeRangeHours = if (maxTimeEpoch > minTimeEpoch) (maxTimeEpoch - minTimeEpoch) / 3600f else hours.size.toFloat() - 1f
        val hourWidth = widthPx.toFloat() / (timeRangeHours + 1f).coerceAtLeast(1f)

        // --- Paints ---

        val curveStrokeDp = 1f

        // Actual observed line — solid gradient, primary in past portion
        val actualLinePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = dpToPx(context, curveStrokeDp)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                shader = buildTempGradient(graphTop, graphBottom, minTemp, maxTemp, tempRange)
            }

        // Forecast line — thin dashed gradient, runs full width
        val forecastDashedPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = dpToPx(context, 1f)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                shader = buildTempGradient(graphTop, graphBottom, minTemp, maxTemp, tempRange)
                pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, 8f), dpToPx(context, 4f)), 0f)
            }

        // Keep backward-compat alias for label-placement code below that references originalCurvePaint
        val originalCurvePaint = actualLinePaint

        // Expected Truth Line (Ghost Dashed Curve) — future only
        val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 55 // ~22% opacity
            strokeWidth = dpToPx(context, 1.2f)
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(0.1f, dpToPx(context, 4f)), 0f)
        }

        // Gradient fill under the Expected Truth (Reality)
        val expectedFillPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader =
                    buildTempGradient(
                        graphTop,
                        graphBottom,
                        minTemp,
                        maxTemp,
                        tempRange,
                        alphaTop = 68,
                        alphaBottom = 0,
                    )
            }

        val currentTimePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF9F0A")
                strokeWidth = dpToPx(context, 0.5f)
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, 4f), dpToPx(context, 3f)), 0f)
            }

        val labelScale = bitmapScale.coerceIn(0.5f, 1f)

        val hourLabelTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#99FFFFFF")
                textSize = dpToPx(context, 19.5f * labelScale)
                textAlign = Paint.Align.CENTER
                setShadowLayer(dpToPx(context, 1f), 0f, dpToPx(context, 0.5f), Color.parseColor("#44000000"))
            }

        val tempLabelTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFFFFF")
                textSize = dpToPx(context, 19.5f * labelScale)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setShadowLayer(dpToPx(context, 2f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
            }

        val nowLabelTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#BBFF9F0A")
                textSize = dpToPx(context, 15.5f * labelScale)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setShadowLayer(dpToPx(context, 1f), 0f, 0f, Color.parseColor("#44000000"))
            }

        val dayLabelTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#88FFFFFF")
                textSize = dpToPx(context, 19.5f * labelScale)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }

        val todayDayLabelPaint =
            Paint(dayLabelTextPaint).apply {
                color = Color.parseColor("#BBFF9F0A")
            }

        // --- Build paths ---
        val effectiveDelta = appliedDelta ?: 0f

        // Forecast curve — pure forecast temps, runs full width (thin dashed)
        val rawForecastTemps = hours.map { it.temperature }
        val smoothedForecastTemps = GraphRenderUtils.smoothValues(rawForecastTemps, iterations = 1)

        // --- Reality / Truth Curve (Solid Line Path + Ghost Line Path + Fetch Dot grounding) ---
        // For the solid actual line (past) and fetch dot grounding, we use raw observed values
        // where available, falling back to forecast + delta elsewhere.
        // We remove smoothing here to ensure the solid line meets the dot exactly.
        val rawTruthTemps = hours.map { it.actualTemperature ?: (it.temperature + effectiveDelta) }
        val smoothedTruthTemps = rawTruthTemps // No smoothing for exact junction matching

        // Calculate anchorDelta at fetch time using raw values to ensure perfect grounding.
        val fetchTime = actualSeriesAnchorAt?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        }
        val fetchIdx = fetchTime?.let { time -> hours.indexOfLast { !it.dateTime.isAfter(time) } } ?: -1
        val fetchFraction = if (fetchTime != null && fetchIdx != -1 && fetchIdx < smoothedForecastTemps.lastIndex) {
            java.time.Duration.between(hours[fetchIdx].dateTime, fetchTime).toMinutes() / 60f
        } else null
        
        val interpolatedForecastAtFetch = if (fetchFraction != null && fetchIdx != -1) {
            smoothedForecastTemps[fetchIdx] + (smoothedForecastTemps[fetchIdx + 1] - smoothedForecastTemps[fetchIdx]) * fetchFraction
        } else null

        val interpolatedTruthAtFetch = if (fetchFraction != null && fetchIdx != -1) {
            smoothedTruthTemps[fetchIdx] + (smoothedTruthTemps[fetchIdx + 1] - smoothedTruthTemps[fetchIdx]) * fetchFraction
        } else null

        val anchorDelta = if (interpolatedForecastAtFetch != null && interpolatedTruthAtFetch != null) {
            interpolatedTruthAtFetch - interpolatedForecastAtFetch
        } else effectiveDelta

        // --- Ghost Line Curve (Future Projection) ---
        // Parallelism: We ensure the ghost line is a pure translation of the forecast line
        // by making the smoothedExpectedTemps exactly equal to smoothedForecastTemps + anchorDelta.
        val smoothedExpectedTemps = smoothedForecastTemps.map { it + anchorDelta }

        // --- Label Curve (Used ONLY for label text values) ---
        // We show raw actual history or raw forecast (no delta) in the label text.
        // We keep smoothing here so the label placement is visually fluid.
        val rawLabelTemps = hours.map { it.actualTemperature ?: it.temperature }
        val smoothedLabelTemps = rawLabelTemps

        // Keep backward-compat name for transitionX and fetch dot grounding
        val smoothedActualOrForecastTemps = smoothedTruthTemps

        val originalPoints = mutableListOf<Pair<Float, Float>>()
        val forecastPoints = mutableListOf<Pair<Float, Float>>()
        val expectedPoints = mutableListOf<Pair<Float, Float>>()

        hours.indices.forEach { index ->
            val pointEpoch = hours[index].dateTime.toEpochSecond(java.time.ZoneOffset.UTC)
            val x = hourWidth / 2f + ((pointEpoch - minTimeEpoch) / 3600f) * hourWidth
            // Use Truth Y for the solid line path so it meets the dot
            val yTruth = graphTop + graphHeight * (1 - (smoothedTruthTemps[index] - minTemp) / tempRange)
            originalPoints.add(x to yTruth)

            val yForecast = graphTop + graphHeight * (1 - (smoothedForecastTemps[index] - minTemp) / tempRange)
            forecastPoints.add(x to yForecast)
            
            // Use translation-based expected Y for ghost line shape
            val yExpected = graphTop + graphHeight * (1 - (smoothedExpectedTemps[index] - minTemp) / tempRange)
            expectedPoints.add(x to yExpected)
        }

        onPointsResolved?.invoke(
            PointsDebug(
                original = originalPoints,
                forecast = forecastPoints,
                expected = expectedPoints,
            )
        )

        // Build paths. 
        // Reality paths (Actual solid line, Ghost projection line) use JAGAGED paths (linear segments)
        // to ensure they perfectly match the linear interpolation used for the fetch dot grounding.
        val (originalPath, _) = GraphRenderUtils.buildJaggedPath(originalPoints, graphBottom)
        val (expectedPath, expectedFillPath) = GraphRenderUtils.buildJaggedPath(expectedPoints, graphBottom)

        // Forecast line (thin dashed background) stays smoothed for visual fluidness.
        val (forecastPath, forecastFillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(forecastPoints, graphBottom)

        // Compute NOW x-position first (needed to anchor transitionX)
        val nowX =
            GraphRenderUtils.computeNowX(
                items = hours,
                points = originalPoints,
                currentTime = currentTime,
                hourWidth = hourWidth,
                isCurrentHour = { it.isCurrentHour },
                dateTimeOf = { it.dateTime },
            )
        val nowIndicatorVisible = nowX != null && nowX in 0f..widthPx.toFloat()

        // Transition X: where solid actual line ends
        val lastActualIndex = hours.indexOfLast { it.isActual }
        val rawTransitionX: Float? = if (lastActualIndex >= 0) originalPoints[lastActualIndex].first else null
        val fetchDotX: Float? = if (actualSeriesAnchorAt != null && fetchTime != null) {
            GraphRenderUtils.computeXForTime(
                targetTime = fetchTime,
                items = hours,
                points = originalPoints,
                hourWidth = hourWidth,
                dateTimeOf = { it.dateTime },
            )
        } else null
        
        val transitionX: Float? = rawTransitionX?.let { raw ->
            listOfNotNull(raw, nowX, fetchDotX).min()
        }
        android.util.Log.d("ActualsDebug", "renderGraph: hours=${hours.size}, lastActualIndex=$lastActualIndex, nowX=$nowX, fetchDotX=$fetchDotX, rawTransitionX=$rawTransitionX, transitionX=$transitionX, widthPx=$widthPx")

        // --- Draw fill ---
        // Fill is always under the forecast line (full width at low opacity)
        canvas.drawPath(forecastFillPath, expectedFillPaint)

        // --- Draw ghost line — projects from observation dot into the future ---
        if (nowIndicatorVisible && appliedDelta != null && kotlin.math.abs(appliedDelta) >= 0.1f && fetchDotX != null) {
            // Use same anchor delta logic for onGhostLineDebug
            val expectedY = if (interpolatedTruthAtFetch != null) {
                graphTop + graphHeight * (1 - (interpolatedTruthAtFetch - minTemp) / tempRange)
            } else null

            if (expectedY != null) {
                onGhostLineDebug?.invoke(GhostLineDebug(fetchDotX, expectedY))
            }

            canvas.save()
            canvas.clipRect(fetchDotX, 0f, widthPx.toFloat(), heightPx.toFloat())
            canvas.drawPath(expectedPath, ghostPaint)
            canvas.restore()
        }

        // --- Draw forecast line (thin dashed, full width) ---
        canvas.drawPath(forecastPath, forecastDashedPaint)

        // --- Draw actual line (solid, past portion only) ---
        if (transitionX != null) {
            canvas.save()
            canvas.clipRect(0f, 0f, transitionX + dpToPx(context, 1f), heightPx.toFloat())
            canvas.drawPath(originalPath, actualLinePaint)
            canvas.restore()
        }

        val fetchY: Float? = if (interpolatedTruthAtFetch != null) {
            graphTop + graphHeight * (1 - (interpolatedTruthAtFetch - minTemp) / tempRange)
        } else {
            null
        }
        val fetchDotWithinWindow = fetchDotX != null && fetchY != null
        if (actualSeriesAnchorAt != null) {
            onFetchDotResolved?.invoke(
                FetchDotDebug(
                    actualSeriesAnchorAt = actualSeriesAnchorAt,
                    fetchDotX = fetchDotX,
                    fetchY = fetchY,
                    withinWindow = fetchDotWithinWindow,
                ),
            )
        }

        // --- Draw labels, icons, current-time indicator ---
        val minHourLabelSpacing = dpToPx(context, 42f * labelScale)

        // 1. Hour Labels and Icons
        val drawnIconBounds = mutableListOf<RectF>()
        GraphRenderUtils.drawHourLabels(
            canvas = canvas,
            items = hours,
            points = originalPoints,
            widthPx = widthPx,
            heightPx = heightPx,
            minHourLabelSpacing = minHourLabelSpacing,
            hourLabelTextPaint = hourLabelTextPaint,
            dpToPx = { dpToPx(context, it) },
            showLabel = { it.showLabel },
            labelText = { it.label },
        ) { index, clampedX ->
            val hour = hours[index]
            if (hour.iconRes != null) {
                val drawable = androidx.core.content.ContextCompat.getDrawable(context, hour.iconRes)
                if (drawable != null) {
                    val iconY = footerTop + iconTopPad
                    val iconX = clampedX - iconSize / 2f
                    val iconRect = RectF(iconX, iconY, iconX + iconSize, iconY + iconSize)
                    drawnIconBounds.add(iconRect)

                    drawable.setBounds(
                        iconRect.left.toInt(),
                        iconRect.top.toInt(),
                        iconRect.right.toInt(),
                        iconRect.bottom.toInt(),
                    )

                    if (!hour.isRainy && !hour.isMixed) {
                        val iconTint =
                            when {
                                hour.isNight -> Color.parseColor("#BBBBBB")
                                hour.isSunny -> Color.parseColor("#FFD60A")
                                else -> Color.parseColor("#BBBBBB")
                            }
                        drawable.setTint(iconTint)
                    }
                    drawable.draw(canvas)
                }
            }
        }

        // 2. Key temperature labels
        val dailyHighIndex = smoothedLabelTemps.indices.maxByOrNull { smoothedLabelTemps[it] } ?: -1
        val dailyLowIndex = smoothedLabelTemps.indices.minByOrNull { smoothedLabelTemps[it] } ?: -1

        fun findLocalExtremaIndices(): List<Int> {
            val extrema = mutableListOf<Int>()
            if (smoothedLabelTemps.size < 3) return extrema
            var i = 1
            while (i < smoothedLabelTemps.size - 1) {
                val current = smoothedLabelTemps[i]
                val prev = smoothedLabelTemps[i - 1]
                if (current > prev && current > smoothedLabelTemps[i + 1]) extrema.add(i)
                else if (current < prev && current < smoothedLabelTemps[i + 1]) extrema.add(i)
                else if (current == smoothedLabelTemps[i + 1] && current != prev) {
                    var j = i + 1
                    while (j < smoothedLabelTemps.size - 1 && smoothedLabelTemps[j] == current) j++
                    val next = smoothedLabelTemps[j]
                    if (j < smoothedLabelTemps.size) {
                        if (current > prev && current > next) extrema.add((i + j) / 2) 
                        else if (current < prev && current < next) extrema.add((i + j) / 2)
                    }
                    i = j - 1
                }
                i++
            }
            return extrema
        }

        val localExtrema = findLocalExtremaIndices()

        fun bilateralExtremaProminence(index: Int): Float {
            val current = smoothedLabelTemps[index]
            val localExtremaSet = localExtrema.toSet()
            fun maxDeltaInDirection(step: Int): Float {
                var maxDelta = 0f
                var cursor = index + step
                while (cursor in smoothedLabelTemps.indices) {
                    val delta = Math.abs(smoothedLabelTemps[cursor] - current)
                    if (delta > maxDelta) maxDelta = delta
                    if (cursor != index + step && cursor in localExtremaSet) break
                    cursor += step
                }
                return maxDelta
            }
            val leftDelta = maxDeltaInDirection(-1)
            val rightDelta = maxDeltaInDirection(1)
            if (leftDelta == 0f || rightDelta == 0f) return 0f
            return Math.min(leftDelta, rightDelta)
        }

        val significantLocalExtrema = localExtrema.filter { bilateralExtremaProminence(it) >= MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES }

        val specialIndices = mutableListOf<Int>()
        if (dailyLowIndex >= 0) specialIndices.add(dailyLowIndex)
        if (dailyHighIndex >= 0 && dailyHighIndex != dailyLowIndex) specialIndices.add(dailyHighIndex)
        significantLocalExtrema.forEach { idx ->
            if (idx !in specialIndices) {
                val labelText = String.format("%.1f", smoothedLabelTemps[idx])
                if (specialIndices.none { Math.abs(idx - it) <= 3 && String.format("%.1f", smoothedLabelTemps[it]) == labelText }) specialIndices.add(idx)
            }
        }
        if (0 !in specialIndices) specialIndices.add(0)
        if (hours.size > 1 && (hours.size - 1) !in specialIndices) specialIndices.add(hours.size - 1)

        val drawnLabelBounds = mutableListOf<RectF>()

        fun centerOfRun(anchorIdx: Int): Pair<Float, Float> {
            val value = smoothedLabelTemps[anchorIdx]
            var first = anchorIdx
            var last = anchorIdx
            while (first > 0 && smoothedLabelTemps[first - 1] == value) first--
            while (last < smoothedLabelTemps.lastIndex && smoothedLabelTemps[last + 1] == value) last++
            val cx = (originalPoints[first].first + originalPoints[last].first) / 2f
            val cy = (originalPoints[first].second + originalPoints[last].second) / 2f
            return cx to cy
        }

        for (idx in specialIndices) {
            val (sx, sy) = if (idx == dailyLowIndex || idx == dailyHighIndex) centerOfRun(idx) else originalPoints[idx].first to originalPoints[idx].second
            val label = String.format("%.1f°", smoothedLabelTemps[idx])
            val textWidth = tempLabelTextPaint.measureText(label)
            val textHeight = tempLabelTextPaint.textSize
            val clampedX = sx.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)

            val leftVal = smoothedLabelTemps.subList(0, idx).findLast { it != smoothedLabelTemps[idx] } ?: 0f
            val isPeak = (idx == dailyHighIndex || (idx in significantLocalExtrema && smoothedLabelTemps[idx] > leftVal))
            val isValley = (idx == dailyLowIndex || (idx in significantLocalExtrema && smoothedLabelTemps[idx] < leftVal))

            val preferBelow = if (isPeak) false else if (isValley) true else sy < graphTop + graphHeight / 2f
            val attempts = if (preferBelow) listOf(true, false) else listOf(false, true)
            
            for (drawBelow in attempts) {
                val candidateY = if (drawBelow) sy + textHeight + dpToPx(context, 3f) else sy - dpToPx(context, 5f)
                val bounds = RectF(clampedX - textWidth / 2f, candidateY - textHeight, clampedX + textWidth / 2f, candidateY)
                if (bounds.bottom <= heightPx && drawnLabelBounds.none { RectF.intersects(it, bounds) } && drawnIconBounds.none { RectF.intersects(it, bounds) }) {
                    canvas.drawText(label, clampedX, candidateY, tempLabelTextPaint)
                    drawnLabelBounds.add(bounds)

                    val role = when {
                        idx == dailyLowIndex -> "LOW"
                        idx == dailyHighIndex -> "HIGH"
                        idx == 0 -> "START"
                        idx == hours.lastIndex -> "END"
                        else -> "LOCAL"
                    }

                    onLabelPlaced?.invoke(
                        LabelPlacementDebug(
                            index = idx,
                            role = role,
                            temperature = smoothedLabelTemps[idx],
                            rawTemperature = hours[idx].temperature,
                            x = clampedX,
                            y = candidateY,
                            placedAbove = !drawBelow,
                            reason = if (drawBelow) "below" else "above",
                        ),
                    )
                    break
                }
            }
        }

        // Day labels
        val fm = dayLabelTextPaint.fontMetrics ?: Paint.FontMetrics()
        val dayLabelTextHeight = fm.descent - fm.ascent
        val dayYTop    = graphTop + dayLabelTextHeight
        val dayYMid    = (graphTop + graphBottom) / 2f
        val dayYBottom = heightPx - dpToPx(context, 14f)

        fun collides(bounds: RectF): Boolean =
            drawnLabelBounds.any { RectF.intersects(it, bounds) } ||
            drawnIconBounds.any { RectF.intersects(it, bounds) }

        val today = java.time.LocalDate.now()
        val leftDate = hours.first().dateTime.toLocalDate()
        val rightDate = hours.last().dateTime.toLocalDate()
        val leftText = hours.first().dateTime.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        val rightText = hours.last().dateTime.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        val leftTextWidth  = (if (leftDate == today) todayDayLabelPaint else dayLabelTextPaint).measureText(leftText)
        val rightTextWidth = (if (rightDate == today) todayDayLabelPaint else dayLabelTextPaint).measureText(rightText)

        data class DayCandidate(val date: java.time.LocalDate, val x: Float, val dayText: String)
        val dayCandidates = listOf(
            DayCandidate(leftDate,  leftTextWidth / 2f,            leftText),
            DayCandidate(rightDate, widthPx - rightTextWidth / 2f, rightText),
        )

        val drawnDayLabelBounds = mutableListOf<RectF>()
        for ((candidateIndex, candidate) in dayCandidates.withIndex()) {
            val side = if (candidateIndex == 0) "LEFT" else "RIGHT"
            val isToday = candidate.date == today
            val paint = if (isToday) todayDayLabelPaint else dayLabelTextPaint
            val textWidth = paint.measureText(candidate.dayText)

            fun dayBounds(x: Float, y: Float): RectF =
                RectF(x - textWidth / 2f, y + fm.ascent, x + textWidth / 2f, y + fm.descent)

            // Try placements
            val topB = dayBounds(candidate.x, dayYTop)
            if (!collides(topB) && !drawnDayLabelBounds.any { RectF.intersects(it, topB) }) {
                canvas.drawText(candidate.dayText, candidate.x, dayYTop, paint)
                drawnDayLabelBounds.add(topB)
                onDayLabelPlaced?.invoke(DayLabelPlacementDebug(side, candidate.dayText, candidate.date, candidate.x, dayYTop, "TOP", isToday))
                continue
            }
            val midB = dayBounds(candidate.x, dayYMid)
            if (!collides(midB) && !drawnDayLabelBounds.any { RectF.intersects(it, midB) }) {
                canvas.drawText(candidate.dayText, candidate.x, dayYMid, paint)
                drawnDayLabelBounds.add(midB)
                onDayLabelPlaced?.invoke(DayLabelPlacementDebug(side, candidate.dayText, candidate.date, candidate.x, dayYMid, "MIDDLE", isToday))
                continue
            }
            val botB = dayBounds(candidate.x, dayYBottom)
            canvas.drawText(candidate.dayText, candidate.x, dayYBottom, paint)
            onDayLabelPlaced?.invoke(DayLabelPlacementDebug(side, candidate.dayText, candidate.date, candidate.x, dayYBottom, "BOTTOM", isToday))
        }

        GraphRenderUtils.drawNowIndicator(
            canvas,
            if (nowIndicatorVisible) nowX else null,
            graphTop,
            graphHeight,
            currentTimePaint,
            nowLabelTextPaint,
        ) { dpToPx(context, it) }

        // Draw "Last Fetch Dot"
        if (actualSeriesAnchorAt != null && fetchDotX != null) {
            val resolvedFetchTemp = interpolatedTruthAtFetch
            if (fetchY != null && resolvedFetchTemp != null) {
                val dotRadius = dpToPx(context, 3.2f * labelScale)
                val clampedFetchX = fetchDotX.coerceIn(dotRadius, widthPx.toFloat() - dotRadius)

                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = tempToColor(resolvedFetchTemp)
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(clampedFetchX, fetchY, dotRadius, dotPaint)

                val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(context, 1.5f * labelScale)
                }
                canvas.drawCircle(clampedFetchX, fetchY, dotRadius, ringPaint)

                val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#44000000")
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(context, 0.5f * labelScale)
                }
                canvas.drawCircle(clampedFetchX, fetchY, dotRadius + ringPaint.strokeWidth / 2f, outerRingPaint)

                if (hours.size <= 8) {
                    val ageMinutes = java.time.Duration.between(fetchTime!!, currentTime).toMinutes()
                    if (ageMinutes >= 0) {
                        val ageText = if (ageMinutes >= 60) {
                            val h = ageMinutes / 60
                            val m = ageMinutes % 60
                            if (m > 0) "${h}h ${m}m" else "${h}h"
                        } else "${ageMinutes}m"

                        val ageTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#BBFFFFFF")
                            textSize = dpToPx(context, 10f * labelScale)
                            textAlign = Paint.Align.LEFT
                            setShadowLayer(dpToPx(context, 1f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
                        }
                        val textX = clampedFetchX + dotRadius + dpToPx(context, 4f * labelScale)
                        val textY = fetchY + ageTextPaint.textSize / 3f
                        val textWidth = ageTextPaint.measureText(ageText)
                        val finalX = if (textX + textWidth > widthPx) {
                            clampedFetchX - dotRadius - dpToPx(context, 4f * labelScale) - textWidth
                        } else textX
                        canvas.drawText(ageText, finalX, textY, ageTextPaint)
                    }
                }
            }
        }

        return bitmap
    }

    private fun dpToPx(context: Context, dp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}
