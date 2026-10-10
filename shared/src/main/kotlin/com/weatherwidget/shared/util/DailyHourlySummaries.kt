package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import java.time.LocalDate
import java.time.ZoneId

/**
 * The three numbers the daily forecast view reads from hourly rows — noon cloud and the 8am–8pm /
 * 8pm–8am rain maxima — kept on the daily `forecasts` row so hourly need only be stored to 72 h
 * (`performance/261010-daily-view-summaries-instead-of-far-hourly.md`). One rule for Android and
 * desktop.
 *
 * Written from a fetch's whole hourly download before the 72 h trim. A field is taken only when the
 * rows **cover its whole window**; otherwise the previous row's value is kept ([carryForward]) —
 * a fetch that does not reach a day never blanks what an earlier one saved (user, 2026-10-10).
 */
object DailyHourlySummaries {
    private const val HOUR_MS = 3_600_000L

    data class Summary(
        val noonCloudPercent: Int? = null,
        val dayPrecipMax: Int? = null,
        val nightPrecipMax: Int? = null,
    ) {
        val isEmpty: Boolean get() = noonCloudPercent == null && dayPrecipMax == null && nightPrecipMax == null
    }

    /**
     * True when [rows] span [startMs] until [endMs] at their own step: the first row at or before
     * the window's first slot and the last at or after its last. The step is the series' smallest
     * gap — 1 h, or 3 h for OWM's free `/forecast` — so a 3-hourly 09…18 series covers 08:00–20:00.
     * A payload that starts mid-window (today's elapsed morning) or ends inside it does not.
     */
    fun windowCovered(rows: List<HourlyForecast>, startMs: Long, endMs: Long): Boolean {
        val times = rows.asSequence().map { it.dateTime }.distinct().sorted().toList()
        if (times.isEmpty()) return false
        val step = times.zipWithNext { a, b -> b - a }.filter { it > 0 }.minOrNull() ?: HOUR_MS
        val first = times.firstOrNull { it >= startMs - step + 1 } ?: return false
        val last = times.lastOrNull { it < endMs } ?: return false
        return first <= startMs + step - HOUR_MS && last >= endMs - step && first <= last
    }

    /**
     * [date]'s summary from [rows] — **one source at one site** (a download, or one site's stored
     * rows). Each field is null when its window is not covered.
     */
    fun forDate(
        rows: List<HourlyForecast>,
        date: LocalDate,
        sourceId: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Summary {
        val dayStart = date.atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()
        val dayEnd = date.atTime(20, 0).atZone(zoneId).toInstant().toEpochMilli()
        val nightEnd = date.plusDays(1).atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()
        val maxima = DailyRainLabels.periodMaxima(rows, date, zoneId)
        return Summary(
            noonCloudPercent = DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(
                hourly = rows.map { if (it.source == null) it.copy(source = sourceId) else it },
                date = date,
                displaySourceId = sourceId,
                rowSourceId = sourceId,
                zone = zoneId,
            ),
            dayPrecipMax = maxima.dayMax.takeIf { windowCovered(rows, dayStart, dayEnd) },
            nightPrecipMax = maxima.nightMax.takeIf { windowCovered(rows, dayEnd, nightEnd) },
        )
    }

    /**
     * [primary] (this fetch's download) per field, else [secondary] (the site's stored rows, which
     * hold hours a Google one-page fetch left in place) — each judged on its own coverage.
     */
    fun forDate(
        primary: List<HourlyForecast>,
        secondary: List<HourlyForecast>,
        date: LocalDate,
        sourceId: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Summary = carryForward(
        incoming = forDate(primary, date, sourceId, zoneId),
        prior = if (secondary.isEmpty()) null else forDate(secondary, date, sourceId, zoneId),
    )

    /** Per field: [incoming] when present, else [prior]'s. Never null over a value. */
    fun carryForward(incoming: Summary, prior: Summary?): Summary {
        if (prior == null) return incoming
        return Summary(
            noonCloudPercent = incoming.noonCloudPercent ?: prior.noonCloudPercent,
            dayPrecipMax = incoming.dayPrecipMax ?: prior.dayPrecipMax,
            nightPrecipMax = incoming.nightPrecipMax ?: prior.nightPrecipMax,
        )
    }

    /**
     * The day/night rain % the daily view shows for a live (today/future) day: the forecast row's
     * stored maximum, else the hourly window max as before (rows written before these columns
     * existed), else null — the caller then falls back to the provider's period value.
     *
     * The row comes first because it is never older than the hours: every fetch writes both, from
     * one download, and computes the row from it whole. Hours past the 72 h routine store are not
     * rewritten by routine fetches (an on-demand fetch's, or rows kept from before 2026-10-10), so
     * reading hourly first would let them override a fresher row.
     */
    fun liveDayNight(
        sitedRows: List<HourlyForecast>,
        date: LocalDate,
        storedDayMax: Int?,
        storedNightMax: Int?,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): DailyRainLabels.DayNightPrecip {
        val maxima = if (storedDayMax != null && storedNightMax != null) null else DailyRainLabels.periodMaxima(sitedRows, date, zoneId)
        return DailyRainLabels.DayNightPrecip(
            dayMax = storedDayMax ?: maxima?.dayMax,
            nightMax = storedNightMax ?: maxima?.nightMax,
        )
    }

    /** Hours to keep in live and snapshot storage from a fetch made at [nowMs]: up to its horizon. */
    fun keepUntilMs(nowMs: Long, hoursAhead: Int): Long =
        (nowMs / HOUR_MS) * HOUR_MS + hoursAhead * HOUR_MS
}
