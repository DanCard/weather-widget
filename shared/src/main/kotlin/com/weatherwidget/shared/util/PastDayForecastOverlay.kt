package com.weatherwidget.shared.util

/**
 * Which stored forecast a past day's overlay (yellow) bar draws when `daily_history` has no frozen
 * overlay for it. Shared by Android (`DailyPastDayResolver`, the text-only `DailyViewLogic` path) and
 * desktop (`DesktopDailyForecastModel`), which had three copies with two different rules.
 *
 * 1. Newest row with a real range (high AND low, high != low).
 * 2. Else newest row with high AND low, even collapsed — draw what the day has rather than nothing.
 * 3. Else none. A high-only or low-only row would draw half a bar.
 *
 * Step 1 is desktop's old filter, now on Android too: NWS rows for 2026-09-02/03 had collapsed to
 * 74/74, and Android drew them as zero-height bars where an older 74/56 row existed.
 * See plans/261002-share-past-day-forecast-overlay.md.
 */
object PastDayForecastOverlay {

    fun <T> pick(
        candidates: List<T>,
        high: (T) -> Float?,
        low: (T) -> Float?,
        fetchedAt: (T) -> Long,
    ): T? {
        val complete = candidates.filter { high(it) != null && low(it) != null }
        return complete.filter { high(it) != low(it) }.maxByOrNull(fetchedAt)
            ?: complete.maxByOrNull(fetchedAt)
    }
}
