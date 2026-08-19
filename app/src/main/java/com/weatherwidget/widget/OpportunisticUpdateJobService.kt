package com.weatherwidget.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.weatherwidget.WeatherWidgetApp
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
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
        val processAgeMs = WeatherWidgetApp.processAgeMs(SystemClock.elapsedRealtime())
        val state =
            "charging=${powerState.isCharging} battery=${powerState.batteryLevel} " +
                "cutoff=${CurrentTempFetchPolicy.OPPORTUNISTIC_MIN_BATTERY_PERCENT} processAgeMs=$processAgeMs"
        if (
            !CurrentTempFetchPolicy.shouldScheduleOpportunisticJob(
                batteryLevel = powerState.batteryLevel,
            )
        ) {
            Log.d(TAG, "Opportunistic job stopping at battery cutoff: $state")
            writeLog(applicationContext, "outcome=cancelled_battery_cutoff $state", "INFO")
            cancelOpportunisticUpdate(applicationContext)
            return false
        }

        Log.d(TAG, "Opportunistic update job started $state")

        // Re-arm the plug-in trigger from here rather than from the trigger's own run. It only arms
        // while discharging, and this job is the most reliable recurring execution the app gets on
        // battery. Doing it from PowerConnectedJobService's own path would re-schedule that job's
        // JOB_ID while it is still running, which JobScheduler treats as a replacement and answers
        // with onStopJob.
        PowerConnectedJobService.ensureScheduled(applicationContext)
        // A young process does NOT mean "startup churn" here. JobScheduler cold-starts the process
        // in order to run this job — on a device that aggressively kills the app that is the normal
        // case, not the exception — so `processAgeMs` is a second or two on exactly the runs that
        // matter most. Skipping the whole body then made the 45-minute on-battery loop a no-op: it
        // ran 5 times on Aug 17, 2 on Aug 18 and 0 on Aug 19, while the charging loop (which stops
        // outright when unplugged) was the only thing still fetching. See
        // plans/260819-today-overlay-station-drop-and-dead-opportunistic-loop.md.
        //
        // The grace now suppresses only the UI repaint, which is the part a starting process is
        // about to do anyway. The fetch always proceeds: it is throttled and freshness-gated
        // downstream (CURR_FETCH_THROTTLE_SKIP / the repository staleness check), so a redundant
        // enqueue costs an early return, whereas a skipped one costs 45 minutes of staleness.
        val withinStartupGrace = processAgeMs < STARTUP_GRACE_PERIOD_MS

        job =
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val hasHourly = DataFreshness.hasRecentHourlyData(applicationContext)
                    // Check if we have recent hourly data for interpolation
                    if (hasHourly && !withinStartupGrace) {
                        Log.d(TAG, "Triggering UI-only update from opportunistic job")

                        WidgetWorkScheduler.enqueueUiRepaint(
                            applicationContext,
                            reason = "opportunistic_job_ui",
                        )
                    } else {
                        Log.d(
                            TAG,
                            "Skipping opportunistic UI repaint: " +
                                if (!hasHourly) "no recent hourly data" else "within startup grace",
                        )
                    }
                    logJob(
                        applicationContext,
                        "outcome=fetch_enqueued $state hasRecentHourly=$hasHourly " +
                            "uiRepaint=${if (hasHourly && !withinStartupGrace) "enqueued" else "skipped"} " +
                            "startupGrace=$withinStartupGrace",
                        "INFO",
                    )

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

        /**
         * `app_logs` tag for the on-battery refresh loop. This class used to log only to logcat, so
         * a loop that had stopped running left no trace that outlived the log buffer — the failure
         * was diagnosable only by noticing the *absence* of `CURR_FETCH_START reason=opportunistic_job`
         * rows. Every arm, fire and early return now persists.
         */
        const val DB_TAG = "OPPORTUNISTIC_JOB"
        private const val JOB_ID = 1002
        private const val STARTUP_GRACE_PERIOD_MS = 15_000L

        /**
         * Fire-and-forget `app_logs` write. Scheduling happens from [WeatherWidgetApp.onCreate],
         * which deliberately performs no database work, so the write is pushed off the calling
         * thread rather than being made a suspend point of the caller.
         */
        private fun writeLog(
            context: Context,
            message: String,
            level: String = "DEBUG",
        ) {
            // Everything, including resolving the application context, happens inside the coroutine
            // and inside [logJob]'s catch. Scheduling is reachable from Application.onCreate and from
            // unit tests with a bare mocked Context, and a breadcrumb must never be the thing that
            // brings either down. Callers are already application-scoped (JobService / Application),
            // and the reference lives only for this write.
            CoroutineScope(Dispatchers.IO).launch { logJob(context, message, level) }
        }

        private suspend fun logJob(
            context: Context,
            message: String,
            level: String = "DEBUG",
        ) {
            try {
                WeatherDatabase.getDatabase(context).appLogDao().log(DB_TAG, message, level)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist opportunistic job log", e)
            }
        }

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
                writeLog(
                    context,
                    "outcome=not_scheduled_battery_cutoff charging=${powerState.isCharging} " +
                        "battery=${powerState.batteryLevel} " +
                        "cutoff=${CurrentTempFetchPolicy.OPPORTUNISTIC_MIN_BATTERY_PERCENT}",
                    "INFO",
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
                writeLog(
                    context,
                    "outcome=scheduled intervalMin=${CurrentTempFetchPolicy.OPPORTUNISTIC_INTERVAL_MINUTES} " +
                        "charging=${powerState.isCharging} battery=${powerState.batteryLevel}",
                    "INFO",
                )
            } else {
                Log.e(TAG, "Failed to schedule opportunistic update job")
                writeLog(
                    context,
                    "outcome=schedule_failed result=$result charging=${powerState.isCharging} " +
                        "battery=${powerState.batteryLevel}",
                    "ERROR",
                )
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
            writeLog(context, "outcome=cancelled", "INFO")
        }

        internal fun getPowerState(context: Context): BatterySnapshot =
            BatterySnapshotProvider.snapshot(context)
    }
}
