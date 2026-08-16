package com.weatherwidget.shared.observations

/**
 * Decides what the stations list shows when the recent window is empty, shared by the Android
 * Current Observations screen and the desktop Observations window.
 *
 * The screen's normal query is bounded to [RECENT_WINDOW_MS]. When that returns nothing for the
 * displayed source, a blank list plus "No recent observations found" is the least useful of the
 * available answers: the DB usually still holds *something* for that source, and how old it is, is
 * exactly the diagnostic the screen exists to surface. So the caller re-runs its query unbounded and
 * hands both results here.
 *
 * Deliberately NOT a fetch trigger. Reaching further back in the DB costs nothing; a network fetch
 * on every empty render costs battery and, when the empty list is caused by a scoping bug rather
 * than missing data, retries forever without changing the outcome (2026-08-15, Samsung Fold — see
 * `plans/260815-observations-empty-list-stale-location-scope-opus.md`).
 *
 * Also deliberately NOT cross-source: [older] must already be filtered to the displayed source, so
 * an empty NWS list can never be papered over with Open-Meteo rows.
 */
object StaleObservationFallback {

    /** The bounded window the screen queries first. */
    const val RECENT_WINDOW_MS = 24L * 60L * 60L * 1000L

    /**
     * [rows] are what to render. [ageMs] is null when they came from the recent window (render the
     * normal subtitle) and non-null when they are the older fallback — in which case it is the age
     * of the *newest* row shown, and the subtitle must say so.
     */
    data class Outcome<T>(val rows: List<T>, val ageMs: Long?)

    /**
     * [recent] is the in-window result, [older] the same query with no lower time bound (so it is a
     * superset). Both must already carry the caller's source filter.
     */
    fun <T> resolve(
        recent: List<T>,
        older: List<T>,
        nowMs: Long,
        timestampOf: (T) -> Long,
    ): Outcome<T> {
        if (recent.isNotEmpty()) return Outcome(recent, null)
        val newest = older.maxOfOrNull(timestampOf) ?: return Outcome(emptyList(), null)
        // A clock that moved backwards must not render "-4h ago"; treat the row as brand new and let
        // the timestamp column carry the oddity.
        return Outcome(older, (nowMs - newest).coerceAtLeast(0L))
    }

    /**
     * Compact age for the stale subtitle: "45min", "6h", "3d". Matches the abbreviation style
     * `ForecastHistoryActivity.formatRelativeTime` already uses, so the two age labels in the app
     * read the same; the surrounding "… ago" sentence is a localized resource on each platform.
     */
    fun formatAge(ageMs: Long): String {
        val minutes = ageMs.coerceAtLeast(0L) / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            minutes < 60L -> "${minutes}min"
            hours < 24L -> "${hours}h"
            else -> "${days}d"
        }
    }
}
