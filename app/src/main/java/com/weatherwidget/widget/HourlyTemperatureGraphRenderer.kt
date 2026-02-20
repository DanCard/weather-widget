package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.Log
import android.util.TypedValue
import java.time.LocalDateTime

object HourlyTemperatureGraphRenderer {
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
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (hours.isEmpty()) return bitmap

        // Find temperature range for scaling
        val allTemps = hours.map { it.temperature }
        val minTemp = (allTemps.minOrNull() ?: 0f)
        val maxTemp = (allTemps.maxOrNull() ?: 100f)
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1f)

        val density = context.resources.displayMetrics.density
        val heightDp = heightPx / density

        // Layout zones (top to bottom):
        // [current temp/icon] [temp labels above curve] [graph area] [temp labels below] [icons] [hour labels]
        val topPadding = dpToPx(context, 12f) // Space for current temp and weather icon
        val iconSizeDp = 16f // Larger icons (was 8dp)
        val iconSize = dpToPx(context, iconSizeDp).toInt()
        val labelHeight = dpToPx(context, 10f)
        val iconTopPad = dpToPx(context, 2f)
        val iconBottomPad = dpToPx(context, 1f)

        val graphTop = topPadding
        val graphBottom = heightPx - labelHeight - iconBottomPad - iconSize - iconTopPad
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        val hourWidth = widthPx.toFloat() / hours.size

        // --- Paints ---

        // Main curve: scale stroke width with widget height, colored by temperature
        val curveStrokeDp = if (heightDp >= 160) 1.5f else 2f
        val curvePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = dpToPx(context, curveStrokeDp)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                shader = buildTempGradient(graphTop, graphBottom, minTemp, maxTemp, tempRange)
            }

        // Gradient fill under curve: same temperature colors but fading to transparent at bottom
        val gradientPaint =
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
                    ) // 27% alpha at top, 0% at bottom
            }

        // Current-time vertical line
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
                color = Color.parseColor("#BBFF9F0A") // ~73% alpha for lighter feel
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

        // --- Build curve path & gradient fill path ---
        val points = mutableListOf<Pair<Float, Float>>() // x,y for each hour
        val rawTemps = hours.map { it.temperature }
        val smoothedTemps = GraphRenderUtils.smoothValues(rawTemps, iterations = 1)

        // Compute all data points first
        hours.forEachIndexed { index, _ ->
            val x = hourWidth * index + hourWidth / 2
            val y = graphTop + graphHeight * (1 - (smoothedTemps[index] - minTemp) / tempRange)
            points.add(x to y)
        }

        val (curvePath, fillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(points, graphBottom)

        // Draw gradient fill, then curve on top
        canvas.drawPath(fillPath, gradientPaint)
        canvas.drawPath(curvePath, curvePaint)

        // --- Draw labels, icons, current-time indicator ---
        val minHourLabelSpacing = dpToPx(context, 42f * labelScale)

        // Compute NOW x-position early
        val nowX =
            GraphRenderUtils.computeNowX(
                items = hours,
                points = points,
                currentTime = currentTime,
                hourWidth = hourWidth,
                isCurrentHour = { it.isCurrentHour },
                dateTimeOf = { it.dateTime },
            )

        // 1. Draw Hour Labels and Icons FIRST so they are behind temperature labels
        val drawnIconBounds = mutableListOf<RectF>()
        GraphRenderUtils.drawHourLabels(
            canvas = canvas,
            items = hours,
            points = points,
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

                    // Rain/storm/mixed icons keep native vector colors (grey cloud + blue rain)
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

        // 2. Key temperature labels — find min/max from the data used to draw the curve
        val dailyHighIndex = smoothedTemps.indices.maxByOrNull { smoothedTemps[it] } ?: -1
        val dailyLowIndex = smoothedTemps.indices.minByOrNull { smoothedTemps[it] } ?: -1

        // Find local extrema (peaks and valleys) that are significant enough to label
        fun findLocalExtremaIndices(): List<Int> {
            val extrema = mutableListOf<Int>()
            if (smoothedTemps.size < 3) return extrema

            var i = 1
            while (i < smoothedTemps.size - 1) {
                val current = smoothedTemps[i]
                val prev = smoothedTemps[i - 1]
                
                if (current > prev && current > smoothedTemps[i + 1]) {
                    extrema.add(i) // Local Max
                } else if (current < prev && current < smoothedTemps[i + 1]) {
                    extrema.add(i) // Local Min
                } else if (current == smoothedTemps[i + 1] && current != prev) {
                    var j = i + 1
                    while (j < smoothedTemps.size - 1 && smoothedTemps[j] == current) {
                        j++
                    }
                    val next = smoothedTemps[j]
                    if (j < smoothedTemps.size) {
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
            val current = smoothedTemps[index]
            val localExtremaSet = localExtrema.toSet()

            fun maxDeltaInDirection(step: Int): Float {
                var maxDelta = 0f
                var cursor = index + step
                while (cursor in smoothedTemps.indices) {
                    val delta = Math.abs(smoothedTemps[cursor] - current)
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

        val significantLocalExtrema =
            localExtrema.filter { index ->
                bilateralExtremaProminence(index) >= MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES
            }

        // Priority order: low (1) -> high (2) -> local extrema (3) -> start (4) -> end (5)
        val specialIndices = mutableListOf<Int>()
        if (dailyLowIndex >= 0) specialIndices.add(dailyLowIndex)
        if (dailyHighIndex >= 0 && dailyHighIndex != dailyLowIndex) specialIndices.add(dailyHighIndex)

        significantLocalExtrema.forEach { idx ->
            if (idx !in specialIndices) {
                val labelText = String.format("%.0f", smoothedTemps[idx])
                val duplicatesNearby = specialIndices.any { existing ->
                    Math.abs(idx - existing) <= 3 &&
                        String.format("%.0f", smoothedTemps[existing]) == labelText
                }
                if (!duplicatesNearby) specialIndices.add(idx)
            }
        }

        if (0 !in specialIndices) specialIndices.add(0)
        if (hours.size > 1 && (hours.size - 1) !in specialIndices) specialIndices.add(hours.size - 1)

        val drawnLabelBounds = mutableListOf<RectF>()

        // For min/max, find center of consecutive points at the same value
        fun centerOfRun(anchorIdx: Int): Pair<Float, Float> {
            val value = smoothedTemps[anchorIdx]
            var first = anchorIdx
            var last = anchorIdx
            while (first > 0 && smoothedTemps[first - 1] == value) first--
            while (last < smoothedTemps.lastIndex && smoothedTemps[last + 1] == value) last++
            val cx = (points[first].first + points[last].first) / 2f
            val cy = (points[first].second + points[last].second) / 2f
            return cx to cy
        }

        fun isLocalMax(index: Int): Boolean {
            if (index <= 0 || index >= smoothedTemps.lastIndex) return false
            val current = smoothedTemps[index]
            val prev = smoothedTemps[index - 1]
            val next = smoothedTemps[index + 1]
            return (current >= prev && current > next) || (current > prev && current >= next)
        }

        fun isLocalMin(index: Int): Boolean {
            if (index <= 0 || index >= smoothedTemps.lastIndex) return false
            val current = smoothedTemps[index]
            val prev = smoothedTemps[index - 1]
            val next = smoothedTemps[index + 1]
            return (current <= prev && current < next) || (current < prev && current <= next)
        }

        for (idx in specialIndices) {
            val (sx, sy) = if (idx == dailyLowIndex || idx == dailyHighIndex) {
                centerOfRun(idx)
            } else {
                points[idx].first to points[idx].second
            }
            val label = String.format("%.0f°", smoothedTemps[idx])
            val textWidth = tempLabelTextPaint.measureText(label)
            val textHeight = tempLabelTextPaint.textSize
            val clampedX = sx.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)

            val isPeak = (idx == dailyHighIndex || (idx in significantLocalExtrema && isLocalMax(idx)))
            val isValley = (idx == dailyLowIndex || (idx in significantLocalExtrema && isLocalMin(idx)))

            val preferBelowHeuristic = sy < graphTop + graphHeight / 2f
            val preferBelow =
                when {
                    isPeak -> false
                    isValley -> true
                    else -> preferBelowHeuristic
                }

            val attempts = if (preferBelow) listOf(true, false) else listOf(false, true)
            var placedLabelY = 0f
            var placedBounds: RectF? = null
            var finalDrawBelow = false

            for (drawBelow in attempts) {
                val belowLabelY = sy + textHeight + dpToPx(context, 3f)
                val aboveLabelY = sy - dpToPx(context, 5f)
                val candidateY = if (drawBelow) belowLabelY else aboveLabelY

                val bounds =
                    RectF(
                        clampedX - textWidth / 2f,
                        candidateY - textHeight,
                        clampedX + textWidth / 2f,
                        candidateY,
                    )

                val inVerticalBounds = bounds.top >= 0f && bounds.bottom <= heightPx
                if (!inVerticalBounds) continue

                // Skip if overlaps any already-drawn label OR ICON
                val overlapsLabel = drawnLabelBounds.any { RectF.intersects(it, bounds) }
                val overlapsIcon = drawnIconBounds.any { RectF.intersects(it, bounds) }
                
                if (!overlapsLabel && !overlapsIcon) {
                    placedLabelY = candidateY
                    placedBounds = bounds
                    finalDrawBelow = drawBelow
                    break
                }
            }

            if (placedBounds != null) {
                canvas.drawText(label, clampedX, placedLabelY, tempLabelTextPaint)
                drawnLabelBounds.add(placedBounds)

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
                        temperature = smoothedTemps[idx],
                        rawTemperature = hours[idx].temperature,
                        x = clampedX,
                        y = placedLabelY,
                        placedAbove = !finalDrawBelow,
                        reason = if (finalDrawBelow) "below" else "above",
                    ),
                )
            }
        }

        // Draw day of week indicators at 8am (start of the "active" day)
        val dayLabelHour = 8
        val dayY = heightPx - dpToPx(context, 14f)
        hours.forEachIndexed { index, hour ->
            if (hour.dateTime.hour == dayLabelHour) {
                val dayText = hour.dateTime.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                val centerX = points[index].first
                val textWidth = dayLabelTextPaint.measureText(dayText)
                val clampedX = centerX.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)
                canvas.drawText(dayText, clampedX, dayY, dayLabelTextPaint)
            }
        }

        // Draw leading day label only if 8am for the same day isn't already in the data
        if (hours.isNotEmpty() && hours.first().dateTime.hour != dayLabelHour) {
            val firstDate = hours.first().dateTime.toLocalDate()
            val sameDayAnchorExists = hours.any {
                it.dateTime.hour == dayLabelHour && it.dateTime.toLocalDate() == firstDate
            }
            if (!sameDayAnchorExists) {
                val dayText = hours.first().dateTime.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                val textWidth = dayLabelTextPaint.measureText(dayText)
                val x = textWidth / 2f
                canvas.drawText(dayText, x, dayY, dayLabelTextPaint)
            }
        }

        GraphRenderUtils.drawNowIndicator(
            canvas = canvas,
            nowX = nowX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            currentTimePaint = currentTimePaint,
            nowLabelTextPaint = nowLabelTextPaint,
            dpToPx = { dpToPx(context, it) },
        )

        return bitmap
    }

    private fun dpToPx(
        context: Context,
        dp: Float,
    ): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        )
    }
}
