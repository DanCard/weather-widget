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
    // which is why an earlier attempt at a "slight pink tint" read as plain white. Showing the hue
    // at all means coming down in lightness, so the actual sits at 85% lightness — which is what
    // lets 32% saturation register as pink rather than disappear.
    //
    // Both are deliberately restrained: the hues identify the curves, they are not the subject.
    // Value still carries the distinction (ratio 1.6) where the two curves lie exactly on top of
    // each other, which is most of a clear or overcast day.
    //
    // Both sit high on the lightness scale, which costs hue perception — the gamut narrows toward
    // white, so the pink reads as a warm cast rather than a colour. That is the intended balance
    // here; if the tint ever needs to be more obvious, raise saturation rather than reaching for a
    // different hue, and expect to give back some lightness to do it.
    private const val COLOR_CLOUD_CURVE = "#B5BAB9"   // light neutral grey (hsl 163, 4%, 72%)
    private const val COLOR_CLOUD_ACTUAL = "#F1E4E8"  // pale pink-white    (hsl 343, 32%, 92%)

    // Labels sit lighter than their curves so they stay readable on the dark plot, while keeping
    // enough of the hue to tie each number to the line it describes.
    private const val COLOR_CLOUD_LABEL_FORECAST = "#E9ECEB" // pale neutral white

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
