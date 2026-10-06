package com.weatherwidget.data.repository

import com.weatherwidget.data.model.WeatherSource

/**
 * The `app_logs` tag a failed forecast fetch is filed under, per source. One table for the writer
 * ([ForecastFetchCoordinator]) and the reader (`ApiSourceWarningHelper`), which had drifted: the
 * reader had no arm for Tomorrow.io, so a failing Tomorrow.io key never surfaced its fetch error on
 * the widget.
 *
 * The strings are kept verbatim — bug-report triage and saved queries search for them.
 */
object SourceFetchLogTags {
    fun failureTag(source: WeatherSource): String? =
        when (source) {
            WeatherSource.NWS -> "FETCH_NWS_FAIL"
            WeatherSource.OPEN_METEO -> "FETCH_METEO_FAIL"
            WeatherSource.OPEN_WEATHER_MAP -> "FETCH_OWM_FAIL"
            WeatherSource.WEATHER_API -> "FETCH_WAPI_FAIL"
            WeatherSource.SILURIAN -> "FETCH_SILURIAN_FAIL"
            WeatherSource.TOMORROW_IO -> "FETCH_TMRW_FAIL"
            WeatherSource.GOOGLE_WEATHER -> "FETCH_GOOGLE_FAIL"
            // Not fetched as forecast providers.
            WeatherSource.VISUAL_CROSSING,
            WeatherSource.METAR,
            WeatherSource.SYNOPTIC,
            WeatherSource.GENERIC_GAP,
            -> null
        }
}
