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

    /**
     * True if [hourly] has ≥2 data points within [date]'s midnight→midnight window.
     *
     * If [sourceIds] is non-null, only points whose source is in the set (or [GENERIC_GAP_SOURCE])
     * count. Pass null to count any source.
     */
    fun hasHourlyForDay(
        hourly: List<HourlyForecast>,
        date: LocalDate,
        sourceIds: Set<String>? = null,
    ): Boolean {
        val zoneId = ZoneId.systemDefault()
        val startMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val inWindow = hourly.filter { it.dateTime in startMs until endMs }
        val matching = if (sourceIds != null) {
            inWindow.filter {
                it.source == null || it.source in sourceIds || it.source == GENERIC_GAP_SOURCE
            }
        } else {
            inWindow
        }
        return matching.size >= 2
    }

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

    /** Phase-1 banner: shown immediately on tap before the refresh result is known. */
    fun buildPendingMessage(dayLabel: String): String =
        "Hourly data missing for $dayLabel\nA refresh will be triggered"

    /** Phase-2 banner: shown after the refresh completes, reporting whether data arrived. */
    fun buildResultMessage(dayLabel: String, hasHourly: Boolean, endLabel: String?): String =
        if (hasHourly) "Results of refresh:\nHourly data now available for $dayLabel"
        else if (endLabel != null) "Results of refresh:\nNo hourly data for $dayLabel — data ends $endLabel"
        else "Results of refresh:\nNo hourly data for $dayLabel"
}
