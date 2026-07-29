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
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        LocationHandoffStore.clear(context)
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

        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                launchAsync(context) {
                    WidgetIntentRouter.renderAllWidgetsFromCache(context, repository)
                }
            }
            Intent.ACTION_LOCALE_CHANGED -> {
                Log.d(TAG, "onReceive: Locale change broadcast received")
                launchAsync(context) {
                    WidgetIntentRouter.renderAllWidgetsFromCache(context, repository)
                }
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
        private val lastUpdateByWidgetId = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        private const val STARTUP_DEBOUNCE_MS = 500L
        private const val TAG = "WeatherWidgetProvider"
    }
}
