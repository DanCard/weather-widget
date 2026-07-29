package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetPushDispatcher
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import java.time.LocalDateTime

/** Implements action state transitions and delegates data/render work to the daily/graph pipelines. */
internal object WidgetIntentActionHandler {
    private const val TAG = "WidgetIntentAction"
    private val contextResolver = WidgetRefreshContextResolver()
    private val refreshRequester = InteractionRefreshRequester()

    suspend fun renderAllWidgetsFromCache(
        context: Context,
        repository: WeatherRepository?,
    ) {
        val ids =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
                .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
                .toIntArray()
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()
        WidgetInteractionCoordinator.forEachWidgetIsolated(
            ids,
            onFailure = { id, error ->
                Log.e(TAG, "Cache repaint failed for widget $id", error)
                appLogDao.log(
                    "WIDGET_RENDER_FAIL",
                    "widget=$id path=refresh_action_cache_first " +
                        "${error.javaClass.simpleName}: ${error.message}",
                    "ERROR",
                )
            },
        ) { id ->
            WidgetInteractionCoordinator.withWidgetLock(id) {
                refreshWidget(
                    context,
                    id,
                    "refresh_action_cache_first",
                    repository,
                    actionTag = "REFRESH",
                    partialPush = true,
                    origin = WidgetPushDispatcher.Origin.ACTION_REFRESH,
                )
            }
        }
    }

    suspend fun navigate(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
        repository: WeatherRepository?,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val viewMode = WidgetStateManager(context).getViewMode(appWidgetId)
        val refreshContext =
            prepareContext(
                context,
                appWidgetId,
                if (viewMode.isGraphMode) "graph_nav" else "daily_nav",
            )
        val request =
            request(
                context,
                appWidgetId,
                refreshContext,
                LocalDateTime.now(),
                repository,
                startMs,
                "NAV",
            )
        if (viewMode.isGraphMode) {
            GraphInteractionRenderer.navigate(
                InteractionRenderDispatcher.graphRequest(request),
                isLeft,
            )
        } else {
            DailyInteractionRenderer.navigate(
                InteractionRenderDispatcher.dailyRequest(request),
                isLeft,
            )
        }
    }

    suspend fun cycleZoom(
        context: Context,
        appWidgetId: Int,
        zoomCenterOffset: Int?,
        repository: WeatherRepository?,
    ) {
        if (WidgetStateManager(context).getViewMode(appWidgetId) == ViewMode.DAILY) {
            Log.w(TAG, "Ignoring stale cycle-zoom PendingIntent for daily widget $appWidgetId")
            return
        }
        val startMs = SystemClock.elapsedRealtime()
        val refreshContext = prepareContext(context, appWidgetId, "cycle_zoom")
        GraphInteractionRenderer.cycleZoom(
            InteractionRenderDispatcher.graphRequest(
                request(
                    context,
                    appWidgetId,
                    refreshContext,
                    LocalDateTime.now(),
                    repository,
                    startMs,
                    "CYCLE_ZOOM",
                ),
            ),
            zoomCenterOffset,
        )
    }

    suspend fun toggleApi(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository?,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val newSource = stateManager.toggleDisplaySource(appWidgetId)
        val viewMode = stateManager.getViewMode(appWidgetId)
        val refreshContext = prepareContext(context, appWidgetId, "toggle_api")
        val now = LocalDateTime.now()
        if (
            GraphInteractionRenderer.selectedSourceNeedsRefresh(
                context,
                appWidgetId,
                refreshContext,
                newSource,
                now,
                System.currentTimeMillis(),
            )
        ) {
            refreshRequester.requestForced(
                context,
                refreshContext.database.appLogDao(),
                reason = "toggle_api_stale",
                targetSourceId = newSource.id,
            )
        }
        InteractionRenderDispatcher.render(
            viewMode,
            request(
                context,
                appWidgetId,
                refreshContext,
                now,
                repository,
                startMs,
                "TOGGLE_API",
                "source=${newSource.id}",
            ),
        )
    }

    suspend fun toggleView(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository?,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val newMode = WidgetStateManager(context).toggleViewMode(appWidgetId)
        renderAfterTransition(
            newMode,
            context,
            appWidgetId,
            repository,
            startMs,
            "toggle_view",
            "TOGGLE_VIEW",
            "mode=${newMode.name}",
        )
    }

    suspend fun togglePrecip(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository?,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val newMode = WidgetStateManager(context).togglePrecipitationMode(appWidgetId)
        renderAfterTransition(
            newMode,
            context,
            appWidgetId,
            repository,
            startMs,
            "toggle_precip",
            "TOGGLE_PRECIP",
        )
    }

    suspend fun setView(
        context: Context,
        appWidgetId: Int,
        targetMode: ViewMode,
        targetOffset: Int,
        repository: WeatherRepository?,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val previousMode = stateManager.getViewMode(appWidgetId)
        stateManager.setViewMode(appWidgetId, targetMode)
        if (targetMode == ViewMode.DAILY || previousMode == ViewMode.DAILY) {
            stateManager.setZoomLevel(appWidgetId, ZoomLevel.WIDE)
        }
        if (targetMode.isGraphMode && targetOffset != Int.MIN_VALUE) {
            stateManager.setHourlyOffset(appWidgetId, targetOffset)
        }
        renderAfterTransition(
            targetMode,
            context,
            appWidgetId,
            repository,
            startMs,
            "set_view",
            "SET_VIEW",
            "mode=${targetMode.name}",
        )
    }

    suspend fun resize(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository?,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val database = WeatherDatabase.getDatabase(context)
        val viewMode = WidgetStateManager(context).getViewMode(appWidgetId)
        database.appLogDao().log(
            "WIDGET_LIFECYCLE",
            "phase=handleResize_entry widget=$appWidgetId thread=${Thread.currentThread().name}",
        )
        ResizeDiagnosticsLogger.log(
            context,
            AppWidgetManager.getInstance(context),
            appWidgetId,
            viewMode.name,
            database.appLogDao(),
        )
        refreshWidget(
            context,
            appWidgetId,
            "resize",
            repository,
            startMs,
            "RESIZE",
            partialPush = false,
            origin = WidgetPushDispatcher.Origin.RESIZE,
        )
    }

    private suspend fun renderAfterTransition(
        viewMode: ViewMode,
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository?,
        startMs: Long,
        staleReason: String,
        actionTag: String,
        metadata: String = "",
    ) {
        val refreshContext = prepareContext(context, appWidgetId, staleReason)
        InteractionRenderDispatcher.render(
            viewMode,
            request(
                context,
                appWidgetId,
                refreshContext,
                LocalDateTime.now(),
                repository,
                startMs,
                actionTag,
                metadata,
            ),
        )
    }

    private suspend fun refreshWidget(
        context: Context,
        appWidgetId: Int,
        reason: String,
        repository: WeatherRepository?,
        startTimeMs: Long = SystemClock.elapsedRealtime(),
        actionTag: String = "REFRESH",
        extraMetadata: String = "",
        partialPush: Boolean = true,
        origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.ACTION_REFRESH,
    ) {
        val refreshContext = prepareContext(context, appWidgetId, reason)
        val viewMode = WidgetStateManager(context).getViewMode(appWidgetId)
        InteractionRenderDispatcher.render(
            viewMode,
            request(
                context,
                appWidgetId,
                refreshContext,
                LocalDateTime.now(),
                repository,
                startTimeMs,
                actionTag,
                extraMetadata,
                partialPush,
                origin,
            ),
        )
        refreshContext.database.appLogDao().log(
            "WIDGET_RENDER_OK",
            "widget=$appWidgetId view=$viewMode path=$reason action=$actionTag",
        )
    }

    private suspend fun prepareContext(
        context: Context,
        appWidgetId: Int,
        reason: String,
    ): WidgetRefreshContextResolver.Resolved {
        val resolved = contextResolver.resolve(context, appWidgetId)
        refreshRequester.requestIfStale(context, resolved, reason)
        return resolved
    }

    private fun request(
        context: Context,
        appWidgetId: Int,
        refreshContext: WidgetRefreshContextResolver.Resolved,
        now: LocalDateTime,
        repository: WeatherRepository?,
        startTimeMs: Long,
        actionTag: String,
        extraMetadata: String = "",
        partialPush: Boolean = false,
        origin: WidgetPushDispatcher.Origin = WidgetPushDispatcher.Origin.USER_INTERACTION,
    ) = InteractionRenderDispatcher.Request(
        context,
        appWidgetId,
        refreshContext,
        now,
        repository,
        startTimeMs,
        actionTag,
        extraMetadata,
        partialPush,
        origin,
    )
}
