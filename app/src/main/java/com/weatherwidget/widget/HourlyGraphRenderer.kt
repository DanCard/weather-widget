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
        val minTemp = (allTemps.minOrNull() ?: 0f) - 5f
        val maxTemp = (allTemps.maxOrNull() ?: 100f) + 5f
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1f)

        // Scale factor based on widget dimensions
        val density = context.resources.displayMetrics.density
        val widthDp = widthPx / density
        val heightDp = heightPx / density

        // Height-based scale factor
        val baseHeightDp = 136f  // 2-row widget height
        val heightScaleFactor = when {
            heightDp < 150f -> 1.0f      // 2 rows or less: baseline
            heightDp < 250f -> 1.1f      // 3 rows: 10% bigger
            else -> 1.2f                  // 4+ rows: 20% bigger
        }

        // Layout constants
        val horizontalPadding = dpToPx(context, 8f)
        val topPadding = dpToPx(context, 20f)
        val bottomPadding = dpToPx(context, 2f)
        val labelHeight = dpToPx(context, 24f * heightScaleFactor)
        val graphTop = topPadding
        val graphBottom = heightPx - labelHeight - bottomPadding
        val graphHeight = graphBottom - graphTop

        // Calculate hour spacing
        val hourWidth = (widthPx - 2 * horizontalPadding) / hours.size.toFloat()

        // Paints
        val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5AC8FA")
            strokeWidth = dpToPx(context, 3f * heightScaleFactor)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val currentTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9F0A")
            strokeWidth = dpToPx(context, 2f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, 4f), dpToPx(context, 4f)), 0f)
        }

        val hourLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#AAAAAA")
            textSize = dpToPx(context, 12f * heightScaleFactor)
            textAlign = Paint.Align.CENTER
        }

        val tempLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = dpToPx(context, 12f * heightScaleFactor)
            textAlign = Paint.Align.CENTER
        }

        val nowLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9F0A")
            textSize = dpToPx(context, 10f * heightScaleFactor)
            textAlign = Paint.Align.CENTER
        }

        // Icon paints
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.parseColor("#AAAAAA"), PorterDuff.Mode.SRC_IN)
        }
        val sunnyIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.parseColor("#FFD60A"), PorterDuff.Mode.SRC_IN)
        }
        val iconSize = dpToPx(context, 16f * heightScaleFactor).toInt()

        // Draw temperature curve using smooth bezier
        val curvePath = Path()
        hours.forEachIndexed { index, hour ->
            val x = horizontalPadding + hourWidth * index + hourWidth / 2
            val y = graphTop + graphHeight * (1 - (hour.temperature - minTemp) / tempRange)

            if (index == 0) {
                curvePath.moveTo(x, y)
            } else {
                // Create smooth bezier curve between points
                val prevX = horizontalPadding + hourWidth * (index - 1) + hourWidth / 2
                val prevY = graphTop + graphHeight * (1 - (hours[index - 1].temperature - minTemp) / tempRange)
                val controlX = (prevX + x) / 2
                curvePath.quadTo(controlX, prevY, x, y)
            }
        }
        canvas.drawPath(curvePath, curvePaint)

        // Draw hour labels and temperature points
        // Track last label positions to avoid overlap
        var lastTempLabelX = -1000f
        var lastHourLabelX = -1000f
        val minTempLabelSpacing = dpToPx(context, 40f)  // Minimum spacing between temp labels
        val minHourLabelSpacing = dpToPx(context, 28f)  // Minimum spacing between hour labels

        hours.forEachIndexed { index, hour ->
            val x = horizontalPadding + hourWidth * index + hourWidth / 2
            val y = graphTop + graphHeight * (1 - (hour.temperature - minTemp) / tempRange)

            // Draw temperature label at peaks/valleys (local extrema)
            val isPeak = index > 0 && index < hours.size - 1 &&
                    hour.temperature >= hours[index - 1].temperature &&
                    hour.temperature >= hours[index + 1].temperature
            val isValley = index > 0 && index < hours.size - 1 &&
                    hour.temperature <= hours[index - 1].temperature &&
                    hour.temperature <= hours[index + 1].temperature
            val isEndpoint = index == 0 || index == hours.size - 1

            // Only draw if this is a significant point AND not too close to last label
            val shouldDrawTemp = (isPeak || isValley || isEndpoint || hour.isCurrentHour) &&
                    (x - lastTempLabelX >= minTempLabelSpacing)

            if (shouldDrawTemp) {
                val tempLabel = String.format("%.0f°", hour.temperature)
                val tempYOffset = if (isPeak || (isEndpoint && hour.temperature > hours.getOrNull(1)?.temperature ?: hour.temperature)) {
                    -dpToPx(context, 8f * heightScaleFactor)
                } else {
                    dpToPx(context, 20f * heightScaleFactor)
                }
                canvas.drawText(tempLabel, x, y + tempYOffset, tempLabelTextPaint)
                lastTempLabelX = x
            }

            // Draw hour label at bottom (only if showLabel is true AND not overlapping)
            if (hour.showLabel && (x - lastHourLabelX >= minHourLabelSpacing)) {
                val labelY = heightPx - bottomPadding
                canvas.drawText(hour.label, x, labelY, hourLabelTextPaint)
                lastHourLabelX = x
                
                // Draw icon above label if available
                if (hour.iconRes != null) {
                    val drawable = androidx.core.content.ContextCompat.getDrawable(context, hour.iconRes)
                    if (drawable != null) {
                        val iconY = labelY - dpToPx(context, 20f * heightScaleFactor) - iconSize
                        val iconX = x - iconSize / 2f
                        
                        drawable.setBounds(
                            iconX.toInt(),
                            iconY.toInt(),
                            (iconX + iconSize).toInt(),
                            (iconY + iconSize).toInt()
                        )
                        
                        if (hour.isSunny) {
                            drawable.setTint(Color.parseColor("#FFD60A"))
                        } else {
                            drawable.setTint(Color.parseColor("#AAAAAA"))
                        }
                        
                        drawable.draw(canvas)
                    }
                }
            }
        }

        // Draw current time indicator
        val currentHourIndex = hours.indexOfFirst { it.isCurrentHour }
        if (currentHourIndex != -1) {
            val anchorHour = hours[currentHourIndex]
            // Calculate precise position based on minutes between anchor hour and current time
            val minutesOffset = java.time.Duration.between(anchorHour.dateTime, currentTime).toMinutes()
            val offsetPx = (minutesOffset / 60f) * hourWidth
            val x = horizontalPadding + hourWidth * currentHourIndex + hourWidth / 2 + offsetPx
            
            canvas.drawLine(x, graphTop, x, graphBottom, currentTimePaint)
            canvas.drawText("NOW", x, graphTop - dpToPx(context, 4f), nowLabelTextPaint)
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
