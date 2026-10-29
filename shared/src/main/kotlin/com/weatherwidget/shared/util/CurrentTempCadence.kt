package com.weatherwidget.shared.util

/**
 * How often the displayed source's current temperature / recent observations are fetched — ONE rule
 * for Android and desktop (user, 2026-10-11; performance/261011-unify-current-temp-cadence-shared.md).
 *
 * | Power | Screen on | Screen off | Screen off, watch armed |
 * |---|---|---|---|
 * | Charging (plugged in) | 10 min | 60 min | 30 min |
 * | Battery ≥ 70 % | 20 min | none | 45 min |
 * | Battery 66–69 % | 45 min | none | none |
 * | Battery ≤ 65 % | none | none | none |
 *
 * "Charging" is physically plugged in, not the forecast cadence's ≥ 80 % stand-in. Each platform
 * keeps its own way of waking: Android runs the [loopIntervalMinutes] bands on a WorkManager loop
 * (charging) or an RTC alarm (battery, screen on) and the 45-min band as a screen-blind JobScheduler
 * job that asks [opportunisticAllowed] when it runs; desktop runs [intervalMinutes] on one loop.
 * Other sources' observations: [NonPrimaryObservationPolicy].
 *
 * Screen off nobody sees a reading (plans/261028-screen-off-cadence-and-due-time-wakes.md): a screen-on
 * fetches when the last one is old enough ([screenOnCatchUp]), the widget estimates in between, and
 * history is refilled (NWS gap fill; Tomorrow.io's 23 h window on every forecast fetch). Charging
 * screen-off is hourly, matching the hourly METAR (16 min until 2026-10-19, 30 min until 2026-10-28);
 * battery screen-off polls nothing. The exception is the one-shot dominant-station notification
 * ([com.weatherwidget.shared.notify.DominantTempWatch]): the user waits for it with the screen off, so
 * while it is armed the screen-off bands keep their earlier pace.
 */
object CurrentTempCadence {
    const val CHARGING_SCREEN_ON_MINUTES = 10L
    const val CHARGING_SCREEN_OFF_MINUTES = 60L

    /** Charging screen-off while the dominant-station watch is armed. */
    const val CHARGING_SCREEN_OFF_WATCH_MINUTES = 30L

    /** On a charger, screen-on fetches when the last fetch is at least this old. */
    const val CHARGING_SCREEN_ON_CATCH_UP_MINUTES = 15L
    const val BATTERY_SCREEN_ON_MINUTES = BatteryTier.SCREEN_ON_OBSERVATION_INTERVAL_MINUTES
    const val OPPORTUNISTIC_MINUTES = 45L

    /** Above this (screen on) the 45-min band runs; Android's job is scheduled only above it. */
    const val OPPORTUNISTIC_MIN_BATTERY_PERCENT = BatteryTier.OPPORTUNISTIC_MIN_BATTERY_PERCENT

    /** With the screen off the 45-min band needs this much (user, 2026-10-11: 66–69 % screen off → none). */
    const val OPPORTUNISTIC_SCREEN_OFF_MIN_BATTERY_PERCENT = BatteryTier.SCREEN_ON_OBSERVATION_MIN_BATTERY_PERCENT

    fun chargingIntervalMinutes(screenOn: Boolean, watchArmed: Boolean): Long = when {
        screenOn -> CHARGING_SCREEN_ON_MINUTES
        watchArmed -> CHARGING_SCREEN_OFF_WATCH_MINUTES
        else -> CHARGING_SCREEN_OFF_MINUTES
    }

    /** The loop bands (charging, or battery ≥ 70 % with the screen on); null otherwise. */
    fun loopIntervalMinutes(isCharging: Boolean, screenOn: Boolean, batteryLevel: Int, watchArmed: Boolean): Long? = when {
        isCharging -> chargingIntervalMinutes(screenOn, watchArmed)
        BatteryTier.screenOnObservationAllowed(batteryLevel, screenOn) -> BATTERY_SCREEN_ON_MINUTES
        else -> null
    }

    /**
     * Whether the 45-min band may fetch now: on battery only (a charger runs the loop), screen on
     * > 65 %, screen off ≥ 70 % and only while the watch is armed.
     */
    fun opportunisticAllowed(isCharging: Boolean, batteryLevel: Int, screenOn: Boolean, watchArmed: Boolean): Boolean = when {
        isCharging -> false
        screenOn -> batteryLevel > OPPORTUNISTIC_MIN_BATTERY_PERCENT
        else -> watchArmed && batteryLevel >= OPPORTUNISTIC_SCREEN_OFF_MIN_BATTERY_PERCENT
    }

    /** The whole table as one interval (desktop's single loop); null = no fetch now. */
    fun intervalMinutes(isCharging: Boolean, screenOn: Boolean, batteryLevel: Int, watchArmed: Boolean): Long? =
        loopIntervalMinutes(isCharging, screenOn, batteryLevel, watchArmed)
            ?: OPPORTUNISTIC_MINUTES.takeIf { opportunisticAllowed(isCharging, batteryLevel, screenOn, watchArmed) }

    enum class ScreenOnCatchUp {
        /** A recent fetch on a charger, or battery below the cutoff: screen-on does not touch the loop. */
        NONE,

        /** Last fetch is at least one interval old: fetch now; the run reschedules the loop. */
        FETCH_NOW,

        /** Last fetch is recent: start the loop, first run when the interval since it elapses. */
        SCHEDULE,
    }

    /**
     * What the screen coming on does. On battery: without the catch-up the loop's first run is a full
     * interval after screen-on, and a typical session is shorter than that, so it would rarely fetch.
     * On a charger the loop is already running, but screen-off spaces it up to [CHARGING_SCREEN_OFF_MINUTES]
     * apart: fetch now when the last one is ≥ [CHARGING_SCREEN_ON_CATCH_UP_MINUTES] old, else leave
     * the loop be. [lastFetchMs] is the last **current-temperature** fetch (≤ 0 = none).
     */
    fun screenOnCatchUp(isCharging: Boolean, batteryLevel: Int, lastFetchMs: Long, nowMs: Long): ScreenOnCatchUp {
        if (isCharging) {
            val stale = lastFetchMs <= 0L || nowMs - lastFetchMs >= CHARGING_SCREEN_ON_CATCH_UP_MINUTES * MINUTE_MS
            return if (stale) ScreenOnCatchUp.FETCH_NOW else ScreenOnCatchUp.NONE
        }
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
