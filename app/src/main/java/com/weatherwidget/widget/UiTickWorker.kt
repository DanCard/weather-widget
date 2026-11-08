package com.weatherwidget.widget

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkerParameters

/**
 * The periodic UI tick (now line, fetch-dot age, current-temp interpolation), shared by the
 * screen-off [UIUpdateReceiver] alarm and the screen-on [UiTickWorker] job.
 */
object UiTick {
    private const val TAG = "UiTick"

    suspend fun run(context: Context, via: String) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
            Log.d(TAG, "Screen is off, skipping UI update work but preserving schedule via=$via")
            UIUpdateScheduler(context).scheduleNextUpdate()
            return
        }

        Log.d(TAG, "UI update alarm triggered via=$via")

        WidgetWorkScheduler.enqueueUiRepaint(context, reason = WidgetTimedRepaintGate.REASON_UI_TICK)

        // Heartbeat recovery: Ensure the charging loop is running if the device is plugged in.
        // This handles cases where ACTION_POWER_CONNECTED was missed by the OS.
        CurrentTempUpdateScheduler.scheduleNextChargingUpdate(context, powerManager.isInteractive)

        UIUpdateScheduler(context).scheduleNextUpdate()
    }

    /**
     * Policy for (re)scheduling the screen-on tick job. A tick reschedules itself while it is still
     * running, and REPLACE would cancel that run. So: running with no successor → append one; running
     * with a successor already queued → keep it; otherwise replace the pending tick with the new delay.
     */
    @VisibleForTesting
    internal fun tickWorkPolicy(states: List<WorkInfo.State>): ExistingWorkPolicy {
        val running = states.any { it == WorkInfo.State.RUNNING }
        val pending = states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }
        return when {
            running && pending -> ExistingWorkPolicy.KEEP
            running -> ExistingWorkPolicy.APPEND_OR_REPLACE
            else -> ExistingWorkPolicy.REPLACE
        }
    }
}

/**
 * Screen-on UI tick as a delayed job. Samsung Freecess freezes the idle widget process and holds
 * all its alarms, wakeup and non-wakeup alike (`Deferring alarm … due to freecess`), so the alarm tick
 * left the now dot's age stale for 35 minutes with the screen on. A job start unfreezes the app
 * (`UFZ … reason: job`); plans/261108-samsung-freecess-stalls-now-dot.md. No constraints: the tick
 * only repaints from cache.
 */
class UiTickWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        UiTick.run(applicationContext, via = "job")
        return Result.success()
    }
}
