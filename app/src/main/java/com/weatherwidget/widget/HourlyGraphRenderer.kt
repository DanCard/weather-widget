package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.TypedValue
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object HourlyGraphRenderer {

    data class HourData(
        val dateTime: LocalDateTime,
        val temperature: Float,
        val label: String,           // "12a", "1p", "2p"
        val iconRes: Int? = null,
        val isSunny: Boolean = false,
        val isCurrentHour: Boolean = false,
        val showLabel: Boolean = true  // Only at intervals
    )

    fun renderGraph(
        context: Context,
        hours: List<HourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime
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
        val topPadding = dpToPx(context, 12f)       // Space for current temp and weather icon
        val iconSizeDp = 16f                         // Larger icons (was 8dp)
        val iconSize = dpToPx(context, iconSizeDp).toInt()
        val labelHeight = dpToPx(context, 10f)
        val iconTopPad = dpToPx(context, 2f)
        val iconBottomPad = dpToPx(context, 1f)

        val graphTop = topPadding
        val graphBottom = heightPx - labelHeight - iconBottomPad - iconSize - iconTopPad
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        val hourWidth = widthPx.toFloat() / hours.size

        // --- Paints ---

        // Main curve: scale stroke width with widget height
        val curveStrokeDp = if (heightDp >= 160) 1.5f else 2f
        val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5AC8FA")
            strokeWidth = dpToPx(context, curveStrokeDp)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Gradient fill under curve
        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, graphTop, 0f, graphBottom,
                Color.parseColor("#445AC8FA"),  // 27% alpha blue at top
                Color.parseColor("#005AC8FA"),  // 0% alpha at bottom
                Shader.TileMode.CLAMP
            )
        }

        // Current-time vertical line
        val currentTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9F0A")
            strokeWidth = dpToPx(context, 0.5f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, 4f), dpToPx(context, 3f)), 0f)
        }

        val hourLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#99FFFFFF")
            textSize = dpToPx(context, 10f)
            textAlign = Paint.Align.CENTER
            setShadowLayer(dpToPx(context, 1f), 0f, dpToPx(context, 0.5f), Color.parseColor("#44000000"))
        }

        val tempLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = dpToPx(context, 10f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 2f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
        }

        val nowLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BBFF9F0A")  // ~73% alpha for lighter feel
            textSize = dpToPx(context, 8f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 1f), 0f, 0f, Color.parseColor("#44000000"))
        }

        // --- Build curve path & gradient fill path ---
        val curvePath = Path()
        val fillPath = Path()
        val points = mutableListOf<Pair<Float, Float>>() // x,y for each hour

        // Compute all data points first
        hours.forEachIndexed { index, hour ->
            val x = hourWidth * index + hourWidth / 2
            val y = graphTop + graphHeight * (1 - (hour.temperature - minTemp) / tempRange)
            points.add(x to y)
        }

        if (points.isNotEmpty()) {
            curvePath.moveTo(points[0].first, points[0].second)
            fillPath.moveTo(points[0].first, points[0].second)

            if (points.size == 1) {
                // Single point — nothing to connect
            } else {
                // Catmull-Rom tangents for smooth cubic bezier curves
                val tangents = points.indices.map { i ->
                    when (i) {
                        0 -> Pair(
                            (points[1].first - points[0].first) * 0.5f,
                            (points[1].second - points[0].second) * 0.5f
                        )
                        points.size - 1 -> Pair(
                            (points[i].first - points[i - 1].first) * 0.5f,
                            (points[i].second - points[i - 1].second) * 0.5f
                        )
                        else -> Pair(
                            (points[i + 1].first - points[i - 1].first) * 0.5f,
                            (points[i + 1].second - points[i - 1].second) * 0.5f
                        )
                    }
                }

                for (i in 0 until points.size - 1) {
                    val cp1x = points[i].first + tangents[i].first / 3f
                    val cp1y = points[i].second + tangents[i].second / 3f
                    val cp2x = points[i + 1].first - tangents[i + 1].first / 3f
                    val cp2y = points[i + 1].second - tangents[i + 1].second / 3f
                    curvePath.cubicTo(cp1x, cp1y, cp2x, cp2y, points[i + 1].first, points[i + 1].second)
                    fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, points[i + 1].first, points[i + 1].second)
                }
            }

            // Close fill path along the bottom
            fillPath.lineTo(points.last().first, graphBottom)
            fillPath.lineTo(points.first().first, graphBottom)
            fillPath.close()
        }

        // Draw gradient fill, then curve on top
        canvas.drawPath(fillPath, gradientPaint)
        canvas.drawPath(curvePath, curvePaint)

        // --- Draw labels, icons, current-time indicator ---
        var lastHourLabelX = -1000f
        val minHourLabelSpacing = dpToPx(context, 28f)

        // Key temperature labels — always drawn, no spacing check
        val today = currentTime.toLocalDate()
        val dailyHighIndex = hours.indices.maxByOrNull { hours[it].temperature } ?: -1
        val dailyLowIndex = hours.indices.minByOrNull { hours[it].temperature } ?: -1

        // History high (max temp from hours before current time)
        val pastIndices = hours.indices.filter { hours[it].dateTime.isBefore(currentTime) }
        val pastHighIndex = pastIndices.maxByOrNull { hours[it].temperature } ?: -1

        // Collect indices that get special labels so we can draw them and skip duplicates
        val specialIndices = mutableSetOf<Int>()
        if (dailyHighIndex >= 0) specialIndices.add(dailyHighIndex)
        if (dailyLowIndex >= 0 && dailyLowIndex != dailyHighIndex) specialIndices.add(dailyLowIndex)
        if (pastHighIndex >= 0 && pastHighIndex !in specialIndices) specialIndices.add(pastHighIndex)
        specialIndices.add(0) // Start of graph
        if (hours.size > 1) specialIndices.add(hours.size - 1) // End of graph

        for (idx in specialIndices) {
            val sx = points[idx].first
            val sy = points[idx].second
            val label = String.format("%.0f°", hours[idx].temperature)
            val textHalfWidth = tempLabelTextPaint.measureText(label) / 2f
            val clampedX = sx.coerceIn(textHalfWidth, widthPx - textHalfWidth)

            // Smart placement: draw label toward center of graph
            // If point is in upper half, draw below; if in lower half, draw above
            val graphCenter = graphTop + graphHeight / 2f
            val drawBelow = sy < graphCenter

            if (drawBelow) {
                // Point is in upper half: draw below the curve
                canvas.drawText(label, clampedX, sy + dpToPx(context, 14f), tempLabelTextPaint)
            } else {
                // Point is in lower half: draw above the curve
                canvas.drawText(label, clampedX, sy - dpToPx(context, 4f), tempLabelTextPaint)
            }
        }

        hours.forEachIndexed { index, hour ->
            val x = points[index].first
            val y = points[index].second

            // Hour labels at bottom, with weather icons aligned above
            if (hour.showLabel && (x - lastHourLabelX >= minHourLabelSpacing)) {
                val labelY = heightPx - dpToPx(context, 1f)
                canvas.drawText(hour.label, x, labelY, hourLabelTextPaint)
                lastHourLabelX = x

                // Weather icon above hour label
                if (hour.iconRes != null) {
                    val drawable = androidx.core.content.ContextCompat.getDrawable(context, hour.iconRes)
                    if (drawable != null) {
                        val iconY = graphBottom + iconTopPad
                        val iconX = x - iconSize / 2f

                        drawable.setBounds(
                            iconX.toInt(),
                            iconY.toInt(),
                            (iconX + iconSize).toInt(),
                            (iconY + iconSize).toInt()
                        )

                        val isNighttime = hour.dateTime.hour < 6 || hour.dateTime.hour >= 20
                        val iconTint = when {
                            isNighttime -> Color.parseColor("#BBBBBB")
                            hour.isSunny -> Color.parseColor("#FFD60A")
                            else -> Color.parseColor("#BBBBBB")
                        }
                        drawable.setTint(iconTint)
                        drawable.draw(canvas)
                    }
                }
            }
        }

        // Current time indicator: dashed line + "NOW" label
        val currentHourIndex = hours.indexOfFirst { it.isCurrentHour }
        if (currentHourIndex != -1) {
            val anchorHour = hours[currentHourIndex]
            val minutesOffset = java.time.Duration.between(anchorHour.dateTime, currentTime).toMinutes()
            val offsetPx = (minutesOffset / 60f) * hourWidth
            val x = points[currentHourIndex].first + offsetPx

            // Dashed vertical line (60% of graph height, centered)
            val lineHeight = graphHeight * 0.6f
            val lineTop = graphTop + (graphHeight - lineHeight) / 2f
            val lineBottom = lineTop + lineHeight
            canvas.drawLine(x, lineTop, x, lineBottom, currentTimePaint)

            // "NOW" label just above the dashed line
            canvas.drawText("NOW", x, lineTop - dpToPx(context, 2f), nowLabelTextPaint)
        }

        return bitmap
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }
}
