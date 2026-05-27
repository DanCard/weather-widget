package com.weatherwidget.widget

import android.content.Context
import android.util.Log
import android.util.TypedValue

object HourlyGraphDefaults {
    const val WEATHER_ICON_SIZE_DP = 15f
    const val WATERMARK_ICON_SIZE_DP = 24f
    const val MIN_ICON_GRAPH_WIDTH_PX = 420

    // Inline footer row: <hour><a|p><weather icon>. The icon is sized relative to the hour-label
    // text (not a fixed dp) so it scales with the text at every widget size and can't silently
    // shrink the way a labelScale-multiplied dp constant did. 1.4x makes the icon the focal point.
    const val FOOTER_ICON_TO_TEXT_RATIO = 1.0f
    // Gap between the hour digits / meridiem and the icon in the inline footer group.
    // Narrow widgets use a negative gap to pull the group tight (eating the transparent padding
    // weather drawables carry) and save horizontal space; wide widgets have room to breathe.
    const val FOOTER_ICON_GAP_NARROW_DP = -1f
    const val FOOTER_ICON_GAP_WIDE_DP = 0f
    const val FOOTER_BOTTOM_INSET_DP = 1f
    // A widget this many columns or fewer is treated as "narrow": it gets the wider 6-hour marker
    // cadence (vs 4-hour) and the tight negative icon gap. 6 covers full-width phone widgets
    // (e.g. a Pixel 7 Pro full-width widget computes to ~6 cols); tablets at 7+ cols are "wide".
    // Tunable.
    const val NARROW_WIDGET_MAX_COLUMNS = 6
    // Hour-marker cadence (hours between labels) for the WIDE zoom view on a narrow widget.
    // Wide widgets keep ZoomLevel.WIDE.labelInterval (4).
    const val NARROW_WIDE_LABEL_INTERVAL = 6

    const val ICON_TINT_NIGHT = "#BBBBBB"
    const val ICON_TINT_TWILIGHT = "#FFA726"
    const val ICON_TINT_SUNNY = "#FFD60A"
    const val ICON_TINT_DEFAULT = "#BBBBBB"

    const val COLOR_HOUR_LABEL = "#99FFFFFF"
    const val COLOR_PERCENT_LABEL = "#FFFFFF"
    const val COLOR_NOW_LABEL = "#BBFF9F0A"
    const val COLOR_DAY_LABEL = "#88FFFFFF"
    const val COLOR_TODAY_LABEL = "#BBFF9F0A"
    const val COLOR_CURRENT_TIME = "#FF9F0A"
    const val COLOR_SHADOW_LIGHT = "#44000000"
    const val COLOR_SHADOW_DARK = "#88000000"

    const val HOUR_LABEL_TEXT_SIZE_DP = 23.0f
    const val NOW_LABEL_TEXT_SIZE_DP = 15.5f
    const val DAY_LABEL_TEXT_SIZE_DP = 23.0f
    const val PERCENT_LABEL_TEXT_SIZE_DP = 23.0f

    const val CURVE_STROKE_TALL_DP = 2.5f
    const val CURVE_STROKE_SHORT_DP = 3f
    const val CURRENT_TIME_STROKE_DP = 1.0f
    const val CURRENT_TIME_DASH_ON_DP = 4f
    const val CURRENT_TIME_DASH_OFF_DP = 3f
    const val TALL_GRAPH_HEIGHT_DP = 160

    const val MAX_LABEL_CANDIDATES = 5
    val DENSE_LABEL_DIFF_THRESHOLDS = listOf(5, 10, 15)
    const val LABEL_FILTER_NEARBY_WINDOW = 5

    const val BOTTOM_LABEL_HEIGHT_DP = 20f
    const val LABEL_SAFE_BOTTOM_INSET_DP = 2f
    const val DEFAULT_HOUR_LABEL_SPACING_DP = 28f
    const val WATERMARK_ALPHA = 96
    const val WATERMARK_MIN_HOURS = 3
    const val NOW_LINE_HEIGHT_FRACTION = 0.6f

    const val SHADOW_RADIUS_LIGHT_DP = 1f
    const val SHADOW_DY_DP = 0.5f
    const val SHADOW_RADIUS_STRONG_DP = 2f

    const val SOFT_DIP_WINDOW_SIZE = 5
    const val TRENDING_THRESHOLD_PX = 0.5f

    val OVERLAY_X_FRACTIONS = listOf(0.15f, 0.3f, 0.45f, 0.6f, 0.75f)
    val OVERLAY_Y_FRACTIONS = listOf(0.12f, 0.25f, 0.38f, 0.5f, 0.65f, 0.8f)
}

object GraphLayout {
    private const val TOP_TEMP_BUFFER_RATIO = 0.1f
    private const val BOTTOM_TEMP_BUFFER_RATIO = 0.03f
    private const val MIN_TOP_TEMP_BUFFER_DEGREES = 3f
    private const val MIN_BOTTOM_TEMP_BUFFER_DEGREES = 2.5f
    private const val GRAPH_TOP_PADDING_DP = 16f
    private const val GRAPH_TO_FOOTER_GAP_DP = -1f
    private const val DEFAULT_FALLBACK_MIN_TEMP = 0f
    private const val DEFAULT_FALLBACK_MAX_TEMP = 100f
    private const val MIN_TEMP_RANGE = 1f
    private const val MIN_GRAPH_HEIGHT_DP = 1f

    data class Layout(
        val topPadding: Float,
        val iconSize: Int,
        val footerTop: Float,
        val graphTop: Float,
        val graphBottom: Float,
        val graphHeight: Float,
        val iconTopPad: Float,
    )

    fun computeScaling(hours: List<HourData>): Triple<Float, Float, Float> {
        val allTemps = hours.map { it.temperature }.filter { !it.isNaN() } + hours.mapNotNull { it.actualTemperature }
        val rawMin = allTemps.minOrNull() ?: DEFAULT_FALLBACK_MIN_TEMP
        val rawMax = allTemps.maxOrNull() ?: DEFAULT_FALLBACK_MAX_TEMP
        val rawRange = (rawMax - rawMin).coerceAtLeast(MIN_TEMP_RANGE)

        val topBuffer = (rawRange * TOP_TEMP_BUFFER_RATIO).coerceAtLeast(MIN_TOP_TEMP_BUFFER_DEGREES)
        val bottomBuffer = (rawRange * BOTTOM_TEMP_BUFFER_RATIO).coerceAtLeast(MIN_BOTTOM_TEMP_BUFFER_DEGREES)
        val minTemp = rawMin - bottomBuffer
        val maxTemp = rawMax + topBuffer
        val tempRange = (maxTemp - minTemp).coerceAtLeast(MIN_TEMP_RANGE)
        Log.d("TempGraphRenderer", "Scaling: rawMin=$rawMin, rawMax=$rawMax, minTemp=$minTemp, maxTemp=$maxTemp, tempRange=$tempRange")
        return Triple(minTemp, maxTemp, tempRange)
    }

    // The footer is now a single inline row (<hour><a|p><icon>) sized by [footerIconSize], the
    // tallest element. Reserving just that one row (vs. the old stacked icon + label rows) hands
    // the reclaimed vertical space back to the graph curve.
    fun computeLayout(context: Context, heightPx: Int, labelScale: Float, footerIconSize: Float): Layout {
        val topPadding = dpToPx(context, GRAPH_TOP_PADDING_DP * labelScale)
        val bottomInset = dpToPx(context, HourlyGraphDefaults.FOOTER_BOTTOM_INSET_DP)

        val graphTop = topPadding
        val footerTop = heightPx - footerIconSize - bottomInset
        val graphBottom = (footerTop - dpToPx(context, GRAPH_TO_FOOTER_GAP_DP * labelScale)).coerceAtLeast(graphTop + MIN_GRAPH_HEIGHT_DP * labelScale)
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(MIN_GRAPH_HEIGHT_DP * labelScale)
        Log.d("TempGraphRenderer", "Layout: heightPx=$heightPx, footerTop=$footerTop, graphTop=$graphTop, graphBottom=$graphBottom, graphHeight=$graphHeight")
        return Layout(topPadding, footerIconSize.toInt(), footerTop, graphTop, graphBottom, graphHeight, iconTopPad = 0f)
    }

    private fun dpToPx(context: Context, dp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}
