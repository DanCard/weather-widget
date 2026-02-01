package com.weatherwidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receiver that triggers widget updates when the user unlocks the screen.
 *
 * Always does UI-only update from cache for instant feedback.
 * If charging and data is stale, also triggers background data fetch.
 */
class ScreenOnReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return

        Log.d(TAG, "Screen unlocked - triggering UI update")

        // Always trigger UI-only update for instant feedback
        triggerUiOnlyUpdate(context)

        // If charging, check data staleness and fetch if needed
        if (isCharging(context)) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (DataFreshness.isDataStale(context)) {
                        Log.d(TAG, "Screen unlocked while charging and data is stale - triggering background fetch")
                        triggerBackgroundFetch(context)
                    } else {
                        Log.d(TAG, "Screen unlocked while charging but data is fresh - skipping fetch")
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        } else {
            Log.d(TAG, "Screen unlocked but not charging - UI update only")
        }
    }

    private fun isCharging(context: Context): Boolean {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun triggerUiOnlyUpdate(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean(WeatherWidgetWorker.KEY_UI_ONLY_REFRESH, true)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
        Log.d(TAG, "UI-only update enqueued")
    }

    private fun triggerBackgroundFetch(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
        Log.d(TAG, "Background data fetch enqueued")
    }

    companion object {
        private const val TAG = "ScreenOnReceiver"
    }
}
