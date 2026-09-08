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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/** Non-exported receiver for app-owned widget commands and PendingIntents. */
@dagger.hilt.android.AndroidEntryPoint
class WidgetActionReceiver : BroadcastReceiver() {
    @VisibleForTesting
    internal var scope = CoroutineScope(SupervisorJob() + WidgetInteractionDispatcher.dispatcher)

    @Inject
    lateinit var repository: WeatherRepository

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val receivedAtElapsedMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "onReceive action=${intent.action}")
        com.weatherwidget.WeatherWidgetApp.logFirstTriggerOnce(
            "WidgetActionReceiver:${intent.action}",
        )
        val handler = actionHandlers[intent.action]
        if (handler != null) {
            handler(context, intent, receivedAtElapsedMs)
        } else {
            logRejected(intent, "unknown_action")
        }
    }

    private val actionHandlers: Map<String, (Context, Intent, Long) -> Unit> = mapOf(
        WidgetActions.ACTION_REFRESH to { context, intent, _ ->
            launchGlobal(context) {
                val requestedWidgetId =
                    intent.getIntExtra(
                        AppWidgetManager.EXTRA_APPWIDGET_ID,
                        AppWidgetManager.INVALID_APPWIDGET_ID,
                    ).takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
                WidgetRefreshCoordinator.refresh(
                    context,
                    intent.getBooleanExtra(WidgetActions.EXTRA_UI_ONLY, false),
                    repository,
                    requestedWidgetId,
                )
            }
        },
        WidgetActions.ACTION_SHOW_TOAST to { context, intent, receivedAtElapsedMs ->
            if (!hasValidWidgetId(intent) ||
                intent.getStringExtra(WidgetActions.EXTRA_TOAST_MESSAGE).isNullOrBlank()
            ) {
                logRejected(intent, "invalid_toast")
            } else {
                handleShowToast(context, intent, receivedAtElapsedMs)
            }
        },
        WidgetActions.ACTION_DAY_CLICK to { context, intent, receivedAtElapsedMs ->
            if (!WidgetDayClickCoordinator.isValid(intent)) {
                logRejected(intent, "invalid_day_click")
            } else {
                launchForWidget(context, intent, receivedAtElapsedMs) {
                    WidgetIntentRouter.handleDayClick(context, intent, repository)
                }
            }
        },
        WidgetActions.ACTION_NO_HOURLY_REFRESH_COMPLETE to { context, intent, receivedAtElapsedMs ->
            if (!WidgetDayClickCoordinator.isValid(intent)) {
                logRejected(intent, "invalid_no_hourly_complete")
            } else {
                launchForWidget(context, intent, receivedAtElapsedMs) {
                    WidgetIntentRouter.handleRefreshComplete(context, intent)
                }
            }
        },
        WidgetActions.ACTION_NAV_LEFT to { context, intent, receivedAtElapsedMs ->
            handleNav(context, intent, receivedAtElapsedMs, isLeft = true)
        },
        WidgetActions.ACTION_NAV_RIGHT to { context, intent, receivedAtElapsedMs ->
            handleNav(context, intent, receivedAtElapsedMs, isLeft = false)
        },
        WidgetActions.ACTION_TOGGLE_API to { context, intent, receivedAtElapsedMs ->
            launchForValidWidget(context, intent, receivedAtElapsedMs) { appWidgetId ->
                val toggleStartMs = SystemClock.elapsedRealtime()
                WidgetIntentRouter.handleToggleApi(context, appWidgetId, repository)
                val afterToggleMs = SystemClock.elapsedRealtime()
                WidgetRefreshCoordinator.restartHeartbeats(context)
                Log.i(
                    "INTERACTION_E2E",
                    "action=ACTION_TOGGLE_API widget=$appWidgetId phase=tail " +
                        "handler=${afterToggleMs - toggleStartMs}ms " +
                        "heartbeats=${SystemClock.elapsedRealtime() - afterToggleMs}ms",
                )
            }
        },
        WidgetActions.ACTION_RESET_SOURCE to { context, intent, receivedAtElapsedMs ->
            launchForValidWidget(context, intent, receivedAtElapsedMs) { appWidgetId ->
                WidgetIntentRouter.handleResetSource(context, appWidgetId, repository)
                WidgetRefreshCoordinator.restartHeartbeats(context)
            }
        },
        WidgetActions.ACTION_TOGGLE_VIEW to { context, intent, receivedAtElapsedMs ->
            launchForValidWidget(context, intent, receivedAtElapsedMs) { appWidgetId ->
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
        },
        WidgetActions.ACTION_TOGGLE_PRECIP to { context, intent, receivedAtElapsedMs ->
            launchForValidWidget(context, intent, receivedAtElapsedMs) { appWidgetId ->
                WidgetIntentRouter.handleTogglePrecip(context, appWidgetId, repository)
                WidgetRefreshCoordinator.restartHeartbeats(context)
            }
        },
        WidgetActions.ACTION_CYCLE_ZOOM to { context, intent, receivedAtElapsedMs ->
            handleCycleZoom(context, intent, receivedAtElapsedMs)
        },
        WidgetActions.ACTION_SET_VIEW to { context, intent, receivedAtElapsedMs ->
            handleSetViewAction(context, intent, receivedAtElapsedMs)
        },
    )

    private fun handleNav(
        context: Context,
        intent: Intent,
        receivedAtElapsedMs: Long,
        isLeft: Boolean,
    ) {
        launchForValidWidget(context, intent, receivedAtElapsedMs) { appWidgetId ->
            WidgetIntentRouter.handleNavigation(
                context,
                appWidgetId,
                isLeft,
                repository,
            )
        }
    }

    private fun handleSetViewAction(
        context: Context,
        intent: Intent,
        receivedAtElapsedMs: Long,
    ) {
        val targetView = parseTargetView(intent)
        if (!hasValidWidgetId(intent) || targetView == null) {
            logRejected(intent, "invalid_set_view")
            return
        }
        launchForValidWidget(context, intent, receivedAtElapsedMs) { appWidgetId ->
            val interactionToken = "set-view-$receivedAtElapsedMs"
            WidgetIntentRouter.handleSetView(
                context,
                appWidgetId,
                targetView,
                intent.getIntExtra(
                    WidgetActions.EXTRA_HOURLY_OFFSET,
                    Int.MIN_VALUE,
                ),
                repository,
                interactionToken,
            )
            val receiveToCompleteMs = SystemClock.elapsedRealtime() - receivedAtElapsedMs
            WeatherDatabase.getDatabase(context).appLogDao().log(
                "SET_VIEW_E2E_TIMING",
                "widget=$appWidgetId token=$interactionToken mode=${targetView.name} " +
                    "receiveToComplete=${receiveToCompleteMs}ms",
            )
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
        receivedAtElapsedMs: Long,
    ) {
        val appWidgetId = widgetId(intent)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            logRejected(intent, "invalid_widget")
            return
        }
        // The authoritative DAILY-mode guard lives in WidgetIntentActionHandler.cycleZoom
        // (under the per-widget lock); a lingering CYCLE_ZOOM PendingIntent for a widget that
        // since switched to DAILY is a benign stale-tap case, not an error.
        val centerOffset =
            if (intent.hasExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET)) {
                intent.getIntExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET, 0)
            } else {
                null
            }
        launchForWidget(context, intent, receivedAtElapsedMs) {
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
        receivedAtElapsedMs: Long,
    ) {
        val appWidgetId = widgetId(intent)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            logRejected(intent, "invalid_widget")
            return
        }
        val message = requireNotNull(intent.getStringExtra(WidgetActions.EXTRA_TOAST_MESSAGE))
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        launchForWidget(context, intent, receivedAtElapsedMs) {
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
        receivedAtElapsedMs: Long,
        block: suspend CoroutineScope.(Int) -> Unit,
    ) {
        val appWidgetId = widgetId(intent)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            logRejected(intent, "invalid_widget")
            return
        }
        launchForWidget(context, intent, receivedAtElapsedMs) { block(appWidgetId) }
    }

    /**
     * Every widget action funnels through here, which is why the click-latency timing lives here and
     * not in each action.
     *
     * Until 2026-09-06 only ACTION_SET_VIEW measured itself end to end, so TOGGLE_API, CYCLE_ZOOM,
     * DAILY_NAV and RESIZE — the taps actually reported as slow — produced no user-perceived number
     * at all, and their `*_SLOW` rows timed a sub-span of the wait. Measuring at this seam also means
     * a new action cannot be added without a number.
     *
     * `queueMs` is the interval this could not previously see: broadcast receipt to the coroutine
     * body actually running, i.e. time spent waiting for a slot on
     * [WidgetInteractionDispatcher]. Split out because it and the work itself have opposite fixes —
     * a queue wait argues about concurrency shape, a slow body argues about caching.
     */
    private fun launchForWidget(
        context: Context,
        intent: Intent,
        receivedAtElapsedMs: Long,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val appWidgetId = widgetId(intent)
        val action = intent.action?.substringAfterLast('.') ?: "unknown"
        val job = launchAsync(context, CoroutineStart.LAZY) {
            val startedAtMs = SystemClock.elapsedRealtime()
            // Emitted at START as well as completion, deliberately. A single line in `finally`
            // cannot tell "the coroutine never ran" from "it ran and never finished" — and it can
            // genuinely never finish: BroadcastAsyncRunner releases the pending result after its
            // 8s watchdog and the process can then be reclaimed mid-flight, taking the completion
            // line with it. The queue figure is wanted on its own anyway.
            Log.i(
                "INTERACTION_E2E",
                "action=$action widget=$appWidgetId phase=start queue=${startedAtMs - receivedAtElapsedMs}ms",
            )
            try {
                block()
            } finally {
                val doneAtMs = SystemClock.elapsedRealtime()
                // Logcat, not app_logs: this fires on every tap, and a diagnostic's own write must
                // never land on the path it is measuring. Same rule as OBS_RANGE_READ.
                Log.i(
                    "INTERACTION_E2E",
                    "action=$action widget=$appWidgetId phase=done " +
                        "e2e=${doneAtMs - receivedAtElapsedMs}ms " +
                        "queue=${startedAtMs - receivedAtElapsedMs}ms " +
                        "work=${doneAtMs - startedAtMs}ms",
                )
            }
        }
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
