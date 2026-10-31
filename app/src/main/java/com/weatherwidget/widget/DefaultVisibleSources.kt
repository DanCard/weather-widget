package com.weatherwidget.widget

import com.weatherwidget.data.model.WeatherSource

/** The visible-source list a fresh install starts with. */
object DefaultVisibleSources {
    /**
     * Debug builds add Tomorrow.io.
     */
    fun forBuild(debug: Boolean, hasBakedGoogleKey: Boolean = false): List<WeatherSource> =
        if (debug) {
            listOf(
                WeatherSource.NWS,
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
                WeatherSource.TOMORROW_IO,
            )
        } else {
            listOf(
                WeatherSource.NWS,
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
            )
        }
}
