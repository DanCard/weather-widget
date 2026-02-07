package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.TypedValue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

object ForecastEvolutionRenderer {
    private const val SNAPSHOT_BUCKET_HOURS = 4L
    private const val MILLIS_PER_HOUR = 60L * 60L * 1000L

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
    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val DATETIME_FORMATTER = DateTimeFormatter.ofPattern("M/d h a", Locale.getDefault())

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

    fun renderHighErrorGraph(
        context: Context,
        nwsPoints: List<EvolutionPoint>,
        meteoPoints: List<EvolutionPoint>,
        actualHigh: Int?,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        return renderErrorGraph(
            context = context,
            nwsPoints = nwsPoints,
            meteoPoints = meteoPoints,
            actualValue = actualHigh,
            widthPx = widthPx,
            heightPx = heightPx,
            isHigh = true
        )
    }

    fun renderLowErrorGraph(
        context: Context,
        nwsPoints: List<EvolutionPoint>,
        meteoPoints: List<EvolutionPoint>,
        actualLow: Int?,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        return renderErrorGraph(
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

        fun tempFor(point: EvolutionPoint): Int? {
            return if (isHigh) point.highTemp else point.lowTemp
        }

        // Consolidate snapshots into time buckets so we preserve temporal evolution
        // while preventing dense same-day spikes. Defaults to 4-hour resolution.
        fun bucketize(points: List<EvolutionPoint>): List<EvolutionPoint> {
            val bucketMillis = SNAPSHOT_BUCKET_HOURS * MILLIS_PER_HOUR
            return points
                .filter { tempFor(it) != null }
                .groupBy { point ->
                    point.fetchedAt / bucketMillis
                }
                .mapNotNull { (_, bucketPoints) -> bucketPoints.maxByOrNull { it.fetchedAt } }
        }

        val nwsSeries = bucketize(nwsPoints)
        val meteoSeries = bucketize(meteoPoints)

        if (nwsSeries.isEmpty() && meteoSeries.isEmpty()) {
            return bitmap
        }

        // Collect all temperature values for scaling
        val allTemps = mutableListOf<Int>()
        nwsSeries.forEach {
            tempFor(it)?.let { temp -> allTemps.add(temp) }
        }
        meteoSeries.forEach {
            tempFor(it)?.let { temp -> allTemps.add(temp) }
        }
        actualValue?.let { allTemps.add(it) }

        if (allTemps.isEmpty()) return bitmap

        val forecastSamples = (nwsSeries + meteoSeries).mapNotNull { point ->
            val temp = tempFor(point)
            temp?.let {
                ForecastSample(
                    temp = it,
                    daysAhead = point.daysAhead,
                    source = point.source
                )
            }
        }

        if (forecastSamples.size == 1) {
            return renderSinglePointBarGraph(
                context = context,
                widthPx = widthPx,
                heightPx = heightPx,
                sample = forecastSamples.first(),
                actualValue = actualValue,
                isHigh = isHigh
            )
        }

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

        val allTimes = (nwsSeries + meteoSeries).map { it.fetchedAt }
        if (allTimes.isEmpty()) return bitmap
        val minTime = allTimes.minOrNull() ?: return bitmap
        val maxTime = allTimes.maxOrNull() ?: return bitmap
        val isSingleTimeDataset = minTime == maxTime

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

        val timeTicks = buildTimeTicks(minTime, maxTime)
        timeTicks.forEach { tick ->
            val x = getTimeX(tick, graphLeft, graphWidth, minTime, maxTime, isSingleTimeDataset)
            val label = formatTimeLabel(tick, minTime, maxTime)
            canvas.drawText(label, x, heightPx - dpToPx(context, 8f), labelPaint)
            canvas.drawLine(x, graphTop, x, graphBottom, gridPaint)
        }

        // Helper function to get Y position for temperature
        fun getY(temp: Float): Float {
            return graphBottom - graphHeight * (temp - minTemp) / tempRange
        }

        fun getX(point: EvolutionPoint): Float {
            return getTimeX(point.fetchedAt, graphLeft, graphWidth, minTime, maxTime, isSingleTimeDataset)
        }

        // Draw NWS curve
        if (nwsSeries.isNotEmpty()) {
            val sortedNws = nwsSeries.sortedBy { it.fetchedAt }
            val path = Path()
            var lastPoint: PathPoint? = null

            sortedNws.forEach { point ->
                val temp = tempFor(point)
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
                val temp = tempFor(point)
                if (temp != null) {
                    val x = getX(point)
                    val y = getY(temp.toFloat())
                    canvas.drawCircle(x, y, dpToPx(context, 3f), nwsPaint.apply { style = Paint.Style.FILL })
                }
            }
            nwsPaint.style = Paint.Style.STROKE
        }

        // Draw Open-Meteo curve
        if (meteoSeries.isNotEmpty()) {
            val sortedMeteo = meteoSeries.sortedBy { it.fetchedAt }
            val path = Path()
            var lastPoint: PathPoint? = null

            sortedMeteo.forEach { point ->
                val temp = tempFor(point)
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
                val temp = tempFor(point)
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

    private fun renderErrorGraph(
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
        canvas.drawColor(Color.TRANSPARENT)

        if (actualValue == null || (nwsPoints.isEmpty() && meteoPoints.isEmpty())) {
            return bitmap
        }

        fun tempFor(point: EvolutionPoint): Int? {
            return if (isHigh) point.highTemp else point.lowTemp
        }

        fun bucketize(points: List<EvolutionPoint>): List<EvolutionPoint> {
            val bucketMillis = SNAPSHOT_BUCKET_HOURS * MILLIS_PER_HOUR
            return points
                .filter { tempFor(it) != null }
                .groupBy { point ->
                    point.fetchedAt / bucketMillis
                }
                .mapNotNull { (_, bucketPoints) -> bucketPoints.maxByOrNull { it.fetchedAt } }
        }

        val nwsSeries = bucketize(nwsPoints)
        val meteoSeries = bucketize(meteoPoints)
        val allSeries = nwsSeries + meteoSeries
        if (allSeries.isEmpty()) return bitmap

        val errorSamples = allSeries.mapNotNull { point ->
            tempFor(point)?.let { temp ->
                ErrorSample(
                    error = temp - actualValue,
                    daysAhead = point.daysAhead,
                    fetchedAt = point.fetchedAt,
                    source = point.source
                )
            }
        }
        if (errorSamples.isEmpty()) return bitmap

        val maxAbsError = errorSamples.maxOf { abs(it.error) }.toFloat()
        val yBound = maxOf(3f, ceil(maxAbsError) + 1f)
        val minError = -yBound
        val maxError = yBound
        val errorRange = maxError - minError

        val paddingLeft = dpToPx(context, 40f)
        val paddingRight = dpToPx(context, 16f)
        val paddingTop = dpToPx(context, 24f)
        val paddingBottom = dpToPx(context, 32f)

        val graphLeft = paddingLeft
        val graphRight = widthPx - paddingRight
        val graphTop = paddingTop
        val graphBottom = heightPx - paddingBottom
        val graphWidth = graphRight - graphLeft
        val graphHeight = graphBottom - graphTop

        val allTimes = errorSamples.map { it.fetchedAt }
        if (allTimes.isEmpty()) return bitmap
        val minTime = allTimes.minOrNull() ?: return bitmap
        val maxTime = allTimes.maxOrNull() ?: return bitmap
        val isSingleTimeDataset = minTime == maxTime

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
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(GRID_COLOR)
            strokeWidth = dpToPx(context, 1f)
            style = Paint.Style.STROKE
        }
        val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACTUAL_COLOR)
            strokeWidth = dpToPx(context, 2f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, 6f), dpToPx(context, 4f)), 0f)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(LABEL_COLOR)
            textSize = dpToPx(context, 13f)
            textAlign = Paint.Align.CENTER
        }
        val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(LABEL_COLOR)
            textSize = dpToPx(context, 13f)
            textAlign = Paint.Align.RIGHT
        }
        val zeroLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACTUAL_COLOR)
            textSize = dpToPx(context, 13f)
            textAlign = Paint.Align.LEFT
        }

        fun getY(error: Float): Float {
            return graphBottom - graphHeight * (error - minError) / errorRange
        }

        for (i in 0..4) {
            val errorValue = minError + (errorRange * i / 4f)
            val y = getY(errorValue)
            canvas.drawLine(graphLeft, y, graphRight, y, gridPaint)
            val label = if (errorValue > 0f) {
                "+${errorValue.toInt()}°"
            } else {
                "${errorValue.toInt()}°"
            }
            canvas.drawText(label, graphLeft - dpToPx(context, 6f), y + dpToPx(context, 4f), yLabelPaint)
        }

        val timeTicks = buildTimeTicks(minTime, maxTime)
        timeTicks.forEach { tick ->
            val x = getTimeX(tick, graphLeft, graphWidth, minTime, maxTime, isSingleTimeDataset)
            canvas.drawText(formatTimeLabel(tick, minTime, maxTime), x, heightPx - dpToPx(context, 8f), labelPaint)
            canvas.drawLine(x, graphTop, x, graphBottom, gridPaint)
        }

        val zeroY = getY(0f)
        canvas.drawLine(graphLeft, zeroY, graphRight, zeroY, zeroPaint)
        canvas.drawText("0°", graphRight + dpToPx(context, 6f), zeroY + dpToPx(context, 4f), zeroLabelPaint)

        fun getX(sample: ErrorSample): Float {
            return getTimeX(sample.fetchedAt, graphLeft, graphWidth, minTime, maxTime, isSingleTimeDataset)
        }

        fun drawSeries(series: List<ErrorSample>, paint: Paint) {
            if (series.isEmpty()) return
            val sortedByTime = series.sortedBy { it.fetchedAt }
            val path = Path()
            var lastPoint: PathPoint? = null

            sortedByTime.forEach { sample ->
                val x = getX(sample)
                val y = getY(sample.error.toFloat())
                if (lastPoint == null) {
                    path.moveTo(x, y)
                } else {
                    val controlX = (lastPoint!!.x + x) / 2f
                    path.quadTo(controlX, lastPoint!!.y, x, y)
                }
                lastPoint = PathPoint(x, y)
            }
            canvas.drawPath(path, paint)

            val originalStyle = paint.style
            paint.style = Paint.Style.FILL
            sortedByTime.forEach { sample ->
                canvas.drawCircle(getX(sample), getY(sample.error.toFloat()), dpToPx(context, 3f), paint)
            }
            paint.style = originalStyle
        }

        drawSeries(errorSamples.filter { it.source == "NWS" }, nwsPaint)
        drawSeries(errorSamples.filter { it.source == "OPEN_METEO" }, meteoPaint)

        return bitmap
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }

    private fun getTimeX(
        timestampMillis: Long,
        graphLeft: Float,
        graphWidth: Float,
        minTime: Long,
        maxTime: Long,
        isSingleTimeDataset: Boolean
    ): Float {
        return if (isSingleTimeDataset) {
            graphLeft + graphWidth / 2f
        } else {
            graphLeft + graphWidth * (timestampMillis - minTime).toFloat() / (maxTime - minTime).toFloat()
        }
    }

    private fun buildTimeTicks(minTime: Long, maxTime: Long): List<Long> {
        if (minTime == maxTime) return listOf(minTime)
        val divisions = 4
        return (0..divisions).map { idx ->
            minTime + ((maxTime - minTime) * idx / divisions)
        }
    }

    private fun formatTimeLabel(timestampMillis: Long, minTime: Long, maxTime: Long): String {
        val zone = ZoneId.systemDefault()
        val instant = Instant.ofEpochMilli(timestampMillis)
        val minDate = Instant.ofEpochMilli(minTime).atZone(zone).toLocalDate()
        val maxDate = Instant.ofEpochMilli(maxTime).atZone(zone).toLocalDate()
        val formatter = if (minDate == maxDate) TIME_FORMATTER else DATETIME_FORMATTER
        return formatter.format(instant.atZone(zone))
    }

    private fun renderSinglePointBarGraph(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        sample: ForecastSample,
        actualValue: Int?,
        isHigh: Boolean
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val allTemps = mutableListOf(sample.temp)
        actualValue?.let { allTemps.add(it) }

        val minTemp = (allTemps.minOrNull() ?: sample.temp).toFloat()
        val maxTemp = (allTemps.maxOrNull() ?: sample.temp).toFloat()
        val tempRange = (maxTemp - minTemp).coerceAtLeast(5f)

        val paddingLeft = dpToPx(context, 40f)
        val paddingRight = dpToPx(context, 16f)
        val paddingTop = dpToPx(context, 24f)
        val paddingBottom = dpToPx(context, 28f)

        val graphLeft = paddingLeft
        val graphRight = widthPx - paddingRight
        val graphTop = paddingTop
        val graphBottom = heightPx - paddingBottom
        val graphWidth = graphRight - graphLeft
        val graphHeight = graphBottom - graphTop

        fun getY(temp: Float): Float {
            return graphBottom - graphHeight * (temp - minTemp) / tempRange
        }

        val sourceColor = if (sample.source == "NWS") NWS_COLOR else METEO_COLOR

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(GRID_COLOR)
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(context, 1f)
        }
        val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(LABEL_COLOR)
            textSize = dpToPx(context, 12f)
            textAlign = Paint.Align.RIGHT
        }
        val forecastBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(sourceColor)
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(context, 5f)
            strokeCap = Paint.Cap.ROUND
        }
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(sourceColor)
            style = Paint.Style.FILL
        }
        val markerOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(context, 1.5f)
        }
        val actualLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACTUAL_COLOR)
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(context, 2f)
            pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, 6f), dpToPx(context, 4f)), 0f)
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(LABEL_COLOR)
            textSize = dpToPx(context, 12f)
            textAlign = Paint.Align.CENTER
        }
        val forecastLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(sourceColor)
            textSize = dpToPx(context, 14f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val actualLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACTUAL_COLOR)
            textSize = dpToPx(context, 12f)
            textAlign = Paint.Align.LEFT
        }

        val maxLabel = String.format("%.0f°", maxTemp)
        val minLabel = String.format("%.0f°", minTemp)
        canvas.drawText(maxLabel, graphLeft - dpToPx(context, 6f), graphTop + dpToPx(context, 4f), yLabelPaint)
        canvas.drawText(minLabel, graphLeft - dpToPx(context, 6f), graphBottom + dpToPx(context, 4f), yLabelPaint)

        val forecastY = getY(sample.temp.toFloat())
        canvas.drawLine(graphLeft, forecastY, graphRight, forecastY, forecastBarPaint)

        if (actualValue != null) {
            val actualY = getY(actualValue.toFloat())
            canvas.drawLine(graphLeft, actualY, graphRight, actualY, actualLinePaint)
            canvas.drawText("Actual ${actualValue}°", graphRight + dpToPx(context, 6f), actualY + dpToPx(context, 4f), actualLabelPaint)
        }

        val markerX = graphLeft + graphWidth / 2f
        canvas.drawCircle(markerX, forecastY, dpToPx(context, 6f), markerPaint)
        canvas.drawCircle(markerX, forecastY, dpToPx(context, 6f), markerOutlinePaint)

        val title = if (isHigh) "Single High Forecast" else "Single Low Forecast"
        canvas.drawText(title, widthPx / 2f, dpToPx(context, 16f), titlePaint)
        val sourceLabel = if (sample.source == "NWS") "NWS" else "Open-Meteo"
        val error = actualValue?.let { it - sample.temp }
        val diffText = if (error != null) {
            val sign = if (error >= 0) "+" else ""
            "  Diff ${sign}${error}°"
        } else {
            ""
        }
        val forecastLabel = "$sourceLabel ${sample.temp}°  (${sample.daysAhead}d)$diffText"
        canvas.drawText(forecastLabel, markerX, forecastY - dpToPx(context, 10f), forecastLabelPaint)

        return bitmap
    }

    // Track last point manually since Path doesn't expose it directly
    private data class PathPoint(val x: Float, val y: Float)
    private data class ForecastSample(val temp: Int, val daysAhead: Int, val source: String)
    private data class ErrorSample(val error: Int, val daysAhead: Int, val fetchedAt: Long, val source: String)
}
