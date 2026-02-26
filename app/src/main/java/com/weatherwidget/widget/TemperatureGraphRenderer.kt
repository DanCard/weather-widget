package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.Log
import android.util.TypedValue
import java.time.LocalDateTime

object TemperatureGraphRenderer {
    private const val DAY_LABEL_SIZE_MULTIPLIER = 1.4f
    private const val MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES = 1.5f

    data class HourData(
        val dateTime: LocalDateTime,
        val temperature: Float,
        val label: String, // "12a", "1p", "2p"
        val iconRes: Int? = null,
        val isNight: Boolean = false,
        val isSunny: Boolean = false,
        val isRainy: Boolean = false,
        val isMixed: Boolean = false,
        val isCurrentHour: Boolean = false,
        val showLabel: Boolean = true, // Only at intervals
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

    fun renderGraph(
        context: Context,
        hours: List<HourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        appliedDelta: Float? = null,
        observedTempFetchedAt: Long? = null,
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (hours.isEmpty()) return bitmap

        // Expected (Corrected) Hours
        val expectedHours = hours.map { it.copy(temperature = it.temperature + (appliedDelta ?: 0f)) }

        // Find temperature range for scaling (using BOTH original and expected temps)
        val allTemps = hours.map { it.temperature } + expectedHours.map { it.temperature }
        val minTemp = (allTemps.minOrNull() ?: 0f)
        val maxTemp = (allTemps.maxOrNull() ?: 100f)
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1f)

        val density = context.resources.displayMetrics.density
        val heightDp = heightPx / density

        // Layout zones
        val topPadding = dpToPx(context, 12f)
        val iconSizeDp = 16f
        val iconSize = dpToPx(context, iconSizeDp).toInt()
        val labelHeight = dpToPx(context, 10f)
        val iconTopPad = dpToPx(context, 2f)
        val iconBottomPad = dpToPx(context, 1f)

        val graphTop = topPadding
        val graphBottom = heightPx - labelHeight - iconBottomPad - iconSize - iconTopPad
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        val hourWidth = widthPx.toFloat() / hours.size

        // --- Paints ---

        // Original Forecast Line (Main Colorful Curve)
        val curveStrokeDp = if (heightDp >= 160) 1.5f else 2f
        val originalCurvePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = dpToPx(context, curveStrokeDp)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                shader = buildTempGradient(graphTop, graphBottom, minTemp, maxTemp, tempRange)
            }

        // Expected Truth Line (Ghost Dashed Curve)
        val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 100 // ~39% opacity (increased from 60 for better visibility as requested)
            strokeWidth = dpToPx(context, 1.2f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, 3f), dpToPx(context, 2f)), 0f)
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
                textSize = dpToPx(context, 16.5f * labelScale)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setShadowLayer(dpToPx(context, 1f), 0f, 0f, Color.parseColor("#44000000"))
            }

        val dayLabelTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#88FFFFFF")
                val dayLabelScale = bitmapScale.coerceIn(0.5f, 1f)
                textSize = dpToPx(context, 10.0f * dayLabelScale * DAY_LABEL_SIZE_MULTIPLIER)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }

        // --- Build paths ---
        val rawOriginalTemps = hours.map { it.temperature }
        val smoothedOriginalTemps = GraphRenderUtils.smoothValues(rawOriginalTemps, iterations = 1)
        
        val rawExpectedTemps = expectedHours.map { it.temperature }
        val smoothedExpectedTemps = GraphRenderUtils.smoothValues(rawExpectedTemps, iterations = 1)

        val originalPoints = mutableListOf<Pair<Float, Float>>()
        val expectedPoints = mutableListOf<Pair<Float, Float>>()

        hours.indices.forEach { index ->
            val x = hourWidth * index + hourWidth / 2
            
            val yOriginal = graphTop + graphHeight * (1 - (smoothedOriginalTemps[index] - minTemp) / tempRange)
            originalPoints.add(x to yOriginal)
            
            val yExpected = graphTop + graphHeight * (1 - (smoothedExpectedTemps[index] - minTemp) / tempRange)
            expectedPoints.add(x to yExpected)
        }

        val (originalPath, _) = GraphRenderUtils.buildSmoothCurveAndFillPaths(originalPoints, graphBottom)
        val (expectedPath, expectedFillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(expectedPoints, graphBottom)

        // Draw Expected Truth fill and Ghost line (Dashed)
        if (appliedDelta != null && kotlin.math.abs(appliedDelta) >= 0.1f) {
            canvas.drawPath(expectedFillPath, expectedFillPaint)
            canvas.drawPath(expectedPath, ghostPaint)
        } else {
            // If no delta, just draw original fill
            val (_, originalFillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(originalPoints, graphBottom)
            canvas.drawPath(originalFillPath, expectedFillPaint)
        }

        // Draw Original Forecast colorful curve on top
        canvas.drawPath(originalPath, originalCurvePaint)

        // --- Draw labels, icons, current-time indicator ---
        val minHourLabelSpacing = dpToPx(context, 42f * labelScale)

        // Compute NOW x-position relative to original points
        val nowX =
            GraphRenderUtils.computeNowX(
                items = hours,
                points = originalPoints,
                currentTime = currentTime,
                hourWidth = hourWidth,
                isCurrentHour = { it.isCurrentHour },
                dateTimeOf = { it.dateTime },
            )

        // 1. Draw Hour Labels and Icons
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
                    val iconY = graphBottom + iconTopPad
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

        // 2. Key temperature labels — placed on the ORIGINAL (Solid) line
        val dailyHighIndex = smoothedOriginalTemps.indices.maxByOrNull { smoothedOriginalTemps[it] } ?: -1
        val dailyLowIndex = smoothedOriginalTemps.indices.minByOrNull { smoothedOriginalTemps[it] } ?: -1

        fun findLocalExtremaIndices(): List<Int> {
            val extrema = mutableListOf<Int>()
            if (smoothedOriginalTemps.size < 3) return extrema
            var i = 1
            while (i < smoothedOriginalTemps.size - 1) {
                val current = smoothedOriginalTemps[i]
                val prev = smoothedOriginalTemps[i - 1]
                if (current > prev && current > smoothedOriginalTemps[i + 1]) extrema.add(i)
                else if (current < prev && current < smoothedOriginalTemps[i + 1]) extrema.add(i)
                else if (current == smoothedOriginalTemps[i + 1] && current != prev) {
                    var j = i + 1
                    while (j < smoothedOriginalTemps.size - 1 && smoothedOriginalTemps[j] == current) j++
                    val next = smoothedOriginalTemps[j]
                    if (j < smoothedOriginalTemps.size) {
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
            val current = smoothedOriginalTemps[index]
            val localExtremaSet = localExtrema.toSet()
            fun maxDeltaInDirection(step: Int): Float {
                var maxDelta = 0f
                var cursor = index + step
                while (cursor in smoothedOriginalTemps.indices) {
                    val delta = Math.abs(smoothedOriginalTemps[cursor] - current)
                    if (delta > maxDelta) maxDelta = delta
                    if (cursor != index + step && cursor in localExtremaSet) break
                    cursor += step
                }
                return maxDelta
            }
            val leftDelta = maxDeltaInDirection(-1)
            val rightDelta = maxDeltaInDirection(1)
            if (leftDelta == 0f || rightDelta == 0f) return 0f
            return minOf(leftDelta, rightDelta)
        }

        val significantLocalExtrema = localExtrema.filter { bilateralExtremaProminence(it) >= MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES }

        val specialIndices = mutableListOf<Int>()
        if (dailyLowIndex >= 0) specialIndices.add(dailyLowIndex)
        if (dailyHighIndex >= 0 && dailyHighIndex != dailyLowIndex) specialIndices.add(dailyHighIndex)
        significantLocalExtrema.forEach { idx ->
            if (idx !in specialIndices) {
                val labelText = String.format("%.0f", smoothedOriginalTemps[idx])
                if (specialIndices.none { Math.abs(idx - it) <= 3 && String.format("%.0f", smoothedOriginalTemps[it]) == labelText }) specialIndices.add(idx)
            }
        }
        if (0 !in specialIndices) specialIndices.add(0)
        if (hours.size > 1 && (hours.size - 1) !in specialIndices) specialIndices.add(hours.size - 1)

        val drawnLabelBounds = mutableListOf<RectF>()

        fun centerOfRun(anchorIdx: Int): Pair<Float, Float> {
            val value = smoothedOriginalTemps[anchorIdx]
            var first = anchorIdx
            var last = anchorIdx
            while (first > 0 && smoothedOriginalTemps[first - 1] == value) first--
            while (last < smoothedOriginalTemps.lastIndex && smoothedOriginalTemps[last + 1] == value) last++
            val cx = (originalPoints[first].first + originalPoints[last].first) / 2f
            val cy = (originalPoints[first].second + originalPoints[last].second) / 2f
            return cx to cy
        }

        for (idx in specialIndices) {
            val (sx, sy) = if (idx == dailyLowIndex || idx == dailyHighIndex) centerOfRun(idx) else originalPoints[idx].first to originalPoints[idx].second
            val label = String.format("%.0f°", smoothedOriginalTemps[idx])
            val textWidth = tempLabelTextPaint.measureText(label)
            val textHeight = tempLabelTextPaint.textSize
            val clampedX = sx.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)

            val isPeak = (idx == dailyHighIndex || (idx in significantLocalExtrema && smoothedOriginalTemps[idx] > smoothedOriginalTemps.getOrElse(idx-1){0f}))
            val isValley = (idx == dailyLowIndex || (idx in significantLocalExtrema && smoothedOriginalTemps[idx] < smoothedOriginalTemps.getOrElse(idx-1){1000f}))

            val preferBelow = if (isPeak) false else if (isValley) true else sy < graphTop + graphHeight / 2f
            val attempts = if (preferBelow) listOf(true, false) else listOf(false, true)
            
            for (drawBelow in attempts) {
                val candidateY = if (drawBelow) sy + textHeight + dpToPx(context, 3f) else sy - dpToPx(context, 5f)
                val bounds = RectF(clampedX - textWidth / 2f, candidateY - textHeight, clampedX + textWidth / 2f, candidateY)
                if (bounds.top >= 0f && bounds.bottom <= heightPx && drawnLabelBounds.none { RectF.intersects(it, bounds) } && drawnIconBounds.none { RectF.intersects(it, bounds) }) {
                    canvas.drawText(label, clampedX, candidateY, tempLabelTextPaint)
                    drawnLabelBounds.add(bounds)

                    val role =
                        when {
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
                            temperature = smoothedOriginalTemps[idx],
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

        // Day of week indicators
        val dayLabelHour = 8
        val dayY = heightPx - dpToPx(context, 14f)
        hours.forEachIndexed { index, hour ->
            if (hour.dateTime.hour == dayLabelHour) {
                val dayText = hour.dateTime.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                val centerX = originalPoints[index].first
                val textWidth = dayLabelTextPaint.measureText(dayText)
                canvas.drawText(dayText, centerX.coerceIn(textWidth / 2f, widthPx - textWidth / 2f), dayY, dayLabelTextPaint)
            }
        }

        GraphRenderUtils.drawNowIndicator(canvas, nowX, graphTop, graphHeight, currentTimePaint, nowLabelTextPaint) { dpToPx(context, it) }

        // Draw "Last Fetch Dot" on the Expected Truth (Ghost) line
        if (observedTempFetchedAt != null) {
            val fetchTime = java.time.Instant.ofEpochMilli(observedTempFetchedAt)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
            
            val fetchX = GraphRenderUtils.computeXForTime(
                targetTime = fetchTime,
                items = expectedHours,
                points = expectedPoints,
                hourWidth = hourWidth,
                dateTimeOf = { it.dateTime }
            )
            
            if (fetchX != null) {
                // Find Y on the smoothed expected curve
                val fetchIdx = expectedHours.indexOfLast { !it.dateTime.isAfter(fetchTime) }
                if (fetchIdx != -1 && fetchIdx < smoothedExpectedTemps.lastIndex) {
                    val baseTemp = smoothedExpectedTemps[fetchIdx]
                    val nextTemp = smoothedExpectedTemps[fetchIdx + 1]
                    val fraction = java.time.Duration.between(expectedHours[fetchIdx].dateTime, fetchTime).toMinutes() / 60f
                    val interpolatedFetchTemp = baseTemp + (nextTemp - baseTemp) * fraction
                    val fetchY = graphTop + graphHeight * (1 - (interpolatedFetchTemp - minTemp) / tempRange)
                    
                    val dotRadius = dpToPx(context, 3.2f * labelScale)
                    val clampedFetchX = fetchX.coerceIn(dotRadius, widthPx.toFloat() - dotRadius)

                    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = tempToColor(interpolatedFetchTemp)
                        style = Paint.Style.FILL
                    }
                    
                    canvas.drawCircle(clampedFetchX, fetchY, dotRadius, dotPaint)
                    
                    // High-contrast white border
                    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        style = Paint.Style.STROKE
                        strokeWidth = dpToPx(context, 1.5f * labelScale)
                    }
                    canvas.drawCircle(clampedFetchX, fetchY, dotRadius, ringPaint)
                    
                    // Darker outer ring for better visibility on light backgrounds
                    val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#44000000")
                        style = Paint.Style.STROKE
                        strokeWidth = dpToPx(context, 0.5f * labelScale)
                    }
                    canvas.drawCircle(clampedFetchX, fetchY, dotRadius + ringPaint.strokeWidth/2f, outerRingPaint)
                }
            }
        }

        return bitmap
    }

    private fun dpToPx(context: Context, dp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}
