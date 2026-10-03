package com.weatherwidget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.util.SharedPreferencesUtil

/**
 * The timer behind the on-battery, screen-on observation loop (every 20 min at >= 70%).
 *
 * **Why an alarm and not a delayed WorkManager request.** Measured on the Pixel 7 Pro 2026-10-03:
 * a 15-minute delayed request sat at JobScheduler `Ready: true` — every constraint satisfied,
 * nothing restricted, other apps' jobs running — from 15:01:39 to past 15:14 without being
 * dispatched, while the non-wakeup `ui_update_alarm` fired on time. An immediate WorkManager request
 * starts within the second, so the alarm only times the loop; the fetch is still an immediate
 * current-temp request.
 *
 * Non-wakeup (`RTC`) on purpose: the loop exists only while the screen is on, so the device is
 * already awake; this adds no wakeups. Screen-off cancels it ([ScreenOnReceiver]).
 *
 * See `performance/261003-observations-every-20-min-on-battery-screen-on.md`.
 */
object BatteryObservationAlarm {
    private const val TAG = "BatteryObsAlarm"
    private const val REQUEST_CODE = 1003
    private const val PREFS_NAME = "battery_observation_alarm"
    private const val KEY_TRIGGER_AT_MS = "trigger_at_ms"

    /** Android's minimum delivery window for a non-exact alarm on API 31+. */
    @VisibleForTesting
    internal val WINDOW_MS = java.util.concurrent.TimeUnit.MINUTES.toMillis(10)

    /**
     * Arm the alarm for [triggerAtMs] unless an earlier one is already pending.
     *
     * Never pushes a pending alarm later: the `ui_update_alarm` heartbeat asks for the loop every
     * 15-60 min, and "re-arm at now + 20" on each ask would postpone the fetch forever.
     *
     * @return true if armed, false if an earlier pending alarm was kept.
     */
    fun arm(context: Context, triggerAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val existingTriggerMs = pendingTriggerAtMs(context)
        if (!shouldReplace(existingTriggerMs, triggerAtMs, nowMs)) {
            Log.d(TAG, "arm: keeping earlier alarm at $existingTriggerMs (requested $triggerAtMs)")
            return false
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return false
        val windowStartMs = windowStartMs(triggerAtMs, nowMs)
        alarmManager.setWindow(AlarmManager.RTC, windowStartMs, WINDOW_MS, pendingIntent)
        prefs(context).edit().putLong(KEY_TRIGGER_AT_MS, triggerAtMs).apply()
        Log.d(TAG, "arm: window opens ${(windowStartMs - nowMs) / 1000}s from now, length ${WINDOW_MS / 1000}s")
        return true
    }

    /**
     * Without exact-alarm permission Android 12+ will not deliver inside less than a 10-minute
     * window (`setAndAllowWhileIdle` got +13.5 min on the Pixel). The window is centred on the
     * nominal time, so a 20-minute loop fetches 15-25 minutes apart — user's choice 2026-10-03 over
     * 20-30 or requesting SCHEDULE_EXACT_ALARM. Never opens before now.
     */
    @VisibleForTesting
    internal fun windowStartMs(triggerAtMs: Long, nowMs: Long): Long =
        maxOf(nowMs, triggerAtMs - WINDOW_MS / 2)

    fun cancel(context: Context) {
        pendingIntent(context, PendingIntent.FLAG_NO_CREATE)?.let { pendingIntent ->
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent)
            pendingIntent.cancel()
        }
        prefs(context).edit().remove(KEY_TRIGGER_AT_MS).apply()
    }

    /** Called by the receiver when the alarm fires: it is no longer pending. */
    internal fun markFired(context: Context) {
        prefs(context).edit().remove(KEY_TRIGGER_AT_MS).apply()
    }

    /**
     * The pending alarm's trigger time, or null if none. The stored time alone is not trusted —
     * alarms do not survive a reboot — so the PendingIntent must also still exist.
     */
    private fun pendingTriggerAtMs(context: Context): Long? {
        val stored = prefs(context).getLong(KEY_TRIGGER_AT_MS, 0L)
        if (stored <= 0L) return null
        return if (pendingIntent(context, PendingIntent.FLAG_NO_CREATE) != null) stored else null
    }

    @VisibleForTesting
    internal fun shouldReplace(existingTriggerMs: Long?, desiredTriggerMs: Long, nowMs: Long): Boolean =
        existingTriggerMs == null || existingTriggerMs <= nowMs || existingTriggerMs > desiredTriggerMs

    private fun pendingIntent(context: Context, flag: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, BatteryObservationAlarmReceiver::class.java),
            flag or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun prefs(context: Context) = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
}
