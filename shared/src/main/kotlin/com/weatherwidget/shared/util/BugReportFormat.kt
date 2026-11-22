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
}
