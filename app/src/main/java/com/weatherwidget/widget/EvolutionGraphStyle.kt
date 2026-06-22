package com.weatherwidget.widget

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import com.weatherwidget.shared.graph.ForecastEvolutionStyle

/**
 * Android `Paint` factory for the forecast-evolution graph. Visual constants (colors, dp metrics)
 * live in the shared [ForecastEvolutionStyle] so the desktop Compose renderer matches exactly; this
 * object only turns them into cached Android `Paint`s.
 */
object EvolutionGraphStyle {
    // Re-exposed for existing call sites; single source of truth is ForecastEvolutionStyle.
    val FORECAST_COLOR get() = ForecastEvolutionStyle.FORECAST_COLOR
    val API_ACTUAL_COLOR get() = ForecastEvolutionStyle.API_ACTUAL_COLOR
    val APP_ACTUAL_COLOR get() = ForecastEvolutionStyle.APP_ACTUAL_COLOR
    val LABEL_COLOR get() = ForecastEvolutionStyle.LABEL_COLOR
    val GRID_COLOR get() = ForecastEvolutionStyle.GRID_COLOR

    val CURVE_STROKE_DP get() = ForecastEvolutionStyle.CURVE_STROKE_DP
    val DATA_POINT_RADIUS_DP get() = ForecastEvolutionStyle.DATA_POINT_RADIUS_DP
    val GRID_STROKE_DP get() = ForecastEvolutionStyle.GRID_STROKE_DP
    val API_ACTUAL_STROKE_DP get() = ForecastEvolutionStyle.API_ACTUAL_STROKE_DP
    val APP_ACTUAL_STROKE_DP get() = ForecastEvolutionStyle.APP_ACTUAL_STROKE_DP
    val ZERO_LINE_STROKE_DP get() = ForecastEvolutionStyle.ZERO_LINE_STROKE_DP
    val DASH_ON_DP get() = ForecastEvolutionStyle.DASH_ON_DP
    val DASH_OFF_DP get() = ForecastEvolutionStyle.DASH_OFF_DP
    val Y_LABEL_SIZE_DP get() = ForecastEvolutionStyle.Y_LABEL_SIZE_DP
    val X_LABEL_SIZE_DP get() = ForecastEvolutionStyle.X_LABEL_SIZE_DP
    val API_ACTUAL_LABEL_SIZE_DP get() = ForecastEvolutionStyle.API_ACTUAL_LABEL_SIZE_DP
    val APP_ACTUAL_LABEL_SIZE_DP get() = ForecastEvolutionStyle.APP_ACTUAL_LABEL_SIZE_DP
    val ZERO_LABEL_SIZE_DP get() = ForecastEvolutionStyle.ZERO_LABEL_SIZE_DP

    val PADDING_LEFT_DP get() = ForecastEvolutionStyle.PADDING_LEFT_DP
    val PADDING_RIGHT_DP get() = ForecastEvolutionStyle.PADDING_RIGHT_DP
    val PADDING_TOP_DP get() = ForecastEvolutionStyle.PADDING_TOP_DP
    val PADDING_BOTTOM_DP get() = ForecastEvolutionStyle.PADDING_BOTTOM_DP
    val LABEL_GAP_DP get() = ForecastEvolutionStyle.LABEL_GAP_DP
    val LABEL_VERTICAL_CENTER_DP get() = ForecastEvolutionStyle.LABEL_VERTICAL_CENTER_DP
    val X_LABEL_SLANT_DEG get() = ForecastEvolutionStyle.X_LABEL_SLANT_DEG

    data class PaintSet(
        val density: Float,
        val forecastCurve: Paint,
        val forecastPoint: Paint,
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

        val forecastCurve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(FORECAST_COLOR)
            strokeWidth = dp(CURVE_STROKE_DP)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val forecastPoint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(FORECAST_COLOR)
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
            forecastCurve = forecastCurve, forecastPoint = forecastPoint,
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
