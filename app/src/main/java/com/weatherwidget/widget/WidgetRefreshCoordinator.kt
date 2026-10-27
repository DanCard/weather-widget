package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.util.RefreshNotice
import com.weatherwidget.shared.util.UserRefreshThrottle
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import kotlinx.coroutines.CancellationException

/** Owns cache repaint, freshness decisions, and widget heartbeat recovery. */
internal object WidgetRefreshCoordinator {
    suspend fun refresh(
        context: Context,
        uiOnly: Boolean,
        repository: WeatherRepository,
        requestedWidgetId: Int? = null,
        userInitiated: Boolean = !uiOnly,
    ) {
        restartHeartbeats(context)
        val snapshot = BatterySnapshotProvider.snapshot(context)
        val isCharging = snapshot.isCharging
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isDataStale = DataFreshness.isDataStale(context)
        val freshnessSummary = DataFreshness.getVisibleSourceFreshnessSummary(context)
        WeatherDatabase.getDatabase(context).appLogDao().log(
            "REFRESH_DECISION",
            "uiOnlyRequested=$uiOnly userInitiated=$userInitiated charging=$isCharging interactive=${powerManager.isInteractive} " +
                "isDataStale=$isDataStale targetWidget=${requestedWidgetId ?: "all"} $freshnessSummary",
            "INFO",
        )
        val cacheNotice = repaintFromCache(context, repository, requestedWidgetId)
        Log.d(
            TAG,
            "Direct cache repaint target=${requestedWidgetId ?: "all"} uiOnly=$uiOnly userInitiated=$userInitiated stale=$isDataStale",
        )
        val syncNotice = if (userInitiated) {
            val nowMs = System.currentTimeMillis()
            val lastUserRefreshMs = FetchMetadata.getLastUserRefreshTime(context)
            when (val decision = UserRefreshThrottle.check(lastUserRefreshMs, nowMs)) {
                is UserRefreshThrottle.Decision.Throttled -> {
                    WeatherDatabase.getDatabase(context).appLogDao().log(
                        "USER_REFRESH_THROTTLED",
                        "elapsedMin=${decision.elapsedMinutes} waitMin=${decision.remainingMinutes}",
                        "INFO",
                    )
                    RefreshNotice.NotQueued(
                        RefreshNotice.Reason.RATE_LIMITED,
                        elapsedMinutes = decision.elapsedMinutes,
                        remainingMinutes = decision.remainingMinutes,
                    )
                }
                is UserRefreshThrottle.Decision.Allowed -> {
                    FetchMetadata.setLastUserRefreshTime(context, nowMs)
                    val pending = WidgetWorkScheduler.pendingOneTimeSync(context)
                    WidgetWorkScheduler.enqueueRequiredImmediateSync(
                        context = context,
                        forceRefresh = true,
                        reason = "widget_tap_to_refresh",
                        userRefresh = true,
                    )
                    pending?.let { RefreshNotice.NotQueued(it) } ?: RefreshNotice.Queued(RefreshNotice.Kind.FORECAST)
                }
            }
        } else if (WidgetRefreshPolicy.shouldTriggerNetworkFetchAfterRefresh(uiOnly, isDataStale)) {
            val pending = WidgetWorkScheduler.pendingOneTimeSync(context)
            // Not forced: "stale" now means some source is due under the cadence, and a normal sync
            // fetches exactly the due sources. Forcing refetched every source, fresh or not.
            WidgetWorkScheduler.enqueueRedundantImmediateSync(
                context = context,
                forceRefresh = false,
                reason = "refresh_action_stale",
            )
            pending?.let { RefreshNotice.NotQueued(it) } ?: RefreshNotice.Queued(RefreshNotice.Kind.FORECAST)
        } else {
            null
        }
        if (userInitiated) {
            if (syncNotice !is RefreshNotice.NotQueued) {
                enqueueHistoryGapFill(context)
            }
        } else if (WidgetRefreshPolicy.shouldRunHistoryGapFillAfterRefresh(uiOnly, isDataStale)) {
            enqueueHistoryGapFill(context)
        }
        // "Tap to refresh" is the user asking for data, so it says what happened either way. The
        // ui-only repaints app screens send on close stay silent.
        if (userInitiated) {
            RefreshNotice.merge(cacheNotice, syncNotice)?.let { notice ->
                val ids = requestedWidgetId?.let { intArrayOf(it) }
                    ?: AppWidgetManager.getInstance(context)
                        .getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
                RefreshToast.show(context, "widget_tap_to_refresh", notice, ids)
            }
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
                snapshot.batteryLevel,
            )
        ) {
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                context,
                isScreenInteractive = powerManager.isInteractive,
            )
        }
    }

    /**
     * Fresh-data Refresh: the daily NWS gap fill alone. The worker runs the shared
     * [com.weatherwidget.shared.observations.NwsGapFillRun.dailyForSources], which owns the "does a
     * visible source read NWS?" gate, so it is not repeated here. Best-effort: the toast already
     * reflects the repaint.
     */
    private suspend fun enqueueHistoryGapFill(context: Context) {
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()
        try {
            val (lat, lon) = ActiveLocationResolver.resolve(
                context, WidgetStateManager(context), WeatherDatabase.getDatabase(context).forecastDao(),
            ) ?: return
            WidgetWorkScheduler.enqueueHistoryGapFill(context, lat, lon, reason = "user_refresh:widget")
            appLogDao.log("OBS_DAILY_GAP_FILL_ENQUEUE", "reason=user_refresh:widget", "INFO")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "History gap-fill enqueue failed", e)
            appLogDao.log("OBS_DAILY_GAP_FILL_ENQUEUE_FAIL", "${e.javaClass.simpleName}: ${e.message}", "ERROR")
        }
    }

    @VisibleForTesting
    internal suspend fun repaintFromCache(
        context: Context,
        repository: WeatherRepository,
        requestedWidgetId: Int?,
    ): RefreshNotice? =
        if (requestedWidgetId == null) {
            WidgetIntentRouter.renderAllWidgetsFromCache(context, repository)
        } else {
            WidgetIntentRouter.renderWidgetFromCache(context, requestedWidgetId, repository)
        }

    private const val TAG = "WidgetRefreshCoordinator"
}
