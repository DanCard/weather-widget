package com.weatherwidget.shared.graph

/**
 * Shared colors and dp metrics for the forecast-evolution graph. Both the Android `Paint` factory
 * (`EvolutionGraphStyle`) and the desktop Compose renderer read these so the two stay visually
 * identical. Colors are `#RRGGBB` / `#AARRGGBB` strings; platforms parse them into their own color
 * types.
 */
object ForecastEvolutionStyle {
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
    // Tall enough for slanted x-axis time labels (see X_LABEL_SLANT_DEG).
    const val PADDING_BOTTOM_DP = 48f
    const val LABEL_GAP_DP = 6f
    const val LABEL_VERTICAL_CENTER_DP = 4f

    /** Degrees to rotate x-axis time labels (negative = counter-clockwise, reading up to the right). */
    const val X_LABEL_SLANT_DEG = -38f
}
