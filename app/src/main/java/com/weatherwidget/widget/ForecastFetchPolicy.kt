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
     * A previous day refreshed on the Forecast History screen: the only fetch that may refill that
     * day's stale elapsed Google hours from `history/hours`
     * ([com.weatherwidget.data.remote.GoogleHistoryRefill]). Null on every other sync.
     */
    val historyRefillDay: java.time.LocalDate? = null,
    /**
     * The user's manual Refresh: the displayed sources ([activeSourceIds]) make a LONG fetch
     * ([com.weatherwidget.data.remote.ForecastHorizonCadence.forced]).
     */
    val userRefresh: Boolean = false,
    /**
     * The user's next alarm: on a charger, LONG comes due in the lead before it
     * ([com.weatherwidget.data.remote.ForecastHorizonCadence.preWakeAtMs]). Null when none is set.
     */
    val wakeAtMs: Long? = null,
)

/**
 * Pure decision functions for scheduling per-source forecast fetches. Which horizon is due is
 * [com.weatherwidget.data.remote.ForecastHorizonCadence] (`:shared`, also used by desktop).
 */
object ForecastFetchPolicy {
    /**
     * How often the periodic worker wakes on a charger. Not a fetch interval: each tick fetches only
     * the sources whose horizon is due, and also resamples location.
     */
    const val CHARGING_TICK_MINUTES = 60L

    /**
     * Off a charger with a high battery: the SHORT screen-on interval, so a 4-h SHORT fetch is never
     * held to a longer tick.
     */
    const val HIGH_BATTERY_TICK_MINUTES = com.weatherwidget.data.remote.ForecastHorizonCadence.SHORT_SCREEN_ON_HOURS * 60L

    private const val OFF_CHARGER_LOW_BATTERY_TICK_MINUTES = 24 * 60L

    private const val DEFAULT_GRACE_MS = 120_000L

    fun periodicTickMinutes(isCharging: Boolean, batteryLevel: Int): Long {
        if (BatteryTier.treatAsCharging(isCharging, batteryLevel)) return CHARGING_TICK_MINUTES
        if (com.weatherwidget.data.remote.ForecastHorizonCadence.isHighBattery(isCharging, batteryLevel)) {
            return HIGH_BATTERY_TICK_MINUTES
        }
        // Below the high level only the 24-h LONG fetch is scheduled, but the tick also resamples
        // location, so it keeps its battery-tier pace.
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
