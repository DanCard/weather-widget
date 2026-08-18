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
import androidx.annotation.VisibleForTesting
import com.weatherwidget.di.RepositoryEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Detects "the device was just plugged in" with a JobScheduler charging constraint, because the
 * `ACTION_POWER_CONNECTED` broadcast [ScreenOnReceiver] listens for never arrives (see
 * [PowerConnectedRefresh] for the evidence).
 *
 * The job is one-shot rather than periodic so it fires on the *transition*: while unplugged it
 * sits pending with the charging constraint unsatisfied, and the moment the charger goes in
 * JobScheduler dispatches it. It then re-arms itself for the next plug-in.
 *
 * That re-arm carries a minimum latency, which is load-bearing: a zero-latency re-arm while the
 * device is still charging would find its constraint already satisfied and re-fire immediately,
 * forever. With the latency the still-charging case degrades into a harmless heartbeat at the
 * charging-loop cadence (and doubles as recovery for a charging loop that died), while the
 * unplugged case — the one that matters — keeps firing the instant power returns.
 */
@RequiresApi(Build.VERSION_CODES.O)
class PowerConnectedJobService : JobService() {
    private var job: Job? = null

    /** Test seam, mirroring [ScreenOnReceiver.resampleLocation]. */
    @VisibleForTesting
    internal var resampleLocation: suspend (Context, String) -> Unit = { ctx, trigger ->
        EntryPointAccessors
            .fromApplication(ctx.applicationContext, RepositoryEntryPoint::class.java)
            .gpsResampler()
            .resample(ctx, trigger)
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Log.d(TAG, "Charging constraint satisfied - running plug-in refresh")
        val outcome = PowerConnectedRefresh.run(applicationContext)

        job =
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    PowerConnectedRefresh.writeLog(applicationContext, outcome, source = "job")
                    // Independent of the current-temp debounce: putting the phone on the charger is
                    // the moment it has most likely just finished moving.
                    resampleLocation(applicationContext, "power_connected")
                } catch (e: Exception) {
                    Log.w(TAG, "Plug-in refresh tail failed", e)
                } finally {
                    // Order matters. Scheduling JOB_ID while this very job is still running makes
                    // JobScheduler treat the arm as a replacement, tear the running job out of the
                    // JobStore and call onStopJob — which cancelled this coroutine mid-flight and
                    // lost the log row and the resample. Finish first, then re-arm.
                    jobFinished(params, false)
                    rearm(applicationContext)
                }
            }
        return true // Job is running asynchronously
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Log.d(TAG, "Plug-in refresh job stopped")
        job?.cancel()
        // Deliberately false: a JobScheduler-driven reschedule uses backoff and, with the charging
        // constraint already satisfied, would re-run almost immediately. The explicit latency-
        // carrying re-arm below is the only thing that should bring this job back.
        return false
    }

    companion object {
        private const val TAG = "PowerConnectedJob"

        @VisibleForTesting
        internal const val JOB_ID = 1003

        /**
         * Delay applied when the job re-arms itself. Matched to the charging loop so a device left
         * on the charger produces at most one of these per loop iteration.
         */
        @VisibleForTesting
        internal val REARM_LATENCY_MS: Long =
            TimeUnit.MINUTES.toMillis(CurrentTempFetchPolicy.CHARGING_INTERVAL_MINUTES)

        /**
         * Arms the trigger if it is not already armed. Deliberately a no-op when a job is pending:
         * callers include widget lifecycle paths that fire often, and re-scheduling would reset a
         * trigger that is already waiting for the charger.
         *
         * The first arm has no latency, so a plug-in that happens seconds later is caught at once.
         */
        fun ensureScheduled(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (jobScheduler.getPendingJob(JOB_ID) != null) {
                Log.d(TAG, "Plug-in trigger already armed")
                return
            }
            schedule(context, jobScheduler, minimumLatencyMs = 0L)
        }

        private fun rearm(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            schedule(context, jobScheduler, minimumLatencyMs = REARM_LATENCY_MS)
        }

        private fun schedule(
            context: Context,
            jobScheduler: JobScheduler,
            minimumLatencyMs: Long,
        ) {
            val result = jobScheduler.schedule(buildJobInfo(context, minimumLatencyMs))
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.d(TAG, "Plug-in trigger armed minimumLatencyMs=$minimumLatencyMs")
            } else {
                Log.e(TAG, "Failed to arm plug-in trigger minimumLatencyMs=$minimumLatencyMs")
            }
        }

        @VisibleForTesting
        internal fun buildJobInfo(
            context: Context,
            minimumLatencyMs: Long,
        ): JobInfo =
            JobInfo.Builder(JOB_ID, ComponentName(context, PowerConnectedJobService::class.java))
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(false)
                // The work this job enqueues carries its own network constraint.
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .apply {
                    if (minimumLatencyMs > 0L) {
                        setMinimumLatency(minimumLatencyMs)
                    }
                }
                // Survive reboots: otherwise the first plug-in after a restart is missed.
                .setPersisted(true)
                .build()
    }
}
