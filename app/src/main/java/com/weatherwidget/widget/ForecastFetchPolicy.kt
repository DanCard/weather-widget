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
)

/**
 * Pure decision functions for scheduling per-source forecast fetches.
 *
 * While charging, fetch cadence scales with screen state and whether a source
 * is the one currently displayed (active) vs. one of the others (non-active).
 * Off-charger, fall back to [BatteryFetchStrategy] tiers and double the interval for non-active
 * sources.
 */
object ForecastFetchPolicy {
    const val CHARGING_SCREEN_ON_ACTIVE_MINUTES = 60L
    const val CHARGING_SCREEN_ON_NONACTIVE_MINUTES = 360L
    const val CHARGING_SCREEN_OFF_ACTIVE_MINUTES = 120L
    const val CHARGING_SCREEN_OFF_NONACTIVE_MINUTES = 480L

    // Off-charger, non-active (not currently displayed) sources fetch less often than the active
    // source — battery matters most off the charger, and a background source can tolerate staler
    // data. Applied as a multiple of the battery-tier interval.
    const val OFF_CHARGER_NONACTIVE_MULTIPLIER = 2L

    private const val OFF_CHARGER_LOW_BATTERY_TICK_MINUTES = 24 * 60L

    private const val DEFAULT_GRACE_MS = 120_000L

    fun intervalMinutes(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
        isActiveSource: Boolean,
        batteryLevel: Int,
    ): Long? {
        val treatAsCharging = BatteryTier.treatAsCharging(isCharging, batteryLevel)

        if (!treatAsCharging) {
            val base = BatteryFetchStrategy.computeFetchInterval(isCharging = false, batteryLevel = batteryLevel)
                ?: return null
            return if (isActiveSource) base else base * OFF_CHARGER_NONACTIVE_MULTIPLIER
        }
        return when {
            isScreenInteractive && isActiveSource -> CHARGING_SCREEN_ON_ACTIVE_MINUTES
            isScreenInteractive && !isActiveSource -> CHARGING_SCREEN_ON_NONACTIVE_MINUTES
            !isScreenInteractive && isActiveSource -> CHARGING_SCREEN_OFF_ACTIVE_MINUTES
            else -> CHARGING_SCREEN_OFF_NONACTIVE_MINUTES
        }
    }

    fun periodicTickMinutes(isCharging: Boolean, batteryLevel: Int): Long {
        val treatAsCharging = BatteryTier.treatAsCharging(isCharging, batteryLevel)
        if (treatAsCharging) return CHARGING_SCREEN_ON_ACTIVE_MINUTES
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
