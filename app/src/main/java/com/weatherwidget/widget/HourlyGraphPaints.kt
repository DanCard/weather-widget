package com.weatherwidget.widget

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import com.weatherwidget.shared.graph.HourlyGraphDefaults

/**
 * Builders for the `android.graphics.Paint` objects shared by the hourly cloud-cover and
 * precipitation graph styles ([CloudCoverGraphStyle] / [PrecipitationGraphStyle]). These two graphs
 * draw the same footer/axis/now furniture, so their NOW line, hour/percent/day/today labels, the
 * fill paint, and `dpToPx` were byte-identical copies.
 *
 * This lives in `:app` (not `:shared`) because `Paint` is an Android type. Only the construction is
 * shared — each style object keeps its own caching strategy and its unique paints (cloud's curve;
 * precip's day/night divider and rain-amount paints).
 */
internal object HourlyGraphPaints {

    fun dpToPx(context: Context, dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)

    /** Flat FILL paint used for the gradient under the curve. */
    fun gradientFill(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Dashed vertical NOW marker. */
    fun currentTime(context: Context, labelScale: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HourlyGraphDefaults.COLOR_CURRENT_TIME
        strokeWidth = dpToPx(context, HourlyGraphDefaults.CURRENT_TIME_STROKE_DP * labelScale)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(
            floatArrayOf(
                dpToPx(context, HourlyGraphDefaults.CURRENT_TIME_DASH_ON_DP * labelScale),
                dpToPx(context, HourlyGraphDefaults.CURRENT_TIME_DASH_OFF_DP * labelScale),
            ),
            0f,
        )
    }

    /** Time-of-day axis labels ("12a", "1p") along the footer. */
    fun hourLabel(context: Context, labelScale: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HourlyGraphDefaults.COLOR_HOUR_LABEL
        textSize = dpToPx(context, HourlyGraphDefaults.HOUR_LABEL_TEXT_SIZE_DP * labelScale)
        textAlign = Paint.Align.CENTER
        setShadowLayer(dpToPx(context, HourlyGraphDefaults.SHADOW_RADIUS_LIGHT_DP * labelScale), 0f, dpToPx(context, HourlyGraphDefaults.SHADOW_DY_DP * labelScale), HourlyGraphDefaults.COLOR_SHADOW_LIGHT)
    }

    /** Percent value labels (cloud cover / rain chance) above the curve. */
    fun percentLabel(context: Context, labelScale: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HourlyGraphDefaults.COLOR_PERCENT_LABEL
        textSize = dpToPx(context, HourlyGraphDefaults.PERCENT_LABEL_TEXT_SIZE_DP * labelScale)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        setShadowLayer(dpToPx(context, HourlyGraphDefaults.SHADOW_RADIUS_STRONG_DP * labelScale), 0f, dpToPx(context, HourlyGraphDefaults.SHADOW_DY_DP * labelScale), HourlyGraphDefaults.COLOR_SHADOW_DARK)
    }

    /** "NOW" caption near the current-time line. */
    fun nowLabel(context: Context, labelScale: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HourlyGraphDefaults.COLOR_NOW_LABEL
        textSize = dpToPx(context, HourlyGraphDefaults.NOW_LABEL_TEXT_SIZE_DP * labelScale)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        setShadowLayer(dpToPx(context, HourlyGraphDefaults.SHADOW_RADIUS_LIGHT_DP * labelScale), 0f, 0f, HourlyGraphDefaults.COLOR_SHADOW_LIGHT)
    }

    /** Per-day date label in the footer (non-today). */
    fun dayLabel(context: Context, labelScale: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HourlyGraphDefaults.COLOR_DAY_LABEL
        textSize = dpToPx(context, HourlyGraphDefaults.DAY_LABEL_TEXT_SIZE_DP * labelScale)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    /** Today's variant of the day label — same metrics, highlight color. */
    fun todayDayLabel(dayLabel: Paint): Paint = Paint(dayLabel).apply {
        color = HourlyGraphDefaults.COLOR_TODAY_LABEL
    }
}
