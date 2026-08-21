package com.weatherwidget.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import com.weatherwidget.shared.graph.HourlyGraphDefaults

object CloudCoverGraphStyle {

    // Complementary hues 180 deg apart (343 pink / 163 mint), but deliberately ASYMMETRIC in how
    // far each is pushed. The forecast is the background quantity and stays essentially grey; the
    // actual is the thing worth looking at and carries real pink.
    //
    // Saturation has to fight lightness here: at 94% lightness even 35% saturation is invisible,
    // which is why the first attempt at a "slight pink tint" read as plain white. Showing the hue
    // at all means coming down in lightness, so the actual sits at 85% and 65% saturation.
    // The pair still separates by value (ratio 1.6) for where the curves overlap exactly.
    private const val COLOR_CLOUD_CURVE = "#96A6A1"   // barely-mint grey (hsl 163, 8%)
    private const val COLOR_CLOUD_ACTUAL = "#F2C0CE"  // clearly pink     (hsl 343, 65%)

    // Labels sit lighter than their curves so they stay readable on the dark plot, while keeping
    // enough of the hue to tie each number to the line it describes.
    private const val COLOR_CLOUD_LABEL_FORECAST = "#DDE8E4" // barely-mint white

    internal data class PaintSet(
        val density: Float,
        val labelScale: Float,
        val tallGraph: Boolean,
        val curvePaint: Paint,
        val actualCurvePaint: Paint,
        val gradientPaint: Paint,
        val currentTimePaint: Paint,
        val hourLabelTextPaint: Paint,
        val percentLabelPaint: Paint,
        val actualPercentLabelPaint: Paint,
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

        val actualCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(COLOR_CLOUD_ACTUAL)
            strokeWidth = dpToPx(context, curveStrokeDp * labelScale)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Shared footer/axis/now furniture (identical to PrecipitationGraphStyle).
        val gradientPaint = HourlyGraphPaints.gradientFill()
        val currentTimePaint = HourlyGraphPaints.currentTime(context, labelScale)
        val hourLabelTextPaint = HourlyGraphPaints.hourLabel(context, labelScale)
        val percentLabelPaint = HourlyGraphPaints.percentLabel(context, labelScale).apply {
            color = Color.parseColor(COLOR_CLOUD_LABEL_FORECAST)
        }
        // Same metrics as the forecast label so both passes measure identically; only the colour
        // differs, tying each number to its curve.
        val actualPercentLabelPaint = Paint(percentLabelPaint).apply {
            color = Color.parseColor(COLOR_CLOUD_ACTUAL)
        }
        val nowLabelTextPaint = HourlyGraphPaints.nowLabel(context, labelScale)
        val dayLabelTextPaint = HourlyGraphPaints.dayLabel(context, labelScale)
        val todayDayLabelPaint = HourlyGraphPaints.todayDayLabel(dayLabelTextPaint)

        val paints = PaintSet(
            density = density,
            labelScale = labelScale,
            tallGraph = tallGraph,
            curvePaint = curvePaint,
            actualCurvePaint = actualCurvePaint,
            gradientPaint = gradientPaint,
            currentTimePaint = currentTimePaint,
            hourLabelTextPaint = hourLabelTextPaint,
            percentLabelPaint = percentLabelPaint,
            actualPercentLabelPaint = actualPercentLabelPaint,
            nowLabelTextPaint = nowLabelTextPaint,
            dayLabelTextPaint = dayLabelTextPaint,
            todayDayLabelPaint = todayDayLabelPaint,
        )
        cachedPaints = paints
        return paints
    }

    internal fun dpToPx(context: Context, dp: Float): Float = HourlyGraphPaints.dpToPx(context, dp)
}
