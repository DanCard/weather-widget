package com.weatherwidget.desktop

import com.weatherwidget.shared.util.BatteryTier
import com.weatherwidget.shared.util.CurrentTempCadence
import com.weatherwidget.shared.util.NonPrimaryObservationPolicy

/**
 * Pure decision functions for battery-aware fetch scheduling on the desktop.
 * Uses shared [BatteryTier] thresholds.
 */
object DesktopFetchStrategy {

    private const val MS_PER_MINUTE = 60 * 1000L

    /** A click on the panel refreshes observations at least this old (user interaction, not a schedule). */
    const val CATCH_UP_STALENESS_THRESHOLD_MINUTES = 10L

    /**
     * The delay in MS for the next observation fetch, or null when none runs now: the shared
     * [CurrentTempCadence] table, one rule with Android (performance/261011-unify-current-temp-cadence-shared.md).
     */
    fun getObservationRefreshDelayMs(isCharging: Boolean, batteryLevel: Int, screenOn: Boolean = true, watchArmed: Boolean = false): Long? =
        CurrentTempCadence.intervalMinutes(isCharging, screenOn, batteryLevel, watchArmed)?.times(MS_PER_MINUTE)

    /**
     * Whether a panel click should refresh observations now: the last observation fetch is at least
     * [CATCH_UP_STALENESS_THRESHOLD_MINUTES] old. Screen wake uses [CurrentTempCadence.screenOnCatchUp].
     */
    fun shouldCatchUpObservations(
        lastFetchMs: Long?,
        nowMs: Long,
        stalenessThresholdMs: Long = CATCH_UP_STALENESS_THRESHOLD_MINUTES * MS_PER_MINUTE
    ): Boolean {
        if (lastFetchMs == null) return true
        return (nowMs - lastFetchMs) >= stalenessThresholdMs
    }

    /**
     * Whether a catch-up kick fetches observations now. Screen wake (`screensaver:*`) follows the shared
     * [CurrentTempCadence.screenOnCatchUp] (battery ≥ 70 %, last fetch ≥ 20 min; on AC ≥ 15 min, since
     * screen-off spaces the loop 30 min apart); a panel click is user interaction and keeps [shouldCatchUpObservations].
     * [lastObservationFetchMs] is the observations' own last fetch, never the forecast's.
     */
    fun shouldCatchUpOnKick(
        reason: String,
        lastObservationFetchMs: Long?,
        isCharging: Boolean,
        batteryLevel: Int,
        nowMs: Long,
    ): Boolean =
        if (reason.startsWith("screensaver")) {
            CurrentTempCadence.screenOnCatchUp(isCharging, batteryLevel, lastObservationFetchMs ?: 0L, nowMs) ==
                CurrentTempCadence.ScreenOnCatchUp.FETCH_NOW
        } else {
            shouldCatchUpObservations(lastObservationFetchMs, nowMs)
        }

    /**
     * Returns the delay in MS for the next non-primary (non-displayed) source actuals fetch, or
     * null to skip this cycle. Intentionally fires ONLY while charging AND the screen is on — the
     * non-primary actuals are a nicety (kept fresh for an instant source-toggle), not worth waking
     * the network on battery or while the user isn't looking at the display.
     *
     * [screenOn] is supplied by the caller (e.g. [ScreenStateDetector]) so this stays pure/testable.
     */
    fun getNonPrimaryObservationDelayMs(isCharging: Boolean, screenOn: Boolean): Long? =
        NonPrimaryObservationPolicy.intervalMinutes(isCharging, screenOn)?.let { it * MS_PER_MINUTE }
}
