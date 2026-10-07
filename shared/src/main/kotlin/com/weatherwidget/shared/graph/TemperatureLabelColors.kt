package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.WeatherColors
import com.weatherwidget.shared.util.WeatherConditionResolver

/**
 * The colour of an hourly temperature value label and its leader line — one rule for the Android
 * widget and the desktop graph. A forecast label wears its hour's weather colour (the same colour as
 * the dashed forecast segment it labels: amber sun, slate cloud, blue rain, silver night, orange
 * twilight); an actual label wears the observed rose. Desktop used to paint every forecast label
 * white, by role.
 */
object TemperatureLabelColors {
    /** Leader line alpha (0..255) over its label's colour. */
    const val LEADER_ALPHA = 80

    const val LEADER_STROKE_DP = 0.5f

    /** [isFuture] is [PlacedLabel.isFuture]: the label belongs to the forecast series. */
    fun labelArgb(isFuture: Boolean, conditionAtLabel: WeatherConditionResolver.ConditionFlags): Int =
        if (isFuture) {
            WeatherColors.forecastColor(
                conditionAtLabel.isSunny,
                conditionAtLabel.isRainy,
                conditionAtLabel.isMixed,
                conditionAtLabel.isNight,
                conditionAtLabel.isTwilight,
            )
        } else {
            WeatherColors.OBSERVED
        }

    fun leaderArgb(labelArgb: Int): Int = TemperatureColorModel.withAlpha(labelArgb, LEADER_ALPHA)

    fun conditionOf(hour: HourData) = WeatherConditionResolver.ConditionFlags(
        isSunny = hour.isSunny,
        isRainy = hour.isRainy,
        isMixed = hour.isMixed,
        isNight = hour.isNight,
        isTwilight = hour.isTwilight,
    )
}
