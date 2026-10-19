package com.weatherwidget.widget

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms [PreWakeFetchScheduler] when the user's next alarm changes. The action is on the framework's
 * implicit-broadcast exemption list, but so the docs said of nothing this app has seen delivered
 * (`USER_PRESENT`, `ACTION_POWER_DISCONNECTED`), so every delivery logs a `PRE_WAKE_FETCH
 * action=alarm_changed` row: their absence on a device means it never arrives, and the hourly charging
 * sync and the plug-in job re-arm alone.
 */
class NextAlarmChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) return
        val app = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = WeatherDatabase.getDatabase(app).appLogDao()
                val change = PreWakeFetchScheduler.arm(app, trigger = "alarm_changed")
                dao.log(PreWakeFetchScheduler.LOG_TAG, "action=alarm_changed result=${change ?: "unchanged"}", "INFO")
            } catch (e: Exception) {
                Log.e(TAG, "Pre-wake re-arm failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "NextAlarmChanged"
    }
}
