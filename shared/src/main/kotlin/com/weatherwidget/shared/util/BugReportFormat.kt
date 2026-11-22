package com.weatherwidget.shared.util

import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Bug-report lines shared by the Android report and the desktop mailto body.
 *
 * Log timestamps in a report are device-local. The timezone line lets the reader line them up with
 * the email `Date:` header and with a host-side `sqlite3 ... 'localtime'` query, which silently
 * returns no rows when the host and device zones differ.
 */
object BugReportFormat {
    fun timezoneLine(now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): String {
        val offset = now.offset.id.let { if (it == "Z") "+00:00" else it }
        return "Timezone: ${now.zone.id} (UTC$offset)"
    }

    const val SUBJECT_PREFIX = "Weather widget bug report"
    private const val SUBJECT_DESCRIPTION_CHARS = 40

    /**
     * `Weather widget bug report: back button doesn't work (26111801)`, so reports are told apart in
     * the inbox. The description's first line, cut to 40 characters; prefix only when it is blank.
     */
    fun subject(description: String, version: String): String {
        val firstLine = description.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val short = if (firstLine.length <= SUBJECT_DESCRIPTION_CHARS) {
            firstLine
        } else {
            firstLine.take(SUBJECT_DESCRIPTION_CHARS - 1).trimEnd() + "…"
        }
        return if (short.isEmpty()) "$SUBJECT_PREFIX ($version)" else "$SUBJECT_PREFIX: $short ($version)"
    }
}
