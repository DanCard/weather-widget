package com.weatherwidget.widget

/**
 * Reconciles the source filter used to build a [DailyActualsBySource] map against the sources a
 * repaint is actually about to display.
 *
 * [com.weatherwidget.data.repository.DailyActualsStore.getDailyActualsWithLiveToday] hard-filters
 * both the `daily_history` past extremes and today's observations down to a caller-supplied source
 * list — that filter is load-bearing for battery and latency (the unfiltered aggregation runs
 * ~600ms on a device with several sources enabled), so it cannot simply be dropped.
 *
 * The hazard is that [WeatherWidgetWorker] loads the map once per run but reads each widget's
 * display source freshly at paint time. Toggling the API source enqueues a forced refresh, so a
 * second toggle can land while that run is still in flight — and the repaint then renders a source
 * whose actuals were filtered away, wiping the past-day actual bars until some later render
 * restores them. See `plans/260801-silurian-history-actuals-stale-source-race.md`.
 *
 * Coverage is measured against the *filter set that was used*, never against the loaded map's keys.
 * A source with genuinely no observations yet legitimately produces no entry, and comparing against
 * keys would make every run pay for a pointless reload.
 */
internal object DailyActualsCoverage {

    /**
     * Source ids the repaint needs that were excluded from the load, in [paintSourceIds] order.
     * Empty when the load already covers everything on screen — the overwhelmingly common case.
     */
    fun uncoveredSources(
        paintSourceIds: Collection<String>,
        loadedForSourceIds: Collection<String>,
    ): List<String> {
        val loaded = loadedForSourceIds.toSet()
        return paintSourceIds.distinct().filter { it !in loaded }
    }

    /**
     * Filter set for a repair reload: everything already loaded plus everything about to be
     * painted. The original sources are retained so widgets that did *not* change source keep their
     * actuals in the same reload.
     */
    fun unionSourceIds(
        paintSourceIds: Collection<String>,
        loadedForSourceIds: Collection<String>,
    ): List<String> = (loadedForSourceIds + paintSourceIds).distinct()
}
