package com.weatherwidget.data.model

/**
 * How old the last forecast made before a period may be and still stand for it, so that a later
 * fetch (a hindcast) cannot replace it. A prior forecast older than this counts as missing.
 *
 * User, 2026-11-13: "don't allow hindcasts unless prior data is missing", with the max age as a
 * **temporary** setting for experimentation (default 6 h; 0 = always take the newest fetch). Each
 * platform copies its setting here at startup and on change. When the experiment ends, delete the
 * setting and hard-code [maxPriorAgeMs]. See plans/261113-no-hindcasts-unless-prior-missing.md.
 */
object HindcastGuard {
    const val DEFAULT_MAX_PRIOR_AGE_HOURS = 6
    const val MAX_PRIOR_AGE_HOURS_LIMIT = 48
    private const val HOUR_MS = 3_600_000L

    @Volatile
    var maxPriorAgeMs: Long = DEFAULT_MAX_PRIOR_AGE_HOURS * HOUR_MS
        private set

    fun setMaxPriorAgeHours(hours: Int) {
        maxPriorAgeMs = hours.coerceIn(0, MAX_PRIOR_AGE_HOURS_LIMIT) * HOUR_MS
    }

    val maxPriorAgeHours: Int get() = (maxPriorAgeMs / HOUR_MS).toInt()

    /** The desktop slider's value line (Android: `R.string.hindcast_guard_value` / `_off`). */
    fun formatMaxPriorAge(hours: Int): String =
        if (hours == 0) "Off — past hours take the newest fetch" else "Max forecast age $hours h"

    /** 0 h turns the guard off: every period takes the newest fetch. */
    val enabled: Boolean get() = maxPriorAgeMs > 0
}
