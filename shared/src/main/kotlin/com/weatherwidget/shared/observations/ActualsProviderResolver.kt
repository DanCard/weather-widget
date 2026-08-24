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
     * both `HistoricalDataKind.NONE`. Their observation rows are `<SOURCE>_MAIN` at `distanceKm = 0`
     * plus the `_1..4` POI offset grid: model output re-filed, not measurement (verified on device
     * 2026-08-23). Borrowing those would reintroduce the circular actuals this whole mechanism
     * exists to replace, with an extra hop to disguise it.
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
        preference: (WeatherSource) -> WeatherSource? = { null },
    ): String {
        if (!borrows(source)) return source.id
        // Same rule as the picker: a preference naming a source that cannot legitimately provide
        // actuals (OpenWeatherMap, Visual Crossing) degrades to the default rather than silently
        // handing the borrower someone else's forecast.
        val chosen = preference(source)?.takeIf { canProvide(it) && it != source }
        return (chosen ?: DEFAULT_PROVIDER).id
    }
}
