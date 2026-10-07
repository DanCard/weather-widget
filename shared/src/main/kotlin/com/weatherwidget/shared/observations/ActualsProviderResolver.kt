package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.HistoricalDataKind
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.NwsCoverage

/**
 * Answers "where do THIS source's actuals come from?".
 *
 * Most sources default to themselves when unconfigured: NWS observations drive NWS actuals,
 * WeatherAPI's archived history drives WeatherAPI's, Open-Meteo's analysis drives Open-Meteo's.
 * A forecast-only provider without observation products (Silurian, Google Weather) **borrows**
 * actuals from a measured feed by default: [WeatherSource.NWS] inside NWS coverage, where it is
 * denser and is also the feed NWS's own forecast is scored against, and [WeatherSource.METAR] (raw
 * airport reports, independently measured, available worldwide) everywhere else. The default is
 * derived from the active location on every read and never stored
 * (plans/261007-borrowers-default-to-nws-actuals-inside-coverage.md).
 *
 * Any configurable forecast source allows the user to select an alternate actuals provider (e.g.
 * METAR, Synoptic, NWS, Open-Meteo, Tomorrow.io, WeatherAPI) across both Android and Desktop.
 */
object ActualsProviderResolver {

    /**
     * A borrowing source's default outside NWS coverage, or when no location is known. Worldwide,
     * keyless, measured. Inside coverage the default is [WeatherSource.NWS]; see [borrowerDefault].
     */
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

    /**
     * The platform's active location, installed once at startup, for the same reason as
     * [installPreferenceSource]: the default provider depends on it, and many of the callers are
     * pure blend code. Null means no location is known, and the default is then [DEFAULT_PROVIDER].
     */
    @Volatile
    private var installedLocation: () -> Pair<Double, Double>? = { null }

    /** Install the platform's active-location lookup. Call once, early. */
    fun installLocationSource(lookup: () -> Pair<Double, Double>?) {
        installedLocation = lookup
    }

    /** Restore the no-location default. For tests. */
    fun resetLocationSource() {
        installedLocation = { null }
    }

    /** The currently installed location lookup. */
    fun locationSource(): () -> Pair<Double, Double>? = installedLocation

    /**
     * The feed a borrowing source uses when the user has not chosen one: NWS inside NWS coverage,
     * [DEFAULT_PROVIDER] (METAR) elsewhere or when no location is known.
     */
    fun borrowerDefault(location: Pair<Double, Double>? = installedLocation()): WeatherSource =
        if (location != null && NwsCoverage.covers(location.first, location.second)) {
            WeatherSource.NWS
        } else {
            DEFAULT_PROVIDER
        }

    /** True when [source] has no observation product of its own and must borrow one. */
    fun borrows(source: WeatherSource): Boolean =
        source != WeatherSource.METAR && !source.supportsTemperatureActuals

    /**
     * True when [source] may carry measured highs/lows in `daily_history` — either its own
     * observation product, or a borrowed one via [providerIdFor].
     *
     * Shared by Android's `DailyActualsStore` and desktop's `loadDailyActuals` so a forecast-only
     * source (Silurian) is never dropped from the actuals read just because
     * [WeatherSource.supportsTemperatureActuals] is false: its computed highs/lows come from
     * METAR/Synoptic, and skipping them blanked today's high-water mark (ghost) on desktop.
     */
    fun hasTemperatureActuals(source: WeatherSource): Boolean =
        source.supportsTemperatureActuals || borrows(source)

    /** True when [source] allows configuring an alternative actuals provider. */
    fun allowsAlternativeProvider(source: WeatherSource): Boolean =
        source != WeatherSource.GENERIC_GAP && source != WeatherSource.METAR && source != WeatherSource.SYNOPTIC

    /** The default provider for [source] at [location] (the active location unless given). */
    fun defaultProviderFor(
        source: WeatherSource,
        location: Pair<Double, Double>? = installedLocation(),
    ): WeatherSource =
        if (borrows(source)) borrowerDefault(location) else source

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
     * Tomorrow.io's five-minute analysis product, which makes it a [Tier.DERIVED] candidate rather than no
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
    fun candidates(): List<WeatherSource> {
        val default = borrowerDefault()
        return WeatherSource.entries
            .filter(::canProvide)
            .sortedWith(
                compareBy(
                    { if (it == default) 0 else 1 },
                    { if (tierOf(it) == Tier.MEASURED) 0 else 1 },
                    { it.id },
                ),
            )
    }

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
    ): String = resolve(source, preference, installedLocation)

    /** [providerIdFor] at an explicit location instead of the installed active one. */
    fun providerIdAt(
        source: WeatherSource,
        latitude: Double,
        longitude: Double,
        preference: (WeatherSource) -> WeatherSource? = installedPreference,
    ): String = resolve(source, preference) { latitude to longitude }

    private fun resolve(
        source: WeatherSource,
        preference: (WeatherSource) -> WeatherSource?,
        // Read only for a borrowing source with no usable preference.
        location: () -> Pair<Double, Double>?,
    ): String {
        if (!allowsAlternativeProvider(source)) return source.id
        val chosen = preference(source)?.takeIf { canProvide(it) && it != source }
        if (chosen != null) return chosen.id
        if (!borrows(source)) return source.id
        return borrowerDefault(location()).id
    }
}
