package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pure logic for the "no hourly data" day-tap flow, shared between Android and desktop.
 *
 * Android uses this after querying the DB (it checks in-DB rows). Desktop uses this against the
 * already-loaded [RawFetch.hourly] list — no DB access needed.
 */
object NoHourlyChecker {

    const val GENERIC_GAP_SOURCE = "GENERIC_GAP"

    /** How long the "no hourly" banner stays visible before auto-dismissing. */
    const val MESSAGE_DURATION_MS = 8_000L

    /** A line needs two points: fewer distinct hours than this draw nothing. */
    const val MIN_DRAWABLE_HOURS = 2

    /**
     * True if [hourly] has ≥[MIN_DRAWABLE_HOURS] distinct hours in [date] — only the part of it
     * inside [view] when given (the hours the graph draws). A day [view] does not reach counts as
     * covered: none of it is on screen.
     *
     * Distinct hours, not rows: NWS data ending at Sun 00:00 held several rows for that one hour, so
     * a WIDE 03:00–21:00 view opened blank with no word (Fold 4, 2026-11-15).
     *
     * If [sourceIds] is non-null, only points whose source is in the set (or [GENERIC_GAP_SOURCE])
     * count. Pass null to count any source.
     */
    fun hasHourlyForDay(
        hourly: List<HourlyForecast>,
        date: LocalDate,
        sourceIds: Set<String>? = null,
        view: HourSpan? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        var startMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        // Exclusive end: the next midnight belongs to the next day.
        var endMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1
        if (view != null) {
            startMs = maxOf(startMs, view.start.atZone(zoneId).toInstant().toEpochMilli())
            endMs = minOf(endMs, view.end.atZone(zoneId).toInstant().toEpochMilli())
            if (endMs < startMs) return true
        }
        return distinctHours(hourly, startMs, endMs, sourceIds) >= MIN_DRAWABLE_HOURS
    }

    /**
     * The day to name on a graph that will draw nothing, or null. Only a [view] starting at or after
     * the current hour: an earlier one can draw observations without any forecast. The day is the
     * view's centre's.
     */
    fun emptyViewDay(
        hourly: List<HourlyForecast>,
        view: HourSpan,
        sourceIds: Set<String>?,
        now: java.time.LocalDateTime,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): LocalDate? {
        if (view.start.isBefore(now.truncatedTo(java.time.temporal.ChronoUnit.HOURS))) return null
        val startMs = view.start.atZone(zoneId).toInstant().toEpochMilli()
        val endMs = view.end.atZone(zoneId).toInstant().toEpochMilli()
        if (distinctHours(hourly, startMs, endMs, sourceIds) >= MIN_DRAWABLE_HOURS) return null
        val halfHours = java.time.Duration.between(view.start, view.end).toHours() / 2
        return view.start.plusHours(halfHours).toLocalDate()
    }

    /** Logged when a graph draws the [emptyViewDay] notice, both platforms. */
    const val EMPTY_NOTICE_TAG = "HOURLY_EMPTY_NOTICE"

    /** The [EMPTY_NOTICE_TAG] line; [prefix] carries the platform's own fields (Android: the widget). */
    fun emptyNoticeLine(sourceId: String, view: HourSpan, message: String, prefix: String = ""): String =
        "${if (prefix.isNotEmpty()) "$prefix " else ""}source=$sourceId view=${view.start}..${view.end} -> \"$message\""

    /** Distinct hours of [sourceIds] (null: any) points in [startMs]..[endMs], both inclusive. */
    private fun distinctHours(hourly: List<HourlyForecast>, startMs: Long, endMs: Long, sourceIds: Set<String>?): Int =
        hourly.asSequence()
            .filter { it.dateTime in startMs..endMs }
            .filter { sourceIds == null || it.source == null || it.source in sourceIds || it.source == GENERIC_GAP_SOURCE }
            .map { Math.floorDiv(it.dateTime, HOUR_MS) }
            .distinct()
            .count()

    private const val HOUR_MS = 3_600_000L

    /** Formats [date] as "EEE MMM d" — e.g. "Tue Jul 7". */
    fun formatDayLabel(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault()))

    /** Formats an epoch-millis timestamp as "EEE MMM d 'at' h a" — e.g. "Mon Jul 6 at 4 PM". */
    fun formatEndLabel(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEE MMM d 'at' h a", Locale.getDefault()))

    /**
     * The latest hourly point's "data ends" label, or null if no future-facing points exist.
     * Only looks at points between now and 40 days out. If [sourceIds] is non-null, filters to those.
     */
    fun lastHourlyEndLabel(
        hourly: List<HourlyForecast>,
        sourceIds: Set<String>? = null,
    ): String? {
        val now = System.currentTimeMillis()
        val horizon = now + TimeUnit.DAYS.toMillis(40)
        val relevant = hourly
            .filter { it.dateTime in now..horizon }
            .let { all -> if (sourceIds != null) all.filter { it.source in sourceIds } else all }
        val lastMs = relevant.maxOfOrNull { it.dateTime } ?: return null
        return formatEndLabel(lastMs)
    }

    /**
     * Banner message shown on the daily view when the tapped day has no hourly data.
     * E.g. "No hourly forecast for Tue Jul 7 — data ends Mon Jul 6 at 4 PM"
     */
    fun buildMessage(dayLabel: String, endLabel: String?): String =
        if (endLabel != null) "No hourly forecast for $dayLabel — data ends $endLabel"
        else "No hourly forecast for $dayLabel"

    /** Phase-1 banner over the tapped day's (empty) hourly graph while its fetch runs. */
    fun buildPendingMessage(dayLabel: String): String = "Fetching hourly forecast for $dayLabel…"

    /** Phase-2 banner: shown after the refresh completes, reporting whether data arrived. */
    fun buildResultMessage(dayLabel: String, hasHourly: Boolean, endLabel: String?): String =
        if (hasHourly) "Results of refresh:\nHourly data now available for $dayLabel"
        else if (endLabel != null) "Results of refresh:\nNo hourly data for $dayLabel — data ends $endLabel"
        else "Results of refresh:\nNo hourly data for $dayLabel"
}
