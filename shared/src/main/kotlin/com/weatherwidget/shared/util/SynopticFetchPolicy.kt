package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver

/**
 * Decides whether Synoptic observations are worth fetching this cycle, and at which cadence.
 */
object SynopticFetchPolicy {

    enum class Tier {
        /** Something displayed depends on these actuals — refresh at the displayed-source rate. */
        PRIMARY,

        /** Only background sources depend on them — the cheap charging-and-screen-on loop. */
        NON_PRIMARY,

        /** Nothing would read the rows. Do not spend a request. */
        NONE,
    }

    /**
     * Visible sources that borrow actuals from Synoptic.
     */
    fun consumers(
        visibleSources: List<WeatherSource>,
        actualsPreference: (WeatherSource) -> WeatherSource? = ActualsProviderResolver.preferenceSource(),
    ): List<WeatherSource> =
        visibleSources.filter { source ->
            ActualsProviderResolver.borrows(source) &&
                ActualsProviderResolver.providerIdFor(source, actualsPreference) == WeatherSource.SYNOPTIC.id
        }

    fun tierFor(
        visibleSources: List<WeatherSource>,
        activeDisplaySourceIds: Set<String>,
        actualsPreference: (WeatherSource) -> WeatherSource? = ActualsProviderResolver.preferenceSource(),
    ): Tier {
        val consumers = consumers(visibleSources, actualsPreference)
        return when {
            consumers.isEmpty() -> Tier.NONE
            consumers.any { it.id in activeDisplaySourceIds } -> Tier.PRIMARY
            else -> Tier.NON_PRIMARY
        }
    }
}
