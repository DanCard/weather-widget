package com.weatherwidget.data.model

/**
 * Describes what a source's past-hour product represents.
 *
 * This is deliberately more precise than the old `providesHistoricalActuals` boolean: data can be
 * suitable for a source-specific history curve without being a station observation. In particular,
 * WeatherAPI documents `/history.json` as archived provider history, not NWS-style station truth.
 */
enum class HistoricalDataKind(
    val preservesHistoricalPrecipitation: Boolean,
    val preservesHistoricalCloud: Boolean = preservesHistoricalPrecipitation,
) {
    STATION_OBSERVATION(true),
    REANALYSIS_ARCHIVE(true),
    ARCHIVED_PROVIDER_HISTORY(true),
    RECENT_ANALYSIS(true),
    NONE(false),
}

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
    val historicalDataKind: HistoricalDataKind = HistoricalDataKind.NONE,
    /** Whether observation/analysis rows may drive temperature actuals for this source. */
    val supportsTemperatureActuals: Boolean = true,
    /** Whether this source exposes a documented observation/analysis cloud product. */
    val supportsCloudActuals: Boolean = historicalDataKind.preservesHistoricalCloud,
    /** Whether elapsed forecast/history rows may be re-filed as observations. */
    val supportsHistoricalActualsBackfill: Boolean = supportsTemperatureActuals,
) {
    NWS(
        id = "NWS",
        displayName = "NWS",
        shortDisplayName = "NWS",
        historicalDataKind = HistoricalDataKind.STATION_OBSERVATION,
    ),
    OPEN_METEO(
        id = "OPEN_METEO",
        displayName = "Open-Meteo",
        shortDisplayName = "Meteo",
        // The endpoints used by this app are the Forecast API's model-current, minutely_15, and
        // past_days products. They are model output, not station observations. Open-Meteo's
        // separate Historical Weather API is reanalysis, but it is not called here.
        historicalDataKind = HistoricalDataKind.NONE,
        supportsTemperatureActuals = false,
        supportsCloudActuals = false,
        supportsHistoricalActualsBackfill = false,
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
        historicalDataKind = HistoricalDataKind.ARCHIVED_PROVIDER_HISTORY,
    ),
    GENERIC_GAP(
        id = "Generic",
        displayName = "Climate Avg",
        shortDisplayName = "C",
        supportsHourly = false,
        supportsTemperatureActuals = false,
    ),
    SILURIAN(
        id = "SILURIAN",
        displayName = "Silurian",
        shortDisplayName = "Silur",
        historicalDataKind = HistoricalDataKind.NONE,
        // Silurian documents `include_past` on /forecast/hourly as forecast output, not an
        // observation or analysis product. Keep its forecast curves, but never relabel the
        // elapsed portion of that response as temperature or cloud actuals.
        supportsTemperatureActuals = false,
        supportsCloudActuals = false,
    ),
    TOMORROW_IO(
        id = "TOMORROW_IO",
        displayName = "Tomorrow.io",
        shortDisplayName = "Tmrw",
        // Provisionally treat the bounded six-hour Timeline lookback as recent analysis. It is
        // stored under distinct provenance from /realtime so it can be compared and removed alone
        // if later evidence shows the product is only revised forecast history.
        historicalDataKind = HistoricalDataKind.RECENT_ANALYSIS,
        supportsTemperatureActuals = true,
        supportsCloudActuals = true,
        supportsHistoricalActualsBackfill = true,
    ),
    ;

    /**
     * True when the source needs a user-supplied API key. NWS and Open-Meteo are free and keyless,
     * so a failure from either must never be reported as a missing-key problem.
     */
    val requiresApiKey: Boolean
        get() = when (this) {
            VISUAL_CROSSING, OPEN_WEATHER_MAP, WEATHER_API, SILURIAN, TOMORROW_IO -> true
            NWS, OPEN_METEO, GENERIC_GAP -> false
        }

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
