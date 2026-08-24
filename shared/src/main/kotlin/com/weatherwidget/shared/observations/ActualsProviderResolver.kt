package com.weatherwidget.shared.observations

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
     * Feeds a user could reasonably pick as a borrowing source's actuals provider: every source that
     * ships real station observations or analysis, plus METAR.
     *
     * Exposed for the future Settings picker so the option list cannot drift from what the resolver
     * will actually accept.
     */
    fun candidates(): List<WeatherSource> =
        listOf(DEFAULT_PROVIDER) +
            WeatherSource.entries.filter {
                it != DEFAULT_PROVIDER &&
                    it != WeatherSource.GENERIC_GAP &&
                    it.supportsTemperatureActuals
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
        preference: (WeatherSource) -> WeatherSource? = { null },
    ): String {
        if (!borrows(source)) return source.id
        val chosen = preference(source)
            ?.takeIf { it == DEFAULT_PROVIDER || it.supportsTemperatureActuals }
            ?.takeIf { it != source }
        return (chosen ?: DEFAULT_PROVIDER).id
    }
}
