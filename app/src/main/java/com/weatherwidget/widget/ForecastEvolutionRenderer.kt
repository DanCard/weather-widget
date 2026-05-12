package com.weatherwidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import com.weatherwidget.data.model.WeatherSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

object ForecastEvolutionRenderer {
    private const val SNAPSHOT_BUCKET_HOURS = 4L
    private const val MILLIS_PER_HOUR = 60L * 60L * 1000L

    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val DATETIME_FORMATTER = DateTimeFormatter.ofPattern("M/d h a", Locale.getDefault())

    data class EvolutionPoint(
        val forecastDate: String,
        val fetchedAt: Long,
        val daysAhead: Int,
        val highTemp: Float?,
        val lowTemp: Float?,
        val source: WeatherSource,
    )

    fun renderHighGraph(
        context: Context,
        nwsPoints: List<EvolutionPoint>,
        meteoPoints: List<EvolutionPoint>,
        actualHigh: Float?,
        appActualHigh: Float?,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap = renderGraph(context, nwsPoints, meteoPoints, actualHigh, appActualHigh, widthPx, heightPx, isHigh = true)

    fun renderLowGraph(
        context: Context,
        nwsPoints: List<EvolutionPoint>,
        meteoPoints: List<EvolutionPoint>,
        actualLow: Float?,
        appActualLow: Float?,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap = renderGraph(context, nwsPoints, meteoPoints, actualLow, appActualLow, widthPx, heightPx, isHigh = false)

    fun renderHighErrorGraph(
        context: Context,
        nwsPoints: List<EvolutionPoint>,
        meteoPoints: List<EvolutionPoint>,
        actualHigh: Float?,
        appActualHigh: Float?,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap = renderErrorGraph(context, nwsPoints, meteoPoints, actualHigh, appActualHigh, widthPx, heightPx, isHigh = true)

    fun renderLowErrorGraph(
        context: Context,
        nwsPoints: List<EvolutionPoint>,
        meteoPoints: List<EvolutionPoint>,
        actualLow: Float?,
        appActualLow: Float?,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap = renderErrorGraph(context, nwsPoints, meteoPoints, actualLow, appActualLow, widthPx, heightPx, isHigh = false)

    private fun renderGraph(
        context: Context,
        nwsPoints: List<EvolutionPoint>,
        meteoPoints: List<EvolutionPoint>,
        actualValue: Float?,
        appActualValue: Float?,
        widthPx: Int,
        heightPx: Int,
        isHigh: Boolean,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        if (nwsPoints.isEmpty() && meteoPoints.isEmpty()) return bitmap

        fun tempFor(point: EvolutionPoint): Float? =
            if (isHigh) point.highTemp else point.lowTemp

        val nwsSeries = bucketize(nwsPoints) { tempFor(it) }
        val meteoSeries = bucketize(meteoPoints) { tempFor(it) }

        if (nwsSeries.isEmpty() && meteoSeries.isEmpty()) return bitmap

        val allTemps = collectTemps(nwsSeries + meteoSeries, ::tempFor, actualValue, appActualValue)
        if (allTemps.isEmpty()) return bitmap

        val forecastSamples = (nwsSeries + meteoSeries).mapNotNull { point ->
            tempFor(point)?.let { temp -> ForecastSample(temp, point.daysAhead, point.source) }
        }

        if (forecastSamples.size == 1) {
            return renderSinglePointBarGraph(context, widthPx, heightPx, forecastSamples.first(), actualValue, appActualValue, isHigh)
        }

        val axisScale = NiceAxisScale.compute(allTemps.minOrNull() ?: 0f, allTemps.maxOrNull() ?: 100f)
        val layout = computeLayout(context, widthPx, heightPx)
        val paints = EvolutionGraphStyle.getPaints(context)
        val dp = { dp: Float -> dpToPx(context, dp) }

        val allPoints = nwsSeries + meteoSeries
        val timeAxis = TimeAxis(allPoints.map { it.fetchedAt }, layout)

        drawGridAndAxes(canvas, layout, axisScale, timeAxis, paints, dp, isError = false)

        drawSeriesCurve(canvas, nwsSeries, ::tempFor, axisScale, timeAxis, layout, paints.nwsCurve, paints.nwsPoint, dp)
        drawSeriesCurve(canvas, meteoSeries, ::tempFor, axisScale, timeAxis, layout, paints.meteoCurve, paints.meteoPoint, dp)

        drawActualLine(canvas, layout, axisScale, actualValue, paints.apiActualLine, "API actual: ${actualValue?.let { formatTempLabel(it) }}", paints.apiActualLabel, dp)
        drawActualLine(canvas, layout, axisScale, appActualValue, paints.appActualLine, "Location actual: ${appActualValue?.let { formatTempLabel(it) }}", paints.appActualLabel, dp)

        return bitmap
    }

    private fun renderErrorGraph(
        context: Context,
        nwsPoints: List<EvolutionPoint>,
        meteoPoints: List<EvolutionPoint>,
        actualValue: Float?,
        appActualValue: Float?,
        widthPx: Int,
        heightPx: Int,
        isHigh: Boolean,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val baseline = appActualValue ?: actualValue ?: return bitmap
        if (nwsPoints.isEmpty() && meteoPoints.isEmpty()) return bitmap

        fun tempFor(point: EvolutionPoint): Float? =
            if (isHigh) point.highTemp else point.lowTemp

        val nwsSeries = bucketize(nwsPoints) { tempFor(it) }
        val meteoSeries = bucketize(meteoPoints) { tempFor(it) }
        val allSeries = nwsSeries + meteoSeries
        if (allSeries.isEmpty()) return bitmap

        val errorSamples = allSeries.mapNotNull { point ->
            tempFor(point)?.let { temp ->
                ErrorSample(error = temp - baseline, daysAhead = point.daysAhead, fetchedAt = point.fetchedAt, source = point.source)
            }
        }
        if (errorSamples.isEmpty()) return bitmap

        val maxAbsError = errorSamples.maxOf { abs(it.error) }
        val yBound = maxOf(3f, ceil(maxAbsError) + 1f)
        val axisScale = NiceAxisScale.computeSymmetric(yBound, minRange = 6f)

        val layout = computeLayout(context, widthPx, heightPx)
        val paints = EvolutionGraphStyle.getPaints(context)
        val dp = { dp: Float -> dpToPx(context, dp) }

        val timeAxis = TimeAxis(errorSamples.map { it.fetchedAt }, layout)

        drawGridAndAxes(canvas, layout, axisScale, timeAxis, paints, dp, isError = true)

        val zeroY = axisScale.valueToY(0f, layout.graphTop, layout.graphHeight)
        canvas.drawLine(layout.graphLeft, zeroY, layout.graphRight, zeroY, paints.zeroLine)
        canvas.drawText("Location actual", layout.graphRight + dp(EvolutionGraphStyle.LABEL_GAP_DP), zeroY + dp(EvolutionGraphStyle.LABEL_VERTICAL_CENTER_DP), paints.zeroLabel)

        if (actualValue != null && appActualValue != null) {
            val apiBias = actualValue - appActualValue
            if (abs(apiBias) > 0.01f) {
                val apiY = axisScale.valueToY(apiBias, layout.graphTop, layout.graphHeight)
                canvas.drawLine(layout.graphLeft, apiY, layout.graphRight, apiY, paints.apiActualLine)
                canvas.drawText("API actual", layout.graphRight + dp(EvolutionGraphStyle.LABEL_GAP_DP), apiY + dp(EvolutionGraphStyle.LABEL_VERTICAL_CENTER_DP), paints.apiActualLabel)
            }
        }

        drawErrorSeriesCurve(canvas, errorSamples.filter { it.source == WeatherSource.NWS }, axisScale, timeAxis, layout, paints.nwsCurve, paints.nwsPoint, dp)
        drawErrorSeriesCurve(canvas, errorSamples.filter { it.source == WeatherSource.OPEN_METEO }, axisScale, timeAxis, layout, paints.meteoCurve, paints.meteoPoint, dp)

        return bitmap
    }

    private fun renderSinglePointBarGraph(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        sample: ForecastSample,
        actualValue: Float?,
        appActualValue: Float?,
        isHigh: Boolean,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val allTemps = mutableListOf(sample.temp)
        actualValue?.let { allTemps.add(it) }
        appActualValue?.let { allTemps.add(it) }

        val axisScale = NiceAxisScale.compute(
            allTemps.minOrNull() ?: sample.temp,
            allTemps.maxOrNull() ?: sample.temp,
        )

        val dp = { dp: Float -> dpToPx(context, dp) }
        val paddingBottom = dp(EvolutionGraphStyle.PADDING_BOTTOM_DP - 4f)
        val graphLeft = dp(EvolutionGraphStyle.PADDING_LEFT_DP)
        val graphRight = widthPx - dp(EvolutionGraphStyle.PADDING_RIGHT_DP)
        val graphTop = dp(EvolutionGraphStyle.PADDING_TOP_DP)
        val graphBottom = heightPx - paddingBottom
        val graphWidth = graphRight - graphLeft
        val graphHeight = graphBottom - graphTop

        val sourceColor = if (sample.source == WeatherSource.NWS) EvolutionGraphStyle.NWS_COLOR else EvolutionGraphStyle.METEO_COLOR

        val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(EvolutionGraphStyle.LABEL_COLOR)
            textSize = dp(12f)
            textAlign = Paint.Align.RIGHT
        }

        for (tick in axisScale.ticks) {
            val y = axisScale.valueToY(tick, graphTop, graphHeight)
            val label = formatAxisLabel(tick)
            canvas.drawText(label, graphLeft - dp(EvolutionGraphStyle.LABEL_GAP_DP), y + dp(EvolutionGraphStyle.LABEL_VERTICAL_CENTER_DP), yLabelPaint)
        }

        val forecastY = axisScale.valueToY(sample.temp, graphTop, graphHeight)
        val forecastBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(sourceColor)
            style = Paint.Style.STROKE
            strokeWidth = dp(5f)
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(graphLeft, forecastY, graphRight, forecastY, forecastBarPaint)

        if (actualValue != null) {
            val apiActualY = axisScale.valueToY(actualValue, graphTop, graphHeight)
            val apiActualLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(EvolutionGraphStyle.API_ACTUAL_COLOR)
                style = Paint.Style.STROKE
                strokeWidth = dp(EvolutionGraphStyle.API_ACTUAL_STROKE_DP)
                pathEffect = DashPathEffect(floatArrayOf(dp(EvolutionGraphStyle.DASH_ON_DP), dp(EvolutionGraphStyle.DASH_OFF_DP)), 0f)
            }
            val apiActualLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(EvolutionGraphStyle.API_ACTUAL_COLOR)
                textSize = dp(12f)
                textAlign = Paint.Align.LEFT
            }
            canvas.drawLine(graphLeft, apiActualY, graphRight, apiActualY, apiActualLinePaint)
            canvas.drawText("API actual: ${formatTempLabel(actualValue)}", graphRight + dp(EvolutionGraphStyle.LABEL_GAP_DP), apiActualY + dp(EvolutionGraphStyle.LABEL_VERTICAL_CENTER_DP), apiActualLabelPaint)
        }

        if (appActualValue != null) {
            val appActualY = axisScale.valueToY(appActualValue, graphTop, graphHeight)
            val appActualLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(EvolutionGraphStyle.APP_ACTUAL_COLOR)
                style = Paint.Style.STROKE
                strokeWidth = dp(EvolutionGraphStyle.APP_ACTUAL_STROKE_DP)
            }
            val appActualLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(EvolutionGraphStyle.APP_ACTUAL_COLOR)
                textSize = dp(13f)
                textAlign = Paint.Align.LEFT
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawLine(graphLeft, appActualY, graphRight, appActualY, appActualLinePaint)
            canvas.drawText("Location actual: ${formatTempLabel(appActualValue)}", graphRight + dp(EvolutionGraphStyle.LABEL_GAP_DP), appActualY + dp(EvolutionGraphStyle.LABEL_VERTICAL_CENTER_DP), appActualLabelPaint)
        }

        val markerX = graphLeft + graphWidth / 2f
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(sourceColor)
            style = Paint.Style.FILL
        }
        val markerOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }
        canvas.drawCircle(markerX, forecastY, dp(6f), markerPaint)
        canvas.drawCircle(markerX, forecastY, dp(6f), markerOutlinePaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(EvolutionGraphStyle.LABEL_COLOR)
            textSize = dp(12f)
            textAlign = Paint.Align.CENTER
        }
        val title = if (isHigh) "Single High Forecast" else "Single Low Forecast"
        canvas.drawText(title, widthPx / 2f, dp(16f), titlePaint)

        val forecastLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(sourceColor)
            textSize = dp(14f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val sourceLabel = sample.source.displayName
        val baseline = appActualValue ?: actualValue
        val error = baseline?.let { it - sample.temp }
        val diffText = if (error != null) {
            val sign = if (error >= 0) "+" else ""
            "  Diff ${sign}${formatTempLabel(error)}"
        } else ""
        val forecastLabel = "$sourceLabel ${sample.temp}°  (${sample.daysAhead}d)$diffText"
        canvas.drawText(forecastLabel, markerX, forecastY - dp(10f), forecastLabelPaint)

        return bitmap
    }

    private class TimeAxis(
        timestamps: List<Long>,
        layout: GraphLayout,
    ) {
        val minTime: Long = timestamps.minOrNull() ?: 0L
        val maxTime: Long = timestamps.maxOrNull() ?: 0L
        val isSingleTime: Boolean = minTime == maxTime
        val ticks: List<Long> = buildTimeTicks(minTime, maxTime)

        fun xForTime(timeMs: Long, layout: GraphLayout): Float =
            if (isSingleTime) layout.graphLeft + layout.graphWidth / 2f
            else layout.graphLeft + layout.graphWidth * (timeMs - minTime).toFloat() / (maxTime - minTime).toFloat()

        fun formatLabel(tickMs: Long): String = formatTimeLabel(tickMs, minTime, maxTime)
    }

    private data class GraphLayout(
        val graphLeft: Float,
        val graphRight: Float,
        val graphTop: Float,
        val graphBottom: Float,
    ) {
        val graphWidth: Float get() = graphRight - graphLeft
        val graphHeight: Float get() = graphBottom - graphTop
    }

    private fun computeLayout(context: Context, widthPx: Int, heightPx: Int): GraphLayout {
        val dp = { d: Float -> dpToPx(context, d) }
        return GraphLayout(
            graphLeft = dp(EvolutionGraphStyle.PADDING_LEFT_DP),
            graphRight = widthPx - dp(EvolutionGraphStyle.PADDING_RIGHT_DP),
            graphTop = dp(EvolutionGraphStyle.PADDING_TOP_DP),
            graphBottom = heightPx - dp(EvolutionGraphStyle.PADDING_BOTTOM_DP),
        )
    }

    private fun drawGridAndAxes(
        canvas: Canvas,
        layout: GraphLayout,
        axisScale: AxisScale,
        timeAxis: TimeAxis,
        paints: EvolutionGraphStyle.PaintSet,
        dp: (Float) -> Float,
        isError: Boolean,
    ) {
        for (tick in axisScale.ticks) {
            val y = axisScale.valueToY(tick, layout.graphTop, layout.graphHeight)
            canvas.drawLine(layout.graphLeft, y, layout.graphRight, y, paints.gridLine)
            val label = if (isError) formatErrorLabel(tick) else formatAxisLabel(tick)
            canvas.drawText(label, layout.graphLeft - dp(EvolutionGraphStyle.LABEL_GAP_DP), y + dp(EvolutionGraphStyle.LABEL_VERTICAL_CENTER_DP), paints.yLabel)
        }

        for (tick in timeAxis.ticks) {
            val x = timeAxis.xForTime(tick, layout)
            canvas.drawText(timeAxis.formatLabel(tick), x, layout.graphBottom + dp(EvolutionGraphStyle.PADDING_BOTTOM_DP) - dp(8f), paints.xLabel)
            canvas.drawLine(x, layout.graphTop, x, layout.graphBottom, paints.gridLine)
        }
    }

    private fun drawSeriesCurve(
        canvas: Canvas,
        series: List<EvolutionPoint>,
        tempFor: (EvolutionPoint) -> Float?,
        axisScale: AxisScale,
        timeAxis: TimeAxis,
        layout: GraphLayout,
        curvePaint: Paint,
        pointPaint: Paint,
        dp: (Float) -> Float,
    ) {
        if (series.isEmpty()) return
        val sorted = series.sortedBy { it.fetchedAt }
        val path = Path()
        var lastX = 0f
        var lastY = 0f
        var started = false

        for (point in sorted) {
            val temp = tempFor(point) ?: continue
            val x = timeAxis.xForTime(point.fetchedAt, layout)
            val y = axisScale.valueToY(temp, layout.graphTop, layout.graphHeight)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                val controlX = (lastX + x) / 2f
                path.quadTo(controlX, lastY, x, y)
            }
            lastX = x
            lastY = y
        }
        canvas.drawPath(path, curvePaint)

        for (point in sorted) {
            val temp = tempFor(point) ?: continue
            val x = timeAxis.xForTime(point.fetchedAt, layout)
            val y = axisScale.valueToY(temp, layout.graphTop, layout.graphHeight)
            canvas.drawCircle(x, y, dp(EvolutionGraphStyle.DATA_POINT_RADIUS_DP), pointPaint)
        }
    }

    private fun drawErrorSeriesCurve(
        canvas: Canvas,
        series: List<ErrorSample>,
        axisScale: AxisScale,
        timeAxis: TimeAxis,
        layout: GraphLayout,
        curvePaint: Paint,
        pointPaint: Paint,
        dp: (Float) -> Float,
    ) {
        if (series.isEmpty()) return
        val sorted = series.sortedBy { it.fetchedAt }
        val path = Path()
        var lastX = 0f
        var lastY = 0f
        var started = false

        for (sample in sorted) {
            val x = timeAxis.xForTime(sample.fetchedAt, layout)
            val y = axisScale.valueToY(sample.error, layout.graphTop, layout.graphHeight)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                val controlX = (lastX + x) / 2f
                path.quadTo(controlX, lastY, x, y)
            }
            lastX = x
            lastY = y
        }
        canvas.drawPath(path, curvePaint)

        for (sample in sorted) {
            val x = timeAxis.xForTime(sample.fetchedAt, layout)
            val y = axisScale.valueToY(sample.error, layout.graphTop, layout.graphHeight)
            canvas.drawCircle(x, y, dp(EvolutionGraphStyle.DATA_POINT_RADIUS_DP), pointPaint)
        }
    }

    private fun drawActualLine(
        canvas: Canvas,
        layout: GraphLayout,
        axisScale: AxisScale,
        value: Float?,
        linePaint: Paint,
        labelText: String,
        labelPaint: Paint,
        dp: (Float) -> Float,
    ) {
        if (value == null) return
        val y = axisScale.valueToY(value, layout.graphTop, layout.graphHeight)
        canvas.drawLine(layout.graphLeft, y, layout.graphRight, y, linePaint)
        canvas.drawText(labelText, layout.graphRight + dp(EvolutionGraphStyle.LABEL_GAP_DP), y + dp(EvolutionGraphStyle.LABEL_VERTICAL_CENTER_DP), labelPaint)
    }

    private fun bucketize(
        points: List<EvolutionPoint>,
        tempFor: (EvolutionPoint) -> Float?,
    ): List<EvolutionPoint> {
        val bucketMillis = SNAPSHOT_BUCKET_HOURS * MILLIS_PER_HOUR
        return points
            .filter { tempFor(it) != null }
            .groupBy { it.fetchedAt / bucketMillis }
            .mapNotNull { (_, bucketPoints) -> bucketPoints.maxByOrNull { it.fetchedAt } }
    }

    private fun collectTemps(
        points: List<EvolutionPoint>,
        tempFor: (EvolutionPoint) -> Float?,
        actualValue: Float?,
        appActualValue: Float?,
    ): List<Float> {
        val temps = mutableListOf<Float>()
        points.forEach { tempFor(it)?.let { t -> temps.add(t) } }
        actualValue?.let { temps.add(it) }
        appActualValue?.let { temps.add(it) }
        return temps
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

    private fun formatAxisLabel(value: Float): String {
        val rounded = floatRoundToInt(value)
        return if (abs(value - rounded) < 0.01f) "${rounded}°"
        else String.format("%.1f°", value)
    }

    private fun formatErrorLabel(value: Float): String {
        val rounded = floatRoundToInt(value)
        val roundedStr = if (abs(value - rounded) < 0.01f) "${abs(rounded)}" 
        else String.format("%.1f", abs(value))
        return when {
            value > 0.05f -> "+${roundedStr}°"
            value < -0.05f -> "-${roundedStr}°"
            else -> "0°"
        }
    }

    private fun formatTempLabel(value: Float): String =
        com.weatherwidget.util.TempUtils.formatTemp(value) ?: ""

    private fun dpToPx(context: Context, dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)

    private fun floatRoundToInt(v: Float): Int = v.roundToInt()

    private data class ForecastSample(val temp: Float, val daysAhead: Int, val source: WeatherSource)

    private data class ErrorSample(val error: Float, val daysAhead: Int, val fetchedAt: Long, val source: WeatherSource)
}
