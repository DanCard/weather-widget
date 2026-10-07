package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver

/**
 * Which observation feed to fetch so a source's actuals exist: the fetch-side counterpart of
 * [ActualsProviderResolver], which answers the same question on the read side.
 *
 * Both platforms used to answer this in their own fetch code, and each got it partly wrong in a
 * different place. Desktop's full refresh fetched borrowed actuals only when the provider was METAR
 * or Synoptic, while its observation loop also covered NWS. Android's current-temp loop fetched
 * only sources that file their own actuals, and its NWS backfill ran only for NWS itself. So Google
 * Weather with NWS as its actuals provider got no NWS observations from the refresh button, a source
 * switch, or a provider switch, on either platform
 * (plans/261007-desktop-borrowed-nws-actuals-not-fetched-on-full-refresh.md). Each platform now asks
 * here and keeps only the code that performs the fetch.
 */
object ActualsFeedPolicy {

    /**
     * Station networks with their own refreshers on both platforms (`MetarFetchPolicy`,
     * `SynopticFetchPolicy`, and their schedulers), never fetched through a per-source path.
     */
    val STATION_NETWORK_FEEDS: Set<WeatherSource> = setOf(WeatherSource.METAR, WeatherSource.SYNOPTIC)

    /**
     * The feed that supplies [source]'s actuals at this location, or null when no feed can serve
     * it here. Today the only such case is NWS outside [NwsCoverage]: the stored preference is the
     * user's choice and is never rewritten for coverage, so it is filtered here, every time it is read.
     */
    fun feedFor(
        source: WeatherSource,
        latitude: Double,
        longitude: Double,
        actualsPreference: (WeatherSource) -> WeatherSource? = ActualsProviderResolver.preferenceSource(),
    ): WeatherSource? {
        val feed = WeatherSource.fromId(ActualsProviderResolver.providerIdAt(source, latitude, longitude, actualsPreference))
        if (feed == WeatherSource.NWS && !NwsCoverage.covers(latitude, longitude)) return null
        return feed
    }

    /**
     * The observation pull a full refresh of [displaySource] must make alongside its forecast
     * fetch, or null when none is needed.
     *
     * None is needed when the source is its own provider, because its forecast fetch already
     * supplies the actuals (NWS station readings, Tomorrow.io's five-minute history, the re-filed
     * past hours of the others). It is also null when no feed serves this location.
     *
     * For NWS, the recent window is enough whenever the stored rows already reach into it.
     * Observations are stored per site, not per displayed source, so the week another source (or
     * NWS itself) already fetched is reused, not pulled again. Only a real gap
     * ([newestStoredFeedMs] missing or older than [NWS_RECENT_WINDOW_COVERS_GAP_MS]) needs the 7-day
     * pull, which costs about 30 s
     * (performance/261004-desktop-wake-refresh-stalls-on-7day-obs-window.md).
     * [deferObservationWindow] is the launch/wake catch-up, which always takes the recent window and
     * fills history afterwards ([hasDeferredHistoryWindow]). Every other feed's recovery pull is
     * already bounded, so it is unaffected.
     */
    fun fullRefreshFetch(
        displaySource: WeatherSource,
        latitude: Double,
        longitude: Double,
        deferObservationWindow: Boolean,
        newestStoredFeedMs: Long?,
        nowMs: Long,
        actualsPreference: (WeatherSource) -> WeatherSource? = ActualsProviderResolver.preferenceSource(),
    ): ObservationFetch? {
        val feed = feedFor(displaySource, latitude, longitude, actualsPreference) ?: return null
        if (feed == displaySource) return null
        val storedCoversGap = newestStoredFeedMs != null && nowMs - newestStoredFeedMs < NWS_RECENT_WINDOW_COVERS_GAP_MS
        return ObservationFetch(
            feed = feed,
            recentOnly = feed == WeatherSource.NWS && (deferObservationWindow || storedCoversGap),
        )
    }

    /**
     * The newest stored NWS row is close enough for the recent pull to bridge to it. Desktop's
     * recent window is 90 minutes (`DesktopWeatherService.RECENT_OBSERVATION_WINDOW_MINUTES`); this
     * leaves a 30-minute margin for stations that publish late.
     */
    const val NWS_RECENT_WINDOW_COVERS_GAP_MS: Long = 60 * 60 * 1000L

    /** True when [displaySource]'s actuals come from NWS, the one feed with a deferrable 7-day window. */
    fun hasDeferredHistoryWindow(
        displaySource: WeatherSource,
        latitude: Double,
        longitude: Double,
        actualsPreference: (WeatherSource) -> WeatherSource? = ActualsProviderResolver.preferenceSource(),
    ): Boolean = feedFor(displaySource, latitude, longitude, actualsPreference) == WeatherSource.NWS

    /**
     * Visible sources other than [feed] itself whose actuals come from [feed]. Backs
     * `MetarFetchPolicy` and `SynopticFetchPolicy`, whose "is anyone reading this?" question is the
     * same one for every feed.
     */
    fun borrowers(
        feed: WeatherSource,
        visibleSources: List<WeatherSource>,
        actualsPreference: (WeatherSource) -> WeatherSource? = ActualsProviderResolver.preferenceSource(),
    ): List<WeatherSource> =
        visibleSources.filter { source ->
            source != feed && ActualsProviderResolver.providerIdFor(source, actualsPreference) == feed.id
        }

    /**
     * True when a fetch serving [sources] needs [feed]'s station observations: one of them is [feed]
     * itself, or takes its actuals from it here.
     */
    fun requiresFeed(
        feed: WeatherSource,
        sources: Collection<WeatherSource>,
        latitude: Double,
        longitude: Double,
        actualsPreference: (WeatherSource) -> WeatherSource? = ActualsProviderResolver.preferenceSource(),
    ): Boolean =
        sources.any { source ->
            source == feed || feedFor(source, latitude, longitude, actualsPreference) == feed
        }

    /**
     * Feeds Android's per-source current-temperature loop should fetch for [requested], each mapped
     * to the requested sources it serves (in [requested] order, so callers can rank and throttle a
     * feed by its consumers).
     *
     * A source that files its own actuals is always its own feed, which keeps the loop fetching
     * what it did before. A source whose provider is a different feed adds that provider, so a
     * borrower reaches its feed even when the feed is not itself visible. A forecast-only source is
     * never fetched as its own feed: it must never file its model current as an observation.
     * [STATION_NETWORK_FEEDS] are left to their own refreshers.
     */
    fun currentTempFeeds(
        requested: List<WeatherSource>,
        latitude: Double,
        longitude: Double,
        actualsPreference: (WeatherSource) -> WeatherSource? = ActualsProviderResolver.preferenceSource(),
    ): Map<WeatherSource, List<WeatherSource>> {
        val result = LinkedHashMap<WeatherSource, MutableList<WeatherSource>>()
        for (source in requested.distinct()) {
            if (source.supportsTemperatureActuals && source !in STATION_NETWORK_FEEDS) {
                result.getOrPut(source) { mutableListOf() }.add(source)
            }
            val feed = feedFor(source, latitude, longitude, actualsPreference) ?: continue
            if (feed != source && feed.supportsTemperatureActuals && feed !in STATION_NETWORK_FEEDS) {
                val consumers = result.getOrPut(feed) { mutableListOf() }
                if (source !in consumers) consumers.add(source)
            }
        }
        return result
    }

    /** One observation pull: which feed, and whether to narrow it to the recent window. */
    data class ObservationFetch(val feed: WeatherSource, val recentOnly: Boolean)
}
