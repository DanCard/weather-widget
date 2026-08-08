package com.weatherwidget.shared.stats

import com.weatherwidget.data.model.HistoricalDataKind
import com.weatherwidget.data.model.WeatherSource

/**
 * Decides **whose** `daily_history` row supplies the actual when scoring a source's forecast.
 *
 * Before this existed, `AccuracyCalculator` filtered `daily_history` to `it.source == source.id`,
 * so every source was graded against its own actuals row. That is fine for NWS (real station
 * observations) but circular for a [HistoricalDataKind.NONE] source: its `computedHighTemp` blends
 * nothing but its own `<SOURCE>_MAIN` backfill rows, which are that source's hourly *forecast*
 * re-filed as observations. Visual Crossing and OpenWeatherMap would score near-perfect by
 * construction.
 *
 * Pairs with a display-side setting that chooses *which field* on the resolved row to read
 * (native actual vs blended location). This object only chooses the row.
 */
object ActualsBaselineResolver {

    /**
     * Quality ranking, best first. Deliberately independent of the user's source order: that order
     * expresses display preference, not data quality, and a user whose list happens to start with
     * WeatherAPI should still be graded against NWS station observations when those exist.
     *
     * Decision recorded 2026-08-08. To grade strictly by the user's order instead, drop
     * [kindRank] from the comparator in [resolveBaselineSource] and keep only the position term —
     * `ActualsBaselineResolverTest.stationObservationOutranksEarlierProviderHistory` is the test
     * that pins the current behaviour and must fail if it is swapped.
     */
    private val KIND_QUALITY_ORDER = listOf(
        HistoricalDataKind.STATION_OBSERVATION,
        HistoricalDataKind.REANALYSIS_ARCHIVE,
        HistoricalDataKind.ARCHIVED_PROVIDER_HISTORY,
        HistoricalDataKind.RECENT_ANALYSIS,
    )

    /** True when the source has a genuine past-weather product, rather than only a forecast. */
    fun hasNativeActuals(source: WeatherSource): Boolean =
        source.historicalDataKind != HistoricalDataKind.NONE

    private fun kindRank(source: WeatherSource): Int =
        KIND_QUALITY_ORDER.indexOf(source.historicalDataKind)
            .takeIf { it >= 0 }
            ?: Int.MAX_VALUE

    /**
     * @param gradedSource the source whose forecast is being scored.
     * @param orderedVisibleSources the user's enabled sources, primary first
     *   (`WeatherSourcePreferences.visibleSources()`). Used only to break ties within a kind.
     * @param hasRowForDate whether that source has a `daily_history` row for the date in question
     *   at this location. A source with real actuals but no stored row cannot serve as a baseline.
     * @return the source to read the actual from, or null when nothing qualifies — in which case
     *   the day must be **excluded** from the statistics. Never fall back to [gradedSource]'s own
     *   row when it has no native actuals; that is the circularity this exists to prevent.
     */
    fun resolveBaselineSource(
        gradedSource: WeatherSource,
        orderedVisibleSources: List<WeatherSource>,
        hasRowForDate: (WeatherSource) -> Boolean,
    ): WeatherSource? {
        if (hasNativeActuals(gradedSource) && hasRowForDate(gradedSource)) return gradedSource

        return orderedVisibleSources
            .filter { it != gradedSource && hasNativeActuals(it) }
            .distinct()
            .sortedWith(
                compareBy(
                    { kindRank(it) },
                    { orderedVisibleSources.indexOf(it) },
                ),
            )
            .firstOrNull(hasRowForDate)
    }
}
