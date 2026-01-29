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

        // Scale factor based on widget height (base is ~136dp for 2 rows)
        val baseHeightDp = 136f
        val heightDp = heightPx / context.resources.displayMetrics.density
        val scaleFactor = (heightDp / baseHeightDp).coerceIn(1f, 2.5f)

        // Layout constants (scaled)
        val padding = dpToPx(context, 8f * scaleFactor)
        val labelHeight = dpToPx(context, 32f * scaleFactor)
        val graphTop = padding
        val graphBottom = heightPx - labelHeight - padding
        val graphHeight = graphBottom - graphTop

        val dayWidth = (widthPx - 2 * padding) / days.size
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
            canvas.drawText("${day.high}°", centerX, highY - dpToPx(context, 6f * scaleFactor), tempTextPaint)
            canvas.drawText("${day.low}°", centerX, lowY + dpToPx(context, 22f * scaleFactor), tempTextPaint)

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
