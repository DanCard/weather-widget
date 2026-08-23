package com.weatherwidget.shared.actuals

import java.time.LocalDate
import java.time.ZoneId

/**
 * Decides whether a day's observations actually cover the day, so a truncated window's minimum is
 * never presented as the day's observed low.
 *
 * **Why this is not the existing coverage check.** The sparse-history self-heal measures *gaps
 * between consecutive observations*. A window that begins at noon has no gaps at all — it is
 * perfectly dense over the half-day it covers — so gap density is structurally blind to a truncated
 * *start*. Samsung 2026-08-22: a GPS excursion promoted a site whose rows began at 12:00, and the
 * heal reported `reason=coverage_ok latest_gap_min=19 max_gap_min=10` while the today column showed
 * 66.52° (the noon reading) in place of the real 57.03° overnight low.
 *
 * The daily minimum is the vulnerable statistic. A late start removes the coldest hours of the day
 * — the pre-dawn ones — so the surviving minimum is not merely imprecise, it is a different
 * quantity ("lowest since we started watching") wearing the label of the day's low. The maximum
 * degrades far more gracefully, which is why only the low is gated here.
 *
 * See plans/260822-today-low-backfill-then-forecast-fallback.md.
 */
object TodayActualsCoverage {
    /**
     * How late the first observation of the day may be and still be treated as covering the
     * overnight minimum.
     *
     * An hour is comfortably inside the pre-dawn plateau — the daily low typically lands near
     * sunrise, hours after midnight — so a fetch that first lands at 00:45 has not missed it. It is
     * also far short of the failure this guards against, where coverage began at noon.
     */
    const val DAY_START_GRACE_MINUTES = 60L

    /**
     * True when [obsTimestampsMs] do not reach back to the start of [date], i.e. the day's low has
     * not been observed and any minimum drawn from these rows would be a fabricated actual.
     *
     * Callers should respond by backfilling and, until that lands, passing a null actual low so the
     * forecast low renders instead (`DailyDayValueResolver.resolveTodayLineValues`).
     *
     * An empty list counts as uncovered: nothing was observed, so nothing can be claimed.
     */
    fun dayStartUncovered(
        obsTimestampsMs: List<Long>,
        date: LocalDate,
        zone: ZoneId,
    ): Boolean {
        if (obsTimestampsMs.isEmpty()) return true
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val deadlineMs = dayStartMs + DAY_START_GRACE_MINUTES * 60_000L
        // Rows from a previous day (blend context padding) do not count as covering this day's
        // start, but they must not disqualify it either — hence the lower bound.
        val earliestOnDay = obsTimestampsMs.filter { it >= dayStartMs }.minOrNull() ?: return true
        return earliestOnDay > deadlineMs
    }
}
