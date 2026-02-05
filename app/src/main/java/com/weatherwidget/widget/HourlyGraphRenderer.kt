package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.Log
import android.util.TypedValue
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object HourlyGraphRenderer {

    data class HourData(
        val dateTime: LocalDateTime,
        val temperature: Float,
        val label: String,           // "12a", "1p", "2p"
        val iconRes: Int? = null,
        val isNight: Boolean = false,
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
            textSize = dpToPx(context, 13.0f)
            textAlign = Paint.Align.CENTER
            setShadowLayer(dpToPx(context, 1f), 0f, dpToPx(context, 0.5f), Color.parseColor("#44000000"))
        }

        val tempLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = dpToPx(context, 13.0f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 2f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
        }

        val nowLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BBFF9F0A")  // ~73% alpha for lighter feel
            textSize = dpToPx(context, 11.0f)
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

        // Compute NOW x-position early (needed for label proximity suppression)
        val currentHourIndex = hours.indexOfFirst { it.isCurrentHour }
        val nowX: Float? = if (currentHourIndex != -1) {
            val minutesOffset = java.time.Duration.between(hours[currentHourIndex].dateTime, currentTime).toMinutes()
            points[currentHourIndex].first + (minutesOffset / 60f) * hourWidth
        } else null

        // Key temperature labels — priority-ordered list (first drawn wins collisions)
        val dailyHighIndex = hours.indices.maxByOrNull { hours[it].temperature } ?: -1
        val dailyLowIndex = hours.indices.minByOrNull { hours[it].temperature } ?: -1

        // Priority order: low (1) → high (2) → start (3) → end (4)
        val specialIndices = mutableListOf<Int>()
        if (dailyLowIndex >= 0) specialIndices.add(dailyLowIndex)
        if (dailyHighIndex >= 0 && dailyHighIndex != dailyLowIndex) specialIndices.add(dailyHighIndex)
        if (0 !in specialIndices) specialIndices.add(0)
        if (hours.size > 1 && (hours.size - 1) !in specialIndices) specialIndices.add(hours.size - 1)

        val drawnLabelBounds = mutableListOf<RectF>()

        Log.d("HourlyGraph", "=== Label drawing: specialIndices=$specialIndices, dailyHighIdx=$dailyHighIndex (${if (dailyHighIndex >= 0) hours[dailyHighIndex].temperature else "N/A"}), dailyLowIdx=$dailyLowIndex (${if (dailyLowIndex >= 0) hours[dailyLowIndex].temperature else "N/A"}), nowX=$nowX, hourWidth=$hourWidth")

        for (idx in specialIndices) {
            val sx = points[idx].first
            val sy = points[idx].second
            val label = String.format("%.0f°", hours[idx].temperature)
            val textWidth = tempLabelTextPaint.measureText(label)
            val textHeight = tempLabelTextPaint.textSize
            val clampedX = sx.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)

            // Smart placement: draw label toward center of graph
            val graphCenter = graphTop + graphHeight / 2f
            val drawBelow = sy < graphCenter
            val labelY = if (drawBelow) sy + dpToPx(context, 14f) else sy - dpToPx(context, 4f)

            // Build bounding rect for overlap detection
            val bounds = RectF(
                clampedX - textWidth / 2f,
                labelY - textHeight,
                clampedX + textWidth / 2f,
                labelY
            )

            // Skip if overlaps any already-drawn label
            val overlaps = drawnLabelBounds.any { RectF.intersects(it, bounds) }

            val roleName = when (idx) {
                dailyLowIndex -> "LOW"
                dailyHighIndex -> "HIGH"
                0 -> "START"
                hours.size - 1 -> "END"
                else -> "OTHER"
            }

            if (!overlaps) {
                canvas.drawText(label, clampedX, labelY, tempLabelTextPaint)
                drawnLabelBounds.add(bounds)
                Log.d("HourlyGraph", "  DRAWN $roleName idx=$idx temp=${hours[idx].temperature} x=$clampedX y=$labelY bounds=$bounds")
            } else {
                Log.d("HourlyGraph", "  SKIPPED $roleName idx=$idx temp=${hours[idx].temperature} overlaps=$overlaps bounds=$bounds")
            }
        }

        hours.forEachIndexed { index, hour ->
            val x = points[index].first
            val y = points[index].second

            // Hour labels at bottom, with weather icons aligned above
            if (hour.showLabel && (x - lastHourLabelX >= minHourLabelSpacing)) {
                val labelY = heightPx - dpToPx(context, 1f)
                val textWidth = hourLabelTextPaint.measureText(hour.label)
                val clampedX = x.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)
                
                canvas.drawText(hour.label, clampedX, labelY, hourLabelTextPaint)
                lastHourLabelX = x

                // Weather icon above hour label
                if (hour.iconRes != null) {
                    val drawable = androidx.core.content.ContextCompat.getDrawable(context, hour.iconRes)
                    if (drawable != null) {
                        val iconY = graphBottom + iconTopPad
                        val iconX = clampedX - iconSize / 2f

                        drawable.setBounds(
                            iconX.toInt(),
                            iconY.toInt(),
                            (iconX + iconSize).toInt(),
                            (iconY + iconSize).toInt()
                        )

                        val iconTint = when {
                            hour.isNight -> Color.parseColor("#BBBBBB")
                            hour.isSunny -> Color.parseColor("#FFD60A")
                            else -> Color.parseColor("#BBBBBB")
                        }
                        drawable.setTint(iconTint)
                        drawable.draw(canvas)
                    }
                }
            }
        }

        // Current time indicator: dashed line + "NOW" label (reuses precomputed nowX)
        if (nowX != null) {
            // Dashed vertical line (60% of graph height, centered)
            val lineHeight = graphHeight * 0.6f
            val lineTop = graphTop + (graphHeight - lineHeight) / 2f
            val lineBottom = lineTop + lineHeight
            canvas.drawLine(nowX, lineTop, nowX, lineBottom, currentTimePaint)

            // "NOW" label just above the dashed line
            canvas.drawText("NOW", nowX, lineTop - dpToPx(context, 2f), nowLabelTextPaint)
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
