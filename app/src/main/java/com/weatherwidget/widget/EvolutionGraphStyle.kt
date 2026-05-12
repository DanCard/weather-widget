package com.weatherwidget.widget

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue

object EvolutionGraphStyle {
    const val NWS_COLOR = "#5AC8FA"
    const val METEO_COLOR = "#34C759"
    const val API_ACTUAL_COLOR = "#FF9F0A"
    const val APP_ACTUAL_COLOR = "#FF3B30"
    const val LABEL_COLOR = "#AAAAAA"
    const val GRID_COLOR = "#333333"

    const val CURVE_STROKE_DP = 2.5f
    const val DATA_POINT_RADIUS_DP = 3f
    const val GRID_STROKE_DP = 1f
    const val API_ACTUAL_STROKE_DP = 1.5f
    const val APP_ACTUAL_STROKE_DP = 2f
    const val ZERO_LINE_STROKE_DP = 2f
    const val DASH_ON_DP = 6f
    const val DASH_OFF_DP = 4f
    const val Y_LABEL_SIZE_DP = 13f
    const val X_LABEL_SIZE_DP = 13f
    const val API_ACTUAL_LABEL_SIZE_DP = 13f
    const val APP_ACTUAL_LABEL_SIZE_DP = 14.5f
    const val ZERO_LABEL_SIZE_DP = 13f

    const val PADDING_LEFT_DP = 40f
    const val PADDING_RIGHT_DP = 16f
    const val PADDING_TOP_DP = 24f
    const val PADDING_BOTTOM_DP = 32f
    const val LABEL_GAP_DP = 6f
    const val LABEL_VERTICAL_CENTER_DP = 4f

    data class PaintSet(
        val density: Float,
        val nwsCurve: Paint,
        val nwsPoint: Paint,
        val meteoCurve: Paint,
        val meteoPoint: Paint,
        val gridLine: Paint,
        val yLabel: Paint,
        val xLabel: Paint,
        val apiActualLine: Paint,
        val apiActualLabel: Paint,
        val appActualLine: Paint,
        val appActualLabel: Paint,
        val zeroLine: Paint,
        val zeroLabel: Paint,
    )

    @Volatile
    private var cached: PaintSet? = null

    fun getPaints(context: Context): PaintSet {
        val density = context.resources.displayMetrics.density
        cached?.let { if (it.density == density) return it }

        val dp = { dp: Float -> dpToPx(context, dp) }

        val nwsCurve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(NWS_COLOR)
            strokeWidth = dp(CURVE_STROKE_DP)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val nwsPoint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(NWS_COLOR)
            style = Paint.Style.FILL
        }
        val meteoCurve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(METEO_COLOR)
            strokeWidth = dp(CURVE_STROKE_DP)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val meteoPoint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(METEO_COLOR)
            style = Paint.Style.FILL
        }
        val gridLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(GRID_COLOR)
            strokeWidth = dp(GRID_STROKE_DP)
            style = Paint.Style.STROKE
        }
        val yLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(LABEL_COLOR)
            textSize = dp(Y_LABEL_SIZE_DP)
            textAlign = Paint.Align.RIGHT
        }
        val xLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(LABEL_COLOR)
            textSize = dp(X_LABEL_SIZE_DP)
            textAlign = Paint.Align.CENTER
        }
        val apiActualLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(API_ACTUAL_COLOR)
            strokeWidth = dp(API_ACTUAL_STROKE_DP)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dp(DASH_ON_DP), dp(DASH_OFF_DP)), 0f)
        }
        val apiActualLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(API_ACTUAL_COLOR)
            textSize = dp(API_ACTUAL_LABEL_SIZE_DP)
            textAlign = Paint.Align.LEFT
        }
        val appActualLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(APP_ACTUAL_COLOR)
            strokeWidth = dp(APP_ACTUAL_STROKE_DP)
            style = Paint.Style.STROKE
        }
        val appActualLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(APP_ACTUAL_COLOR)
            textSize = dp(APP_ACTUAL_LABEL_SIZE_DP)
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val zeroLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(APP_ACTUAL_COLOR)
            strokeWidth = dp(ZERO_LINE_STROKE_DP)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dp(DASH_ON_DP), dp(DASH_OFF_DP)), 0f)
        }
        val zeroLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(APP_ACTUAL_COLOR)
            textSize = dp(ZERO_LABEL_SIZE_DP)
            textAlign = Paint.Align.LEFT
        }

        val paintSet = PaintSet(
            density = density,
            nwsCurve = nwsCurve, nwsPoint = nwsPoint,
            meteoCurve = meteoCurve, meteoPoint = meteoPoint,
            gridLine = gridLine, yLabel = yLabel, xLabel = xLabel,
            apiActualLine = apiActualLine, apiActualLabel = apiActualLabel,
            appActualLine = appActualLine, appActualLabel = appActualLabel,
            zeroLine = zeroLine, zeroLabel = zeroLabel,
        )
        cached = paintSet
        return paintSet
    }

    private fun dpToPx(context: Context, dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}
