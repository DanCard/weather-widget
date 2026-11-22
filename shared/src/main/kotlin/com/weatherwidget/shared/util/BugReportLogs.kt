package com.weatherwidget.shared.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Which `app_logs` rows a bug report attaches, and how they are written (`logs.tsv`), on both
 * platforms. See `plans/261121-bug-report-attachments.md`.
 *
 * Every row of the last [RECENT_WINDOW_MS] (where the symptom almost always is), plus [isKeyEvent]
 * rows from the whole retention window (where an earlier cause usually is: a location move, a
 * cancelled sync, a failed fetch). Measured on a Galaxy Z Fold4: 2 h ≈ 1,000 rows; a day of
 * WARN/ERROR ≈ 60 rows.
 */
object BugReportLogs {
    const val RECENT_WINDOW_MS: Long = 2 * 60 * 60 * 1000L
    const val KEY_EVENT_WINDOW_MS: Long = 72 * 60 * 60 * 1000L

    /** Levels that are always key events. */
    val KEY_EVENT_LEVELS: List<String> = listOf("WARN", "ERROR")

    /**
     * Sparse tags that mark state changes worth seeing hours later. Not the retention list
     * (`APP_LOG_PROTECTED_TAGS`), which keeps high-volume push/paint breadcrumbs.
     */
    val KEY_EVENT_TAGS: List<String> = listOf(
        "CONFIG",
        "NO_LOCATION",
        "LOCATION_CHANGE_REFRESH",
        "LOCATION_FETCH_PENDING",
        "LOCATION_MIGRATION",
        "TIMEZONE_CHANGE",
        "SYNC_CANCELLED",
        "NET_FETCH_START",
        "NET_FETCH_COMPLETE",
        "HISTORY_PRUNE",
        "PROC_EXIT",
        "COLD_START_PERF",
        "CLICK_WATCHDOG",
        HeaderFit.LOG_TAG,
        "SOURCE_ORDER",
        "SOURCE_LIST_EDIT",
        "SETTINGS_REFRESH",
        "POWER_CONNECTED_EVENT",
        "DB_CREATE",
        "DB_DESTRUCTIVE_MIGRATION",
    )

    /** Tags that record what the user tapped, for the summary's "Recent taps" lines. */
    val TAP_TAGS: List<String> = listOf(
        "INTERACTION_E2E",
        "CLICK_DAILY",
        "DAILY_NAV_ATTEMPT",
        "WIDGET_TOAST",
        "SET_VIEW_TIMING",
        "TOGGLE_VIEW_TIMING",
        "TOGGLE_API_TIMING",
        "SETTINGS_REFRESH",
    )

    data class Row(val epochMs: Long, val level: String, val tag: String, val message: String)

    enum class Part(val label: String) { RECENT("recent"), KEY("key") }

    data class Selected(val row: Row, val part: Part)

    fun isKeyEvent(row: Row): Boolean = row.level in KEY_EVENT_LEVELS || row.tag in KEY_EVENT_TAGS

    /**
     * The attached rows, oldest first: all rows at or after `now - RECENT_WINDOW_MS`, and key events
     * back to `now - KEY_EVENT_WINDOW_MS`. [rows] may hold more (a platform query can over-fetch);
     * anything outside the rule is dropped here so both platforms attach the same set.
     */
    fun select(rows: List<Row>, nowMs: Long): List<Selected> {
        val recentSince = nowMs - RECENT_WINDOW_MS
        val keySince = nowMs - KEY_EVENT_WINDOW_MS
        return rows
            .mapNotNull { row ->
                when {
                    row.epochMs >= recentSince -> Selected(row, Part.RECENT)
                    row.epochMs >= keySince && isKeyEvent(row) -> Selected(row, Part.KEY)
                    else -> null
                }
            }
            .sortedBy { it.row.epochMs }
    }

    /** Logged once per built report, both platforms, with [builtLine]. */
    const val BUILT_TAG = "BUG_REPORT_BUILT"

    /** `bytes=… recentRows=… keyRows=… images=… db=… entries=…`: what a report attached, for tuning the windows. */
    fun builtLine(zipBytes: Long, logs: List<Selected>, images: Int, db: Boolean, entries: List<String>?): String =
        "bytes=$zipBytes recentRows=${logs.count { it.part == Part.RECENT }} keyRows=${logs.count { it.part == Part.KEY }} " +
            "images=$images db=$db entries=${entries?.joinToString(",") ?: "none"}"

    const val TSV_HEADER = "epoch_ms\tlocal_time\tpart\tlevel\ttag\tmessage"

    private val localTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** One `logs.tsv` line. Backslash, tab, newline and CR in any field are escaped (`\\ \t \n \r`). */
    fun tsvLine(selected: Selected, zone: ZoneId): String {
        val r = selected.row
        val time = localTime.format(Instant.ofEpochMilli(r.epochMs).atZone(zone))
        return listOf(r.epochMs.toString(), time, selected.part.label, r.level, r.tag, r.message)
            .joinToString("\t") { escape(it) }
    }

    fun tsv(selected: List<Selected>, zone: ZoneId): String = buildString {
        append(TSV_HEADER).append('\n')
        selected.forEach { append(tsvLine(it, zone)).append('\n') }
    }

    internal fun escape(field: String): String =
        field.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    /**
     * The summary's tap lines, newest last: `21:06:41 INTERACTION_E2E action=ACTION_SHOW_TOAST …`,
     * each cut to [maxWidth] so mail clients don't wrap them. Only the [Part.RECENT] window: some
     * tap tags are also key events (`SETTINGS_REFRESH`), and days-old rows would read as recent
     * taps without a date (Fold4 report, 2026-11-22).
     */
    fun tapLines(selected: List<Selected>, zone: ZoneId, limit: Int = 10, maxWidth: Int = 76): List<String> {
        val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
        return selected
            .filter { it.part == Part.RECENT }
            .map { it.row }
            .filter { it.tag in TAP_TAGS }
            .sortedBy { it.epochMs }
            .takeLast(limit)
            .map { r ->
                val line = "${clock.format(Instant.ofEpochMilli(r.epochMs).atZone(zone))} ${r.tag} " +
                    r.message.replace(Regex("\\s+"), " ")
                if (line.length <= maxWidth) line else line.take(maxWidth - 1) + "…"
            }
    }
}
