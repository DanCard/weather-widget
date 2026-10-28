package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.weatherwidget.WeatherWidgetApp
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.handlers.InteractionLatency
import com.weatherwidget.widget.handlers.InteractionTrace
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Common base for widget broadcast receivers. Owns interaction routing, E2E timing instrumentation,
 * and dispatching.
 */
abstract class BaseWidgetActionReceiver : BroadcastReceiver() {
    abstract val tag: String
    abstract var scope: CoroutineScope
    abstract val actionHandlers: Map<String, (Context, Intent, Long) -> Unit>

    @Inject
    lateinit var repository: WeatherRepository

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val receivedAtElapsedMs = SystemClock.elapsedRealtime()
        UsageHoursTracker.sight(context.applicationContext)
        Log.d(tag, "onReceive action=${intent.action}")
        WeatherWidgetApp.logFirstTriggerOnce(
            "$tag:${intent.action}",
        )
        val handler = actionHandlers[intent.action]
        if (handler != null) {
            handler(context, intent, receivedAtElapsedMs)
        } else {
            logRejected(intent, "unknown_action")
        }
    }

    protected val interactionHandlers: Map<String, (Context, Intent, Long) -> Unit> = mapOf(
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
        WidgetActions.ACTION_RECENTER_TODAY to { context, intent, receivedAtElapsedMs ->
            launchForValidWidget(context, intent, receivedAtElapsedMs) { appWidgetId ->
                WidgetIntentRouter.handleRecenterToday(context, appWidgetId, repository)
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

    protected fun handleNav(
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

    protected fun handleSetViewAction(
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
            caller = tag,
            start = start,
            block = block,
        )

    protected fun handleCycleZoom(
        context: Context,
        intent: Intent,
        receivedAtElapsedMs: Long,
    ) {
        val appWidgetId = widgetId(intent)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            logRejected(intent, "invalid_widget")
            return
        }
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

    protected fun handleShowToast(
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

    protected fun launchForValidWidget(
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

    protected fun launchForWidget(
        context: Context,
        intent: Intent,
        receivedAtElapsedMs: Long,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val appWidgetId = widgetId(intent)
        val cancelled = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            WidgetStateManager(context).setLastInteractedWidgetId(appWidgetId)
            val list = WidgetUpdateTracker.cancelOtherJobs(appWidgetId)
            if (list.isNotEmpty()) {
                Log.d(tag, "Preempted other widget updates: active=$appWidgetId cancelled=$list")
                val repo = if (::repository.isInitialized) repository else null
                WidgetPreemptionSettler.recordPreempted(context, list, repo)
            }
            list
        } else {
            emptyList()
        }
        val action = intent.action?.substringAfterLast('.') ?: "unknown"
        val ordinal = tapsInProcess.incrementAndGet()
        val processAgeAtReceiptMs = WeatherWidgetApp.processAgeMs(receivedAtElapsedMs)
        val trace = InteractionTrace()
        val job = launchAsync(context, CoroutineStart.LAZY) {
            if (cancelled.isNotEmpty()) {
                runCatching {
                    WeatherDatabase.getDatabase(context).appLogDao().log(
                        "WIDGET_INTERACTION_PREEMPTION",
                        "active=$appWidgetId cancelled=${cancelled.joinToString(",")}",
                        "INFO",
                    )
                }
            }
            val startedAtMs = SystemClock.elapsedRealtime()
            Log.i(
                "INTERACTION_E2E",
                "action=$action widget=$appWidgetId phase=start queue=${startedAtMs - receivedAtElapsedMs}ms",
            )
            try {
                withContext(trace) { block() }
            } finally {
                val doneAtMs = SystemClock.elapsedRealtime()
                val e2eMs = doneAtMs - receivedAtElapsedMs
                Log.i(
                    "INTERACTION_E2E",
                    "action=$action widget=$appWidgetId phase=done " +
                        "e2e=${e2eMs}ms " +
                        "queue=${startedAtMs - receivedAtElapsedMs}ms " +
                        "work=${doneAtMs - startedAtMs}ms",
                )
                if (InteractionLatency.shouldPersist(ordinal, e2eMs)) {
                    withContext(NonCancellable) {
                        runCatching {
                            WeatherDatabase.getDatabase(context).appLogDao().log(
                                InteractionLatency.TAG,
                                InteractionLatency.format(
                                    action = action,
                                    appWidgetId = appWidgetId,
                                    ordinal = ordinal,
                                    processAgeAtReceiptMs = processAgeAtReceiptMs,
                                    queueMs = startedAtMs - receivedAtElapsedMs,
                                    lockWaitMs = trace.lockWaitMs,
                                    workMs = doneAtMs - startedAtMs,
                                    e2eMs = e2eMs,
                                ),
                            )
                        }.onFailure { Log.w(tag, "INTERACTION_E2E row write failed", it) }
                    }
                }
            }
        }
        WidgetActionJobRegistry.track(appWidgetId, job)
        job.start()
    }

    protected fun widgetId(intent: Intent): Int =
        intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )

    protected fun hasValidWidgetId(intent: Intent): Boolean =
        widgetId(intent) != AppWidgetManager.INVALID_APPWIDGET_ID

    protected fun parseTargetView(intent: Intent): ViewMode? =
        intent
            .getStringExtra(WidgetActions.EXTRA_TARGET_VIEW)
            ?.let { value -> runCatching { ViewMode.valueOf(value) }.getOrNull() }

    protected fun logRejected(
        intent: Intent,
        reason: String,
    ) {
        Log.w(
            tag,
            "Rejected widget action=${intent.action} reason=$reason widget=${widgetId(intent)}",
        )
    }

    companion object {
        /** Widget taps since this process started; receivers are per-broadcast, this is not. */
        val tapsInProcess = AtomicInteger(0)
    }
}
