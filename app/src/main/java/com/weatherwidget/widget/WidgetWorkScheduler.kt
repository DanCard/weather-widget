package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Single owner for widget WorkManager names, request construction, and collision policy.
 *
 * Running-capable requests never use REPLACE. KEEP is reserved for redundant work; required
 * follow-ups use APPEND_OR_REPLACE so their input/callback contract cannot be discarded.
 */
object WidgetWorkScheduler {
    const val WORK_NAME_PERIODIC = "weather_widget_update"
    const val WORK_NAME_ONE_TIME = "weather_widget_one_time"
    const val WORK_NAME_STARTUP_DELAYED = "weather_widget_startup_delayed"
    const val WORK_NAME_CURRENT_TEMP = "weather_widget_current_temp"
    const val WORK_NAME_OBSERVATION_BACKFILL = "weather_widget_observation_backfill"
    const val WORK_NAME_UI = "weather_widget_one_time_ui"
    private const val WORK_NAME_UI_DELAYED_PREFIX = "weather_widget_one_time_ui_delayed_"

    fun schedulePeriodicSync(context: Context) {
        val batteryStatus: Intent? =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val isCharging = BatteryStatePolicy.isEffectivelyCharging(batteryStatus)
        val tickMinutes = ForecastFetchPolicy.periodicTickMinutes(isCharging, batteryLevel)
        val request =
            PeriodicWorkRequestBuilder<WeatherWidgetWorker>(tickMinutes, TimeUnit.MINUTES)
                .setInputData(
                    Data.Builder()
                        .putString(
                            WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON,
                            "periodic_${tickMinutes}m",
                        )
                        .build(),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        val nextWindowStartMs =
            System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(tickMinutes)
        Log.d(
            TAG,
            "PERIODIC_REFRESH_SCHEDULE: name=$WORK_NAME_PERIODIC intervalMinutes=$tickMinutes " +
                "charging=$isCharging battery=$batteryLevel policy=update " +
                "nextWindowStartMs=$nextWindowStartMs",
        )
    }

    fun enqueueRedundantImmediateSync(
        context: Context,
        forceRefresh: Boolean = false,
        reason: String = "unspecified",
        targetSourceId: String? = null,
    ): OneTimeWorkRequest =
        enqueueFullSync(
            context = context,
            uniqueName = WORK_NAME_ONE_TIME,
            policy = ExistingWorkPolicy.KEEP,
            forceRefresh = forceRefresh,
            reason = reason,
            targetSourceId = targetSourceId,
        )

    fun enqueueRequiredImmediateSync(
        context: Context,
        forceRefresh: Boolean = true,
        reason: String,
        targetSourceId: String? = null,
    ): OneTimeWorkRequest =
        enqueueFullSync(
            context = context,
            uniqueName = WORK_NAME_ONE_TIME,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            forceRefresh = forceRefresh,
            reason = reason,
            targetSourceId = targetSourceId,
        )

    fun enqueueForcedSync(
        context: Context,
        reason: String,
        policy: ExistingWorkPolicy,
        initialDelayMs: Long = 0L,
        targetSourceId: String? = null,
    ): OneTimeWorkRequest {
        require(policy != ExistingWorkPolicy.REPLACE) {
            "Running-capable widget work must never use REPLACE"
        }
        return enqueueFullSync(
            context = context,
            uniqueName = WORK_NAME_ONE_TIME,
            policy = policy,
            forceRefresh = true,
            reason = reason,
            initialDelayMs = initialDelayMs,
            targetSourceId = targetSourceId,
        )
    }

    fun enqueueDelayedStartupSync(
        context: Context,
        reason: String,
        initialDelayMs: Long,
    ): OneTimeWorkRequest =
        enqueueFullSync(
            context = context,
            uniqueName = WORK_NAME_STARTUP_DELAYED,
            policy = ExistingWorkPolicy.KEEP,
            forceRefresh = false,
            reason = reason,
            initialDelayMs = initialDelayMs,
        )

    fun enqueueRequiredNoHourlyFollowUp(
        context: Context,
        appWidgetId: Int,
        date: String,
        lat: Double,
        lon: Double,
        targetSourceId: String,
    ): OneTimeWorkRequest =
        enqueueFullSync(
            context = context,
            uniqueName = WORK_NAME_ONE_TIME,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            forceRefresh = true,
            reason = "day_click_no_hourly",
            targetSourceId = targetSourceId,
            extraInput = {
                putInt(WeatherWidgetWorker.KEY_NO_HOURLY_WIDGET_ID, appWidgetId)
                putString(WeatherWidgetWorker.KEY_NO_HOURLY_DATE, date)
                putDouble(WeatherWidgetWorker.KEY_NO_HOURLY_LAT, lat)
                putDouble(WeatherWidgetWorker.KEY_NO_HOURLY_LON, lon)
            },
        )

    /**
     * Enqueues a required observation-history repair after any active repair.
     *
     * A newer request is not redundant with a running request because its lookback window ends
     * later. KEEP can therefore discard the only request that covers a newly visible overnight
     * gap. APPEND_OR_REPLACE retains that follow-up without cancelling a running worker.
     */
    fun enqueueRequiredObservationBackfill(
        context: Context,
        latitude: Double,
        longitude: Double,
        lookbackHours: Long,
        reason: String,
        initialDelayMs: Long,
    ): OneTimeWorkRequest {
        val request =
            OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                .setInputData(
                    Data.Builder()
                        .putBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, true)
                        .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LAT, latitude)
                        .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LON, longitude)
                        .putLong(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_HOURS, lookbackHours)
                        .putString(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_REASON, reason)
                        .build(),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_OBSERVATION_BACKFILL,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        Log.d(
            TAG,
            "Observation backfill enqueued policy=APPEND_OR_REPLACE reason=$reason " +
                "delayMs=$initialDelayMs id=${request.id}",
        )
        return request
    }

    fun enqueueUiRepaint(
        context: Context,
        reason: String = "unspecified",
    ): OneTimeWorkRequest {
        val request = buildUiRequest(reason)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_UI,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        Log.d(TAG, "UI repaint enqueued reason=$reason id=${request.id}")
        return request
    }

    fun enqueueDelayedUiRepaint(
        context: Context,
        appWidgetId: Int,
        reason: String,
        initialDelayMs: Long,
    ): OneTimeWorkRequest {
        require(appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
        require(initialDelayMs > 0L)
        val request =
            buildUiRequest(reason, initialDelayMs)
        WorkManager.getInstance(context).enqueueUniqueWork(
            delayedUiWorkName(appWidgetId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        Log.d(
            TAG,
            "Delayed UI repaint enqueued widget=$appWidgetId reason=$reason " +
                "delayMs=$initialDelayMs id=${request.id}",
        )
        return request
    }

    internal fun delayedUiWorkName(appWidgetId: Int): String =
        "$WORK_NAME_UI_DELAYED_PREFIX$appWidgetId"

    private fun enqueueFullSync(
        context: Context,
        uniqueName: String,
        policy: ExistingWorkPolicy,
        forceRefresh: Boolean,
        reason: String,
        initialDelayMs: Long = 0L,
        targetSourceId: String? = null,
        extraInput: (Data.Builder.() -> Unit)? = null,
    ): OneTimeWorkRequest {
        val data =
            Data.Builder()
                .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, forceRefresh)
                .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, reason)
                .apply {
                    targetSourceId?.let {
                        putString(WeatherWidgetWorker.KEY_TARGET_SOURCE, it)
                    }
                    extraInput?.invoke(this)
                }
                .build()
        val request =
            OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                .setInputData(data)
                .apply {
                    if (initialDelayMs > 0L) {
                        setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                    } else {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, policy, request)
        Log.d(
            TAG,
            "Full sync enqueued name=$uniqueName policy=$policy reason=$reason " +
                "force=$forceRefresh delayMs=$initialDelayMs id=${request.id}",
        )
        return request
    }

    private fun buildUiRequest(
        reason: String,
        initialDelayMs: Long = 0L,
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean(WeatherWidgetWorker.KEY_UI_ONLY_REFRESH, true)
                    .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, reason)
                    .build(),
            )
            .apply {
                if (initialDelayMs > 0L) {
                    setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                } else {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()

    private const val TAG = "WidgetWorkScheduler"
}
