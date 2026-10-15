package com.weatherwidget.shared.months

import com.weatherwidget.shared.graph.HourlyGraphDefaults
import com.weatherwidget.shared.graph.TemperatureColorModel

/**
 * Colours (ARGB ints) and dp metrics for the months view. Android converts them to `Paint`s and
 * desktop to Compose colours/strokes, so the two draw the same picture.
 */
object MonthsGraphStyle {
    const val GRID_COLOR: Int = 0x26FFFFFF
    const val MAJOR_TICK_COLOR: Int = 0x40FFFFFF
    const val AXIS_LABEL_COLOR: Int = 0x99FFFFFF.toInt()
    const val NORMAL_BAND_COLOR: Int = 0x1FFFFFFF
    const val TODAY_COLOR: Int = HourlyGraphDefaults.COLOR_CURRENT_TIME
    const val EMPTY_TEXT_COLOR: Int = 0xBBFFFFFF.toInt()

    const val LINE_STROKE_DP = 2f
    const val DASH_ON_DP = 5f
    const val DASH_OFF_DP = 4f
    const val DOT_RADIUS_DP = 2f
    const val TODAY_STROKE_DP = 1.5f
    const val GRID_STROKE_DP = 1f

    const val LABEL_SIZE_DP = 12f
    const val AXIS_LABEL_SIZE_DP = 11f
    const val LABEL_GAP_DP = 4f

    const val PADDING_LEFT_DP = 34f
    const val PADDING_RIGHT_DP = 10f
    const val PADDING_TOP_DP = 22f
    const val PADDING_BOTTOM_DP = 24f

    /** Line colour for a °F value — the app's temperature gradient, as on the daily bars. */
    fun lineColor(tempF: Float): Int = TemperatureColorModel.tempToColorArgb(tempF)
}
