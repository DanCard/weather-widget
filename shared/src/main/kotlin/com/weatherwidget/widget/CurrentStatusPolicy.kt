package com.weatherwidget.widget

import com.weatherwidget.data.model.CurrentStatus
import com.weatherwidget.data.model.ForecastSnapshot

/**
 * Pure JVM policy governing staleness and fallback rules for [CurrentStatus].
 *
 * Single-owner resolution: the daemon publishes a resolved [CurrentStatus] to SQLite every
 * `STATUS_TICK_MS` (120s) for the active display source. When the user switches display source,
 * or on fresh launch before the daemon publishes, the database may hold stale rows from previous
 * sessions or inactive sources.
 *
 * This policy ensures:
 * 1. A [CurrentStatus] older than [MAX_AGE_MS] is rejected as stale.
 * 2. When published status is null or stale, the UI and panel cleanly fall back to
 *    [ForecastSnapshot.resolved] (which [CurrentTempRun] resolves freshly in memory).
 */
object CurrentStatusPolicy {

    /**
     * Maximum age in milliseconds for a [CurrentStatus] to be considered fresh.
     * Set to 5 minutes (300,000 ms), which accommodates 2.5× the 120s status tick while
     * preventing hours-old rows from inactive sources from being displayed.
     */
    const val MAX_AGE_MS: Long = 300_000L

    /**
     * Allow up to 10 seconds of clock skew for timestamps slightly in the future.
     */
    const val CLOCK_SKEW_TOLERANCE_MS: Long = 10_000L

    /**
     * Checks if [status] was updated recently relative to [nowMs].
     * Returns false if [status] is null, older than [maxAgeMs], or in the future beyond clock skew tolerance.
     */
    fun isFresh(
        status: CurrentStatus?,
        nowMs: Long,
        maxAgeMs: Long = MAX_AGE_MS,
    ): Boolean {
        if (status == null) return false
        val age = nowMs - status.updatedAt
        return age in -CLOCK_SKEW_TOLERANCE_MS..maxAgeMs
    }

    data class CurrentDisplayValues(
        val currentTemp: Float?,
        val deltaFromYesterday: Float?,
        val appliedDelta: Float?,
        val isFromPublishedStatus: Boolean,
    )

    /**
     * Resolves the effective current display values between daemon-published [status]
     * and in-memory [snapshot].
     */
    fun resolve(
        status: CurrentStatus?,
        snapshot: ForecastSnapshot?,
        nowMs: Long,
        maxAgeMs: Long = MAX_AGE_MS,
    ): CurrentDisplayValues {
        return if (isFresh(status, nowMs, maxAgeMs)) {
            CurrentDisplayValues(
                currentTemp = status!!.displayTempF,
                deltaFromYesterday = status.deltaFromYesterdayF,
                appliedDelta = status.appliedDeltaF,
                isFromPublishedStatus = true,
            )
        } else {
            CurrentDisplayValues(
                currentTemp = snapshot?.resolved?.currentTemp,
                deltaFromYesterday = snapshot?.resolved?.deltaFromYesterday,
                appliedDelta = snapshot?.resolved?.appliedDelta,
                isFromPublishedStatus = false,
            )
        }
    }
}
