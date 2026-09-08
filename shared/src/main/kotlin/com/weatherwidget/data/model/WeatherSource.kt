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
 *
 * Source metadata (display name, description, signup URL, key requirements) lives here as
 * constructor params so adding a new entry is a single edit — no scattered `when` blocks to
 * forget.
 */
enum class WeatherSource(
    val id: String,
    val displayName: String,
    val shortDisplayName: String,
    val description: String,
    val supportsHourly: Boolean = true,
    val historicalDataKind: HistoricalDataKind = HistoricalDataKind.NONE,
    /** Whether observation/analysis rows may drive temperature actuals for this source. */
    val supportsTemperatureActuals: Boolean = true,
    /** Whether this source exposes a documented observation/analysis cloud product. */
    val supportsCloudActuals: Boolean = historicalDataKind.preservesHistoricalCloud,
    /** Whether elapsed forecast/history rows may be re-filed as observations. */
    val supportsHistoricalActualsBackfill: Boolean = supportsTemperatureActuals,
    /** Signup page for key-requiring sources; keyless sources fall back to open-meteo.com. */
    val signupUrl: String = "https://open-meteo.com",
    /**
     * True when the source needs an API key from anywhere (user-entered or build-time provisioned).
     * NWS and Open-Meteo are free and keyless, so a failure from either must never be reported
     * as a missing-key problem. Synoptic's token is build-time provisioned and it is never
     * user-selectable, so it is false.
     */
    val requiresApiKey: Boolean = false,
    /**
     * True when the user must manually enter a key in Settings for the source to work at all.
     * Differs from [requiresApiKey] only for SILURIAN, which has a build-time key fallback and
     * therefore does not require the user to enter one.
     */
    val requiresUserEnteredKey: Boolean = false,
) {
    NWS(
        id = "NWS",
        displayName = "NWS",
        shortDisplayName = "NWS",
        description = "National Weather Service (US only)",
        historicalDataKind = HistoricalDataKind.STATION_OBSERVATION,
    ),
    OPEN_METEO(
        id = "OPEN_METEO",
        displayName = "Open-Meteo",
        shortDisplayName = "Meteo",
        description = "Open-Meteo — shown as Meteo (global coverage)",
        historicalDataKind = HistoricalDataKind.RECENT_ANALYSIS,
        supportsTemperatureActuals = true,
        supportsCloudActuals = true,
        supportsHistoricalActualsBackfill = true,
    ),
    VISUAL_CROSSING(
        id = "VISUAL_CROSSING",
        displayName = "Visual Crossing",
        shortDisplayName = "VisCr",
        description = "Visual Crossing — shown as VisCr (global coverage)",
        requiresApiKey = true,
        requiresUserEnteredKey = true,
    ),
    OPEN_WEATHER_MAP(
        id = "OPEN_WEATHER_MAP",
        displayName = "OpenWeatherMap",
        shortDisplayName = "OWM",
        description = "OpenWeatherMap — shown as OWM (global coverage)",
        signupUrl = "https://home.openweathermap.org/users/sign_up",
        requiresApiKey = true,
        requiresUserEnteredKey = true,
    ),
    WEATHER_API(
        id = "WEATHER_API",
        displayName = "WeatherAPI",
        shortDisplayName = "WAPI",
        description = "WeatherAPI — shown as WAPI (global coverage)",
        historicalDataKind = HistoricalDataKind.ARCHIVED_PROVIDER_HISTORY,
        signupUrl = "https://www.weatherapi.com/signup.aspx",
        requiresApiKey = true,
        requiresUserEnteredKey = true,
    ),
    /**
     * Raw METAR observations from `aviationweather.gov`. An **actuals feed, not a forecast
     * provider** — it has no forecast product at all, so it is deliberately absent from
     * [com.weatherwidget.shared.util.WeatherSourceOrdering.ALL_CONFIGURABLE] and can never be
     * selected as a display source.
     *
     * Its rows exist to supply actuals to the real providers that ship none of their own —
     * `ALL_CONFIGURABLE.filter { !it.supportsTemperatureActuals }`, currently OPEN_METEO and
     * SILURIAN. That set deliberately excludes GENERIC_GAP, which is not a provider: it synthesizes
     * climate normals for future dates beyond real forecast coverage and never needs actuals.
     *
     * Also the app's only station-observation source outside the United States, where NWS
     * discovery fails outright. See plans/260823-aviationweather-metar-transport.md.
     */
    METAR(
        id = "METAR",
        displayName = "METAR",
        shortDisplayName = "MTR",
        description = "Airport METAR observations (actuals only, never user-selectable)",
        supportsHourly = false,
        historicalDataKind = HistoricalDataKind.STATION_OBSERVATION,
        supportsTemperatureActuals = true,
        // Never re-file a forecast as an observation for this source: it HAS no forecast, and the
        // whole point of the feed is that its actuals are independently measured.
        supportsHistoricalActualsBackfill = false,
    ),
    /**
     * Synoptic Data / MesoWest as a first-class **actuals provider** — its own station discovery and
     * its own observation rows, selectable by a forecast-only source via [ActualsProviderResolver].
     *
     * Distinct from the long-standing Synoptic **web fallback**, which stays exactly as it is: that
     * path fetches the 3 nearest *NWS* stations and files them under `api = "NWS"` with
     * `isWebFallback = true`, because it redistributes the same ASOS METARs the NWS API serves and
     * is merged per-station by `LatestObservationMerge.preferNewest` as the freshness path
     * (20-60 minutes ahead of the API). Relabelling those rows would strip ~12 % of NWS's
     * observations out of NWS's own blend and break that merge. Same upstream service, two
     * deliberately separate uses.
     *
     * As a provider it is far denser than METAR — 386 stations within 25 miles of Mountain View
     * versus 5, measured 2026-08-23 — but US-only (a Paris query returns none) and it needs a token,
     * which is why [ActualsProviderResolver.DEFAULT_PROVIDER] remains METAR.
     *
     * Not in [com.weatherwidget.shared.util.WeatherSourceOrdering.ALL_CONFIGURABLE]: like METAR it is
     * a feed, never a display source.
     */
    SYNOPTIC(
        id = "SYNOPTIC",
        displayName = "Synoptic",
        shortDisplayName = "Syn",
        description = "Synoptic/MesoWest stations (actuals only, never user-selectable)",
        supportsHourly = false,
        historicalDataKind = HistoricalDataKind.STATION_OBSERVATION,
        supportsTemperatureActuals = true,
        // No forecast of its own to re-file, and the point of the feed is measured data.
        supportsHistoricalActualsBackfill = false,
    ),
    GENERIC_GAP(
        id = "Generic",
        displayName = "Climate Avg",
        shortDisplayName = "C",
        description = "Synthetic climate-normal fallback (never user-selectable)",
        supportsHourly = false,
        supportsTemperatureActuals = false,
    ),
    SILURIAN(
        id = "SILURIAN",
        displayName = "Silurian",
        shortDisplayName = "Silur",
        description = "Silurian.ai — shown as Silur (global coverage)",
        historicalDataKind = HistoricalDataKind.NONE,
        // Silurian documents `include_past` on /forecast/hourly as forecast output, not an
        // observation or analysis product. Keep its forecast curves, but never relabel the
        // elapsed portion of that response as temperature or cloud actuals.
        supportsTemperatureActuals = false,
        supportsCloudActuals = false,
        signupUrl = "https://earth.weather.silurian.ai",
        requiresApiKey = true,
        // Silurian has a build-time key fallback, so the user does not HAVE to enter one.
        requiresUserEnteredKey = false,
    ),
    TOMORROW_IO(
        id = "TOMORROW_IO",
        displayName = "Tomorrow.io",
        shortDisplayName = "Tmrw",
        description = "Tomorrow.io — shown as Tmrw (global coverage)",
        historicalDataKind = HistoricalDataKind.RECENT_ANALYSIS,
        supportsTemperatureActuals = true,
        supportsCloudActuals = true,
        supportsHistoricalActualsBackfill = true,
        signupUrl = "https://app.tomorrow.io/signup",
        requiresApiKey = true,
        requiresUserEnteredKey = true,
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
                "METAR" -> METAR
                "SYNOPTIC" -> SYNOPTIC
                else -> null
            }

        /**
         * Maps a display source string (from UI/SharedPreferences) to WeatherSource.
         * Handles both "NWS" and "Open-Meteo" formats.
         */
        fun fromDisplaySource(displaySource: String): WeatherSource =
            fromDisplaySourceOrNull(displaySource) ?: NWS

        /**
         * Maps a database ID to WeatherSource. Unrecognised ids fall back to NWS so callers
         * that read from the database always render something. Every new enum entry is
         * automatically covered — no per-entry `when` arm to forget.
         */
        fun fromId(id: String): WeatherSource =
            entries.firstOrNull { it.id == id } ?: NWS

        /**
         * Gets the database source name from a display source string.
         * "NWS" -> "NWS", "Open-Meteo" -> "OPEN_METEO"
         */
        fun getDatabaseSourceName(displaySource: String): String = fromDisplaySource(displaySource).id
    }
}
