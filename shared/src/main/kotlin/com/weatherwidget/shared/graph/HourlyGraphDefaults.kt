package com.weatherwidget.shared.graph

object HourlyGraphDefaults {
    const val WEATHER_ICON_SIZE_DP = 15f
    const val WATERMARK_ICON_SIZE_DP = 24f
    const val MIN_ICON_GRAPH_WIDTH_PX = 420

    const val FOOTER_ICON_TO_TEXT_RATIO = 1.0f
    const val FOOTER_ICON_GAP_NARROW_DP = -1f
    const val FOOTER_ICON_GAP_WIDE_DP = 0f
    const val FOOTER_BOTTOM_INSET_DP = 1f
    const val NARROW_WIDGET_MAX_COLUMNS = 6
    const val NARROW_WIDE_LABEL_INTERVAL = 6

    const val ICON_TINT_NIGHT: Int = 0xFFBBBBBB.toInt()
    const val ICON_TINT_TWILIGHT: Int = 0xFFFFA726.toInt()
    const val ICON_TINT_SUNNY: Int = 0xFFFFD60A.toInt()
    const val ICON_TINT_DEFAULT: Int = 0xFFBBBBBB.toInt()

    const val COLOR_HOUR_LABEL: Int = 0x99FFFFFF.toInt()
    const val COLOR_PERCENT_LABEL: Int = 0xFFFFFFFF.toInt()
    const val COLOR_NOW_LABEL: Int = 0xBBFF9F0A.toInt()
    const val COLOR_DAY_LABEL: Int = 0x88FFFFFF.toInt()
    const val COLOR_TODAY_LABEL: Int = 0xBBFF9F0A.toInt()
    const val COLOR_CURRENT_TIME: Int = 0xFFFF9F0A.toInt()
    const val COLOR_SHADOW_LIGHT: Int = 0x44000000.toInt()
    const val COLOR_SHADOW_DARK: Int = 0x88000000.toInt()

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
