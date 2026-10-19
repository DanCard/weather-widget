package com.weatherwidget.widget

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import com.weatherwidget.data.remote.ForecastHorizonCadence
import com.weatherwidget.util.SharedPreferencesUtil
import java.time.Instant
import java.time.ZoneId

/**
 * Arms a charging-only sync just before the user's next alarm, so the first glance of the morning sees
 * a LONG forecast fetched minutes ago ([ForecastHorizonCadence.preWakeAtMs];
 * performance/261019-pre-alarm-long-fetch-while-charging.md).
 *
 * The sync is an ordinary unforced one: the cadence decides it is LONG-due because the fetch context
 * carries the alarm ([ForecastFetchContext.wakeAtMs]). This only makes sure a check lands inside the
 * lead — the 60-min charging tick can miss a 45-min window. The phone is not in Doze while charging,
 * so the charging-constrained work runs close to its delay. Unplugged before then, it waits for the
 * next charge and runs as a plain cadence-judged sync.
 *
 * Re-armed from every full sync (the hourly charging tick), the plug-in job, app start and
 * [NextAlarmChangedReceiver]; it re-enqueues only when the alarm it was armed for changes.
 */
internal object PreWakeFetchScheduler {
    private const val TAG = "PreWakeFetch"
    const val LOG_TAG = "PRE_WAKE_FETCH"
    private const val PREFS_NAME = "pre_wake_fetch_prefs"
    private const val KEY_ARMED_WAKE_AT_MS = "armed_wake_at_ms"

    /** The next alarm the user's clock app set ([AlarmManager.setAlarmClock]); null when none. */
    fun nextAlarmMs(context: Context): Long? =
        try {
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.nextAlarmClock?.triggerTime
        } catch (e: Exception) {
            Log.w(TAG, "nextAlarmClock failed", e)
            null
        }

    /**
     * Arms, re-arms or cancels for [wakeAtMs]. Returns a `PRE_WAKE_FETCH` log line when something
     * changed, null when the armed state already matches.
     */
    fun arm(
        context: Context,
        wakeAtMs: Long? = nextAlarmMs(context),
        nowMs: Long = System.currentTimeMillis(),
        trigger: String,
    ): String? {
        val prefs = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        val armed = prefs.getLong(KEY_ARMED_WAKE_AT_MS, 0L).takeIf { it > 0L }
        val checkAt = ForecastHorizonCadence.preWakeCheckAtMs(wakeAtMs, nowMs)
        if (checkAt == null || wakeAtMs == null) {
            if (armed == null) return null
            prefs.edit().remove(KEY_ARMED_WAKE_AT_MS).apply()
            // The work for a passed alarm has already run; cancelling it is then a no-op.
            if (armed > nowMs) WidgetWorkScheduler.cancelPreWakeSync(context)
            val reason = when {
                wakeAtMs == null -> "no_alarm"
                wakeAtMs <= nowMs -> "passed"
                else -> "too_far"
            }
            return "action=cancelled reason=$reason armedAlarm=${clock(armed)} trigger=$trigger"
        }
        if (armed == wakeAtMs) return null
        WidgetWorkScheduler.enqueuePreWakeSync(context, delayMs = checkAt - nowMs)
        prefs.edit().putLong(KEY_ARMED_WAKE_AT_MS, wakeAtMs).apply()
        return "action=armed at=${clock(checkAt)} alarm=${clock(wakeAtMs)} delayMin=${(checkAt - nowMs) / 60_000L} " +
            "trigger=$trigger"
    }

    private fun clock(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).let {
            "%s %02d:%02d".format(it.toLocalDate(), it.hour, it.minute)
        }
}
