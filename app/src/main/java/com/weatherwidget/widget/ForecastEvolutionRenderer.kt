package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.TypedValue

object ForecastEvolutionRenderer {

    data class EvolutionPoint(
        val forecastDate: String,      // When forecast was made
        val fetchedAt: Long,           // Exact fetch time
        val daysAhead: Int,            // How many days ahead this forecast was for
        val highTemp: Int?,
        val lowTemp: Int?,
        val source: String             // "NWS" or "OPEN_METEO"
    )

    // Colors
    private const val NWS_COLOR = "#5AC8FA"       // Blue
    private const val METEO_COLOR = "#34C759"     // Green
    private const val ACTUAL_COLOR = "#FF9F0A"    // Orange
    private const val LABEL_COLOR = "#AAAAAA"     // Gray
    private const val GRID_COLOR = "#333333"      // Dark gray

    fun renderHighGraph(
        context: Context,
        nwsPoints: List<EvolutionPoint>,
        meteoPoints: List<EvolutionPoint>,
        actualHigh: Int?,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        return renderGraph(
            context = context,
            nwsPoints = nwsPoints,
            meteoPoints = meteoPoints,
            actualValue = actualHigh,
            widthPx = widthPx,
            heightPx = heightPx,
            isHigh = true
        )
    }

    fun renderLowGraph(
        context: Context,
        nwsPoints: List<EvolutionPoint>,
        meteoPoints: List<EvolutionPoint>,
        actualLow: Int?,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        return renderGraph(
            context = context,
            nwsPoints = nwsPoints,
            meteoPoints = meteoPoints,
            actualValue = actualLow,
            widthPx = widthPx,
            heightPx = heightPx,
            isHigh = false
        )
    }

    private fun renderGraph(
        context: Context,
        nwsPoints: List<EvolutionPoint>,
        meteoPoints: List<EvolutionPoint>,
        actualValue: Int?,
        widthPx: Int,
        heightPx: Int,
        isHigh: Boolean
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw background
        canvas.drawColor(Color.TRANSPARENT)

        if (nwsPoints.isEmpty() && meteoPoints.isEmpty()) {
            return bitmap
        }

        // Collect all temperature values for scaling
        val allTemps = mutableListOf<Int>()
        nwsPoints.forEach {
            if (isHigh) it.highTemp?.let { temp -> allTemps.add(temp) }
            else it.lowTemp?.let { temp -> allTemps.add(temp) }
        }
        meteoPoints.forEach {
            if (isHigh) it.highTemp?.let { temp -> allTemps.add(temp) }
            else it.lowTemp?.let { temp -> allTemps.add(temp) }
        }
        actualValue?.let { allTemps.add(it) }

        if (allTemps.isEmpty()) return bitmap

        val minTemp = allTemps.minOrNull()?.toFloat() ?: 0f
        val maxTemp = allTemps.maxOrNull()?.toFloat() ?: 100f
        val tempRange = (maxTemp - minTemp).coerceAtLeast(5f)  // Minimum 5 degree range

        // Layout constants
        val paddingLeft = dpToPx(context, 40f)   // Space for Y-axis labels
        val paddingRight = dpToPx(context, 16f)
        val paddingTop = dpToPx(context, 24f)    // Space for title
        val paddingBottom = dpToPx(context, 32f) // Space for X-axis labels

        val graphLeft = paddingLeft
        val graphRight = widthPx - paddingRight
        val graphTop = paddingTop
        val graphBottom = heightPx - paddingBottom
        val graphWidth = graphRight - graphLeft
        val graphHeight = graphBottom - graphTop

        // Get unique days for X-axis (sorted by daysAhead descending, so 7d is left)
        val allDays = (nwsPoints + meteoPoints)
            .map { it.daysAhead }
            .distinct()
            .sortedDescending()

        if (allDays.isEmpty()) return bitmap

        val minDay = allDays.minOrNull() ?: 1
        val maxDay = allDays.maxOrNull() ?: 7
        val dayRange = (maxDay - minDay).coerceAtLeast(1)

        // Paints
        val nwsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(NWS_COLOR)
            strokeWidth = dpToPx(context, 2.5f)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val meteoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(METEO_COLOR)
            strokeWidth = dpToPx(context, 2.5f)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val actualPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACTUAL_COLOR)
            strokeWidth = dpToPx(context, 2f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, 6f), dpToPx(context, 4f)), 0f)
        }

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(GRID_COLOR)
            strokeWidth = dpToPx(context, 1f)
            style = Paint.Style.STROKE
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(LABEL_COLOR)
            textSize = dpToPx(context, 13.0f)
            textAlign = Paint.Align.CENTER
        }

        val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(LABEL_COLOR)
            textSize = dpToPx(context, 13.0f)
            textAlign = Paint.Align.RIGHT
        }

        val actualLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACTUAL_COLOR)
            textSize = dpToPx(context, 14.5f)
            textAlign = Paint.Align.LEFT
        }

        // Draw grid lines (horizontal)
        val gridSteps = 4
        for (i in 0..gridSteps) {
            val temp = minTemp + (tempRange * i / gridSteps)
            val y = graphBottom - graphHeight * (temp - minTemp) / tempRange
            canvas.drawLine(graphLeft, y, graphRight, y, gridPaint)

            // Y-axis labels
            val label = String.format("%.0f°", temp)
            canvas.drawText(label, graphLeft - dpToPx(context, 6f), y + dpToPx(context, 4f), yLabelPaint)
        }

        // Draw X-axis labels (days ahead)
        allDays.forEach { day ->
            val x = graphLeft + graphWidth * (maxDay - day) / dayRange
            val label = "${day}d"
            canvas.drawText(label, x, heightPx - dpToPx(context, 8f), labelPaint)

            // Vertical grid line
            canvas.drawLine(x, graphTop, x, graphBottom, gridPaint)
        }

        // Helper function to get Y position for temperature
        fun getY(temp: Float): Float {
            return graphBottom - graphHeight * (temp - minTemp) / tempRange
        }

        // Helper function to get X position for a point
        fun getX(point: EvolutionPoint): Float {
            // Group points by daysAhead, then distribute within that day's column
            val sameDayPoints = (nwsPoints + meteoPoints)
                .filter { it.daysAhead == point.daysAhead }
                .sortedBy { it.fetchedAt }
            val indexInDay = sameDayPoints.indexOfFirst { it.fetchedAt == point.fetchedAt }
            val totalInDay = sameDayPoints.size.coerceAtLeast(1)

            val dayX = graphLeft + graphWidth * (maxDay - point.daysAhead) / dayRange
            val offset = if (totalInDay > 1) {
                (indexInDay - (totalInDay - 1) / 2f) * dpToPx(context, 8f) / totalInDay
            } else 0f

            return dayX + offset
        }

        // Draw NWS curve
        if (nwsPoints.isNotEmpty()) {
            val sortedNws = nwsPoints.sortedBy { it.daysAhead }
            val path = Path()
            var lastPoint: PathPoint? = null

            sortedNws.forEach { point ->
                val temp = if (isHigh) point.highTemp else point.lowTemp
                if (temp != null) {
                    val x = getX(point)
                    val y = getY(temp.toFloat())
                    if (lastPoint == null) {
                        path.moveTo(x, y)
                    } else {
                        // Smooth bezier curve
                        val controlX = (lastPoint!!.x + x) / 2
                        path.quadTo(controlX, lastPoint!!.y, x, y)
                    }
                    lastPoint = PathPoint(x, y)
                }
            }
            canvas.drawPath(path, nwsPaint)

            // Draw points
            sortedNws.forEach { point ->
                val temp = if (isHigh) point.highTemp else point.lowTemp
                if (temp != null) {
                    val x = getX(point)
                    val y = getY(temp.toFloat())
                    canvas.drawCircle(x, y, dpToPx(context, 3f), nwsPaint.apply { style = Paint.Style.FILL })
                }
            }
            nwsPaint.style = Paint.Style.STROKE
        }

        // Draw Open-Meteo curve
        if (meteoPoints.isNotEmpty()) {
            val sortedMeteo = meteoPoints.sortedBy { it.daysAhead }
            val path = Path()
            var lastPoint: PathPoint? = null

            sortedMeteo.forEach { point ->
                val temp = if (isHigh) point.highTemp else point.lowTemp
                if (temp != null) {
                    val x = getX(point)
                    val y = getY(temp.toFloat())
                    if (lastPoint == null) {
                        path.moveTo(x, y)
                    } else {
                        val controlX = (lastPoint!!.x + x) / 2
                        path.quadTo(controlX, lastPoint!!.y, x, y)
                    }
                    lastPoint = PathPoint(x, y)
                }
            }
            canvas.drawPath(path, meteoPaint)

            // Draw points
            sortedMeteo.forEach { point ->
                val temp = if (isHigh) point.highTemp else point.lowTemp
                if (temp != null) {
                    val x = getX(point)
                    val y = getY(temp.toFloat())
                    canvas.drawCircle(x, y, dpToPx(context, 3f), meteoPaint.apply { style = Paint.Style.FILL })
                }
            }
            meteoPaint.style = Paint.Style.STROKE
        }

        // Draw actual value line (for past dates)
        if (actualValue != null) {
            val y = getY(actualValue.toFloat())
            canvas.drawLine(graphLeft, y, graphRight, y, actualPaint)

            // Label
            val label = "Actual: $actualValue°"
            canvas.drawText(label, graphRight + dpToPx(context, 6f), y + dpToPx(context, 4f), actualLabelPaint)
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

    // Track last point manually since Path doesn't expose it directly
    private data class PathPoint(val x: Float, val y: Float)
}
