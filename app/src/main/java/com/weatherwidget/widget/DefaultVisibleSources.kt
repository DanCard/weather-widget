package com.weatherwidget.widget

import com.weatherwidget.data.model.WeatherSource

/** The visible-source list a fresh install starts with. */
object DefaultVisibleSources {
    /**
     * Debug builds add Tomorrow.io and, only when the build baked a Google key, Google first as
     * primary (user's call 2026-10-06). Keying Google on the baked key rather than on [debug] alone
     * keeps a debug build made without `GOOGLE_WEATHER_API_KEY` from defaulting to a primary source
     * it cannot fetch.
     */
    fun forBuild(debug: Boolean, hasBakedGoogleKey: Boolean): List<WeatherSource> =
        if (debug) {
            listOfNotNull(
                WeatherSource.GOOGLE_WEATHER.takeIf { hasBakedGoogleKey },
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
