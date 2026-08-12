package com.weatherwidget.widget

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.handlers.WidgetIntentRouter

/** Owns cache repaint, freshness decisions, and widget heartbeat recovery. */
internal object WidgetRefreshCoordinator {
    suspend fun refresh(
        context: Context,
        uiOnly: Boolean,
        repository: WeatherRepository,
        requestedWidgetId: Int? = null,
    ) {
        restartHeartbeats(context)
        val snapshot = BatterySnapshotProvider.snapshot(context)
        val isCharging = snapshot.isCharging
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isDataStale = DataFreshness.isDataStale(context)
        val freshnessSummary = DataFreshness.getVisibleSourceFreshnessSummary(context)
        WeatherDatabase.getDatabase(context).appLogDao().log(
            "REFRESH_DECISION",
            "uiOnlyRequested=$uiOnly charging=$isCharging interactive=${powerManager.isInteractive} " +
                "isDataStale=$isDataStale targetWidget=${requestedWidgetId ?: "all"} $freshnessSummary",
            "INFO",
        )
        repaintFromCache(context, repository, requestedWidgetId)
        Log.d(
            TAG,
            "Direct cache repaint target=${requestedWidgetId ?: "all"} uiOnly=$uiOnly stale=$isDataStale",
        )
        if (WidgetRefreshPolicy.shouldTriggerNetworkFetchAfterRefresh(uiOnly, isDataStale)) {
            WidgetWorkScheduler.enqueueRedundantImmediateSync(
                context = context,
                forceRefresh = true,
                reason = "refresh_action_stale",
            )
        }
    }

    suspend fun restartHeartbeats(context: Context) {
        UIUpdateScheduler(context).scheduleNextUpdate()
        val snapshot = BatterySnapshotProvider.snapshot(context)
        val isCharging = snapshot.isCharging
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (
            CurrentTempFetchPolicy.shouldScheduleChargingLoop(
                isCharging,
                powerManager.isInteractive,
            )
        ) {
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                context,
                isScreenInteractive = powerManager.isInteractive,
            )
        }
    }

    @VisibleForTesting
    internal suspend fun repaintFromCache(
        context: Context,
        repository: WeatherRepository,
        requestedWidgetId: Int?,
    ) {
        if (requestedWidgetId == null) {
            WidgetIntentRouter.renderAllWidgetsFromCache(context, repository)
        } else {
            WidgetIntentRouter.renderWidgetFromCache(context, requestedWidgetId, repository)
        }
    }

    private const val TAG = "WidgetRefreshCoordinator"
}
