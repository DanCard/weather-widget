package com.weatherwidget.shared.sourceview

import java.time.LocalDate
import java.time.ZoneId

/**
 * When the user last switched to each source — what [com.weatherwidget.data.remote.HourlyCadence]
 * measures view age from. ONE rule for Android and desktop.
 *
 * `source_last_view` keeps one timestamp per source, overwritten on every view: the last view, not a
 * history (`source_view_days` stays per-day with no timestamps; user, 2026-10-11). A source with no
 * timestamp yet — viewed only before the table existed — falls back to its newest `source_view_days`
 * day, taken as the **end** of that local day, which errs toward fetching.
 * performance/261011-hourly-cadence-by-last-view-and-fuzzy-full-window.md
 */
object SourceLastView {
    /** What [com.weatherwidget.data.remote.HourlyCadence] needs from the view tables, per platform read. */
    data class Recency(val lastViewedAtMs: Map<String, Long>, val inGrace: Boolean) {
        fun of(sourceId: String): Long? = lastViewedAtMs[sourceId]

        companion object {
            /** No view data (table unreadable): every never-viewed source gets the grace stand-in. */
            val UNKNOWN = Recency(emptyMap(), inGrace = true)
        }
    }

    /** Each of [sourceIds]' last view, and whether tracking is still in its first days. */
    fun recency(
        sourceIds: Collection<String>,
        timestamps: Map<String, Long>,
        rows: List<SourceViewDayRow>,
        trackingSince: LocalDate?,
        today: LocalDate,
        zone: ZoneId,
    ): Recency = Recency(
        lastViewedAtMs = sourceIds.mapNotNull { id -> resolve(id, timestamps, rows, zone)?.let { id to it } }.toMap(),
        inGrace = com.weatherwidget.data.remote.HourlyCadence.inGrace(trackingSince, today),
    )

    /** [sourceId]'s last view: its timestamp, else the end of its newest counted day, else null. */
    fun resolve(
        sourceId: String,
        timestamps: Map<String, Long>,
        rows: List<SourceViewDayRow>,
        zone: ZoneId,
    ): Long? = timestamps[sourceId] ?: newestDay(sourceId, rows)?.let { endOfDayMs(it, zone) }

    private fun newestDay(sourceId: String, rows: List<SourceViewDayRow>): LocalDate? =
        rows.filter { it.sourceId == sourceId && it.switches > 0 }.maxOfOrNull { it.date }

    fun endOfDayMs(date: LocalDate, zone: ZoneId): Long =
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
