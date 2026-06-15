package com.weatherwidget.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import com.weatherwidget.shared.graph.HourlyGraphDefaults

object CloudCoverGraphStyle {

    private const val COLOR_CLOUD_CURVE = "#AAAAAA"

    internal data class PaintSet(
        val density: Float,
        val labelScale: Float,
        val tallGraph: Boolean,
        val curvePaint: Paint,
        val gradientPaint: Paint,
        val currentTimePaint: Paint,
        val hourLabelTextPaint: Paint,
        val percentLabelPaint: Paint,
        val nowLabelTextPaint: Paint,
        val dayLabelTextPaint: Paint,
        val todayDayLabelPaint: Paint,
    )

    private var cachedPaints: PaintSet? = null

    internal fun ensurePaints(context: Context, tallGraph: Boolean, labelScale: Float): PaintSet {
        val density = context.resources.displayMetrics.density
        val current = cachedPaints
        if (current != null && current.density == density && current.tallGraph == tallGraph && current.labelScale == labelScale) {
            return current
        }

        val curveStrokeDp = if (tallGraph) HourlyGraphDefaults.CURVE_STROKE_TALL_DP else HourlyGraphDefaults.CURVE_STROKE_SHORT_DP
        val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(COLOR_CLOUD_CURVE)
            strokeWidth = dpToPx(context, curveStrokeDp * labelScale)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Shared footer/axis/now furniture (identical to PrecipitationGraphStyle).
        val gradientPaint = HourlyGraphPaints.gradientFill()
        val currentTimePaint = HourlyGraphPaints.currentTime(context, labelScale)
        val hourLabelTextPaint = HourlyGraphPaints.hourLabel(context, labelScale)
        val percentLabelPaint = HourlyGraphPaints.percentLabel(context, labelScale)
        val nowLabelTextPaint = HourlyGraphPaints.nowLabel(context, labelScale)
        val dayLabelTextPaint = HourlyGraphPaints.dayLabel(context, labelScale)
        val todayDayLabelPaint = HourlyGraphPaints.todayDayLabel(dayLabelTextPaint)

        val paints = PaintSet(
            density = density,
            labelScale = labelScale,
            tallGraph = tallGraph,
            curvePaint = curvePaint,
            gradientPaint = gradientPaint,
            currentTimePaint = currentTimePaint,
            hourLabelTextPaint = hourLabelTextPaint,
            percentLabelPaint = percentLabelPaint,
            nowLabelTextPaint = nowLabelTextPaint,
            dayLabelTextPaint = dayLabelTextPaint,
            todayDayLabelPaint = todayDayLabelPaint,
        )
        cachedPaints = paints
        return paints
    }

    internal fun dpToPx(context: Context, dp: Float): Float = HourlyGraphPaints.dpToPx(context, dp)
}
