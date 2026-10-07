package com.weatherwidget.shared.util

/**
 * How often a source's forecast is refetched, on Android and desktop.
 *
 * One rule for every provider: the split is displayed vs. other source, never per provider (user,
 * 2026-10-07: "no special case for Google"; other sources fetching less often is good). The charger
 * values were 60/120 min for the displayed source, which spent Google's per-project daily
 * `forecast/hours` quota by 03:16. "Now" stays fresh through observations and current temperature,
 * which keep their own cadence. See plans/261007-google-hours-one-page-and-slower-charger-cadence.md.
 *
 * Elapsed time since the source's own last successful fetch; no clock slots.
 */
object ForecastCadence {
    const val CHARGING_DISPLAYED_SCREEN_ON_MINUTES = 240L
    const val CHARGING_DISPLAYED_SCREEN_OFF_MINUTES = 360L
    const val CHARGING_OTHER_SCREEN_ON_MINUTES = 480L
    const val CHARGING_OTHER_SCREEN_OFF_MINUTES = 720L

    /**
     * Off a charger, a non-displayed source waits this multiple of the battery-tier interval: battery
     * matters most there, and a background source can tolerate staler data.
     */
    const val OFF_CHARGER_OTHER_MULTIPLIER = 2L

    /**
     * Minutes between forecast fetches, or null when the battery is too low for scheduled fetches.
     * A battery high enough to be treated as charging ([BatteryTier.treatAsCharging]) uses the
     * charger values.
     */
    fun intervalMinutes(
        isCharging: Boolean,
        isScreenOn: Boolean,
        isDisplayedSource: Boolean,
        batteryLevel: Int,
    ): Long? {
        if (BatteryTier.treatAsCharging(isCharging, batteryLevel)) {
            return when {
                isDisplayedSource && isScreenOn -> CHARGING_DISPLAYED_SCREEN_ON_MINUTES
                isDisplayedSource -> CHARGING_DISPLAYED_SCREEN_OFF_MINUTES
                isScreenOn -> CHARGING_OTHER_SCREEN_ON_MINUTES
                else -> CHARGING_OTHER_SCREEN_OFF_MINUTES
            }
        }
        val base = BatteryTier.computeFetchInterval(
            isCharging = false,
            batteryLevel = batteryLevel,
            chargingIntervalMinutes = CHARGING_DISPLAYED_SCREEN_ON_MINUTES,
        ) ?: return null
        return if (isDisplayedSource) base else base * OFF_CHARGER_OTHER_MULTIPLIER
    }
}
