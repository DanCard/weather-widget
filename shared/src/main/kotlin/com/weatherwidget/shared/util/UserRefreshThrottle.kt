package com.weatherwidget.shared.util

/**
 * Pure throttle decision rule for user-initiated refreshes (user, 2026-10-27).
 * User refreshes are limited to at most once every [THROTTLE_MINUTES].
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
     * Checks if a user refresh should be allowed or throttled based on [lastRefreshMs].
     * If within [THROTTLE_MS], returns [Decision.Throttled] with the elapsed and remaining minutes.
     */
    fun check(lastRefreshMs: Long?, nowMs: Long): Decision {
        if (lastRefreshMs == null || lastRefreshMs <= 0L || nowMs < lastRefreshMs) {
            return Decision.Allowed
        }
        val elapsedMs = nowMs - lastRefreshMs
        if (elapsedMs >= THROTTLE_MS) {
            return Decision.Allowed
        }
        val elapsedMinutes = (elapsedMs / 60_000L).toInt()
        val remainingMinutes = ((THROTTLE_MS - elapsedMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        return Decision.Throttled(elapsedMinutes, remainingMinutes)
    }

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
