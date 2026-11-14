package com.weatherwidget.shared.util

/**
 * Pure throttle decision rule for user-initiated refreshes (user, 2026-10-27).
 * A source refreshes at most once every [THROTTLE_MINUTES]: a request is refused when the source was
 * refreshed by the user *or fetched by anything* (popup open, cadence, Settings → Refresh data) within
 * the window (user, 2026-11-13: "limit data refreshes per source to once every 10 minutes max").
 * plans/261113-settings-refresh-skips-fresh-sources.md.
 */
object UserRefreshThrottle {
    const val THROTTLE_MINUTES = 10
    const val THROTTLE_MS = THROTTLE_MINUTES * 60_000L

    sealed interface Decision {
        object Allowed : Decision
        data class Throttled(
            val elapsedMinutes: Int,
            val remainingMinutes: Int,
        ) : Decision
    }

    /**
     * Checks if a user refresh should be allowed or throttled: the later of [lastRefreshMs] (the user's
     * last refresh of this source) and [lastFetchMs] (its last fetch; null where no fetch time applies,
     * e.g. the observations-only refresh). Within [THROTTLE_MS] returns [Decision.Throttled] with the
     * elapsed and remaining minutes. A missing, zero or future time does not count.
     */
    fun check(lastRefreshMs: Long?, nowMs: Long, lastFetchMs: Long?): Decision {
        val lastMs = latest(lastRefreshMs, lastFetchMs, nowMs) ?: return Decision.Allowed
        val elapsedMs = nowMs - lastMs
        if (elapsedMs >= THROTTLE_MS) {
            return Decision.Allowed
        }
        val elapsedMinutes = (elapsedMs / 60_000L).toInt()
        val remainingMinutes = ((THROTTLE_MS - elapsedMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        return Decision.Throttled(elapsedMinutes, remainingMinutes)
    }

    /** The later of [a] and [b] that is a real time no later than [nowMs]; null when neither is. */
    fun latest(a: Long?, b: Long?, nowMs: Long): Long? =
        listOfNotNull(a, b).filter { it in 1..nowMs }.maxOrNull()

    /**
     * Formats the singular/plural string for minutes (e.g. "1 minute", "3 minutes").
     */
    fun formatMinutes(minutes: Int): String =
        if (minutes == 1) "1 minute" else "$minutes minutes"

    /**
     * Formats the English rate limit text:
     * "Not doing because last refresh was X ago, and wait another Y before asking for a refresh"
     */
    fun formatMessage(elapsedMinutes: Int, remainingMinutes: Int): String {
        val xStr = formatMinutes(elapsedMinutes)
        val yStr = formatMinutes(remainingMinutes)
        return "Not doing because last refresh was $xStr ago, and wait another $yStr before asking for a refresh"
    }
}
