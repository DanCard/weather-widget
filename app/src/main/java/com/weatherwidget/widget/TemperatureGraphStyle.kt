package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import com.weatherwidget.shared.graph.HourlyGraphDefaults
import com.weatherwidget.util.WeatherConditionColors
import java.time.Duration
import kotlin.math.round

object TemperatureGraphStyle {
    private const val TAG = "TempGraphStyle"

    // Temperature value labels (on-curve highs/lows and the now/current temp) are 10% larger than
    // the time/axis labels for readability. Hour-of-day labels keep the original 23dp.
    const val TEMP_LABEL_SIZE_DP = 25.3f // 23dp + 10%
    const val HOUR_LABEL_SIZE_DP = 23f
    const val NOW_LABEL_SIZE_DP = 15.5f
    const val DAY_LABEL_SIZE_DP = 23f
    const val VALUE_LABEL_SIZE_DP = 25.3f // 23dp + 10%
    const val STALENESS_LABEL_SIZE_DP = 12f
    const val DOT_RADIUS_DP = 3.2f
    const val RING_STROKE_DP = 1.5f
    const val OUTER_RING_STROKE_DP = 0.5f
    const val HOUR_LABEL_SPACING_DP = 42f
    const val FETCH_DOT_SIDE_GAP_DP = 4f
    const val FETCH_DOT_ABOVE_GAP_DP = 2f
    const val DAY_LABEL_BOTTOM_PADDING_DP = 14f
    const val FORECAST_DASH_ON_DP = 8f
    const val FORECAST_DASH_OFF_DP = 4f
    const val EXPECTED_FILL_ALPHA_TOP = 68
    const val EXPECTED_FILL_ALPHA_BOTTOM = 0
    const val GHOST_ALPHA = 55
    const val ACTUAL_LEADER_LINE_ALPHA = 80
    const val FORECAST_LEADER_LINE_ALPHA = 80
    const val STROKE_ACTUAL_DP = 1f
    const val STROKE_FORECAST_DP = 1.2f
    const val STROKE_LEADER_LINE_DP = 0.5f
    const val AGE_LABEL_MAX_HOURS_SPAN = 12L
    const val DEFAULT_FONT_DESCENT_RATIO = 0.2f

    // Temperature-to-color thresholds
    private const val COLD_THRESHOLD = 50f
    private const val MILD_TEMP = 70f
    private const val HOT_THRESHOLD = 90f

    private val COLOR_COLD = Color.parseColor("#5AC8FA") // Blue
    private val COLOR_MILD = Color.parseColor("#E8A24E") // Golden amber
    private val COLOR_HOT = Color.parseColor("#FF6B35") // Warm orange
    val COLOR_ACTUAL_LINE = WeatherConditionColors.OBSERVED  // Hot pink #FF3366
    val COLOR_ACTUAL_LABEL = WeatherConditionColors.OBSERVED
    val COLOR_FORECAST_LABEL = Color.parseColor("#C5DCFF")

    fun tempToColor(temp: Float): Int {
        return when {
            temp <= COLD_THRESHOLD -> COLOR_COLD
            temp >= HOT_THRESHOLD -> COLOR_HOT
            temp <= MILD_TEMP -> blendColors(COLOR_COLD, COLOR_MILD, (temp - COLD_THRESHOLD) / (MILD_TEMP - COLD_THRESHOLD))
            else -> blendColors(COLOR_MILD, COLOR_HOT, (temp - MILD_TEMP) / (HOT_THRESHOLD - MILD_TEMP))
        }
    }

    private fun blendColors(c1: Int, c2: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val r = (Color.red(c1) * (1 - f) + Color.red(c2) * f).toInt()
        val g = (Color.green(c1) * (1 - f) + Color.green(c2) * f).toInt()
        val b = (Color.blue(c1) * (1 - f) + Color.blue(c2) * f).toInt()
        return Color.rgb(r, g, b)
    }

    fun formatTemp(value: Float): String {
        val rounded = round(value * 10f) / 10f
        return if (rounded % 1f == 0f) {
            String.format("%.0f", rounded)
        } else {
            String.format("%.1f", rounded)
        }
    }

    fun formatColorHex(color: Int): String {
        return String.format("#%06X", (0xFFFFFF and color))
    }

    fun formatAgeLabel(ageMinutes: Long, hoursSpanHours: Long): String? {
        if (ageMinutes < 0) {
            Log.w(TAG, "formatAgeLabel: negative ageMinutes=$ageMinutes, possible clock skew")
            return null
        }
        if (hoursSpanHours > AGE_LABEL_MAX_HOURS_SPAN) return null
        return if (ageMinutes >= 60) "${ageMinutes / 60}h${if (ageMinutes % 60 > 0) " ${ageMinutes % 60}m" else ""}" else "${ageMinutes}m"
    }

    fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    fun fontAscent(paint: Paint): Float {
        val fm = paint.fontMetrics
        return if (fm != null && fm.ascent != 0f) fm.ascent else -paint.textSize
    }

    fun fontDescent(paint: Paint): Float {
        val fm = paint.fontMetrics
        return if (fm != null && fm.descent != 0f) fm.descent else paint.textSize * DEFAULT_FONT_DESCENT_RATIO
    }

    @Volatile
    private var cachedPaints: PaintSet? = null

    fun ensurePaints(context: Context, labelScale: Float): PaintSet {
        val density = context.resources.displayMetrics.density
        val current = cachedPaints
        if (current != null && current.density == density && current.labelScale == labelScale) {
            return current
        }

        val actualLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = dpToPx(context, STROKE_ACTUAL_DP)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = COLOR_ACTUAL_LINE
        }

        val forecastDashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = dpToPx(context, STROKE_ACTUAL_DP)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = WeatherConditionColors.FORECAST_SUNNY // Default; overridden per-segment
            pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, FORECAST_DASH_ON_DP), dpToPx(context, FORECAST_DASH_OFF_DP)), 0f)
        }

        val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = GHOST_ALPHA
            strokeWidth = dpToPx(context, STROKE_FORECAST_DP)
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(0.1f, dpToPx(context, FORECAST_DASH_OFF_DP)), 0f)
        }

        val expectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val currentTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = HourlyGraphDefaults.COLOR_CURRENT_TIME
            strokeWidth = dpToPx(context, STROKE_LEADER_LINE_DP)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, HourlyGraphDefaults.CURRENT_TIME_DASH_ON_DP), dpToPx(context, HourlyGraphDefaults.CURRENT_TIME_DASH_OFF_DP)), 0f)
        }

        val hourLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = HourlyGraphDefaults.COLOR_HOUR_LABEL
            textSize = dpToPx(context, HOUR_LABEL_SIZE_DP * labelScale)
            textAlign = Paint.Align.CENTER
            setShadowLayer(dpToPx(context, HourlyGraphDefaults.SHADOW_RADIUS_LIGHT_DP), 0f, dpToPx(context, HourlyGraphDefaults.SHADOW_DY_DP), HourlyGraphDefaults.COLOR_SHADOW_LIGHT)
        }

        val actualTempLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACTUAL_LABEL
            textSize = dpToPx(context, TEMP_LABEL_SIZE_DP * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, HourlyGraphDefaults.TEMP_LABEL_SHADOW_RADIUS_DP * labelScale), 0f, dpToPx(context, HourlyGraphDefaults.TEMP_LABEL_SHADOW_DY_DP * labelScale), HourlyGraphDefaults.COLOR_SHADOW_SOLID)
        }

        val forecastTempLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_FORECAST_LABEL
            textSize = dpToPx(context, TEMP_LABEL_SIZE_DP * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, HourlyGraphDefaults.TEMP_LABEL_SHADOW_RADIUS_DP * labelScale), 0f, dpToPx(context, HourlyGraphDefaults.TEMP_LABEL_SHADOW_DY_DP * labelScale), HourlyGraphDefaults.COLOR_SHADOW_SOLID)
        }

        val nowLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = HourlyGraphDefaults.COLOR_NOW_LABEL
            textSize = dpToPx(context, NOW_LABEL_SIZE_DP * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, HourlyGraphDefaults.SHADOW_RADIUS_LIGHT_DP), 0f, 0f, HourlyGraphDefaults.COLOR_SHADOW_LIGHT)
        }

        val dayLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = HourlyGraphDefaults.COLOR_DAY_LABEL
            textSize = dpToPx(context, DAY_LABEL_SIZE_DP * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val todayDayLabelPaint = Paint(dayLabelTextPaint).apply {
            color = HourlyGraphDefaults.COLOR_TODAY_LABEL
        }

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(context, RING_STROKE_DP * labelScale)
        }

        val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = HourlyGraphDefaults.COLOR_SHADOW_LIGHT
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(context, OUTER_RING_STROKE_DP * labelScale)
        }

        val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACTUAL_LINE
            textSize = dpToPx(context, VALUE_LABEL_SIZE_DP * labelScale)
            textAlign = Paint.Align.LEFT
            setShadowLayer(dpToPx(context, HourlyGraphDefaults.TEMP_LABEL_SHADOW_RADIUS_DP * labelScale), 0f, dpToPx(context, HourlyGraphDefaults.TEMP_LABEL_SHADOW_DY_DP * labelScale), HourlyGraphDefaults.COLOR_SHADOW_SOLID)
        }

        val stalenessTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ACTUAL_LINE
            textSize = dpToPx(context, STALENESS_LABEL_SIZE_DP * labelScale)
            textAlign = Paint.Align.CENTER
            setShadowLayer(dpToPx(context, HourlyGraphDefaults.SHADOW_RADIUS_LIGHT_DP), 0f, dpToPx(context, HourlyGraphDefaults.SHADOW_DY_DP), HourlyGraphDefaults.COLOR_SHADOW_DARK)
        }

        val actualLeaderLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(COLOR_ACTUAL_LABEL, ACTUAL_LEADER_LINE_ALPHA)
            strokeWidth = dpToPx(context, STROKE_LEADER_LINE_DP)
            style = Paint.Style.STROKE
        }

        val forecastLeaderLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(COLOR_FORECAST_LABEL, FORECAST_LEADER_LINE_ALPHA)
            strokeWidth = dpToPx(context, STROKE_LEADER_LINE_DP)
            style = Paint.Style.STROKE
        }

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        return PaintSet(
            density = density,
            labelScale = labelScale,
            actualLinePaint = actualLinePaint,
            forecastDashedPaint = forecastDashedPaint,
            ghostPaint = ghostPaint,
            expectedFillPaint = expectedFillPaint,
            currentTimePaint = currentTimePaint,
            hourLabelTextPaint = hourLabelTextPaint,
            actualTempLabelTextPaint = actualTempLabelTextPaint,
            forecastTempLabelTextPaint = forecastTempLabelTextPaint,
            nowLabelTextPaint = nowLabelTextPaint,
            dayLabelTextPaint = dayLabelTextPaint,
            todayDayLabelPaint = todayDayLabelPaint,
            ringPaint = ringPaint,
            outerRingPaint = outerRingPaint,
            valueTextPaint = valueTextPaint,
            stalenessTextPaint = stalenessTextPaint,
            actualLeaderLinePaint = actualLeaderLinePaint,
            forecastLeaderLinePaint = forecastLeaderLinePaint,
            dotPaint = dotPaint,
        ).also { cachedPaints = it }
    }

    fun buildTempGradient(
        graphTop: Float,
        graphBottom: Float,
        minTemp: Float,
        maxTemp: Float,
        tempRange: Float,
        alphaTop: Int = 255,
        alphaBottom: Int = 255,
    ): LinearGradient {
        fun tempToPos(t: Float): Float = ((maxTemp - t) / tempRange).coerceIn(0f, 1f)
        val stops = mutableListOf<Pair<Float, Int>>()
        stops.add(0f to tempToColor(maxTemp))
        stops.add(1f to tempToColor(minTemp))
        for (t in listOf(HOT_THRESHOLD, MILD_TEMP, COLD_THRESHOLD)) {
            if (t > minTemp && t < maxTemp) {
                stops.add(tempToPos(t) to tempToColor(t))
            }
        }
        stops.sortBy { it.first }
        val unique = stops.distinctBy { "%.4f".format(it.first) }
        val positions = unique.map { it.first }.toFloatArray()
        val colorsWithAlpha = unique.map { (pos, color) ->
            val alpha = (alphaTop + (alphaBottom - alphaTop) * pos).toInt().coerceIn(0, 255)
            withAlpha(color, alpha)
        }.toIntArray()

        return LinearGradient(0f, graphTop, 0f, graphBottom, colorsWithAlpha, positions, Shader.TileMode.CLAMP)
    }

    fun tempToY(temp: Float, graphTop: Float, graphHeight: Float, minTemp: Float, tempRange: Float): Float {
        return graphTop + graphHeight * (1 - (temp - minTemp) / tempRange)
    }

    fun dpToPx(context: Context, dp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}
