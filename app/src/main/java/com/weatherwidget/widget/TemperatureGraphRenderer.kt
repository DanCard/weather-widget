package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.TypedValue
import com.weatherwidget.data.local.WeatherEntity

object TemperatureGraphRenderer {

    data class DayData(
        val label: String,
        val high: Int,
        val low: Int,
        val isToday: Boolean = false
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

        // Layout constants
        val padding = dpToPx(context, 8f)
        val labelHeight = dpToPx(context, 16f)
        val graphTop = padding
        val graphBottom = heightPx - labelHeight - padding
        val graphHeight = graphBottom - graphTop

        val dayWidth = (widthPx - 2 * padding) / days.size
        val barWidth = dpToPx(context, 6f)
        val capHeight = dpToPx(context, 2f)

        // Paints
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#007AFF")
            strokeWidth = barWidth
            strokeCap = Paint.Cap.ROUND
        }

        val todayBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9500")
            strokeWidth = barWidth
            strokeCap = Paint.Cap.ROUND
        }

        val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#007AFF")
            strokeWidth = barWidth + dpToPx(context, 4f)
            strokeCap = Paint.Cap.BUTT
        }

        val todayCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9500")
            strokeWidth = barWidth + dpToPx(context, 4f)
            strokeCap = Paint.Cap.BUTT
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#666666")
            textSize = dpToPx(context, 10f)
            textAlign = Paint.Align.CENTER
        }

        val tempTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#333333")
            textSize = dpToPx(context, 9f)
            textAlign = Paint.Align.CENTER
        }

        // Draw each day
        days.forEachIndexed { index, day ->
            val centerX = padding + dayWidth * index + dayWidth / 2

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

            // Draw high/low labels near the caps
            canvas.drawText("${day.high}°", centerX, highY - dpToPx(context, 3f), tempTextPaint)
            canvas.drawText("${day.low}°", centerX, lowY + dpToPx(context, 11f), tempTextPaint)

            // Draw day label at bottom
            canvas.drawText(day.label, centerX, heightPx - padding, textPaint)
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
