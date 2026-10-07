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

    /** A past day's right bar. [isFallback]: a side is not a stored forecast — drawn dashed. */
    data class Resolved(val high: Float, val low: Float, val isFallback: Boolean)

    /**
     * The right bar of a past day's triple bar. First match wins:
     * 1. [frozenHigh]/[frozenLow] (`daily_history.forecast*`, the settled rule), both present;
     * 2. [pick] over [candidates] — the newest row with a pair;
     * 3. per side: the frozen value, else the newest row carrying that side (both real), else the
     *    **earliest** [hindcastHigh]/[hindcastLow] (what the source sent after its
     *    [SameDayExtremeCutoff] cutoff; the earliest is closest to a forecast), else the day's hourly
     *    forecast max/min ([hourlyTemps]) — the value the live column drew that day. The last two are
     *    fallbacks.
     *
     * Null unless both sides resolve (half a bar is never drawn). User's call, 2026-10-07: a source
     * first fetched mid-day had no low for that day, and its past column lost the bar it showed live.
     * See plans/261007-keep-hindcast-extremes-and-dashed-past-forecast-fallback.md.
     */
    /**
     * The [resolve] `hourlyTemps` input: one day's hourly rows that were **forecasts** — fetched
     * before the hour they describe. Google's history hours (and any re-filed past hour) are fetched
     * after the fact; they are observations and must not stand in for a forecast low (desktop drew a
     * Google bar for 2026-10-05 from them, a day Google never forecast). Callers pass the display
     * source's rows for that local date. An unknown fetch time (0, rows built straight from an API
     * response) is not evidence of a forecast either.
     */
    fun <T> forecastHourlyTemps(
        rows: List<T>,
        dateTime: (T) -> Long,
        fetchedAt: (T) -> Long,
        temperature: (T) -> Float,
    ): List<Float> = rows.filter { fetchedAt(it) in 1 until dateTime(it) }.map(temperature)

    fun <T> resolve(
        frozenHigh: Float?,
        frozenLow: Float?,
        candidates: List<T>,
        high: (T) -> Float?,
        low: (T) -> Float?,
        fetchedAt: (T) -> Long,
        hindcastHigh: (T) -> Float? = { null },
        hindcastLow: (T) -> Float? = { null },
        hourlyTemps: List<Float> = emptyList(),
    ): Resolved? {
        if (frozenHigh != null && frozenLow != null) return Resolved(frozenHigh, frozenLow, isFallback = false)
        pick(candidates, high, low, fetchedAt)?.let { return Resolved(high(it)!!, low(it)!!, isFallback = false) }

        fun side(frozen: Float?, main: (T) -> Float?, hindcast: (T) -> Float?, hourly: Float?): Pair<Float, Boolean>? =
            frozen?.let { it to false }
                ?: candidates.filter { main(it) != null }.maxByOrNull(fetchedAt)?.let { main(it)!! to false }
                ?: candidates.filter { hindcast(it) != null }.minByOrNull(fetchedAt)?.let { hindcast(it)!! to true }
                ?: hourly?.let { it to true }

        val h = side(frozenHigh, high, hindcastHigh, hourlyTemps.maxOrNull()) ?: return null
        val l = side(frozenLow, low, hindcastLow, hourlyTemps.minOrNull()) ?: return null
        return Resolved(h.first, l.first, isFallback = h.second || l.second)
    }

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
