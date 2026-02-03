package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.TypedValue

object TemperatureGraphRenderer {

    data class DayData(
        val label: String,
        val high: Int?,
        val low: Int?,
        val iconRes: Int? = null,
        val isSunny: Boolean = false,
        val isToday: Boolean = false,
        val isPast: Boolean = false,            // Is this a historical day?
        val isClimateNormal: Boolean = false,   // Is this long-range climate data?
        val forecastHigh: Int? = null,          // Single forecast
        val forecastLow: Int? = null,           // Single forecast
        val forecastSource: String? = null,     // "NWS" or "Open-Meteo"
        val accuracyMode: AccuracyDisplayMode = AccuracyDisplayMode.NONE
    )

    fun renderGraph(
        context: Context,
        days: List<DayData>,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (days.isEmpty()) return bitmap

        // Find temperature range for scaling (ignore nulls)
        val allTemps = days.flatMap { listOfNotNull(it.high, it.low) }
        val minTemp = (allTemps.minOrNull() ?: 0) - 5
        val maxTemp = (allTemps.maxOrNull() ?: 100) + 5
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1)

        // Scale factor based on widget dimensions
        val density = context.resources.displayMetrics.density
        val widthDp = widthPx / density
        val heightDp = heightPx / density

        // Width-based scale factor: ensure day labels fit (base ~70dp per day)
        val baseDayWidthDp = 70f
        val dayWidthDp = widthDp / days.size
        val widthScaleFactor = (dayWidthDp / baseDayWidthDp).coerceIn(1.0f, 1.8f)

        // Height-based scale factor: scale fonts up with widget height
        // Base height ~136dp (2 rows), scale up 10% per additional row
        val baseHeightDp = 136f  // 2-row widget height
        val heightScaleFactor = when {
            heightDp < 150f -> 1.0f      // 2 rows or less: baseline
            heightDp < 250f -> 1.1f      // 3 rows: 10% bigger
            else -> 1.2f                  // 4+ rows: 20% bigger
        }

        // Combined scale factor for layout elements
        val scaleFactor = widthScaleFactor

        // Layout constants (scaled)
        val horizontalPadding = dpToPx(context, -8f * scaleFactor)  // Slight negative for more space
        val topPadding = dpToPx(context, 20f * scaleFactor)  // Room for API source indicator
        val bottomPadding = dpToPx(context, 2f * scaleFactor)  // Minimal bottom padding
        val labelHeight = dpToPx(context, 24f * scaleFactor)
        val graphTop = topPadding
        val graphBottom = heightPx - labelHeight - bottomPadding
        val graphHeight = graphBottom - graphTop

        val dayWidth = (widthPx - 2 * horizontalPadding) / days.size
        val barWidth = dpToPx(context, 6f * scaleFactor)
        val capHeight = dpToPx(context, 2f * scaleFactor)

        // Paints
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5AC8FA")
            strokeWidth = barWidth
            strokeCap = Paint.Cap.ROUND
        }

        val todayBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9F0A")
            strokeWidth = barWidth
            strokeCap = Paint.Cap.ROUND
        }

        // History bar - yellow for actual past temperatures
        val historyBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD60A")  // Yellow for past days (actual)
            strokeWidth = barWidth
            strokeCap = Paint.Cap.ROUND
        }

        val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5AC8FA")
            strokeWidth = barWidth + dpToPx(context, 4f * scaleFactor)
            strokeCap = Paint.Cap.BUTT
        }

        val todayCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9F0A")
            strokeWidth = barWidth + dpToPx(context, 4f * scaleFactor)
            strokeCap = Paint.Cap.BUTT
        }

        val historyCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD60A")  // Yellow caps for history
            strokeWidth = barWidth + dpToPx(context, 4f * scaleFactor)
            strokeCap = Paint.Cap.BUTT
        }

        // Climate Normal bar - Green for long-range averages
        val climateNormalBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#34C759")
            strokeWidth = barWidth
            strokeCap = Paint.Cap.ROUND
        }

        val climateNormalCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#34C759")
            strokeWidth = barWidth + dpToPx(context, 4f * scaleFactor)
            strokeCap = Paint.Cap.BUTT
        }

        // Scale text sizes with widget height
        val baseDayLabelSize = 24f
        val baseTempLabelSize = 22f
        val dayLabelTextSize = dpToPx(context, baseDayLabelSize * heightScaleFactor)
        val tempLabelTextSize = dpToPx(context, baseTempLabelSize * heightScaleFactor)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#AAAAAA")
            textSize = dayLabelTextSize  // Day labels - scaled with height
            textAlign = Paint.Align.CENTER
        }

        val tempTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = tempLabelTextSize  // Temp labels - scaled with height
            textAlign = Paint.Align.CENTER
        }

        // Accuracy dot colors
        val accuracyGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#34C759")  // Green
        }
        val accuracyYellowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFCC00")  // Yellow
        }
        val accuracyRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF3B30")  // Red
        }

        val forecastTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#888888")
            textSize = dpToPx(context, 10f * scaleFactor)
            textAlign = Paint.Align.CENTER
        }

        // Forecast bar paint (blue line showing what was predicted for past days)
        val forecastBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5AC8FA")  // Blue for forecast comparison
            strokeWidth = barWidth * 0.5f  // Thinner than actual bar
            strokeCap = Paint.Cap.ROUND
        }

        val forecastCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5AC8FA")  // Blue caps
            strokeWidth = (barWidth + dpToPx(context, 4f * scaleFactor)) * 0.5f
            strokeCap = Paint.Cap.BUTT
        }

        val dotRadius = dpToPx(context, 4f * scaleFactor)
        val forecastBarOffset = barWidth * 1.2f  // Offset for forecast bar from main bar
        
        // Icon paints
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.parseColor("#AAAAAA"), PorterDuff.Mode.SRC_IN)
        }
        val sunnyIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.parseColor("#FFD60A"), PorterDuff.Mode.SRC_IN)
        }
        val iconSize = dpToPx(context, 20f * scaleFactor).toInt()

        // Draw each day
        days.forEachIndexed { index, day ->
            val centerX = horizontalPadding + dayWidth * index + dayWidth / 2

            // Draw day label at bottom (always draw this)
            canvas.drawText(day.label, centerX, heightPx - bottomPadding, textPaint)
            
            // Draw weather icon above day label
            if (day.iconRes != null) {
                val drawable = androidx.core.content.ContextCompat.getDrawable(context, day.iconRes)
                if (drawable != null) {
                    val iconY = heightPx - bottomPadding - dayLabelTextSize - dpToPx(context, 4f * scaleFactor) - iconSize
                    val iconX = centerX - iconSize / 2f
                    
                    drawable.setBounds(
                        iconX.toInt(), 
                        iconY.toInt(), 
                        (iconX + iconSize).toInt(), 
                        (iconY + iconSize).toInt()
                    )
                    
                    if (day.isSunny) {
                        drawable.setTint(Color.parseColor("#FFD60A"))
                    } else {
                        drawable.setTint(Color.parseColor("#AAAAAA"))
                    }
                    
                    drawable.draw(canvas)
                }
            }

            // Skip drawing bar if BOTH high and low are missing
            if (day.high == null && day.low == null) return@forEachIndexed

            // Determine paint style
            val paint = when {
                day.isToday -> todayBarPaint
                day.isPast -> historyBarPaint
                day.isClimateNormal -> climateNormalBarPaint
                else -> barPaint  // Future days
            }
            val cap = when {
                day.isToday -> todayCapPaint
                day.isPast -> historyCapPaint
                day.isClimateNormal -> climateNormalCapPaint
                else -> capPaint
            }

            // Calculate Y positions (if values exist)
            val highY = day.high?.let {
                graphTop + graphHeight * (1 - (it - minTemp).toFloat() / tempRange)
            }
            val lowY = day.low?.let {
                graphTop + graphHeight * (1 - (it - minTemp).toFloat() / tempRange)
            }

            // Draw based on available data
            if (highY != null && lowY != null) {
                // Full data: draw vertical bar and both caps
                canvas.drawLine(centerX, highY, centerX, lowY, paint)
                canvas.drawLine(centerX - capHeight, highY, centerX + capHeight, highY, cap)
                canvas.drawLine(centerX - capHeight, lowY, centerX + capHeight, lowY, cap)
            } else if (highY != null) {
                // Only high temp: draw top cap only
                canvas.drawLine(centerX - capHeight, highY, centerX + capHeight, highY, cap)
            } else if (lowY != null) {
                // Only low temp: draw bottom cap only
                canvas.drawLine(centerX - capHeight, lowY, centerX + capHeight, lowY, cap)
            }

            // Draw forecast bar (only if full data available for comparison)
            if (day.accuracyMode == AccuracyDisplayMode.FORECAST_BAR &&
                day.forecastHigh != null && day.forecastLow != null) {
                val forecastHighY = graphTop + graphHeight * (1 - (day.forecastHigh - minTemp).toFloat() / tempRange)
                val forecastLowY = graphTop + graphHeight * (1 - (day.forecastLow - minTemp).toFloat() / tempRange)
                val forecastX = centerX + forecastBarOffset

                // Draw the forecast bar
                canvas.drawLine(forecastX, forecastHighY, forecastX, forecastLowY, forecastBarPaint)

                // Draw forecast caps
                val forecastCapSize = capHeight * 0.6f
                canvas.drawLine(forecastX - forecastCapSize, forecastHighY, forecastX + forecastCapSize, forecastHighY, forecastCapPaint)
                canvas.drawLine(forecastX - forecastCapSize, forecastLowY, forecastX + forecastCapSize, forecastLowY, forecastCapPaint)
            }

            // Draw high label if available
            if (day.high != null) {
                val highLabel = formatTempWithForecast(
                    day.high, day.forecastHigh, day.forecastSource, day.accuracyMode
                )
                // If we have a Y position, use it. Otherwise (shouldn't happen here), skip.
                highY?.let { y ->
                    canvas.drawText(highLabel, centerX, y - dpToPx(context, 6f * scaleFactor), tempTextPaint)
                }
            }

            // Draw low label if available
            if (day.low != null) {
                val lowLabel = formatTempWithForecast(
                    day.low, day.forecastLow, day.forecastSource, day.accuracyMode
                )
                lowY?.let { y ->
                    canvas.drawText(lowLabel, centerX, y + dpToPx(context, 22f * scaleFactor), tempTextPaint)
                }
            }

            // Draw single accuracy dot if applicable (requires high temp)
            if (day.accuracyMode == AccuracyDisplayMode.ACCURACY_DOT && day.forecastHigh != null && day.high != null) {
                val highDiff = kotlin.math.abs(day.high - day.forecastHigh)
                val dotPaint = when {
                    highDiff <= 2 -> accuracyGreenPaint
                    highDiff <= 5 -> accuracyYellowPaint
                    else -> accuracyRedPaint
                }
                highY?.let { y ->
                    canvas.drawCircle(
                        centerX + dpToPx(context, 20f * scaleFactor),
                        y - dpToPx(context, 6f * scaleFactor) - dotRadius,
                        dotRadius,
                        dotPaint
                    )
                }
            }
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

    private fun formatTempWithForecast(
        actual: Int,
        forecast: Int?,
        forecastSource: String?,
        mode: AccuracyDisplayMode
    ): String {
        return when {
            mode == AccuracyDisplayMode.NONE ||
            mode == AccuracyDisplayMode.ACCURACY_DOT ||
            mode == AccuracyDisplayMode.FORECAST_BAR -> {
                "$actual°"
            }
            mode == AccuracyDisplayMode.SIDE_BY_SIDE && forecast != null -> {
                val label = if (forecastSource == "NWS") "N" else "M"
                "$actual° ($label:$forecast°)"
            }
            mode == AccuracyDisplayMode.DIFFERENCE && forecast != null -> {
                val diff = actual - forecast
                val sign = if (diff >= 0) "+" else ""
                val label = if (forecastSource == "NWS") "N" else "M"
                "$actual° ($label:$sign$diff)"
            }
            else -> "$actual°"
        }
    }
}
