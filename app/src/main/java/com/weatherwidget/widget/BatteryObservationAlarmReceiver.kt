package com.weatherwidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires the on-battery, screen-on observation loop ([BatteryObservationAlarm]).
 *
 * Re-checks the policy at fire time: if the device was plugged in, the screen went off without the
 * cancel reaching us, or the battery fell below 70%, the loop simply ends here. Otherwise it
 * enqueues an immediate primary-source fetch and arms the next alarm; the fetch's own post-run
 * scheduling then re-arms it from the fetch's completion, which can only move it earlier.
 */
class BatteryObservationAlarmReceiver : BroadcastReceiver() {
    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    /** Wall clock; replaced in tests that simulate hours of the loop. */
    internal var clock: () -> Long = System::currentTimeMillis

    override fun onReceive(context: Context, intent: Intent) {
        BatteryObservationAlarm.markFired(context)
        val battery = BatterySnapshotProvider.snapshot(context)
        val interactive = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val runs = !battery.isCharging &&
            CurrentTempFetchPolicy.loopIntervalMinutes(battery.isCharging, interactive, battery.batteryLevel) != null

        if (runs) {
            CurrentTempUpdateScheduler.enqueueImmediateUpdate(
                context = context,
                reason = CurrentTempFetchPolicy.loopReason(isCharging = false, overdue = false),
                opportunistic = false,
                targetSourceId = WidgetStateManager(context.applicationContext).getPrimarySource().id,
                expedited = true,
            )
            val nowMs = clock()
            BatteryObservationAlarm.arm(
                context,
                nowMs + java.util.concurrent.TimeUnit.MINUTES.toMillis(
                    CurrentTempFetchPolicy.BATTERY_SCREEN_ON_INTERVAL_MINUTES,
                ),
                nowMs,
            )
        }
        Log.d(TAG, "fired: runs=$runs charging=${battery.isCharging} battery=${battery.batteryLevel} interactive=$interactive")

        val pendingResult = goAsync()
        CoroutineScope(ioDispatcher).launch {
            try {
                WeatherDatabase.getDatabase(context).appLogDao().log(
                    "BATTERY_OBS_ALARM",
                    "outcome=${if (runs) "fetch_enqueued" else "loop_ended"} charging=${battery.isCharging} " +
                        "battery=${battery.batteryLevel} interactive=$interactive",
                    "INFO",
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist BATTERY_OBS_ALARM log", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BatteryObsAlarmRx"
    }
}
