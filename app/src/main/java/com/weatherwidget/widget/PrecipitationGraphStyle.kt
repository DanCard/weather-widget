package com.weatherwidget.widget

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue

object PrecipitationGraphStyle {

    private const val COLOR_CURVE = "#5AC8FA"
    private const val COLOR_RAIN_AMOUNT = "#FFFFFF"
    private const val RAIN_AMOUNT_TEXT_SIZE_DP = 18.0f

    internal data class PaintSet(
        val density: Float,
        val labelScale: Float,
        val heightDp: Float,
        val curvePaint: Paint,
        val gradientPaint: Paint,
        val currentTimePaint: Paint,
        val hourLabelTextPaint: Paint,
        val percentLabelPaint: Paint,
        val nowLabelTextPaint: Paint,
        val dayLabelTextPaint: Paint,
        val todayDayLabelPaint: Paint,
        val rainAmountPaint: Paint,
    )

    @Volatile
    private var cachedPaints: PaintSet? = null
    private val paintsLock = Any()

    internal fun ensurePaints(context: Context, heightDp: Float, labelScale: Float): PaintSet {
        val density = context.resources.displayMetrics.density
        val current = cachedPaints
        if (current != null && current.density == density && current.heightDp == heightDp && current.labelScale == labelScale) {
            return current
        }

        return synchronized(paintsLock) {
            val recheck = cachedPaints
            if (recheck != null && recheck.density == density && recheck.heightDp == heightDp && recheck.labelScale == labelScale) {
                return@synchronized recheck
            }

            val curveStrokeDp = if (heightDp >= HourlyGraphDefaults.TALL_GRAPH_HEIGHT_DP) HourlyGraphDefaults.CURVE_STROKE_TALL_DP else HourlyGraphDefaults.CURVE_STROKE_SHORT_DP
            val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_CURVE)
                strokeWidth = dpToPx(context, curveStrokeDp * labelScale)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }

            val currentTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(HourlyGraphDefaults.COLOR_CURRENT_TIME)
                strokeWidth = dpToPx(context, HourlyGraphDefaults.CURRENT_TIME_STROKE_DP * labelScale)
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, HourlyGraphDefaults.CURRENT_TIME_DASH_ON_DP * labelScale), dpToPx(context, HourlyGraphDefaults.CURRENT_TIME_DASH_OFF_DP * labelScale)), 0f)
            }

            val hourLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(HourlyGraphDefaults.COLOR_HOUR_LABEL)
                textSize = dpToPx(context, HourlyGraphDefaults.HOUR_LABEL_TEXT_SIZE_DP * labelScale)
                textAlign = Paint.Align.CENTER
                setShadowLayer(dpToPx(context, HourlyGraphDefaults.SHADOW_RADIUS_LIGHT_DP * labelScale), 0f, dpToPx(context, HourlyGraphDefaults.SHADOW_DY_DP * labelScale), Color.parseColor(HourlyGraphDefaults.COLOR_SHADOW_LIGHT))
            }

            val percentLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(HourlyGraphDefaults.COLOR_PERCENT_LABEL)
                textSize = dpToPx(context, HourlyGraphDefaults.PERCENT_LABEL_TEXT_SIZE_DP * labelScale)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setShadowLayer(dpToPx(context, HourlyGraphDefaults.SHADOW_RADIUS_STRONG_DP * labelScale), 0f, dpToPx(context, HourlyGraphDefaults.SHADOW_DY_DP * labelScale), Color.parseColor(HourlyGraphDefaults.COLOR_SHADOW_DARK))
            }

            val nowLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(HourlyGraphDefaults.COLOR_NOW_LABEL)
                textSize = dpToPx(context, HourlyGraphDefaults.NOW_LABEL_TEXT_SIZE_DP * labelScale)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setShadowLayer(dpToPx(context, HourlyGraphDefaults.SHADOW_RADIUS_LIGHT_DP * labelScale), 0f, 0f, Color.parseColor(HourlyGraphDefaults.COLOR_SHADOW_LIGHT))
            }

            val dayLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(HourlyGraphDefaults.COLOR_DAY_LABEL)
                textSize = dpToPx(context, HourlyGraphDefaults.DAY_LABEL_TEXT_SIZE_DP * labelScale)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }

            val todayDayLabelPaint = Paint(dayLabelTextPaint).apply {
                color = Color.parseColor(HourlyGraphDefaults.COLOR_TODAY_LABEL)
            }

            val rainAmountPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_RAIN_AMOUNT)
                textSize = dpToPx(context, RAIN_AMOUNT_TEXT_SIZE_DP * labelScale)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(dpToPx(context, HourlyGraphDefaults.SHADOW_RADIUS_STRONG_DP * labelScale), 0f, dpToPx(context, HourlyGraphDefaults.SHADOW_DY_DP * labelScale), Color.parseColor(HourlyGraphDefaults.COLOR_SHADOW_DARK))
            }

            val paints = PaintSet(
                density = density,
                labelScale = labelScale,
                heightDp = heightDp,
                curvePaint = curvePaint,
                gradientPaint = gradientPaint,
                currentTimePaint = currentTimePaint,
                hourLabelTextPaint = hourLabelTextPaint,
                percentLabelPaint = percentLabelPaint,
                nowLabelTextPaint = nowLabelTextPaint,
                dayLabelTextPaint = dayLabelTextPaint,
                todayDayLabelPaint = todayDayLabelPaint,
                rainAmountPaint = rainAmountPaint,
            )
            cachedPaints = paints
            paints
        }
    }

    internal fun dpToPx(context: Context, dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}
