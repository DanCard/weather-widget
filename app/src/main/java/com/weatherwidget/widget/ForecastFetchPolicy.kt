package com.weatherwidget.widget

import com.weatherwidget.shared.util.BatteryTier
import com.weatherwidget.shared.util.NonPrimaryObservationPolicy

/**
 * Caller-supplied state used by [ForecastFetchPolicy] to decide which sources are due.
 * The repository is Android-coupled but this context lets it stay decision-free.
 */
data class ForecastFetchContext(
    val isCharging: Boolean,
    val isScreenInteractive: Boolean,
    val batteryLevel: Int,
    val activeSourceIds: Set<String>,
    /**
     * A tapped day past Google's routine 72 h: the deeper `forecast/hours` horizon that reaches it
     * ([com.weatherwidget.data.remote.HourlyOnDemand]); null on every other sync.
     */
    val hourlyAhead: com.weatherwidget.data.remote.HourlyOnDemand.Request? = null,
    /**
     * The user's manual Refresh: the displayed sources ([activeSourceIds]) write their daily rows even
     * when unchanged. Every fetch is LONG ([com.weatherwidget.data.remote.ForecastHorizonCadence]).
     */
    val userRefresh: Boolean = false,
    /**
     * The next predicted unplug ([com.weatherwidget.shared.util.UnplugPredictor]): on a charger, a fetch
     * comes due in the lead before it ([com.weatherwidget.data.remote.ForecastHorizonCadence.preWakeAtMs]).
     * Null when none is predicted.
     */
    val wakeAtMs: Long? = null,
)

/**
 * Pure decision functions for scheduling per-source forecast fetches. Which horizon is due is
 * [com.weatherwidget.data.remote.ForecastHorizonCadence] (`:shared`, also used by desktop).
 */
object ForecastFetchPolicy {
    /**
     * The periodic worker is a backstop, not the scheduler: every full sync enqueues one due-time sync
     * at the shared [com.weatherwidget.data.remote.ForecastSchedule]'s wake ([WidgetWorkScheduler.enqueueDueWakeSync]),
     * as desktop's forecast loop sleeps on it. This catches a lost due-time sync; screen-on catches it
     * sooner (plans/261028-screen-off-cadence-and-due-time-wakes.md). Was 60 min on a charger or
     * ≥ 80 %, 240 min ≥ 70 %, battery tiers below.
     */
    const val BACKSTOP_TICK_MINUTES = com.weatherwidget.data.remote.ForecastSchedule.MAX_SLEEP_MS / 60_000L

    private const val DEFAULT_GRACE_MS = 120_000L

    fun isDue(
        lastFetchTimeMs: Long,
        intervalMinutes: Long,
        nowMs: Long,
        graceMs: Long = DEFAULT_GRACE_MS,
    ): Boolean {
        return nowMs - lastFetchTimeMs >= (intervalMinutes * 60_000L) - graceMs
    }

    fun nonPrimaryObservationIntervalMinutes(isCharging: Boolean, isScreenInteractive: Boolean): Long? =
        NonPrimaryObservationPolicy.intervalMinutes(isCharging, isScreenInteractive)
}
