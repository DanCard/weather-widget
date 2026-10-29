package com.weatherwidget.widget

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.shared.util.PreUnplugFetchRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms [PreUnplugFetchScheduler] when the user's next alarm changes — including the moment an alarm
 * goes off, which is how a fired alarm reaches the charge history and earns (or loses) the unplug
 * predictor's trust. The action is on the framework's implicit-broadcast exemption list, but so the
 * docs said of nothing this app has seen delivered (`USER_PRESENT`, `ACTION_POWER_DISCONNECTED`), so
 * every delivery logs a `PRE_UNPLUG_FETCH action=alarm_changed` row: their absence on a device means it
 * never arrives, and the hourly charging sync and the plug-in job re-arm alone.
 */
class NextAlarmChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) return
        val app = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WeatherDatabase.getDatabase(app).appLogDao().log(
                    PreUnplugFetchRunner.FETCH_TAG,
                    "action=alarm_changed nextAlarmMs=${PreUnplugFetchScheduler.nextAlarmMs(app)}",
                    "INFO",
                )
                PreUnplugFetchScheduler.runner(app).arm(trigger = "alarm_changed")
            } catch (e: Exception) {
                Log.e(TAG, "Pre-unplug re-arm failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "NextAlarmChanged"
    }
}
