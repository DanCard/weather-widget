package com.weatherwidget.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * JobService for opportunistic UI updates on Android 8+.
 *
 * Uses JobScheduler to piggyback on system wakeups without creating independent wakeups.
 * Scheduled to run periodically but only when the device is already awake.
 */
@RequiresApi(Build.VERSION_CODES.O)
class OpportunisticUpdateJobService : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        Log.d(TAG, "Opportunistic update job started")

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
                            androidx.work.ExistingWorkPolicy.REPLACE,
                            workRequest,
                        )
                    } else {
                        Log.d(TAG, "No recent hourly data, skipping opportunistic update")
                    }

                    CurrentTempUpdateScheduler.enqueueImmediateUpdate(
                        context = applicationContext,
                        reason = "opportunistic_job",
                        opportunistic = true,
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

        /**
         * Schedule opportunistic UI updates using JobScheduler.
         * Only available on Android 8+.
         */
        fun scheduleOpportunisticUpdate(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }

            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val componentName = ComponentName(context, OpportunisticUpdateJobService::class.java)

            val jobInfo =
                JobInfo.Builder(JOB_ID, componentName)
                    // Run every 30 minutes, but only when device is already awake
                    .setPeriodic(TimeUnit.MINUTES.toMillis(30))
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
                Log.d(TAG, "Opportunistic update job scheduled successfully")
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
    }
}
