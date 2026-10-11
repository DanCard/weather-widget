package com.weatherwidget.shared.util

/**
 * How often the displayed source's current temperature / recent observations are fetched — ONE rule
 * for Android and desktop (user, 2026-10-11; performance/261011-unify-current-temp-cadence-shared.md).
 *
 * | Power | Screen on | Screen off |
 * |---|---|---|
 * | Charging (plugged in) | 10 min | 16 min |
 * | Battery ≥ 70 % | 20 min | 45 min |
 * | Battery 66–69 % | 45 min | none |
 * | Battery ≤ 65 % | none | none |
 *
 * "Charging" is physically plugged in, not the forecast cadence's ≥ 80 % stand-in. Each platform
 * keeps its own way of waking: Android runs the [loopIntervalMinutes] bands on a WorkManager loop
 * (charging) or an RTC alarm (battery, screen on) and the 45-min band as a screen-blind JobScheduler
 * job that asks [opportunisticAllowed] when it runs; desktop runs [intervalMinutes] on one loop.
 * Other sources' observations: [NonPrimaryObservationPolicy].
 */
object CurrentTempCadence {
    const val CHARGING_SCREEN_ON_MINUTES = 10L
    const val CHARGING_SCREEN_OFF_MINUTES = 16L
    const val BATTERY_SCREEN_ON_MINUTES = BatteryTier.SCREEN_ON_OBSERVATION_INTERVAL_MINUTES
    const val OPPORTUNISTIC_MINUTES = 45L

    /** Above this (screen on) the 45-min band runs; Android's job is scheduled only above it. */
    const val OPPORTUNISTIC_MIN_BATTERY_PERCENT = BatteryTier.OPPORTUNISTIC_MIN_BATTERY_PERCENT

    /** With the screen off the 45-min band needs this much (user, 2026-10-11: 66–69 % screen off → none). */
    const val OPPORTUNISTIC_SCREEN_OFF_MIN_BATTERY_PERCENT = BatteryTier.SCREEN_ON_OBSERVATION_MIN_BATTERY_PERCENT

    fun chargingIntervalMinutes(screenOn: Boolean): Long =
        if (screenOn) CHARGING_SCREEN_ON_MINUTES else CHARGING_SCREEN_OFF_MINUTES

    /** The 10/16/20-min bands (charging, or battery ≥ 70 % with the screen on); null otherwise. */
    fun loopIntervalMinutes(isCharging: Boolean, screenOn: Boolean, batteryLevel: Int): Long? = when {
        isCharging -> chargingIntervalMinutes(screenOn)
        BatteryTier.screenOnObservationAllowed(batteryLevel, screenOn) -> BATTERY_SCREEN_ON_MINUTES
        else -> null
    }

    /** Whether the 45-min band may fetch on battery now: screen on > 65 %, screen off ≥ 70 %. */
    fun opportunisticAllowed(batteryLevel: Int, screenOn: Boolean): Boolean =
        if (screenOn) batteryLevel > OPPORTUNISTIC_MIN_BATTERY_PERCENT else batteryLevel >= OPPORTUNISTIC_SCREEN_OFF_MIN_BATTERY_PERCENT

    /** The whole table as one interval (desktop's single loop); null = no fetch now. */
    fun intervalMinutes(isCharging: Boolean, screenOn: Boolean, batteryLevel: Int): Long? =
        loopIntervalMinutes(isCharging, screenOn, batteryLevel)
            ?: OPPORTUNISTIC_MINUTES.takeIf { opportunisticAllowed(batteryLevel, screenOn) }

    enum class ScreenOnCatchUp {
        /** Charging, or battery below the cutoff: screen-on does not touch the loop. */
        NONE,

        /** Last fetch is at least one interval old: fetch now; the run reschedules the loop. */
        FETCH_NOW,

        /** Last fetch is recent: start the loop, first run when the interval since it elapses. */
        SCHEDULE,
    }

    /**
     * What the screen coming on does on battery. Without the catch-up the loop's first run is a full
     * interval after screen-on, and a typical session is shorter than that, so it would rarely fetch.
     * [lastFetchMs] is the last **current-temperature** fetch (≤ 0 = none).
     */
    fun screenOnCatchUp(isCharging: Boolean, batteryLevel: Int, lastFetchMs: Long, nowMs: Long): ScreenOnCatchUp {
        if (isCharging) return ScreenOnCatchUp.NONE
        if (!BatteryTier.screenOnObservationAllowed(batteryLevel, isScreenOn = true)) return ScreenOnCatchUp.NONE
        val ageMs = nowMs - lastFetchMs
        return if (lastFetchMs <= 0L || ageMs >= BATTERY_SCREEN_ON_MINUTES * MINUTE_MS) {
            ScreenOnCatchUp.FETCH_NOW
        } else {
            ScreenOnCatchUp.SCHEDULE
        }
    }

    /** Minutes until the next loop run when screen-on found a recent fetch (never below 1). */
    fun screenOnFirstDelayMinutes(lastFetchMs: Long, nowMs: Long): Long {
        val remainingMs = BATTERY_SCREEN_ON_MINUTES * MINUTE_MS - (nowMs - lastFetchMs)
        return (remainingMs / MINUTE_MS).coerceIn(1L, BATTERY_SCREEN_ON_MINUTES)
    }

    private const val MINUTE_MS = 60_000L
}
