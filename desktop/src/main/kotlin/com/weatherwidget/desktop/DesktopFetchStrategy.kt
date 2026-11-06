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
     * [CATCH_UP_STALENESS_THRESHOLD_MINUTES] old (also a return from away). Screen wake re-plans the
     * observation loop instead ([observationLoopWaitMs]).
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
     * How long the observation loop waits before its next fetch, or null when none runs now (power
     * policy): [intervalMinutes] (the shared table for the power and screen state now) counted from the
     * later of the last observation fetch and the loop's own last pass, via the shared
     * [CurrentTempCadence.remainingMs]. 0 = fetch now. The loop's last pass counts so that a failed or
     * away-skipped pass waits an interval rather than spinning.
     * plans/261106-screen-on-replans-observation-loop.md
     */
    /**
     * The observation loop's `lastLoopPassMs` before its first pass. 0 = no pass yet, so the first wait
     * counts from the last observation fetch alone. A loop restart (source switch, settings save)
     * seeded "now" and pushed the fetch a full interval out on every toggle.
     * plans/261106-loop-restart-keeps-observation-deadline.md
     */
    const val OBSERVATION_LOOP_FIRST_PASS_SEED_MS = 0L

    /** Cap on the observation loop's wait for its restart's launch refresh; a hung launch must not stall it. */
    const val OBSERVATION_LOOP_LAUNCH_WAIT_MS = 2 * 60 * 1000L

    fun observationLoopWaitMs(intervalMinutes: Long?, lastObservationFetchMs: Long?, lastLoopPassMs: Long, nowMs: Long): Long? {
        intervalMinutes ?: return null
        return CurrentTempCadence.remainingMs(intervalMinutes, maxOf(lastObservationFetchMs ?: 0L, lastLoopPassMs), nowMs)
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
