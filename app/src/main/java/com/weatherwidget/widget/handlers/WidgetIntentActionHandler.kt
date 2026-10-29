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
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.RefreshNotice
import com.weatherwidget.widget.RefreshToast
import com.weatherwidget.widget.SourceViewRecorder
import com.weatherwidget.shared.sourceview.SourceSwitchRunner
import com.weatherwidget.shared.sourceview.SourceSwitchRunner.RefreshOutcome
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetPaintPrioritizer
import com.weatherwidget.widget.WidgetPushDispatcher
import com.weatherwidget.widget.WidgetRenderer
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomStage
import com.weatherwidget.widget.ZoomWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import java.time.LocalDateTime

/** Implements action state transitions and delegates data/render work to the daily/graph pipelines. */
internal object WidgetIntentActionHandler {
    private const val TAG = "WidgetIntentAction"

    /** Below this a context prepare is not worth a line; this runs on every interaction. */
    private const val PREPARE_SLOW_MS = 50L
    private val contextResolver = WidgetRefreshContextResolver()
    private val refreshRequester = InteractionRefreshRequester()

    suspend fun renderAllWidgetsFromCache(
        context: Context,
        repository: WeatherRepository?,
    ): RefreshNotice? {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val rawIds =
            appWidgetManager
                .getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
                .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
                .toIntArray()
        val lastInteractedId = WidgetStateManager(context).getLastInteractedWidgetId()
        val ids = WidgetPaintPrioritizer.prioritize(
            context = context,
            appWidgetIds = rawIds,
            lastInteractedId = lastInteractedId,
            appWidgetManager = appWidgetManager,
        )
        return renderWidgetsFromCache(context, ids, repository)
    }

    suspend fun renderWidgetFromCache(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository?,
    ) = renderWidgetsFromCache(context, intArrayOf(appWidgetId), repository)

    /** Returns what the widgets' stale checks queued, merged — for "Tap to refresh"'s toast. */
    private suspend fun renderWidgetsFromCache(
        context: Context,
        ids: IntArray,
        repository: WeatherRepository?,
    ): RefreshNotice? {
        var notice: RefreshNotice? = null
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
                notice = RefreshNotice.merge(notice, refreshWidget(
                    context,
                    id,
                    "refresh_action_cache_first",
                    repository,
                    actionTag = "REFRESH",
                    partialPush = true,
                    origin = WidgetPushDispatcher.Origin.ACTION_REFRESH,
                ))
            }
        }
        return notice
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
            ) ?: return
        logIfQueued(context, "widget_nav", refreshContext)
        val request =
            request(
                context,
                appWidgetId,
                refreshContext,
                LocalDateTime.now(),
                repository,
                startMs,
                "NAV",
                deferActuals = true,
            )
        if (viewMode.isGraphMode) {
            GraphInteractionRenderer.navigate(
                InteractionRenderDispatcher.graphRequest(request),
                isLeft,
            )
            // After the paint: a day with no hourly fetches or says so (HourlyOnDemand.panAction).
            com.weatherwidget.widget.WidgetDayClickCoordinator.afterHourlyNavigate(context, appWidgetId)
        } else {
            DailyInteractionRenderer.navigate(
                InteractionRenderDispatcher.dailyRequest(request),
                isLeft,
            )
        }
    }

    suspend fun recenterToday(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository?,
    ) {
        if (WidgetStateManager(context).getViewMode(appWidgetId) != ViewMode.DAILY) {
            Log.w(TAG, "Ignoring stale recenter-today PendingIntent for non-daily widget $appWidgetId")
            return
        }
        val startMs = SystemClock.elapsedRealtime()
        val refreshContext = prepareContext(context, appWidgetId, "daily_recenter_today") ?: return
        DailyInteractionRenderer.recenterToday(
            InteractionRenderDispatcher.dailyRequest(
                request(
                    context,
                    appWidgetId,
                    refreshContext,
                    LocalDateTime.now(),
                    repository,
                    startMs,
                    "RECENTER_TODAY",
                    deferActuals = true,
                ),
            ),
        )
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
        val refreshContext = prepareContext(context, appWidgetId, "cycle_zoom") ?: return
        logIfQueued(context, "widget_zoom", refreshContext)
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
                    deferActuals = true,
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
        switchSource(context, appWidgetId, repository, startMs, SwitchKind.TOGGLE) {
            stateManager.toggleDisplaySource(appWidgetId)
        }
    }

    /**
     * Daily-view home button: put the display source back to the preferred (first visible) source.
     *
     * Deliberately a sibling of [toggleApi] rather than a flag on [setView]: the graph views' home
     * button means "go back to the daily view" and must keep meaning only that. Here the view is
     * already home and the SOURCE is what is off it.
     *
     * When the widget already shows the preferred source the tap came from a stale button — bound
     * under an older source order (e.g. the shown source was dragged first in Settings). The source
     * is left alone but the widget is repainted, so the button is re-decided (hidden, or recentre):
     * returning silently left a visible button that did nothing on every tap (Fold 4, 2026-10-22).
     * That repaint is not a switch, so it does not go through [SourceSwitchRunner].
     */
    suspend fun resetSource(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository?,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val preferred = stateManager.getPrimarySource()
        if (stateManager.getCurrentDisplaySource(appWidgetId) == preferred) {
            Log.d(TAG, "Stale reset-source PendingIntent for widget $appWidgetId; repainting its header")
            val refreshContext = prepareContext(context, appWidgetId, "reset_source_stale") ?: return
            InteractionRenderDispatcher.render(
                stateManager.getViewMode(appWidgetId),
                request(
                    context,
                    appWidgetId,
                    refreshContext,
                    LocalDateTime.now(),
                    repository,
                    startMs,
                    "RESET_SOURCE_STALE",
                    "source=${preferred.id}",
                ),
            )
            return
        }
        switchSource(context, appWidgetId, repository, startMs, SwitchKind.RESET) {
            stateManager.setCurrentDisplaySource(appWidgetId, preferred)
            preferred
        }
    }

    /** What differs between the API button and the home button's source reset. */
    internal enum class SwitchKind(
        val trigger: SourceViewTrigger,
        val reason: String,
        val staleReason: String,
        val actionTag: String,
        val noticeOrigin: String,
        val deferActuals: Boolean,
    ) {
        TOGGLE(SourceViewTrigger.TOGGLE, "toggle_api", "toggle_api_stale", "TOGGLE_API", "widget_toggle_api", true),
        RESET(SourceViewTrigger.HOME, "reset_source", "reset_source_stale", "RESET_SOURCE", "widget_reset_source", false),
    }

    /**
     * A user switch of the displayed source, through the shared [SourceSwitchRunner] (desktop:
     * `DesktopSourceSwitch`). Persist and paint are the critical path; the view tally, the staleness
     * probe / forced refresh and the app_logs row follow. They used to run before the paint: the probe
     * reads Room and the request enqueues WorkManager work, neither of which the paint reads.
     *
     * The deferred phase joins this call ([coroutineScope]), so it still ends inside the receiver's
     * goAsync lifetime, after the widget is painted. A failed paint is held until the deferred phase
     * finishes, then rethrown: thrown inside the scope it would cancel the phase, and the persisted
     * switch would go uncounted and a source with no data unrefreshed.
     * plans/261029-shared-source-switch-runner.md
     */
    private suspend fun switchSource(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository?,
        startMs: Long,
        kind: SwitchKind,
        select: () -> WeatherSource,
    ) {
        val from = WidgetStateManager(context).getCurrentDisplaySource(appWidgetId)
        val host = WidgetSourceSwitchHost(context, appWidgetId, repository, startMs, kind, select)
        var failure: Exception? = null
        coroutineScope {
            try {
                SourceSwitchRunner(host, platform = "android").run(from, kind.trigger, this)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e // already logged by the runner
            }
        }
        failure?.let { throw it }
    }

    internal class WidgetSourceSwitchHost(
        private val context: Context,
        private val appWidgetId: Int,
        private val repository: WeatherRepository?,
        private val startMs: Long,
        private val kind: SwitchKind,
        private val select: () -> WeatherSource,
    ) : SourceSwitchRunner.Host {
        private val now = LocalDateTime.now()
        private val viewMode = WidgetStateManager(context).getViewMode(appWidgetId)

        /** Set by the paint; null when there was no location (the no-location state was painted). */
        private var refreshContext: WidgetRefreshContextResolver.Resolved? = null

        override suspend fun persistSelection(): WeatherSource = select()

        override suspend fun showFromCache(source: WeatherSource) {
            val resolved = prepareContext(context, appWidgetId, kind.reason) ?: return
            refreshContext = resolved
            InteractionRenderDispatcher.render(
                viewMode,
                request(
                    context,
                    appWidgetId,
                    resolved,
                    now,
                    repository,
                    startMs,
                    kind.actionTag,
                    "source=${source.id}",
                    deferActuals = kind.deferActuals,
                ),
            )
        }

        override suspend fun refreshIfStale(source: WeatherSource): RefreshOutcome {
            val resolved = refreshContext ?: return RefreshOutcome.SKIPPED
            val stale = GraphInteractionRenderer.selectedSourceNeedsRefresh(
                context,
                appWidgetId,
                resolved,
                source,
                now,
                System.currentTimeMillis(),
            )
            val forced = if (stale) {
                refreshRequester.requestForced(
                    context,
                    resolved.database.appLogDao(),
                    reason = kind.staleReason,
                    targetSourceId = source.id,
                )
            } else {
                null
            }
            // A source change shows no notice, whatever it queued (user, 2026-10-15): the user is
            // looking at another source, not asking for data. The outcome is still logged.
            RefreshNotice.merge(resolved.refreshNotice, forced)
                ?.let { RefreshToast.logSilent(context, kind.noticeOrigin, it) }
            return when {
                !stale -> RefreshOutcome.FRESH
                forced != null -> RefreshOutcome.QUEUED
                else -> RefreshOutcome.FAILED
            }
        }

        override suspend fun recordView(source: WeatherSource) =
            SourceViewRecorder.record(context, source, viewMode, kind.trigger)

        override suspend fun persistLog(tag: String, message: String, level: String) =
            WeatherDatabase.getDatabase(context).appLogDao().log(tag, message, level)
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
        interactionToken: String? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val previousMode = stateManager.getViewMode(appWidgetId)
        stateManager.setViewMode(appWidgetId, targetMode)
        if (targetMode == ViewMode.DAILY || previousMode == ViewMode.DAILY) {
            stateManager.setZoomLevel(appWidgetId, ZoomStage.WIDE)
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
            buildString {
                append("mode=${targetMode.name}")
                interactionToken?.let { append(" token=$it") }
            },
            deferActuals = true,
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
        deferActuals: Boolean = false,
    ) {
        val refreshContext = prepareContext(context, appWidgetId, staleReason) ?: return
        logIfQueued(context, "widget_$staleReason", refreshContext)
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
                deferActuals = deferActuals,
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
    ): RefreshNotice? {
        val refreshContext = prepareContext(context, appWidgetId, reason) ?: return null
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
        return refreshContext.refreshNotice
    }

    /**
     * Browsing taps (arrows, zoom, view switches) show no notice, even for a refresh they queued: a
     * notice is for a refresh the user asked for (user, 2026-10-16;
     * plans/261016-refresh-notice-only-on-refresh-requests.md). A queued refresh still logs its row.
     */
    private suspend fun logIfQueued(
        context: Context,
        origin: String,
        refreshContext: WidgetRefreshContextResolver.Resolved,
    ) {
        val notice = refreshContext.refreshNotice as? RefreshNotice.Queued ?: return
        RefreshToast.logSilent(context, origin, notice)
    }

    /**
     * Null when there is no location to render at. The caller must abort the interaction — but the
     * no-location state is painted first: the user just tapped the widget, so dropping the
     * interaction silently would look like the tap was lost.
     */
    private suspend fun prepareContext(
        context: Context,
        appWidgetId: Int,
        reason: String,
    ): WidgetRefreshContextResolver.Resolved? {
        // The largest untimed span on the click path as of 2026-09-06: this runs
        // ActiveLocationResolver and forecastDao.getLatestForecastBySource before
        // TEMP_PIPELINE_PERF's timer starts, so a click measured ~1,224ms end to end while the paint
        // it contains reported ~400ms. `requestIfStale` is timed apart from the resolve because it
        // is a separate suspect inside the same call — it can enqueue work.
        val prepareStartMs = SystemClock.elapsedRealtime()
        val resolved = contextResolver.resolve(context, appWidgetId)
        val afterResolveMs = SystemClock.elapsedRealtime()
        if (resolved == null) {
            Log.w(TAG, "No location for widget=$appWidgetId reason=$reason; painting no-location state")
            WidgetRenderer.updateWidgetNoLocation(
                context = context,
                appWidgetManager = AppWidgetManager.getInstance(context),
                appWidgetId = appWidgetId,
            )
            return null
        }
        val notice = refreshRequester.requestIfStale(context, resolved, reason)
        val endMs = SystemClock.elapsedRealtime()
        if (endMs - prepareStartMs >= PREPARE_SLOW_MS) {
            Log.i(
                TAG,
                "INTERACTION_PREPARE_PERF widget=$appWidgetId reason=$reason " +
                    "total=${endMs - prepareStartMs}ms resolve=${afterResolveMs - prepareStartMs}ms " +
                    "staleCheck=${endMs - afterResolveMs}ms",
            )
        }
        return resolved.copy(refreshNotice = notice)
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
        deferActuals: Boolean = false,
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
        deferActuals,
    )
}
