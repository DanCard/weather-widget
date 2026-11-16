package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.WeatherColors

/**
 * The colour of an hourly temperature value label and its leader line — one rule for the Android
 * widget and the desktop graph. A forecast label wears the forecast line's single colour
 * ([WeatherColors.FORECAST_NEUTRAL]); an actual label wears the observed rose. Forecast labels used to
 * take their hour's weather colour (blue in rain) until 2026-11-15.
 */
object TemperatureLabelColors {
    /** Leader line alpha (0..255) over its label's colour. */
    const val LEADER_ALPHA = 80

    const val LEADER_STROKE_DP = 0.5f

    /** [isFuture] is [PlacedLabel.isFuture]: the label belongs to the forecast series. */
    fun labelArgb(isFuture: Boolean): Int =
        if (isFuture) WeatherColors.FORECAST_NEUTRAL else WeatherColors.OBSERVED

    fun leaderArgb(labelArgb: Int): Int = TemperatureColorModel.withAlpha(labelArgb, LEADER_ALPHA)
}
