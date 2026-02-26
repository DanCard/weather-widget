package com.weatherwidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives scheduled UI update alarms and triggers UI-only widget refresh.
 */
class UIUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleUiUpdateAlarm(context)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private suspend fun handleUiUpdateAlarm(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!powerManager.isInteractive) {
            Log.d(TAG, "Screen is off, skipping UI update work but preserving schedule")
            UIUpdateScheduler(context).scheduleNextUpdate()
            return
        }

        Log.d(TAG, "UI update alarm triggered")

        // Trigger UI-only update (no network fetch)
        val workRequest =
            OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                .setInputData(
                    Data.Builder()
                        .putBoolean(WeatherWidgetWorker.KEY_UI_ONLY_REFRESH, true)
                        .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, "ui_update_alarm")
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WeatherWidgetProvider.WORK_NAME_ONE_TIME + "_ui",
            androidx.work.ExistingWorkPolicy.REPLACE,
            workRequest,
        )
        Log.d(TAG, "UI-only update enqueued")

        UIUpdateScheduler(context).scheduleNextUpdate()
    }

    companion object {
        private const val TAG = "UIUpdateReceiver"
    }
}
