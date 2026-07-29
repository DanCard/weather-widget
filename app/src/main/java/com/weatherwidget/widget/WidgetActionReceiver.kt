package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/** Non-exported receiver for app-owned widget commands and PendingIntents. */
@dagger.hilt.android.AndroidEntryPoint
class WidgetActionReceiver : BroadcastReceiver() {
    @VisibleForTesting
    internal var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject
    lateinit var repository: WeatherRepository

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Log.d(TAG, "onReceive action=${intent.action}")
        com.weatherwidget.WeatherWidgetApp.logFirstTriggerOnce(
            "WidgetActionReceiver:${intent.action}",
        )
        when (intent.action) {
            WidgetActions.ACTION_REFRESH ->
                launchGlobal(context) {
                    WidgetRefreshCoordinator.refresh(
                        context,
                        intent.getBooleanExtra(WidgetActions.EXTRA_UI_ONLY, false),
                        repository,
                    )
                }
            WidgetActions.ACTION_SHOW_TOAST -> {
                if (!hasValidWidgetId(intent) ||
                    intent.getStringExtra(WidgetActions.EXTRA_TOAST_MESSAGE).isNullOrBlank()
                ) {
                    logRejected(intent, "invalid_toast")
                    return
                }
                handleShowToast(context, intent)
            }
            WidgetActions.ACTION_DAY_CLICK -> {
                if (!WidgetDayClickCoordinator.isValid(intent)) {
                    logRejected(intent, "invalid_day_click")
                    return
                }
                launchForWidget(context, intent) {
                    WidgetDayClickCoordinator.handleDayClick(context, intent, repository)
                }
            }
            WidgetActions.ACTION_NO_HOURLY_REFRESH_COMPLETE -> {
                if (!WidgetDayClickCoordinator.isValid(intent)) {
                    logRejected(intent, "invalid_no_hourly_complete")
                    return
                }
                launchForWidget(context, intent) {
                    WidgetDayClickCoordinator.handleRefreshComplete(context, intent)
                }
            }
            WidgetActions.ACTION_NAV_LEFT,
            WidgetActions.ACTION_NAV_RIGHT,
            -> launchForValidWidget(context, intent) { appWidgetId ->
                WidgetIntentRouter.handleNavigation(
                    context,
                    appWidgetId,
                    intent.action == WidgetActions.ACTION_NAV_LEFT,
                    repository,
                )
            }
            WidgetActions.ACTION_TOGGLE_API ->
                launchForValidWidget(context, intent) { appWidgetId ->
                    WidgetIntentRouter.handleToggleApi(context, appWidgetId, repository)
                    WidgetRefreshCoordinator.restartHeartbeats(context)
                }
            WidgetActions.ACTION_TOGGLE_VIEW ->
                launchForValidWidget(context, intent) { appWidgetId ->
                    val startMs = SystemClock.elapsedRealtime()
                    WidgetIntentRouter.handleToggleView(context, appWidgetId, repository)
                    WidgetRefreshCoordinator.restartHeartbeats(context)
                    val totalMs = SystemClock.elapsedRealtime() - startMs
                    WeatherDatabase.getDatabase(context).appLogDao().log(
                        "TOGGLE_VIEW_TIMING",
                        "widget=$appWidgetId source=" +
                            "${intent.getStringExtra(WidgetActions.EXTRA_INTERACTION_SOURCE) ?: "unknown"} " +
                            "total=${totalMs}ms",
                    )
                }
            WidgetActions.ACTION_TOGGLE_PRECIP ->
                launchForValidWidget(context, intent) { appWidgetId ->
                    WidgetIntentRouter.handleTogglePrecip(context, appWidgetId, repository)
                    WidgetRefreshCoordinator.restartHeartbeats(context)
                }
            WidgetActions.ACTION_CYCLE_ZOOM -> handleCycleZoom(context, intent)
            WidgetActions.ACTION_SET_VIEW -> {
                val targetView = parseTargetView(intent)
                if (!hasValidWidgetId(intent) || targetView == null) {
                    logRejected(intent, "invalid_set_view")
                    return
                }
                launchForValidWidget(context, intent) { appWidgetId ->
                    WidgetIntentRouter.handleSetView(
                        context,
                        appWidgetId,
                        targetView,
                        intent.getIntExtra(
                            WidgetActions.EXTRA_HOURLY_OFFSET,
                            Int.MIN_VALUE,
                        ),
                        repository,
                    )
                }
            }
            else -> logRejected(intent, "unknown_action")
        }
    }

    @VisibleForTesting
    internal fun launchAsync(
        context: Context,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit,
    ): Job =
        BroadcastAsyncRunner.launch(
            context = context,
            pendingResult = goAsync(),
            scope = scope,
            caller = TAG,
            start = start,
            block = block,
        )

    private fun handleCycleZoom(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId = widgetId(intent)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            logRejected(intent, "invalid_widget")
            return
        }
        val stateManager = WidgetStateManager(context)
        if (stateManager.getViewMode(appWidgetId) == ViewMode.DAILY) {
            Log.e(TAG, "CYCLE_ZOOM received in DAILY mode widget=$appWidgetId")
            return
        }
        val centerOffset =
            if (intent.hasExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET)) {
                intent.getIntExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET, 0)
            } else {
                null
            }
        launchForWidget(context, intent) {
            WidgetIntentRouter.handleCycleZoom(
                context,
                appWidgetId,
                centerOffset,
                repository,
            )
            WidgetRefreshCoordinator.restartHeartbeats(context)
        }
    }

    private fun handleShowToast(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId = widgetId(intent)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            logRejected(intent, "invalid_widget")
            return
        }
        val message = requireNotNull(intent.getStringExtra(WidgetActions.EXTRA_TOAST_MESSAGE))
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        launchForWidget(context, intent) {
            WeatherDatabase.getDatabase(context).appLogDao().log(
                "WIDGET_TOAST",
                "widget=$appWidgetId msg=$message",
                "INFO",
            )
        }
    }

    private fun launchForValidWidget(
        context: Context,
        intent: Intent,
        block: suspend CoroutineScope.(Int) -> Unit,
    ) {
        val appWidgetId = widgetId(intent)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            logRejected(intent, "invalid_widget")
            return
        }
        launchForWidget(context, intent) { block(appWidgetId) }
    }

    private fun launchForWidget(
        context: Context,
        intent: Intent,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val appWidgetId = widgetId(intent)
        val job = launchAsync(context, CoroutineStart.LAZY, block)
        WidgetActionJobRegistry.track(appWidgetId, job)
        job.start()
    }

    private fun launchGlobal(
        context: Context,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        launchAsync(context, block = block)
    }

    private fun widgetId(intent: Intent): Int =
        intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )

    private fun hasValidWidgetId(intent: Intent): Boolean =
        widgetId(intent) != AppWidgetManager.INVALID_APPWIDGET_ID

    private fun parseTargetView(intent: Intent): ViewMode? =
        intent
            .getStringExtra(WidgetActions.EXTRA_TARGET_VIEW)
            ?.let { value -> runCatching { ViewMode.valueOf(value) }.getOrNull() }

    private fun logRejected(
        intent: Intent,
        reason: String,
    ) {
        Log.w(
            TAG,
            "Rejected widget action=${intent.action} reason=$reason widget=${widgetId(intent)}",
        )
    }

    private companion object {
        const val TAG = "WidgetActionReceiver"
    }
}
