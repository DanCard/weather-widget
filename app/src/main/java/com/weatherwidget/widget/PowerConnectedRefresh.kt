package com.weatherwidget.widget

import android.content.Context
import android.os.PowerManager
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.util.SharedPreferencesUtil

/**
 * Everything plugging the device in should set back in motion, shared by the two entry points that
 * can observe that event.
 *
 * [ScreenOnReceiver] is the historical one and is effectively dead: an app targeting API 26+ does
 * not receive implicit broadcasts through a manifest-declared receiver unless the action is on the
 * framework's exemption list, and `ACTION_POWER_CONNECTED` is not. Three days of `app_logs` across
 * a Pixel 7 Pro and a Samsung fold hold zero `POWER_CONNECTED_EVENT` rows, and a real plug event
 * dispatched by the system reached neither device's receiver even with the process alive. The
 * `exported="true"` fix attempted in 260413 addressed a gate that was never the one closing.
 *
 * [PowerConnectedJobService] is the path that actually runs — a JobScheduler charging constraint,
 * which is delivered to background apps. The receiver is kept because it costs nothing and still
 * works wherever the broadcast is delivered.
 *
 * See `plans/260818-power-connected-broadcast-never-delivered.md`.
 */
internal object PowerConnectedRefresh {
    const val PREFS_NAME = "screen_on_receiver_prefs"
    const val KEY_LAST_POWER_CONNECTED_REFRESH_MS = "last_power_connected_refresh_ms"

    /** What [run] decided, carried out to the caller so it can persist the diagnostic row. */
    data class Outcome(
        val result: String,
        val nowMs: Long,
        val lastRefreshMs: Long,
        val elapsedMs: Long,
    )

    /**
     * Restarts the loops a plug-in should restart, then enqueues the debounced current-temp
     * refresh. Pure scheduling — no I/O — so it is safe on the main thread and on a JobService's
     * `onStartJob`.
     */
    fun run(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome {
        // Re-enqueue the periodic forecast worker with the charging-cadence interval (60 min).
        WidgetWorkScheduler.schedulePeriodicSync(context)
        OpportunisticUpdateJobService.scheduleOpportunisticUpdate(context)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isInteractive) {
            NonPrimaryObservationScheduler.scheduleNextUpdate(context, isScreenInteractive = true)
        }

        val prefs = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        val lastRefreshMs = prefs.getLong(KEY_LAST_POWER_CONNECTED_REFRESH_MS, 0L)
        val elapsedMs = nowMs - lastRefreshMs

        if (!PowerConnectedRefreshPolicy.shouldEnqueueRefresh(nowMs, lastRefreshMs)) {
            return Outcome("debounced_skip", nowMs, lastRefreshMs, elapsedMs)
        }

        prefs.edit().putLong(KEY_LAST_POWER_CONNECTED_REFRESH_MS, nowMs).apply()
        // NOT opportunistic. An opportunistic run is gated on
        // `batteryLevel > OPPORTUNISTIC_MIN_BATTERY_PERCENT` and ignores charging entirely, so
        // flagging the plug-in refresh that way blocks it below 65% — the exact battery level at
        // which people reach for a charger. A non-opportunistic run is gated on `isCharging`,
        // which is true by construction here: this only runs off a charging transition. The flag
        // was moot while the broadcast was undelivered; it stops being moot now.
        CurrentTempUpdateScheduler.enqueueImmediateUpdate(
            context = context,
            reason = "power_connected_lazy",
            opportunistic = false,
        )
        return Outcome("enqueued", nowMs, lastRefreshMs, elapsedMs)
    }

    /**
     * `source=` distinguishes the job from the broadcast, so a future reader can tell at a glance
     * whether the receiver ever came back to life on some OEM build.
     */
    suspend fun writeLog(
        context: Context,
        outcome: Outcome,
        source: String,
    ) {
        WeatherDatabase.getDatabase(context).appLogDao().log(
            "POWER_CONNECTED_EVENT",
            "result=${outcome.result} source=$source nowMs=${outcome.nowMs} " +
                "lastRefreshMs=${outcome.lastRefreshMs} elapsedMs=${outcome.elapsedMs} " +
                "debounceMs=${PowerConnectedRefreshPolicy.DEBOUNCE_MS}",
        )
    }
}
