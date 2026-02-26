package com.weatherwidget.widget

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules lightweight current-temperature-only refresh work.
 */
object CurrentTempUpdateScheduler {
    private const val TAG = "CurrentTempScheduler"

    fun enqueueImmediateUpdate(
        context: Context,
        reason: String,
        opportunistic: Boolean,
        force: Boolean = false,
    ) {
        runCatching {
            val constraints =
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val workRequest =
                OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                    .setInputData(
                        Data.Builder()
                            .putBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_ONLY, true)
                            .putBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_OPPORTUNISTIC, opportunistic)
                            .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, reason)
                            .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, force)
                            .build(),
                    )
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WeatherWidgetProvider.WORK_NAME_CURRENT_TEMP,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
            Log.d(TAG, "enqueueImmediateUpdate: reason=$reason opportunistic=$opportunistic force=$force id=${workRequest.id}")
        }.onFailure { e ->
            Log.e(TAG, "enqueueImmediateUpdate failed: ${e.message}", e)
        }
    }

    fun scheduleNextChargingUpdate(context: Context) {
        runCatching {
            val constraints =
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val workRequest =
                OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                    .setInitialDelay(CurrentTempFetchPolicy.CHARGING_INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setInputData(
                        Data.Builder()
                            .putBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_ONLY, true)
                            .putBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_OPPORTUNISTIC, false)
                            .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, "charging_loop")
                            .build(),
                    )
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WeatherWidgetProvider.WORK_NAME_CURRENT_TEMP,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
            Log.d(
                TAG,
                "scheduleNextChargingUpdate: delay=${CurrentTempFetchPolicy.CHARGING_INTERVAL_MINUTES}m id=${workRequest.id}",
            )
        }.onFailure { e ->
            Log.e(TAG, "scheduleNextChargingUpdate failed: ${e.message}", e)
        }
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(WeatherWidgetProvider.WORK_NAME_CURRENT_TEMP)
            Log.d(TAG, "cancel: canceled ${WeatherWidgetProvider.WORK_NAME_CURRENT_TEMP}")
        }.onFailure { e ->
            Log.e(TAG, "cancel failed: ${e.message}", e)
        }
    }
}
