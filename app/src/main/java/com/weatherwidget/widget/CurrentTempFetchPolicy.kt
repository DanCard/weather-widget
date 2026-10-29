package com.weatherwidget.widget

import com.weatherwidget.shared.util.CurrentTempCadence

/**
 * Policy decisions for lightweight current-temperature network refresh.
 */
object CurrentTempFetchPolicy {
    // The numbers are :shared CurrentTempCadence (one rule with desktop); these name them for Android.
    const val CHARGING_INTERVAL_MINUTES = CurrentTempCadence.CHARGING_SCREEN_ON_MINUTES
    const val CHARGING_SCREEN_OFF_INTERVAL_MINUTES = CurrentTempCadence.CHARGING_SCREEN_OFF_MINUTES
    const val OPPORTUNISTIC_INTERVAL_MINUTES = CurrentTempCadence.OPPORTUNISTIC_MINUTES
    const val BATTERY_SCREEN_ON_INTERVAL_MINUTES = CurrentTempCadence.BATTERY_SCREEN_ON_MINUTES
    const val OPPORTUNISTIC_MIN_BATTERY_PERCENT = CurrentTempCadence.OPPORTUNISTIC_MIN_BATTERY_PERCENT

    /**
     * Returns the appropriate charging loop interval based on screen state.
     */
    fun chargingIntervalMinutes(isScreenInteractive: Boolean, watchArmed: Boolean = false): Long =
        CurrentTempCadence.chargingIntervalMinutes(isScreenInteractive, watchArmed)

    /**
     * The one definition of the current-temp loop cadence; null means the loop does not run.
     *
     * - charging: 10 min screen on, 60 min screen off (30 while the dominant-station watch is armed)
     * - on battery, screen on, battery >= 70%: 20 min (user's decision 2026-10-03)
     * - otherwise: null — only the screen-blind 45-min opportunistic job remains
     *
     * [watchArmed]: [WidgetStateManager.notifyOnDominantTempChange] — every production caller passes it.
     *
     * [shouldScheduleChargingLoop], [postRunLoopAction] and the loop branch of [shouldFetchNow] all
     * derive from this. They used to each collapse to `isCharging` independently; if any one of
     * them disagrees with the others, a run is scheduled and then policy-blocked on arrival.
     */
    fun loopIntervalMinutes(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
        batteryLevel: Int,
        watchArmed: Boolean = false,
    ): Long? = CurrentTempCadence.loopIntervalMinutes(isCharging, isScreenInteractive, batteryLevel, watchArmed)

    /** Log/work reason for a loop iteration, so the trace shows which cadence ran. */
    fun loopReason(isCharging: Boolean, overdue: Boolean): String {
        val base = if (isCharging) "charging_loop" else "battery_screen_on_loop"
        return if (overdue) "${base}_overdue" else base
    }

    /** [CurrentTempCadence.screenOnCatchUp] (shared with desktop). */
    fun screenOnCatchUp(
        isCharging: Boolean,
        batteryLevel: Int,
        lastFetchMs: Long,
        nowMs: Long,
    ): CurrentTempCadence.ScreenOnCatchUp = CurrentTempCadence.screenOnCatchUp(isCharging, batteryLevel, lastFetchMs, nowMs)

    /** [CurrentTempCadence.screenOnFirstDelayMinutes]. */
    fun screenOnFirstDelayMinutes(lastFetchMs: Long, nowMs: Long): Long =
        CurrentTempCadence.screenOnFirstDelayMinutes(lastFetchMs, nowMs)

    /**
     * Opportunistic work is allowed only above the battery cutoff. Other current-temperature work
     * runs whenever the loop cadence allows it ([loopIntervalMinutes]). Manual triggers bypass
     * these checks.
     */
    fun shouldFetchNow(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
        isOpportunisticContext: Boolean,
        batteryLevel: Int,
        isManual: Boolean = false,
        watchArmed: Boolean = false,
    ): Boolean {
        if (isManual) return true

        if (isOpportunisticContext) {
            // Battery only; screen on > 65 %, screen off ≥ 70 % while the watch is armed.
            return CurrentTempCadence.opportunisticAllowed(isCharging, batteryLevel, isScreenInteractive, watchArmed)
        }
        return loopIntervalMinutes(isCharging, isScreenInteractive, batteryLevel, watchArmed) != null
    }

    /**
     * Keep the persisted opportunistic job only above the battery-first cutoff. The job rechecks
     * this when it starts, so a request scheduled at 66% cannot perform network work after the
     * battery reaches 65%.
     */
    fun shouldScheduleOpportunisticJob(batteryLevel: Int): Boolean =
        batteryLevel > OPPORTUNISTIC_MIN_BATTERY_PERCENT

    /**
     * Charging preserves the existing all-visible-source behavior. On battery, any non-manual
     * current-temp fetch (the opportunistic job and the screen-on loop) targets only the
     * configured primary source.
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
     * Whether the current-temp loop should run: always while charging, and on battery while the
     * screen is on at >= 70%. See [loopIntervalMinutes].
     */
    fun shouldScheduleChargingLoop(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
        batteryLevel: Int,
        watchArmed: Boolean = false,
    ): Boolean = loopIntervalMinutes(isCharging, isScreenInteractive, batteryLevel, watchArmed) != null

    /**
     * What a worker should do with the charging-loop heartbeat once a current-temp run finishes.
     *
     * Deliberately has NO "cancel" option. Cancelling the unique current-temp work by name
     * (WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP) truncates any concurrently-running
     * opportunistic fetch, because the opportunistic UI-only worker and the fetch worker are
     * enqueued together under that same unique name — the root cause of current temp being slow
     * to refresh on battery (the UI worker would finish first and cancel the in-flight fetch).
     *
     * When the loop is not allowed (see [loopIntervalMinutes]) it is torn down implicitly: it is a self-perpetuating chain, so an
     * on-battery iteration simply does not reschedule the next one. Prompt teardown on unplug is
     * handled by ScreenOnReceiver at safe moments (screen-off / unlock), not from inside a worker.
     */
    enum class PostRunLoopAction {
        /** Loop allowed: schedule the next heartbeat iteration. */
        SCHEDULE_NEXT,

        /** Loop not allowed: do nothing; the loop chain ends because nothing reschedules it. */
        NO_RESCHEDULE,
    }

    fun postRunLoopAction(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
        batteryLevel: Int,
        watchArmed: Boolean = false,
    ): PostRunLoopAction =
        if (shouldScheduleChargingLoop(isCharging, isScreenInteractive, batteryLevel, watchArmed)) {
            PostRunLoopAction.SCHEDULE_NEXT
        } else {
            PostRunLoopAction.NO_RESCHEDULE
        }
}
