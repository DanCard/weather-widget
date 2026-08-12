package com.weatherwidget.widget

import android.content.Context
import androidx.work.WorkManager
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import java.util.UUID

/**
 * Post-run loop management shared by the worker's current-temp/non-primary handlers and the
 * full-sync pipeline. Decides whether a self-perpetuating charging loop keeps rescheduling itself
 * based on the battery/screen state observed at the end of a run.
 */
internal object WidgetLoopScheduler {
    suspend fun manageCurrentTempLoopAfterRun(
        context: Context,
        appLogDao: AppLogDao,
        device: DeviceContext,
        ignoreRunningWorkId: UUID? = null,
    ) {
        when (CurrentTempFetchPolicy.postRunLoopAction(device.isCharging, device.isScreenInteractive)) {
            CurrentTempFetchPolicy.PostRunLoopAction.SCHEDULE_NEXT ->
                CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                    context = context,
                    workManager = WorkManager.getInstance(context),
                    nowMs = System.currentTimeMillis(),
                    ignoreRunningWorkId = ignoreRunningWorkId,
                    isScreenInteractive = device.isScreenInteractive,
                )
            CurrentTempFetchPolicy.PostRunLoopAction.NO_RESCHEDULE ->
                appLogDao.log(
                    "CURR_FETCH_LOOP_STOP",
                    "reason=policy_blocked plugged=${device.isCharging} interactive=${device.isScreenInteractive} action=no_reschedule",
                    "INFO",
                )
        }
    }

    suspend fun manageNonPrimaryLoopAfterRun(
        context: Context,
        appLogDao: AppLogDao,
        device: DeviceContext,
    ) {
        val intervalMinutes = ForecastFetchPolicy.nonPrimaryObservationIntervalMinutes(
            device.isCharging, device.isScreenInteractive,
        )
        if (intervalMinutes != null) {
            NonPrimaryObservationScheduler.scheduleNextUpdate(
                context = context,
                isScreenInteractive = device.isScreenInteractive,
            )
        } else {
            appLogDao.log(
                "NONPRIMARY_LOOP_STOP",
                "reason=policy_blocked plugged=${device.isCharging} interactive=${device.isScreenInteractive}",
                "INFO",
            )
        }
    }
}
