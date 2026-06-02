package com.weatherwidget.widget

/**
 * Policy decisions for lightweight current-temperature network refresh.
 */
object CurrentTempFetchPolicy {
    const val CHARGING_INTERVAL_MINUTES = 10L
    const val CHARGING_SCREEN_OFF_INTERVAL_MINUTES = 16L

    /**
     * Returns the appropriate charging loop interval based on screen state.
     */
    fun chargingIntervalMinutes(isScreenInteractive: Boolean): Long =
        if (isScreenInteractive) CHARGING_INTERVAL_MINUTES else CHARGING_SCREEN_OFF_INTERVAL_MINUTES

    /**
     * Fetch is allowed while charging (regardless of screen state) or on battery
     * in opportunistic contexts. Manual triggers always bypass these checks.
     */
    fun shouldFetchNow(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
        isOpportunisticContext: Boolean,
        isManual: Boolean = false,
    ): Boolean {
        if (isManual) return true

        return if (isCharging) {
            true
        } else {
            isOpportunisticContext
        }
    }

    /**
     * Background charging loop should run whenever the device is charging,
     * regardless of screen state.
     */
    fun shouldScheduleChargingLoop(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
    ): Boolean = isCharging

    /**
     * What a worker should do with the charging-loop heartbeat once a current-temp run finishes.
     *
     * Deliberately has NO "cancel" option. Cancelling the unique current-temp work by name
     * (WeatherWidgetProvider.WORK_NAME_CURRENT_TEMP) truncates any concurrently-running
     * opportunistic fetch, because the opportunistic UI-only worker and the fetch worker are
     * enqueued together under that same unique name — the root cause of current temp being slow
     * to refresh on battery (the UI worker would finish first and cancel the in-flight fetch).
     *
     * On battery the loop is torn down implicitly: it is a self-perpetuating chain, so an
     * on-battery iteration simply does not reschedule the next one. Prompt teardown on unplug is
     * handled by ScreenOnReceiver at safe moments (screen-off / unlock), not from inside a worker.
     */
    enum class PostRunLoopAction {
        /** Charging: schedule the next heartbeat iteration. */
        SCHEDULE_NEXT,

        /** On battery: do nothing; the loop chain ends because nothing reschedules it. */
        NO_RESCHEDULE,
    }

    fun postRunLoopAction(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
    ): PostRunLoopAction =
        if (shouldScheduleChargingLoop(isCharging, isScreenInteractive)) {
            PostRunLoopAction.SCHEDULE_NEXT
        } else {
            PostRunLoopAction.NO_RESCHEDULE
        }
}
