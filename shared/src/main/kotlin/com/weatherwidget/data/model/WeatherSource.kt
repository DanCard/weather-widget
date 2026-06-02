package com.weatherwidget.data.model

/**
 * Enum representing weather data sources.
 * Centralizes source identification to eliminate string constant duplication
 * and provide type-safe source handling throughout the app.
 */
enum class WeatherSource(
    val id: String,
    val displayName: String,
    val shortDisplayName: String,
    val supportsHourly: Boolean = true,
    /**
     * Whether this source has a genuine historical data product for past days — station
     * observations (NWS), a dedicated history endpoint (Silurian `/history/hourly`,
     * WeatherAPI `/history.json`), or a reanalysis-backed archive (Open-Meteo `past_days`).
     *
     * Sources without one (Visual Crossing, OpenWeatherMap) only have a forecast that we slice
     * into the past. For those we must NOT persist past-day precip as a measured "actual" — see
     * `saveHistoricalActuals`. (Temperature backfill is unaffected; this flag governs
     * measured-precip provenance only.)
     *
     * Tomorrow.io is a special case: its dedicated `/v4/historical` endpoint is access-denied on
     * our plan, but its `/v4/timelines` fetch is hard-capped at `minusHours(23)` (see
     * `TomorrowIoApi`). Because it can therefore only ever return past hours that are <24h old —
     * genuinely-recent nowcast/analysis data, never stale multi-day forecast — backfilling those
     * hours as actuals is NOT the forecast-as-actual bug that VC/OWM would hit. So it is `true`.
     * This safety hinges on the 23h cap: do not widen `TomorrowIoApi`'s startTime window.
     */
    val providesHistoricalActuals: Boolean = false,
) {
    NWS(
        id = "NWS",
        displayName = "NWS",
        shortDisplayName = "NWS",
        providesHistoricalActuals = true,
    ),
    OPEN_METEO(
        id = "OPEN_METEO",
        displayName = "Open-Meteo",
        shortDisplayName = "Meteo",
        providesHistoricalActuals = true,
    ),
    VISUAL_CROSSING(
        id = "VISUAL_CROSSING",
        displayName = "Visual Crossing",
        shortDisplayName = "VisCr",
    ),
    OPEN_WEATHER_MAP(
        id = "OPEN_WEATHER_MAP",
        displayName = "OpenWeatherMap",
        shortDisplayName = "OWM",
    ),
    WEATHER_API(
        id = "WEATHER_API",
        displayName = "WeatherAPI",
        shortDisplayName = "WAPI",
        providesHistoricalActuals = true,
    ),
    GENERIC_GAP(
        id = "Generic",
        displayName = "Climate Avg",
        shortDisplayName = "C",
        supportsHourly = false,
    ),
    SILURIAN(
        id = "SILURIAN",
        displayName = "Silurian",
        shortDisplayName = "Silur",
        providesHistoricalActuals = true,
    ),
    TOMORROW_IO(
        id = "TOMORROW_IO",
        displayName = "Tomorrow.io",
        shortDisplayName = "Tmrw",
        // Rolling <24h actuals window only (no multi-day archive); safe because the fetch is
        // capped at 23h back — see the providesHistoricalActuals doc above.
        providesHistoricalActuals = true,
    ),
    ;

    companion object {
        /**
         * Maps a display source string (from UI/SharedPreferences) to WeatherSource.
         * Returns null for unknown inputs so callers can preserve explicit fallback behavior.
         */
        fun fromDisplaySourceOrNull(displaySource: String?): WeatherSource? =
            when (displaySource) {
                "NWS" -> NWS
                "Open-Meteo", "OPEN_METEO" -> OPEN_METEO
                "Visual Crossing", "VISUAL_CROSSING" -> VISUAL_CROSSING
                "OpenWeatherMap", "OPEN_WEATHER_MAP" -> OPEN_WEATHER_MAP
                "WeatherAPI", "WEATHER_API" -> WEATHER_API
                "Silurian", "SILURIAN" -> SILURIAN
                "Tomorrow.io", "TOMORROW_IO" -> TOMORROW_IO
                else -> null
            }

        /**
         * Maps a display source string (from UI/SharedPreferences) to WeatherSource.
         * Handles both "NWS" and "Open-Meteo" formats.
         */
        fun fromDisplaySource(displaySource: String): WeatherSource =
            fromDisplaySourceOrNull(displaySource) ?: NWS

        /**
         * Maps a database ID to WeatherSource.
         */
        fun fromId(id: String): WeatherSource =
            when (id) {
                "NWS" -> NWS
                "OPEN_METEO" -> OPEN_METEO
                "VISUAL_CROSSING" -> VISUAL_CROSSING
                "OPEN_WEATHER_MAP" -> OPEN_WEATHER_MAP
                "WEATHER_API" -> WEATHER_API
                "SILURIAN" -> SILURIAN
                "TOMORROW_IO" -> TOMORROW_IO
                "Generic" -> GENERIC_GAP
                else -> NWS
            }

        /**
         * Gets the database source name from a display source string.
         * "NWS" -> "NWS", "Open-Meteo" -> "OPEN_METEO"
         */
        fun getDatabaseSourceName(displaySource: String): String = fromDisplaySource(displaySource).id
    }
}
