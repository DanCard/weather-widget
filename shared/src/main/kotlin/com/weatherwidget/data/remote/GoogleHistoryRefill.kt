package com.weatherwidget.data.remote

import com.weatherwidget.data.model.HourlyForecast
import java.time.LocalDate
import java.time.ZoneId

/**
 * Google's `history/hours` refilling elapsed hours whose stored forecast went stale — only when
 * the user taps refresh on the Forecast History screen while it shows a **previous day** (user,
 * 2026-10-10), and only that day's hours. Refresh on today or a future day, Settings → "Refresh
 * data", and every automatic fetch never refill.
 *
 * `forecast/hours` starts at the current hour, so once an hour passes no forecast fetch can touch
 * it again: its live row is whatever the last fetch before it said. On 2026-10-09 the project's
 * hourly quota ran out at 05:20 PT, every hour of that day kept the 04:54 forecast (peak 71.7)
 * while `forecast/days` moved the day's high to 69.5, and refresh could not fix it.
 * `history/hours` is the only Google product that still reaches those hours (24 h back).
 *
 * This narrows, for Google only, the rule that elapsed values never overwrite a forecast
 * ([com.weatherwidget.data.model.ElapsedForecastBackfill]): an hour whose live row was fetched
 * more than [STALE_LEAD_MS] before it began was never kept current, so the user's refresh may
 * replace it with Google's estimate of what the hour was. Only the live row is rewritten; the
 * `hourly_forecast_history` snapshots (the as-issued record) are left alone.
 *
 * No daily budget: the user chooses when to refresh, and a refill stamps the rows fetched now, so
 * the next refresh of that day finds nothing stale and requests nothing. The window is 24 h, so in
 * practice the refillable day is yesterday, and only its hours still inside the window.
 * See plans/261010-google-history-refills-stale-elapsed-hours.md.
 */
object GoogleHistoryRefill {
    private const val HOUR_MS = 3_600_000L

    /** 6 h = the slowest displayed charging cadence (360 min); routine fetching never leaves an hour this stale. */
    const val STALE_LEAD_MS = 6 * HOUR_MS

    /**
     * The day a Forecast History refresh may refill: the viewed day when it is before [today], else
     * null (today and future days are still covered by `forecast/hours`).
     */
    fun refillDay(viewed: LocalDate, today: LocalDate): LocalDate? = viewed.takeIf { it.isBefore(today) }

    /**
     * Hour starts inside [GoogleWeatherApi.historyWindow] — and inside [day] when given — whose
     * newest [storedLive] row (this source's live rows at the site) was fetched more than
     * [STALE_LEAD_MS] before the hour began, or that have no row at all.
     */
    fun staleHours(
        storedLive: List<HourlyForecast>,
        nowMs: Long,
        day: LocalDate? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Set<Long> {
        val window = GoogleWeatherApi.historyWindow(nowMs)
        val dayRange = day?.let {
            it.atStartOfDay(zoneId).toInstant().toEpochMilli() until it.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        }
        val newestFetchByHour = storedLive
            .filter { it.dateTime in window }
            .groupBy { it.dateTime }
            .mapValues { (_, rows) -> rows.maxOf { it.fetchedAt } }
        val firstHour = window.first + Math.floorMod(-window.first, HOUR_MS)
        return generateSequence(firstHour) { it + HOUR_MS }
            .takeWhile { it in window }
            .filter { dayRange == null || it in dayRange }
            .filter { hour -> newestFetchByHour[hour]?.let { hour - it > STALE_LEAD_MS } ?: true }
            .toSet()
    }

    /**
     * Whether a fetch requests `history/hours`: a site with no history coverage at all
     * ([needsHistory], any fetch, unchanged), or a refresh of a previous day ([refillDay]) whose
     * [staleHours] (that day's) are not empty.
     */
    fun shouldRequest(needsHistory: Boolean, staleHours: Set<Long>, refillDay: LocalDate?): Boolean =
        needsHistory || (refillDay != null && staleHours.isNotEmpty())

    /** The [history] rows to write into the live table: only [staleHours], one per hour. */
    fun select(history: List<HourlyForecast>, staleHours: Set<Long>): List<HourlyForecast> =
        history.filter { it.dateTime in staleHours }
            .associateBy { it.dateTime }
            .values
            .sortedBy { it.dateTime }

    /** `GOOGLE_HISTORY_REFILL` log message. */
    fun logMessage(day: LocalDate, stale: Set<Long>, refilled: List<HourlyForecast>): String =
        "day=$day stale=${stale.size} refilled=${refilled.size} " +
            "firstHour=${refilled.firstOrNull()?.dateTime} lastHour=${refilled.lastOrNull()?.dateTime}"

    const val LOG_TAG = "GOOGLE_HISTORY_REFILL"
}
