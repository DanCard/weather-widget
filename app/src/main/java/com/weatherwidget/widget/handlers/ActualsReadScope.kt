package com.weatherwidget.widget.handlers

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver

/**
 * The `observations.api` values a paint needs in order to draw (or reason about) one display
 * source's actuals.
 *
 * Exists so every paint-path read scopes itself the SAME way. That is not tidiness: the today-column
 * overlay re-derives `observedAt` from the header's 36h load while [CurrentTempResolver] derives it
 * from its own narrower query, and the two are compared for **exact equality** — if one read sees a
 * row the other does not, the dominant-station rows silently vanish from the overlay. Scoping those
 * two reads differently would reintroduce that skew, so the set lives in one place and every caller
 * on that path takes it from here.
 *
 * The set is exactly what [com.weatherwidget.shared.observations.ObservationSourceMatcher.matchesActualSource]
 * admits on the `api` dimension — it returns true only for `GENERIC_GAP` or for
 * `ActualsProviderResolver.providerIdFor(source)` — so restricting the SQL to these two values
 * cannot change any consumer's output. Every other api was previously read off disk purely to be
 * dropped by the first filter in the blend: measured 2026-09-07 on the Samsung, SYNOPTIC alone was
 * 73% of the last 72h of rows on a device displaying NWS.
 *
 * Resolved, never a literal: a source can be configured to take another feed's actuals (the
 * reporting device has `actuals_provider_SILURIAN = SYNOPTIC`, so Silurian's curve is built entirely
 * from Synoptic rows).
 *
 * **Do not use this to scope a read whose consumer is not source-filtered.** The daily recompute
 * computes history for every source at once and must stay unscoped; see [ObservationDao].
 */
internal object ActualsReadScope {
    fun apisFor(displaySource: WeatherSource): Set<String> =
        setOf(
            ActualsProviderResolver.providerIdFor(displaySource),
            WeatherSource.GENERIC_GAP.id,
        )
}
