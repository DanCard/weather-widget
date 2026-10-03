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
 * a 15-minute delayed request sat at JobScheduler `Ready: true` from 15:01:39 to past 15:14 without
 * being dispatched, while the allow-while-idle `ui_update_alarm` fired on time. The cause was very
 * likely Adaptive Battery Saver (see [arm]), which holds back background apps' jobs and plain
 * alarms. The alarm only times the loop; the fetch is an expedited current-temp request, which
 * Battery Saver does not defer.
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
    private const val KEY_LATEST_DELIVERY_MS = "latest_delivery_ms"

    /**
     * Half-width of the delivery window around the nominal time: the user chose 15-25 minutes for
     * the 20-minute loop.
     */
    @VisibleForTesting
    internal val HALF_WINDOW_MS = java.util.concurrent.TimeUnit.MINUTES.toMillis(5)

    /**
     * AlarmManager gives an inexact allow-while-idle alarm a window of 75% of its delay
     * (measured on the Pixel: an 1080 s delay got a 13m29.997s window).
     */
    @VisibleForTesting
    internal const val SYSTEM_WINDOW_FRACTION = 0.75

    /**
     * Arm the alarm so it is delivered by [nominalAtMs] + 5 min, unless a pending alarm already
     * lands no later than that.
     *
     * **Why `setAndAllowWhileIdle`.** Pixel's Adaptive Battery Saver switches on whenever the phone
     * is unplugged, screen on or off (2026-10-03: ADAPTIVE at 15:40:08, two seconds after unplug;
     * OFF at 16:04:20 on re-plug). Its `force_all_apps_standby` deferred a `setWindow` alarm by
     * +364 days, along with every other background app's plain alarms, while the allow-while-idle
     * `ui_update_alarm` kept firing. The user chose to run the loop anyway.
     *
     * The system sets the window itself (75% of the delay), so the trigger is placed where that
     * window ends at nominal + 5 min: for a 20-minute loop, delivery is about 14.3-25 min out.
     *
     * Never pushes a pending alarm later: the `ui_update_alarm` heartbeat asks for the loop every
     * 15-60 min, and "re-arm at now + 20" on each ask would postpone the fetch forever. A pending
     * alarm counts until its window closes, not until its trigger time: on 2026-10-03 a heartbeat
     * at 16:00:30 replaced a 15:58 alarm whose window was still open until 16:03.
     *
     * @return true if armed, false if an earlier pending alarm was kept.
     */
    fun arm(context: Context, nominalAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val timing = timing(nominalAtMs, nowMs)
        val existingLatestMs = pendingLatestDeliveryMs(context)
        if (!shouldReplace(existingLatestMs, timing.latestMs, nowMs)) {
            Log.d(TAG, "arm: keeping pending alarm due by $existingLatestMs (requested by ${timing.latestMs})")
            return false
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return false
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, timing.triggerMs, pendingIntent)
        prefs(context).edit().putLong(KEY_LATEST_DELIVERY_MS, timing.latestMs).apply()
        Log.d(
            TAG,
            "arm: trigger ${(timing.triggerMs - nowMs) / 1000}s, delivered by ${(timing.latestMs - nowMs) / 1000}s from now",
        )
        return true
    }

    fun cancel(context: Context) {
        pendingIntent(context, PendingIntent.FLAG_NO_CREATE)?.let { pendingIntent ->
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent)
            pendingIntent.cancel()
        }
        prefs(context).edit().remove(KEY_LATEST_DELIVERY_MS).apply()
    }

    /** Called by the receiver when the alarm fires: it is no longer pending. */
    internal fun markFired(context: Context) {
        prefs(context).edit().remove(KEY_LATEST_DELIVERY_MS).apply()
    }

    @VisibleForTesting
    internal data class Timing(val triggerMs: Long, val latestMs: Long)

    /**
     * Trigger so that trigger + 75% of (trigger - now) = nominal + 5 min, i.e. the system's window
     * ends at the latest time the user accepts. Never before now.
     */
    @VisibleForTesting
    internal fun timing(nominalAtMs: Long, nowMs: Long): Timing {
        val latestMs = maxOf(nowMs, nominalAtMs + HALF_WINDOW_MS)
        val triggerDelayMs = ((latestMs - nowMs) / (1.0 + SYSTEM_WINDOW_FRACTION)).toLong()
        return Timing(triggerMs = nowMs + triggerDelayMs, latestMs = latestMs)
    }

    /**
     * The pending alarm's latest delivery time, or null if none. The stored time alone is not
     * trusted — alarms do not survive a reboot — so the PendingIntent must also still exist.
     */
    private fun pendingLatestDeliveryMs(context: Context): Long? {
        val stored = prefs(context).getLong(KEY_LATEST_DELIVERY_MS, 0L)
        if (stored <= 0L) return null
        return if (pendingIntent(context, PendingIntent.FLAG_NO_CREATE) != null) stored else null
    }

    /**
     * Replace unless a pending alarm will still be delivered no later than the requested one. Its
     * window is over once [existingLatestMs] has passed (it fired, or was lost), so replace then.
     */
    @VisibleForTesting
    internal fun shouldReplace(existingLatestMs: Long?, desiredLatestMs: Long, nowMs: Long): Boolean =
        existingLatestMs == null || existingLatestMs < nowMs || existingLatestMs > desiredLatestMs

    private fun pendingIntent(context: Context, flag: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, BatteryObservationAlarmReceiver::class.java),
            flag or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun prefs(context: Context) = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
}
