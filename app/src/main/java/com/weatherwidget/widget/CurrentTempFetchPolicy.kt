package com.weatherwidget.widget

import com.weatherwidget.shared.util.BatteryTier

/**
 * Policy decisions for lightweight current-temperature network refresh.
 */
object CurrentTempFetchPolicy {
    const val CHARGING_INTERVAL_MINUTES = 10L
    const val CHARGING_SCREEN_OFF_INTERVAL_MINUTES = 16L
    const val OPPORTUNISTIC_INTERVAL_MINUTES = 45L

    // Single source of truth for the battery cutoff lives in BatteryTier (shared).
    const val OPPORTUNISTIC_MIN_BATTERY_PERCENT = BatteryTier.OPPORTUNISTIC_MIN_BATTERY_PERCENT

    /**
     * Returns the appropriate charging loop interval based on screen state.
     */
    fun chargingIntervalMinutes(isScreenInteractive: Boolean): Long =
        if (isScreenInteractive) CHARGING_INTERVAL_MINUTES else CHARGING_SCREEN_OFF_INTERVAL_MINUTES

    /**
     * Opportunistic work is allowed only above the battery cutoff. Other current-temperature work
     * is allowed while charging regardless of screen state. Manual triggers bypass these checks.
     */
    fun shouldFetchNow(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
        isOpportunisticContext: Boolean,
        batteryLevel: Int,
        isManual: Boolean = false,
    ): Boolean {
        if (isManual) return true

        if (isOpportunisticContext) {
            return batteryLevel > OPPORTUNISTIC_MIN_BATTERY_PERCENT
        }
        return isCharging
    }

    /**
     * Keep the persisted opportunistic job only above the battery-first cutoff. The job rechecks
     * this when it starts, so a request scheduled at 66% cannot perform network work after the
     * battery reaches 65%.
     */
    fun shouldScheduleOpportunisticJob(batteryLevel: Int): Boolean =
        batteryLevel > OPPORTUNISTIC_MIN_BATTERY_PERCENT

    /**
     * Charging preserves the existing all-visible-source behavior. On battery, the opportunistic
     * fetch targets only the configured primary source.
     */
    fun opportunisticTargetSourceId(
        isCharging: Boolean,
        primarySourceId: String,
    ): String? = if (isCharging) null else primarySourceId

    /**
     * Whether the post-run widget repaint should be skipped because the run could not have
     * changed anything the widgets display. A policy-blocked run fetched nothing; a successful
     * run that attempted zero sources (repository freshness skip, or every source throttled)
     * left the cache byte-identical to what the widgets already show. Repainting all widgets
     * from an unchanged cache is a visible no-op redraw — the post-fetch "double blink".
     * Failed runs still repaint: per-source error indicators may have changed.
     */
    fun shouldSkipPostRunRepaint(
        policyBlocked: Boolean,
        fetchFailed: Boolean,
        attemptedSourceCount: Int,
    ): Boolean {
        if (policyBlocked) return true
        if (fetchFailed) return false
        return attemptedSourceCount == 0
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
     * (WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP) truncates any concurrently-running
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
