package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.WorkManager
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

/** System-facing AppWidgetProvider lifecycle boundary; behavior is delegated to coordinators. */
@dagger.hilt.android.AndroidEntryPoint
class WeatherWidgetProvider : AppWidgetProvider() {

    @VisibleForTesting
    internal var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject
    lateinit var repository: WeatherRepository

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        com.weatherwidget.WeatherWidgetApp.logFirstTriggerOnce("onUpdate")
        // Startup work is coalesced in three independent layers, each for a different duplicate
        // source: this 500ms onUpdate debounce (repeated system broadcasts), the resize debounce
        // in WidgetInteractionCoordinator.awaitLatestResizeRequest, and WidgetUpdateTracker's
        // replace-by-cancel of the prior job for the same widget. None of these cancels a running
        // WeatherWidgetWorker (see the WorkManager enqueue-policy rules in AGENTS.md).
        val now = SystemClock.elapsedRealtime()
        val filteredIds = appWidgetIds.filter { id ->
            val last = lastUpdateByWidgetId[id] ?: 0L
            if (now - last < STARTUP_DEBOUNCE_MS) {
                Log.d(TAG, "onUpdate: Debouncing duplicate update for widget $id")
                false
            } else {
                lastUpdateByWidgetId[id] = now
                true
            }
        }.toIntArray()

        if (filteredIds.isEmpty()) return

        Log.d(TAG, "onUpdate: Updating ${filteredIds.size} widgets")

        val startupToken = WidgetPerfLogger.newToken("startup")
        val onUpdateStartMs = SystemClock.elapsedRealtime()
        launchAsync(context) {
            WidgetStartupCoordinator(repository).updateWidgets(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetIds = filteredIds,
                startupToken = startupToken,
                onUpdateStartMs = onUpdateStartMs,
            )
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        Log.d(TAG, "onAppWidgetOptionsChanged: widgetId=$appWidgetId")
        val job = launchAsync(context) {
            WidgetIntentRouter.handleResize(context, appWidgetId, repository)
        }
        WidgetUpdateTracker.trackJob(appWidgetId, job, WidgetUpdateTracker.JobType.INTERACTION)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetWorkScheduler.schedulePeriodicSync(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            OpportunisticUpdateJobService.scheduleOpportunisticUpdate(context)
            PowerConnectedJobService.ensureScheduled(context)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context)
            .cancelUniqueWork(WidgetWorkScheduler.WORK_NAME_PERIODIC)
        WorkManager.getInstance(context)
            .cancelUniqueWork(WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP)
        NonPrimaryObservationScheduler.cancel(context)

        val uiScheduler = UIUpdateScheduler(context)
        uiScheduler.cancelScheduledUpdates()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            OpportunisticUpdateJobService.cancelOpportunisticUpdate(context)
        }
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        super.onDeleted(context, appWidgetIds)
        val stateManager = stateManager(context)
        for (appWidgetId in appWidgetIds) {
            WidgetActionJobRegistry.cancelAll(appWidgetId)
            WidgetUpdateTracker.cancelJob(appWidgetId)
            stateManager.clearWidgetState(appWidgetId)
            lastUpdateByWidgetId.remove(appWidgetId)
            WidgetPushDispatcher.forgetWidget(appWidgetId)
            WidgetIntentRouter.forgetWidget(appWidgetId)
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        super.onReceive(context, intent)
        Log.d(TAG, "onReceive: action=${intent.action}")
        com.weatherwidget.WeatherWidgetApp.logFirstTriggerOnce("onReceive:${intent.action}")

        if (isCacheRepaintBroadcast(intent.action)) {
            Log.d(TAG, "onReceive: ${intent.action} — repainting all widgets from cache")
            launchAsync(context) {
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
                    WeatherDatabase.getDatabase(context).appLogDao().log(
                        "TIMEZONE_CHANGE",
                        "action=repaint zone=${java.time.ZoneId.systemDefault().id}",
                        "INFO",
                    )
                }
                WidgetIntentRouter.renderAllWidgetsFromCache(context, repository)
            }
        }
    }

    private fun stateManager(context: Context) = WidgetStateManager(context)

    @VisibleForTesting
    internal fun launchAsync(
        context: Context,
        block: suspend CoroutineScope.() -> Unit,
    ): Job =
        BroadcastAsyncRunner.launch(
            context = context,
            pendingResult = goAsync(),
            scope = scope,
            caller = TAG,
            block = block,
        )

    companion object {
        /**
         * Broadcasts whose only effect is that the same cached data must be drawn differently.
         * TIMEZONE_CHANGED: without it "today", day columns and the NOW marker stayed in the old zone
         * until the next scheduled repaint (up to an hour); desktop restarts for the same reason —
         * `plans/260930-desktop-restart-on-system-timezone-change.md`.
         */
        internal fun isCacheRepaintBroadcast(action: String?): Boolean =
            action == Intent.ACTION_LOCALE_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED

        private val lastUpdateByWidgetId = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        private const val STARTUP_DEBOUNCE_MS = 500L
        private const val TAG = "WeatherWidgetProvider"
    }
}
