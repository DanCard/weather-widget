package com.weatherwidget.widget

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Schedules lightweight current-temperature-only refresh work.
 */
object CurrentTempUpdateScheduler {
    private const val TAG = "CurrentTempScheduler"
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

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
            logSchedulerEvent(
                context = context,
                tag = "CURR_FETCH_WORK_ENQUEUED",
                message =
                    "type=immediate reason=$reason opportunistic=$opportunistic force=$force " +
                        "policyDelayMinutes=0 dueAt=${formatTime(System.currentTimeMillis())} " +
                        "workId=${workRequest.id}",
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
            val dueAtMs =
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(CurrentTempFetchPolicy.CHARGING_INTERVAL_MINUTES)
            logSchedulerEvent(
                context = context,
                tag = "CURR_FETCH_WORK_ENQUEUED",
                message =
                    "type=charging_loop reason=charging_loop opportunistic=false force=false " +
                        "policyDelayMinutes=${CurrentTempFetchPolicy.CHARGING_INTERVAL_MINUTES} " +
                        "dueAt=${formatTime(dueAtMs)} workId=${workRequest.id}",
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
            logSchedulerEvent(
                context = context,
                tag = "CURR_FETCH_WORK_CANCELLED",
                message = "name=${WeatherWidgetProvider.WORK_NAME_CURRENT_TEMP}",
            )
            Log.d(TAG, "cancel: canceled ${WeatherWidgetProvider.WORK_NAME_CURRENT_TEMP}")
        }.onFailure { e ->
            Log.e(TAG, "cancel failed: ${e.message}", e)
        }
    }

    private fun logSchedulerEvent(
        context: Context,
        tag: String,
        message: String,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            WeatherDatabase.getDatabase(context).appLogDao().log(tag, message, "INFO")
        }
    }

    private fun formatTime(timestampMs: Long): String = timestampFormatter.format(Instant.ofEpochMilli(timestampMs))
}
