package com.weatherwidget.widget

import com.weatherwidget.shared.util.BatteryTier
import com.weatherwidget.shared.util.ForecastCadence
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
    /** See [com.weatherwidget.data.remote.HourlyFetchGate]: refresh daily/current, not hourly. */
    val hourlyLimited: Boolean = false,
)

/**
 * Pure decision functions for scheduling per-source forecast fetches.
 *
 * The intervals are [ForecastCadence] (`:shared`, also used by desktop): displayed vs. other source,
 * screen on vs. off, battery tier — the same rule for every provider.
 */
object ForecastFetchPolicy {
    /**
     * How often the periodic worker wakes on a charger. Not a fetch interval: each tick fetches only
     * the sources [ForecastCadence] says are due, and also resamples location.
     */
    const val CHARGING_TICK_MINUTES = 60L

    private const val OFF_CHARGER_LOW_BATTERY_TICK_MINUTES = 24 * 60L

    private const val DEFAULT_GRACE_MS = 120_000L

    fun intervalMinutes(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
        isActiveSource: Boolean,
        batteryLevel: Int,
    ): Long? = ForecastCadence.intervalMinutes(
        isCharging = isCharging,
        isScreenOn = isScreenInteractive,
        isDisplayedSource = isActiveSource,
        batteryLevel = batteryLevel,
    )

    fun periodicTickMinutes(isCharging: Boolean, batteryLevel: Int): Long {
        val treatAsCharging = BatteryTier.treatAsCharging(isCharging, batteryLevel)
        if (treatAsCharging) return CHARGING_TICK_MINUTES
        return BatteryFetchStrategy.computeFetchInterval(isCharging = false, batteryLevel = batteryLevel)
            ?: OFF_CHARGER_LOW_BATTERY_TICK_MINUTES
    }

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
