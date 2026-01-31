package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.TypedValue

object TemperatureGraphRenderer {

    data class DayData(
        val label: String,
        val high: Int,
        val low: Int,
        val isToday: Boolean = false,
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

        // Find temperature range for scaling
        val allTemps = days.flatMap { listOf(it.high, it.low) }
        val minTemp = (allTemps.minOrNull() ?: 0) - 5
        val maxTemp = (allTemps.maxOrNull() ?: 100) + 5
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1)

        // Scale factor based on widget height (base is ~136dp for 2 rows)
        val baseHeightDp = 136f
        val heightDp = heightPx / context.resources.displayMetrics.density
        val scaleFactor = (heightDp / baseHeightDp).coerceIn(1f, 2.5f)

        // Layout constants (scaled)
        val horizontalPadding = dpToPx(context, -8f * scaleFactor)  // Slight negative for more space
        val topPadding = dpToPx(context, 20f * scaleFactor)  // Room for API source indicator
        val bottomPadding = dpToPx(context, 2f * scaleFactor)  // Minimal bottom padding
        val labelHeight = dpToPx(context, 40f * scaleFactor)
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

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#AAAAAA")
            textSize = dpToPx(context, 20f * scaleFactor)
            textAlign = Paint.Align.CENTER
        }

        val tempTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = dpToPx(context, 18f * scaleFactor)
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
            textSize = dpToPx(context, 13f * scaleFactor)
            textAlign = Paint.Align.CENTER
        }

        // Forecast bar paint (yellow/gold line showing what was predicted)
        val forecastBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD60A")  // Gold/yellow
            strokeWidth = barWidth * 0.5f  // Thinner than actual bar
            strokeCap = Paint.Cap.ROUND
        }

        val forecastCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD60A")
            strokeWidth = (barWidth + dpToPx(context, 4f * scaleFactor)) * 0.5f
            strokeCap = Paint.Cap.BUTT
        }

        val dotRadius = dpToPx(context, 4f * scaleFactor)
        val forecastBarOffset = barWidth * 1.2f  // Offset for forecast bar from main bar

        // Draw each day
        days.forEachIndexed { index, day ->
            val centerX = horizontalPadding + dayWidth * index + dayWidth / 2

            // Calculate Y positions (inverted - higher temp = lower Y)
            val highY = graphTop + graphHeight * (1 - (day.high - minTemp).toFloat() / tempRange)
            val lowY = graphTop + graphHeight * (1 - (day.low - minTemp).toFloat() / tempRange)

            val paint = if (day.isToday) todayBarPaint else barPaint
            val cap = if (day.isToday) todayCapPaint else capPaint

            // Draw vertical bar (the "error bar" stem)
            canvas.drawLine(centerX, highY, centerX, lowY, paint)

            // Draw caps (horizontal lines at top and bottom)
            canvas.drawLine(centerX - capHeight, highY, centerX + capHeight, highY, cap)
            canvas.drawLine(centerX - capHeight, lowY, centerX + capHeight, lowY, cap)

            // Draw forecast bar (yellow line showing what was predicted) for historical days
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

            // Draw high/low labels with forecast comparison
            val highLabel = formatTempWithForecast(
                day.high, day.forecastHigh, day.forecastSource, day.accuracyMode
            )
            val lowLabel = formatTempWithForecast(
                day.low, day.forecastLow, day.forecastSource, day.accuracyMode
            )

            canvas.drawText(highLabel, centerX, highY - dpToPx(context, 6f * scaleFactor), tempTextPaint)
            canvas.drawText(lowLabel, centerX, lowY + dpToPx(context, 22f * scaleFactor), tempTextPaint)

            // Draw single accuracy dot if applicable
            if (day.accuracyMode == AccuracyDisplayMode.ACCURACY_DOT && day.forecastHigh != null) {
                val highDiff = kotlin.math.abs(day.high - day.forecastHigh)
                val dotPaint = when {
                    highDiff <= 2 -> accuracyGreenPaint
                    highDiff <= 5 -> accuracyYellowPaint
                    else -> accuracyRedPaint
                }
                canvas.drawCircle(
                    centerX + dpToPx(context, 20f * scaleFactor),
                    highY - dpToPx(context, 6f * scaleFactor) - dotRadius,
                    dotRadius,
                    dotPaint
                )
            }

            // Draw day label at bottom
            canvas.drawText(day.label, centerX, heightPx - bottomPadding, textPaint)
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
