package com.weatherwidget.shared.sourceview

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.shared.util.ActualsFeedPolicy
import java.time.LocalDate

/**
 * Which sources are fetched in the **background** — ONE rule for Android and desktop (user,
 * 2026-10-10: "Don't fetch API sources that are unlikely to be used"; "if it hasn't been viewed in 8
 * days then not fetched"). Everything else is on demand: a switch to the source, a day tap needing
 * its hourly, a history refill. Targeted fetches never ask this.
 *
 * A source is fetched in the background when it is displayed or primary, when the user switched to
 * it within the last [RECENT_VIEW_DAYS] days (today included), or while tracking is younger than
 * that. A recency rule, not a [SourceViewProbability] threshold: a source viewed daily until 8 days
 * ago still reads ~48 %, and the user's rule is about the last view.
 *
 * Actuals feeds are derived, never tracked: a feed is needed when a background-fetched source takes
 * its actuals from it ([ActualsFeedPolicy.feedFor]). So a displayed Google keeps NWS observations
 * coming even when nobody looks at NWS's own forecast.
 * See performance/261010-fetch-only-sources-likely-to-be-viewed.md.
 */
object SourceFetchGate {
    /** Not viewed in this many days → not fetched in the background. */
    const val RECENT_VIEW_DAYS = 8L

    data class BackgroundFetch(
        /** Forecasts fetched on the background cadence, in the enabled order. */
        val forecasts: Set<WeatherSource>,
        /** Enabled sources left to on-demand fetching. */
        val off: Set<WeatherSource>,
        /** Observation feeds the background-fetched sources read their actuals from. */
        val actualsFeeds: Set<WeatherSource>,
    ) {
        /** `SOURCE_FETCH_GATE` app_logs line. */
        fun logLine(): String =
            "on=${forecasts.joinToString(",") { it.id }} off=${off.joinToString(",") { it.id }} " +
                "feeds=${actualsFeeds.joinToString(",") { it.id }}"
    }

    /** True while tracking has fewer than [RECENT_VIEW_DAYS] days to judge by (or none at all). */
    fun inGracePeriod(trackingSince: LocalDate?, today: LocalDate): Boolean =
        trackingSince == null || trackingSince.isAfter(today.minusDays(RECENT_VIEW_DAYS))

    /** The user switched to [sourceId] (any trigger) today or in the [RECENT_VIEW_DAYS] − 1 days before. */
    fun viewedRecently(sourceId: String, rows: List<SourceViewDayRow>, today: LocalDate): Boolean {
        val oldest = today.minusDays(RECENT_VIEW_DAYS - 1)
        return rows.any { it.sourceId == sourceId && it.switches > 0 && !it.date.isBefore(oldest) && !it.date.isAfter(today) }
    }

    /** The background forecast set, in [enabled] order. */
    fun backgroundSources(
        enabled: List<WeatherSource>,
        displayedIds: Set<String>,
        primaryId: String?,
        rows: List<SourceViewDayRow>,
        trackingSince: LocalDate?,
        today: LocalDate,
    ): Set<WeatherSource> {
        if (inGracePeriod(trackingSince, today)) return enabled.toCollection(LinkedHashSet())
        return enabled.filterTo(LinkedHashSet()) {
            it.id in displayedIds || it.id == primaryId || viewedRecently(it.id, rows, today)
        }
    }

    /** Feeds that [sources] take their actuals from here: themselves when they file their own, else their provider. */
    fun actualsFeeds(
        sources: Collection<WeatherSource>,
        latitude: Double,
        longitude: Double,
        actualsPreference: (WeatherSource) -> WeatherSource? = ActualsProviderResolver.preferenceSource(),
    ): Set<WeatherSource> =
        sources.mapNotNullTo(LinkedHashSet()) { ActualsFeedPolicy.feedFor(it, latitude, longitude, actualsPreference) }

    fun backgroundFetch(
        enabled: List<WeatherSource>,
        displayedIds: Set<String>,
        primaryId: String?,
        rows: List<SourceViewDayRow>,
        trackingSince: LocalDate?,
        today: LocalDate,
        latitude: Double,
        longitude: Double,
        actualsPreference: (WeatherSource) -> WeatherSource? = ActualsProviderResolver.preferenceSource(),
    ): BackgroundFetch {
        val forecasts = backgroundSources(enabled, displayedIds, primaryId, rows, trackingSince, today)
        return BackgroundFetch(
            forecasts = forecasts,
            off = enabled.filterNotTo(LinkedHashSet()) { it in forecasts },
            actualsFeeds = actualsFeeds(forecasts, latitude, longitude, actualsPreference),
        )
    }
}
