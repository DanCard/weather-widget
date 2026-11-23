package com.weatherwidget.shared.observations

import kotlin.math.ceil

/**
 * Sizes an observation-history backfill lookback to the actual gap instead of always pulling 72 h
 * across all five NWS stations (a 48-minute screen-off gap only needs ~3 h, not 72 h).
 *
 * plans/261123-fast-paint-first-forecast-sync-and-backfill-deferral.md
 */
object HourlyBackfillWindow {
    const val MIN_LOOKBACK_HOURS = 3L
    const val MAX_LOOKBACK_HOURS = 72L
    const val MARGIN_HOURS = 2L

    private val GAP_MIN_REGEX = Regex("(?:latest_gap_min|cloud_series_break_min)=(\\d+)")

    /**
     * Derives the lookback hours from a gap-decision [reason] string (e.g. `latest_gap_min=48`,
     * `no_nws_observations`). Falls back to [defaultHours] when no finite gap is present.
     */
    fun lookbackHours(reason: String, defaultHours: Long = MAX_LOOKBACK_HOURS): Long {
        if (reason.contains("no_nws_observations")) return defaultHours
        val matches = GAP_MIN_REGEX.findAll(reason).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()
        val maxGapMin = matches.maxOrNull() ?: return defaultHours
        return lookbackHoursForGapMinutes(maxGapMin, defaultHours)
    }

    fun lookbackHoursForGapMinutes(gapMinutes: Long, maxHours: Long = MAX_LOOKBACK_HOURS): Long {
        if (gapMinutes <= 0L) return MIN_LOOKBACK_HOURS.coerceAtMost(maxHours)
        val needed = ceil(gapMinutes / 60.0).toLong() + MARGIN_HOURS
        return needed.coerceIn(MIN_LOOKBACK_HOURS, maxHours)
    }
}
