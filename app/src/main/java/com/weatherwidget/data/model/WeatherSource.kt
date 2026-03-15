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
) {
    NWS(
        id = "NWS",
        displayName = "NWS",
        shortDisplayName = "NWS",
    ),
    OPEN_METEO(
        id = "OPEN_METEO",
        displayName = "Open-Meteo",
        shortDisplayName = "Meteo",
    ),
    WEATHER_API(
        id = "WEATHER_API",
        displayName = "WeatherAPI",
        shortDisplayName = "WAPI",
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
                "WeatherAPI", "WEATHER_API" -> WEATHER_API
                "Silurian", "SILURIAN" -> SILURIAN
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
                "WEATHER_API" -> WEATHER_API
                "SILURIAN" -> SILURIAN
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
