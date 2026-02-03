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
        // Remove buffer so the curve hits the edges exactly
        val minTemp = (allTemps.minOrNull() ?: 0f)
        val maxTemp = (allTemps.maxOrNull() ?: 100f)
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1f)

        // Scale factor based on widget dimensions
        val density = context.resources.displayMetrics.density
        val widthDp = widthPx / density
        val heightDp = heightPx / density

        // Height-based scale factor - kept conservative to prevent huge fonts
        val baseHeightDp = 136f  // 2-row widget height
        val heightScaleFactor = when {
            heightDp < 150f -> 1.0f      // 2 rows or less: baseline
            heightDp < 250f -> 1.0f      // 3 rows: keep baseline
            else -> 1.05f                 // 4+ rows: only 5% bigger
        }

        // Layout constants
        // Layout constants
        val horizontalPadding = dpToPx(context, 0f) // Full width (was 4f)
        val topPadding = dpToPx(context, 16f)
        val bottomPadding = dpToPx(context, 0f) // Zero bottom padding

        // Icon size fixed to 8dp
        val iconSizeDp = 8f
        val iconSize = dpToPx(context, iconSizeDp).toInt()

        // Calculate layout height components
        val labelHeight = dpToPx(context, 9f) // Fixed size, no height scaling

        // Log font sizing info
        android.util.Log.d("HourlyGraph", "Widget: ${widthPx}px × ${heightPx}px (${widthDp.toInt()}dp × ${heightDp.toInt()}dp) | heightScaleFactor=$heightScaleFactor | baseLabel=9dp, finalLabel=9dp")
        
        val graphTop = topPadding
        // Bottom reserved area: Text Label + Icon + Padding
        // Added 4dp padding for breathing room
        val graphBottom = heightPx - labelHeight - iconSize - dpToPx(context, 4f)
        val graphHeight = graphBottom - graphTop

        // Calculate hour spacing
        val hourWidth = (widthPx - 2 * horizontalPadding) / hours.size.toFloat()

        // Paints
        val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5AC8FA")
            strokeWidth = dpToPx(context, 2f) // Thinner stroke (was 3f)
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

        val hourLabelSize = dpToPx(context, 9f) // Fixed size, no height scaling
        val hourLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#AAAAAA")
            textSize = hourLabelSize // Smaller (Reduced to 9f)
            textAlign = Paint.Align.CENTER
        }

        val tempLabelSize = dpToPx(context, 9f) // Fixed size, no height scaling
        val tempLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = tempLabelSize // Smaller (Reduced to 9f)
            textAlign = Paint.Align.CENTER
        }

        android.util.Log.d("HourlyGraph", "Font sizes: hourLabel=${hourLabelSize}px (${hourLabelSize/density}dp), tempLabel=${tempLabelSize}px (${tempLabelSize/density}dp)")

        val nowLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9F0A")
            textSize = dpToPx(context, 8.5f) // Fixed size, no height scaling
            textAlign = Paint.Align.CENTER
        }

        // Icon paints
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.parseColor("#AAAAAA"), PorterDuff.Mode.SRC_IN)
        }
        val sunnyIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.parseColor("#FFD60A"), PorterDuff.Mode.SRC_IN)
        }
        
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
        val minHourLabelSpacing = dpToPx(context, 18f)  // Reduced (was 22f) to ensure dense labels fit


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
                val labelY = heightPx - 3f // Experiment: move up 3 pixels
                canvas.drawText(hour.label, x, labelY, hourLabelTextPaint)
                lastHourLabelX = x
                
                // Draw icon above label if available
                if (hour.iconRes != null) {
                    val drawable = androidx.core.content.ContextCompat.getDrawable(context, hour.iconRes)
                    if (drawable != null) {
                        // Position: Restore 2dp gap above the label
                        val iconY = heightPx - labelHeight - iconSize - dpToPx(context, 2f) 
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
