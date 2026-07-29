package com.weatherwidget.widget

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
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
    ) {
        restartHeartbeats(context)
        val batteryStatus: Intent? =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val isCharging = BatteryStatePolicy.isEffectivelyCharging(batteryStatus)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isDataStale = DataFreshness.isDataStale(context)
        val freshnessSummary = DataFreshness.getVisibleSourceFreshnessSummary(context)
        WeatherDatabase.getDatabase(context).appLogDao().log(
            "REFRESH_DECISION",
            "uiOnlyRequested=$uiOnly charging=$isCharging interactive=${powerManager.isInteractive} " +
                "isDataStale=$isDataStale $freshnessSummary",
            "INFO",
        )
        WidgetIntentRouter.renderAllWidgetsFromCache(context, repository)
        Log.d(TAG, "Direct cache repaint uiOnly=$uiOnly stale=$isDataStale")
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
        val batteryStatus: Intent? =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val isCharging = BatteryStatePolicy.isEffectivelyCharging(batteryStatus)
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

    private const val TAG = "WidgetRefreshCoordinator"
}
