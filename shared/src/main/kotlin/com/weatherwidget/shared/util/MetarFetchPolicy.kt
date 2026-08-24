package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource

/**
 * Decides whether raw airport METARs are worth fetching this cycle, and at which cadence.
 *
 * METAR has **no cadence of its own**. It is not a display source and never appears in
 * [WeatherSourceOrdering.ALL_CONFIGURABLE], so it can never be "primary" in its own right. What it
 * is, is an actuals feed for the real providers that ship no observation product of their own —
 * currently OPEN_METEO and SILURIAN. So it inherits the priority of whatever it is feeding:
 *
 * | what is visible / displayed | tier | cadence |
 * |---|---|---|
 * | a no-actuals source is DISPLAYED (e.g. Open-Meteo in Paris) | [Tier.PRIMARY] | `CurrentTempFetchPolicy` — 10 / 16 min charging, 45 min opportunistic above 65 % |
 * | a no-actuals source is visible but not displayed | [Tier.NON_PRIMARY] | [NonPrimaryObservationPolicy] — 30 min, charging AND screen-on only |
 * | nothing visible consumes it | [Tier.NONE] | not fetched at all |
 *
 * The last row is the one that keeps this cheap: a US user running NWS plus Tomorrow.io has no
 * consumer for METAR, so it costs them nothing. The feed is only paid for when something on screen
 * would actually use it.
 *
 * Deliberately pure and platform-free: Android schedules it through `WeatherWidgetWorker`, and the
 * desktop app will need the same decision without WorkManager.
 */
object MetarFetchPolicy {

    enum class Tier {
        /** Something displayed depends on these actuals — refresh at the displayed-source rate. */
        PRIMARY,

        /** Only background sources depend on them — the cheap charging-and-screen-on loop. */
        NON_PRIMARY,

        /** Nothing would read the rows. Do not spend a request. */
        NONE,
    }

    /**
     * Visible sources that ship no observation product of their own, and therefore have nothing to
     * draw an actual curve from unless METAR supplies it.
     *
     * Reads [WeatherSource.supportsTemperatureActuals] rather than naming providers, so a future
     * keyless forecast-only source is picked up without touching this file. GENERIC_GAP cannot slip
     * in: it is not in `ALL_CONFIGURABLE`, so it is never among the visible sources — and it would
     * not want actuals anyway, being a future-only climate-normal filler that is never persisted.
     */
    fun consumers(visibleSources: List<WeatherSource>): List<WeatherSource> =
        visibleSources.filter { it != WeatherSource.METAR && !it.supportsTemperatureActuals }

    fun tierFor(
        visibleSources: List<WeatherSource>,
        activeDisplaySourceIds: Set<String>,
    ): Tier {
        val consumers = consumers(visibleSources)
        return when {
            consumers.isEmpty() -> Tier.NONE
            // Any widget currently showing a consumer promotes the whole fetch: the user is looking
            // at a curve that depends on these rows right now.
            consumers.any { it.id in activeDisplaySourceIds } -> Tier.PRIMARY
            else -> Tier.NON_PRIMARY
        }
    }
}
