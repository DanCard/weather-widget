package com.weatherwidget.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.weatherwidget.WeatherWidgetApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * JobService for opportunistic UI updates on Android 8+.
 *
 * JobScheduler may wake the process and holds a wake lock while this service runs. To bound that
 * battery cost, the job is scheduled only above the battery cutoff. Unplugged work fetches only
 * the primary source.
 */
@RequiresApi(Build.VERSION_CODES.O)
class OpportunisticUpdateJobService : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val powerState = getPowerState(applicationContext)
        if (
            !CurrentTempFetchPolicy.shouldScheduleOpportunisticJob(
                batteryLevel = powerState.batteryLevel,
            )
        ) {
            Log.d(
                TAG,
                "Opportunistic job stopping at battery cutoff: charging=${powerState.isCharging} " +
                    "battery=${powerState.batteryLevel}% cutoff=${CurrentTempFetchPolicy.OPPORTUNISTIC_MIN_BATTERY_PERCENT}%",
            )
            cancelOpportunisticUpdate(applicationContext)
            return false
        }

        Log.d(
            TAG,
            "Opportunistic update job started charging=${powerState.isCharging} battery=${powerState.batteryLevel}%",
        )
        val processAgeMs = WeatherWidgetApp.processAgeMs(SystemClock.elapsedRealtime())
        if (processAgeMs < STARTUP_GRACE_PERIOD_MS) {
            Log.d(TAG, "Skipping opportunistic startup churn; processAgeMs=$processAgeMs")
            jobFinished(params, false)
            return false
        }

        job =
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Check if we have recent hourly data for interpolation
                    if (DataFreshness.hasRecentHourlyData(applicationContext)) {
                        Log.d(TAG, "Triggering UI-only update from opportunistic job")

                        // Trigger UI-only update (no network fetch)
                        val workRequest =
                            OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                                .setInputData(
                                    Data.Builder()
                                        .putBoolean(WeatherWidgetWorker.KEY_UI_ONLY_REFRESH, true)
                                        .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, "opportunistic_job_ui")
                                        .build(),
                                )
                                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                .build()

                        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                            WeatherWidgetProvider.WORK_NAME_ONE_TIME + "_ui",
                            // Same "_ui" worker as triggerUiOnlyUpdate: never cancel a running repaint
                            // (segfaults ART on debuggable builds — [[samsung_widget_dead_native_sigsegv]]).
                            androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                            workRequest,
                        )
                    } else {
                        Log.d(TAG, "No recent hourly data, skipping opportunistic update")
                    }

                    CurrentTempUpdateScheduler.enqueueImmediateUpdate(
                        context = applicationContext,
                        reason = "opportunistic_job",
                        opportunistic = true,
                        targetSourceId =
                            CurrentTempFetchPolicy.opportunisticTargetSourceId(
                                isCharging = powerState.isCharging,
                                primarySourceId = WidgetStateManager(applicationContext).getPrimarySource().id,
                            ),
                    )
                } finally {
                    jobFinished(params, false)
                }
            }

        return true // Job is running asynchronously
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Log.d(TAG, "Opportunistic update job stopped")
        job?.cancel()
        return true // Reschedule if stopped
    }

    companion object {
        private const val TAG = "OpportunisticUpdateJob"
        private const val JOB_ID = 1002
        private const val STARTUP_GRACE_PERIOD_MS = 15_000L

        /**
         * Schedule opportunistic UI updates using JobScheduler.
         * Only available on Android 8+.
         */
        fun scheduleOpportunisticUpdate(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }

            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val powerState = getPowerState(context)
            if (
                !CurrentTempFetchPolicy.shouldScheduleOpportunisticJob(
                    batteryLevel = powerState.batteryLevel,
                )
            ) {
                jobScheduler.cancel(JOB_ID)
                Log.d(
                    TAG,
                    "Opportunistic update job not scheduled: charging=${powerState.isCharging} " +
                        "battery=${powerState.batteryLevel}% cutoff=${CurrentTempFetchPolicy.OPPORTUNISTIC_MIN_BATTERY_PERCENT}%",
                )
                return
            }

            val componentName = ComponentName(context, OpportunisticUpdateJobService::class.java)

            val jobInfo =
                JobInfo.Builder(JOB_ID, componentName)
                    .setPeriodic(TimeUnit.MINUTES.toMillis(CurrentTempFetchPolicy.OPPORTUNISTIC_INTERVAL_MINUTES))
                    // Don't require charging or idle - run opportunistically
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)
                    // Require any network for potential future use
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    // Persist across reboots
                    .setPersisted(true)
                    .build()

            val result = jobScheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.d(
                    TAG,
                    "Opportunistic update job scheduled every " +
                        "${CurrentTempFetchPolicy.OPPORTUNISTIC_INTERVAL_MINUTES} minutes " +
                        "charging=${powerState.isCharging} battery=${powerState.batteryLevel}%",
                )
            } else {
                Log.e(TAG, "Failed to schedule opportunistic update job")
            }
        }

        /**
         * Cancel the opportunistic update job.
         */
        fun cancelOpportunisticUpdate(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }

            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobScheduler.cancel(JOB_ID)
            Log.d(TAG, "Opportunistic update job cancelled")
        }

        internal fun getPowerState(context: Context): OpportunisticPowerState {
            val batteryStatus: Intent? =
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val rawLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryLevel =
                if (rawLevel >= 0 && scale > 0) {
                    (rawLevel * 100) / scale
                } else {
                    -1
                }
            return OpportunisticPowerState(
                isCharging = BatteryStatePolicy.isEffectivelyCharging(batteryStatus),
                batteryLevel = batteryLevel,
            )
        }
    }
}

internal data class OpportunisticPowerState(
    val isCharging: Boolean,
    val batteryLevel: Int,
)
