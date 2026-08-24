package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.HistoricalDataKind
import com.weatherwidget.data.model.WeatherSource

/**
 * Answers "where do THIS source's actuals come from?".
 *
 * Most sources answer with themselves: NWS observations drive NWS actuals, WeatherAPI's archived
 * history drives WeatherAPI's. But a forecast-only provider — currently Open-Meteo and Silurian —
 * ships no observation product at all, so it has nothing to draw an actual curve from and nothing
 * to grade its forecast against. Measured 2026-08-23: `OPEN_METEO` had **zero** observation rows on
 * a live device.
 *
 * Such a source **borrows** actuals from a real observation feed. The default is
 * [WeatherSource.METAR] — raw airport reports, independently measured, and available worldwide,
 * which matters because a non-US user has no NWS coverage and therefore no other option.
 *
 * ### The preference seam
 *
 * [providerIdFor] takes a `preference` lookup so a future Settings screen can let the user pick the
 * feed per borrowing source ("Open-Meteo actuals from: METAR / NWS / Synoptic / …"). Nothing reads a
 * stored preference yet; the parameter exists so the call sites are already shaped for it and the
 * default is expressed in exactly one place.
 *
 * A source that HAS its own actuals is never overridden — borrowing is a remedy for absence, not a
 * general substitution, and silently regrading NWS against someone else's thermometers would be a
 * different and much larger decision.
 */
object ActualsProviderResolver {

    /** Used when a borrowing source has no explicit preference. Worldwide, keyless, measured. */
    val DEFAULT_PROVIDER: WeatherSource = WeatherSource.METAR

    /**
     * The platform's stored per-source preference, installed once at startup.
     *
     * Nine call sites across both platforms ask "which api supplies this source's actuals?", several
     * of them deep inside pure blend code. Threading a lookup through all of them would push a
     * settings concern into functions whose whole value is that they take only data. This follows
     * the precedent already set by the shared `Log` sink, which Android installs in `onCreate`:
     * configuration supplied once by the platform, read-only thereafter.
     *
     * Defaults to "no preference", so anything that never installs one — tests, the desktop app
     * before its own settings land — behaves exactly as it did before the seam existed.
     */
    @Volatile
    private var installedPreference: (WeatherSource) -> WeatherSource? = { null }

    /** Install the platform's preference lookup. Call once, early. */
    fun installPreferenceSource(lookup: (WeatherSource) -> WeatherSource?) {
        installedPreference = lookup
    }

    /** Restore the no-preference default. For tests, which must not leak state into each other. */
    fun resetPreferenceSource() {
        installedPreference = { null }
    }

    /** The currently installed lookup, for callers that need to pass it on explicitly. */
    fun preferenceSource(): (WeatherSource) -> WeatherSource? = installedPreference

    /** True when [source] has no observation product of its own and must borrow one. */
    fun borrows(source: WeatherSource): Boolean =
        source != WeatherSource.METAR && !source.supportsTemperatureActuals

    /**
     * How trustworthy a candidate's "actuals" really are. The picker should show these as separate
     * groups: borrowing exists to escape circular actuals, so quietly offering a source whose
     * observations ARE its own forecast would defeat the point.
     */
    enum class Tier { MEASURED, DERIVED }

    /**
     * Keyed on [WeatherSource.historicalDataKind], NOT on `supportsTemperatureActuals`.
     *
     * That flag defaults to `true`, so filtering on it offered OpenWeatherMap and Visual Crossing —
     * both `HistoricalDataKind.NONE`.
     *
     * OpenWeatherMap is the interesting exclusion, because it is NOT simply "has no product". It
     * serves `/data/2.5/weather`, which this app already calls, and its rows here are a mix: the
     * `_1..4` POI offset samples come from that live endpoint, while `<SOURCE>_MAIN` is the
     * historical-actuals backfill — the source's own forecast re-filed. It is excluded for three
     * concrete reasons, verified against the live endpoint 2026-08-23:
     *
     *  - **No station identity.** The response names a CITY (`"name": "Los Altos"`, `id 5368335`)
     *    for whatever coordinate you ask for. The `"base": "stations"` field looks like a claim to
     *    the contrary, but OpenWeatherMap documents it as "Internal parameter".
     *  - **No history.** A single point per call, no time series. A provider has to supply a series
     *    for the daily blend and be able to fill in a past day; this can only accumulate forward.
     *  - **Not a measurement.** A blended city-centroid analysis — which this codebase already says
     *    of the POI grid itself: "also model-derived, not real thermometers"
     *    (`ObservationSourceMatcher`).
     *
     * Its honest class would be [HistoricalDataKind.RECENT_ANALYSIS], the same bucket as
     * Tomorrow.io's realtime product, which would make it a [Tier.DERIVED] candidate rather than no
     * candidate at all. Reclassifying is a live option, deliberately not taken: `historicalDataKind`
     * also drives `preservesHistoricalCloud` and the backfill gate, so the change reaches past this
     * picker. Visual Crossing is a plainer case — no historical product in use at all.
     */
    fun tierOf(source: WeatherSource): Tier? = when (source.historicalDataKind) {
        HistoricalDataKind.STATION_OBSERVATION -> Tier.MEASURED
        HistoricalDataKind.REANALYSIS_ARCHIVE,
        HistoricalDataKind.ARCHIVED_PROVIDER_HISTORY,
        HistoricalDataKind.RECENT_ANALYSIS,
        -> Tier.DERIVED
        HistoricalDataKind.NONE -> null
    }

    /** True when [source] can legitimately supply another source's actuals. */
    fun canProvide(source: WeatherSource): Boolean =
        source != WeatherSource.GENERIC_GAP && !borrows(source) && tierOf(source) != null

    /**
     * Feeds a user could pick as a borrowing source's actuals provider, measured ones first and the
     * default at the head.
     *
     * Exposed for the picker so the option list cannot drift from what the resolver accepts.
     */
    fun candidates(): List<WeatherSource> =
        WeatherSource.entries
            .filter(::canProvide)
            .sortedWith(
                compareBy(
                    { if (it == DEFAULT_PROVIDER) 0 else 1 },
                    { if (tierOf(it) == Tier.MEASURED) 0 else 1 },
                    { it.id },
                ),
            )

    /**
     * The `observations.api` value that supplies [source]'s actuals.
     *
     * @param preference optional per-source override, ignored when it names a source that cannot
     *   actually provide actuals — a stale preference must degrade to the default rather than
     *   silently leaving the borrowing source with no curve again.
     */
    fun providerIdFor(
        source: WeatherSource,
        preference: (WeatherSource) -> WeatherSource? = installedPreference,
    ): String {
        if (!borrows(source)) return source.id
        // Same rule as the picker: a preference naming a source that cannot legitimately provide
        // actuals (OpenWeatherMap, Visual Crossing) degrades to the default rather than silently
        // handing the borrower someone else's forecast.
        val chosen = preference(source)?.takeIf { canProvide(it) && it != source }
        return (chosen ?: DEFAULT_PROVIDER).id
    }
}
