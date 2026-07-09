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
import com.weatherwidget.shared.graph.AxisScale
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.ErrorSample
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.EvolutionPoint
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.ForecastSample
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.TimeAxis
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.bucketize
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.collectTemps
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.formatAxisLabel
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.formatErrorLabel
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.formatTempLabel
import com.weatherwidget.shared.graph.NiceAxisScale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Android `Canvas` renderer for the forecast-evolution history graph. All geometry/data-prep and
 * the visual constants live in [ForecastEvolutionGeometry] / [com.weatherwidget.shared.graph.ForecastEvolutionStyle]
 * (shared with the desktop Compose renderer); this object only turns those primitives into a Bitmap.
 *
 * [EvolutionPoint] is re-exported as `ForecastEvolutionRenderer.EvolutionPoint` (typealias below) so
 * existing call sites keep compiling.
 */
object ForecastEvolutionRenderer {
    fun renderHighGraph(
        context: Context,
        points: List<EvolutionPoint>,
        actualHigh: Float?,
        appActualHigh: Float?,
        widthPx: Int,
        heightPx: Int,
        useCelsius: Boolean,
    ): Bitmap = renderGraph(context, points, actualHigh, appActualHigh, widthPx, heightPx, isHigh = true, useCelsius = useCelsius)

    fun renderLowGraph(
        context: Context,
        points: List<EvolutionPoint>,
        actualLow: Float?,
        appActualLow: Float?,
        widthPx: Int,
        heightPx: Int,
        useCelsius: Boolean,
    ): Bitmap = renderGraph(context, points, actualLow, appActualLow, widthPx, heightPx, isHigh = false, useCelsius = useCelsius)

    fun renderHighErrorGraph(
        context: Context,
        points: List<EvolutionPoint>,
        actualHigh: Float?,
        appActualHigh: Float?,
        widthPx: Int,
        heightPx: Int,
        useCelsius: Boolean,
    ): Bitmap = renderErrorGraph(context, points, actualHigh, appActualHigh, widthPx, heightPx, isHigh = true, useCelsius = useCelsius)

    fun renderLowErrorGraph(
        context: Context,
        points: List<EvolutionPoint>,
        actualLow: Float?,
        appActualLow: Float?,
        widthPx: Int,
        heightPx: Int,
        useCelsius: Boolean,
    ): Bitmap = renderErrorGraph(context, points, actualLow, appActualLow, widthPx, heightPx, isHigh = false, useCelsius = useCelsius)

    private fun renderGraph(
        context: Context,
        points: List<EvolutionPoint>,
        actualValue: Float?,
        appActualValue: Float?,
        widthPx: Int,
        heightPx: Int,
        isHigh: Boolean,
        useCelsius: Boolean,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        if (points.isEmpty()) return bitmap

        fun tempFor(point: EvolutionPoint): Float? =
            if (isHigh) point.highTemp else point.lowTemp

        // The history view shows a single selected API at a time, so there is one forecast series
        // drawn in one color (no per-source color).
        val series = bucketize(points) { tempFor(it) }
        if (series.isEmpty()) return bitmap

        val allTemps = collectTemps(series, ::tempFor, actualValue, appActualValue)
        if (allTemps.isEmpty()) return bitmap

        val forecastSamples = series.mapNotNull { point ->
            tempFor(point)?.let { temp -> ForecastSample(temp, point.daysAhead, point.source) }
        }

        if (forecastSamples.size == 1) {
            return renderSinglePointBarGraph(context, widthPx, heightPx, forecastSamples.first(), actualValue, appActualValue, isHigh, useCelsius)
        }

        val axisScale = NiceAxisScale.compute(allTemps.minOrNull() ?: 0f, allTemps.maxOrNull() ?: 100f)
        val layout = computeLayout(context, widthPx, heightPx)
        val paints = EvolutionGraphStyle.getPaints(context)
        val dp = { dp: Float -> dpToPx(context, dp) }

        val timeAxis = TimeAxis(series.map { it.fetchedAt }, ForecastEvolutionGeometry.tickDivisionsForWidth(layout.graphWidth))

        drawGridAndAxes(canvas, layout, axisScale, timeAxis, paints, dp, isError = false, useCelsius = useCelsius)

        drawSeriesCurve(canvas, series, ::tempFor, axisScale, timeAxis, layout, paints.forecastCurve, paints.forecastPoint, dp)

        drawActualLine(canvas, layout, axisScale, actualValue, paints.apiActualLine, "API actual: ${actualValue?.let { formatTempLabel(it, useCelsius) }}", paints.apiActualLabel, dp)
        drawActualLine(canvas, layout, axisScale, appActualValue, paints.appActualLine, "Location actual: ${appActualValue?.let { formatTempLabel(it, useCelsius) }}", paints.appActualLabel, dp)

        return bitmap
    }

    private fun renderErrorGraph(
        context: Context,
        points: List<EvolutionPoint>,
        actualValue: Float?,
        appActualValue: Float?,
        widthPx: Int,
        heightPx: Int,
        isHigh: Boolean,
        useCelsius: Boolean,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val baseline = appActualValue ?: actualValue ?: return bitmap
        if (points.isEmpty()) return bitmap

        fun tempFor(point: EvolutionPoint): Float? =
            if (isHigh) point.highTemp else point.lowTemp

        val series = bucketize(points) { tempFor(it) }
        if (series.isEmpty()) return bitmap

        val errorSamples = ForecastEvolutionGeometry.errorSamples(series, ::tempFor, baseline)
        if (errorSamples.isEmpty()) return bitmap

        val maxAbsError = errorSamples.maxOf { abs(it.error) }
        val yBound = maxOf(3f, ceil(maxAbsError) + 1f)
        val axisScale = NiceAxisScale.computeSymmetric(yBound, minRange = 6f)

        val layout = computeLayout(context, widthPx, heightPx)
        val paints = EvolutionGraphStyle.getPaints(context)
        val dp = { dp: Float -> dpToPx(context, dp) }

        val timeAxis = TimeAxis(errorSamples.map { it.fetchedAt }, ForecastEvolutionGeometry.tickDivisionsForWidth(layout.graphWidth))

        drawGridAndAxes(canvas, layout, axisScale, timeAxis, paints, dp, isError = true, useCelsius = useCelsius)

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

        // Single source at a time: one error series in one color, whatever the API.
        drawErrorSeriesCurve(canvas, errorSamples, axisScale, timeAxis, layout, paints.forecastCurve, paints.forecastPoint, dp)

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
        useCelsius: Boolean,
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

        val sourceColor = EvolutionGraphStyle.FORECAST_COLOR

        val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(EvolutionGraphStyle.LABEL_COLOR)
            textSize = dp(12f)
            textAlign = Paint.Align.RIGHT
        }

        for (tick in axisScale.ticks) {
            val y = axisScale.valueToY(tick, graphTop, graphHeight)
            val label = formatAxisLabel(tick, useCelsius)
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
            canvas.drawText("API actual: ${formatTempLabel(actualValue, useCelsius)}", graphRight + dp(EvolutionGraphStyle.LABEL_GAP_DP), apiActualY + dp(EvolutionGraphStyle.LABEL_VERTICAL_CENTER_DP), apiActualLabelPaint)
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
            canvas.drawText("Location actual: ${formatTempLabel(appActualValue, useCelsius)}", graphRight + dp(EvolutionGraphStyle.LABEL_GAP_DP), appActualY + dp(EvolutionGraphStyle.LABEL_VERTICAL_CENTER_DP), appActualLabelPaint)
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
            "  Diff ${formatErrorLabel(error, useCelsius)}"
        } else ""
        val tempLabel = formatTempLabel(sample.temp, useCelsius)
        val forecastLabel = "$sourceLabel $tempLabel  (${sample.daysAhead}d)$diffText"
        canvas.drawText(forecastLabel, markerX, forecastY - dp(10f), forecastLabelPaint)

        return bitmap
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
        useCelsius: Boolean,
    ) {
        for (tick in axisScale.ticks) {
            val y = axisScale.valueToY(tick, layout.graphTop, layout.graphHeight)
            canvas.drawLine(layout.graphLeft, y, layout.graphRight, y, paints.gridLine)
            val label = if (isError) formatErrorLabel(tick, useCelsius) else formatAxisLabel(tick, useCelsius)
            canvas.drawText(label, layout.graphLeft - dp(EvolutionGraphStyle.LABEL_GAP_DP), y + dp(EvolutionGraphStyle.LABEL_VERTICAL_CENTER_DP), paints.yLabel)
        }

        // Slanted x-axis time labels (right-anchored at the tick) so denser labels never overlap.
        val slantPaint = Paint(paints.xLabel).apply { textAlign = Paint.Align.RIGHT }
        val labelBaseline = layout.graphBottom + dp(EvolutionGraphStyle.LABEL_GAP_DP) + dp(8f)
        for (tick in timeAxis.ticks) {
            val x = timeAxis.xForTime(tick, layout.graphLeft, layout.graphWidth)
            canvas.drawLine(x, layout.graphTop, x, layout.graphBottom, paints.gridLine)
            canvas.save()
            canvas.rotate(EvolutionGraphStyle.X_LABEL_SLANT_DEG, x, labelBaseline)
            canvas.drawText(timeAxis.formatLabel(tick), x, labelBaseline, slantPaint)
            canvas.restore()
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
            val x = timeAxis.xForTime(point.fetchedAt, layout.graphLeft, layout.graphWidth)
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
            val x = timeAxis.xForTime(point.fetchedAt, layout.graphLeft, layout.graphWidth)
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
            val x = timeAxis.xForTime(sample.fetchedAt, layout.graphLeft, layout.graphWidth)
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
            val x = timeAxis.xForTime(sample.fetchedAt, layout.graphLeft, layout.graphWidth)
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

    private fun dpToPx(context: Context, dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}

/** Back-compat alias so existing `ForecastEvolutionRenderer.EvolutionPoint` call sites keep working. */
typealias EvolutionPoint = ForecastEvolutionGeometry.EvolutionPoint
