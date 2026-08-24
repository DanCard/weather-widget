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
            // METAR is aviationweather.gov: free, keyless, and not user-selectable at all.
            // Synoptic DOES need a token, but it is provisioned at build time like the others and
            // is never user-selectable as a display source, so it is not a "requires user key" case.
            NWS, OPEN_METEO, GENERIC_GAP, METAR, SYNOPTIC -> false
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
