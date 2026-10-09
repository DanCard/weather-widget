package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.remote.HourlyOnDemand
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.widget.handlers.NoHourlyDayClickCoordinator
import com.weatherwidget.widget.handlers.WidgetIntentActionHandler
import java.time.LocalDate

/** Owns history and two-phase hourly-availability behavior for daily widget taps. */
internal object WidgetDayClickCoordinator {
    suspend fun handleDayClick(
        context: Context,
        intent: Intent,
        repository: WeatherRepository,
    ) {
        val appWidgetId = widgetId(intent)
        val date = intent.getStringExtra(EXTRA_DATE).orEmpty()
        // showHistory is an explicit opt-in set only by the dedicated forecast-history shortcut
        // (setupHistoryShortcutAt). Day-column taps never set it — past days route to the hourly
        // graph instead of history — so isHistory is log-only here.
        val isHistory = intent.getBooleanExtra(EXTRA_IS_HISTORY, false)
        val showHistory = intent.getBooleanExtra(EXTRA_SHOW_HISTORY, false)
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        val targetViewName =
            intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW) ?: ViewMode.PRECIPITATION.name
        val targetOffset = intent.getIntExtra(WidgetActions.EXTRA_HOURLY_OFFSET, 0)
        val clickSource =
            intent.getStringExtra(WidgetActions.EXTRA_CLICK_SOURCE) ?: "unknown"
        val precipGate =
            intent.getStringExtra(WidgetActions.EXTRA_PRECIP_GATE) ?: "unknown"
        val receiveTimeMs = SystemClock.elapsedRealtime()
        val database = WeatherDatabase.getDatabase(context)
        database.appLogDao().log(
            "CLICK_DAILY",
            "index=$index, date=$date, isHistory=$isHistory, showHistory=$showHistory, " +
                "targetView=$targetViewName, offset=$targetOffset, precipGate=$precipGate, " +
                "clickSource=$clickSource",
        )

        if (showHistory) {
            navigateToHistory(
                context = context,
                intent = intent,
                appWidgetId = appWidgetId,
                date = date,
                database = database,
                receiveTimeMs = receiveTimeMs,
            )
        } else {
            navigateToHourlyView(
                context = context,
                intent = intent,
                appWidgetId = appWidgetId,
                date = date,
                database = database,
                repository = repository,
                receiveTimeMs = receiveTimeMs,
                targetViewName = targetViewName,
                targetOffset = targetOffset,
            )
        }
    }

    suspend fun handleRefreshComplete(
        context: Context,
        intent: Intent,
    ) {
        val appWidgetId = widgetId(intent)
        val date = intent.getStringExtra(EXTRA_DATE).orEmpty()
        val lat = intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LAT, 0.0)
        val lon = intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LON, 0.0)
        val nowMs = System.currentTimeMillis()
        val database = WeatherDatabase.getDatabase(context)
        val stateManager = WidgetStateManager(context)
        val dayLabel = NoHourlyDayClickCoordinator.formatDayLabel(date)
        // A banner for a later day (the user panned on) is not this result's to replace or clear.
        val active = stateManager.getActiveTransientMessage(appWidgetId)
        if (active != null && active != NoHourlyDayClickCoordinator.buildPendingMessage(context, dayLabel)) {
            database.appLogDao().log("CLICK_DAILY_NO_HOURLY", "phase=result date=$date superseded")
            return
        }
        val hasHourly =
            NoHourlyDayClickCoordinator.hasHourlyForTappedDay(
                database = database,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                dateStr = date,
                lat = lat,
                lon = lon,
            )
        if (hasHourly) {
            // The graph under the banner has its data now; drop the "Fetching…" banner (only if it
            // is still the active message — a notice raised meanwhile is not ours to clear).
            // FetchBanner.clear also pushes the banner GONE: RemoteViews visibility is sticky, and
            // the repaint below may be header-only, which left the banner up on the Pixel.
            FetchBanner.clear(
                context,
                NoHourlyDayClickCoordinator.buildPendingMessage(context, dayLabel),
                intArrayOf(appWidgetId),
            )
            database.appLogDao().log("CLICK_DAILY_NO_HOURLY", "phase=result date=$date hasHourly=true -> cleared")
            WidgetWorkScheduler.enqueueUiRepaint(context, "no_hourly_fetched")
            return
        }
        val endLabel =
            NoHourlyDayClickCoordinator.lastHourlyEndLabelForSource(
                database = database,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                lat = lat,
                lon = lon,
            )
        val message =
            NoHourlyDayClickCoordinator.buildResultMessage(
                context = context,
                dayLabel = dayLabel,
                hasHourlyAfterRefresh = false,
                endLabel = endLabel,
            )
        stateManager.setTransientMessage(
            appWidgetId,
            message,
            nowMs + WidgetTransientMessagePolicy.NO_HOURLY_MESSAGE_DURATION_MS,
        )
        database.appLogDao().log(
            "CLICK_DAILY_NO_HOURLY",
            "phase=result date=$date hasHourly=false -> \"$message\"",
        )
        WidgetWorkScheduler.enqueueUiRepaint(context, "show_no_hourly_result")
        WidgetWorkScheduler.enqueueDelayedUiRepaint(
            context = context,
            appWidgetId = appWidgetId,
            reason = "clear_no_hourly_msg",
            initialDelayMs =
                WidgetTransientMessagePolicy.NO_HOURLY_MESSAGE_DURATION_MS +
                    WidgetTransientMessagePolicy.CLEAR_BUFFER_MS,
        )
    }

    /**
     * The hourly view settled on a day by ‹ › rather than a day tap: the same fetch under the
     * "Fetching…" banner for a day Google has not covered, or "No hourly forecast for {day} — data
     * ends …" for a future day no fetch can help ([HourlyOnDemand.panAction];
     * plans/261009-hourly-pan-into-empty-day-fetches.md). The follow-up settles first, so a run of
     * taps ends in one fetch.
     */
    suspend fun afterHourlyNavigate(context: Context, appWidgetId: Int, nowMs: Long = System.currentTimeMillis()) {
        val stateManager = WidgetStateManager(context)
        if (!stateManager.getViewMode(appWidgetId).isGraphMode) return
        val zone = java.time.ZoneId.systemDefault()
        val zoom = stateManager.getZoomWindow(appWidgetId)
        val centerMs = nowMs + stateManager.getHourlyOffset(appWidgetId) * 3_600_000L
        val windowStartMs = centerMs - zoom.backHours * 3_600_000L
        val windowEndMs = centerMs + zoom.forwardHours * 3_600_000L
        val database = WeatherDatabase.getDatabase(context)
        val sourceId = stateManager.getCurrentDisplaySource(appWidgetId).id
        val latest = database.forecastDao().getLatestWeather() ?: return
        val stored = NoHourlyDayClickCoordinator.storedHourlyForSource(
            database, sourceId, latest.locationLat, latest.locationLon, nowMs,
        )
        // Every day the window shows, not only its centre's (HourlyOnDemand.panAction).
        val daysInView = generateSequence(java.time.Instant.ofEpochMilli(windowStartMs).atZone(zone).toLocalDate()) { it.plusDays(1) }
            .takeWhile { !it.isAfter(java.time.Instant.ofEpochMilli(windowEndMs).atZone(zone).toLocalDate()) }
            .toList()
        val hasHourlyByDay = daysInView.associateWith { day ->
            NoHourlyDayClickCoordinator.hasHourlyForTappedDay(
                database, stateManager, appWidgetId, day.toString(), latest.locationLat, latest.locationLon,
            )
        }
        val action = HourlyOnDemand.panAction(sourceId, windowStartMs, windowEndMs, zone, nowMs, stored) { hasHourlyByDay[it] ?: false }
        val date = when (action) {
            is HourlyOnDemand.PanAction.Fetch -> action.date
            is HourlyOnDemand.PanAction.NoDataMessage -> action.date
            HourlyOnDemand.PanAction.Nothing -> null
        }
        database.appLogDao().log(
            "HOURLY_PAN",
            "widget=$appWidgetId window=${daysInView.first()}..${daysInView.last()} source=$sourceId action=$action",
        )
        if (date == null) return
        val dateStr = date.toString()
        val dayLabel = NoHourlyDayClickCoordinator.formatDayLabel(dateStr)
        val pendingMessage = NoHourlyDayClickCoordinator.buildPendingMessage(context, dayLabel)
        if (stateManager.getActiveTransientMessage(appWidgetId) == pendingMessage) return // already fetching it
        when (action) {
            is HourlyOnDemand.PanAction.Fetch -> {
                WidgetWorkScheduler.enqueueRequiredNoHourlyFollowUp(
                    context = context,
                    appWidgetId = appWidgetId,
                    date = dateStr,
                    lat = latest.locationLat,
                    lon = latest.locationLon,
                    targetSourceId = sourceId,
                    settleAfterPan = true,
                )
                // Pushed straight onto the widget: a UI-only repaint of the hourly view can be
                // header-only, and RemoteViews visibility is sticky — the banner never showed (Pixel).
                FetchBanner.show(
                    context, pendingMessage, intArrayOf(appWidgetId), "hourly_pan_pending", nowMs,
                    showMs = NoHourlyDayClickCoordinator.PENDING_MESSAGE_MAX_AGE_MS,
                )
            }
            is HourlyOnDemand.PanAction.NoDataMessage -> {
                val message = NoHourlyDayClickCoordinator.buildNoDataMessage(
                    context,
                    dayLabel,
                    NoHourlyDayClickCoordinator.lastHourlyEndLabelForSource(
                        database, stateManager, appWidgetId, latest.locationLat, latest.locationLon,
                    ),
                )
                FetchBanner.show(
                    context, message, intArrayOf(appWidgetId), "hourly_pan_no_data", nowMs,
                    showMs = WidgetTransientMessagePolicy.NO_HOURLY_MESSAGE_DURATION_MS,
                )
            }
            HourlyOnDemand.PanAction.Nothing -> Unit
        }
    }

    fun isValid(intent: Intent): Boolean {
        val appWidgetId = widgetId(intent)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return false
        val date = intent.getStringExtra(EXTRA_DATE)
        if (date.isNullOrBlank() || runCatching { LocalDate.parse(date) }.isFailure) return false
        if (!intent.hasExtra(ForecastHistoryActivity.EXTRA_LAT) ||
            !intent.hasExtra(ForecastHistoryActivity.EXTRA_LON)
        ) {
            return false
        }
        val lat = intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LON, Double.NaN)
        if (!lat.isFinite() || !lon.isFinite()) return false
        return when (intent.action) {
            WidgetActions.ACTION_DAY_CLICK -> {
                val showHistory =
                    intent.getBooleanExtra(
                        EXTRA_SHOW_HISTORY,
                        intent.getBooleanExtra(EXTRA_IS_HISTORY, false),
                    )
                showHistory ||
                    intent
                        .getStringExtra(WidgetActions.EXTRA_TARGET_VIEW)
                        ?.let { runCatching { ViewMode.valueOf(it) }.isSuccess } == true
            }
            WidgetActions.ACTION_NO_HOURLY_REFRESH_COMPLETE -> true
            else -> true
        }
    }

    private suspend fun navigateToHistory(
        context: Context,
        intent: Intent,
        appWidgetId: Int,
        date: String,
        database: WeatherDatabase,
        receiveTimeMs: Long,
    ) {
        val historyIntent =
            Intent(context, ForecastHistoryActivity::class.java).apply {
                putExtra(ForecastHistoryActivity.EXTRA_TARGET_DATE, date)
                putExtra(
                    ForecastHistoryActivity.EXTRA_LAT,
                    intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LAT, 0.0),
                )
                putExtra(
                    ForecastHistoryActivity.EXTRA_LON,
                    intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LON, 0.0),
                )
                putExtra(
                    ForecastHistoryActivity.EXTRA_SOURCE,
                    intent.getStringExtra(ForecastHistoryActivity.EXTRA_SOURCE).orEmpty(),
                )
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(historyIntent)
        logTiming(database, appWidgetId, "history", date, receiveTimeMs)
    }

    private suspend fun navigateToHourlyView(
        context: Context,
        intent: Intent,
        appWidgetId: Int,
        date: String,
        database: WeatherDatabase,
        repository: WeatherRepository,
        receiveTimeMs: Long,
        targetViewName: String,
        targetOffset: Int,
    ) {
        val targetMode = ViewMode.parseOrDefault(targetViewName, ViewMode.PRECIPITATION)
        val stateManager = WidgetStateManager(context)
        val lat = intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LAT, 0.0)
        val lon = intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LON, 0.0)
        val hasHourly =
            NoHourlyDayClickCoordinator.hasHourlyForTappedDay(
                database = database,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                dateStr = date,
                lat = lat,
                lon = lon,
            )
        val requiresHourly =
            targetMode == ViewMode.PRECIPITATION ||
                targetMode == ViewMode.TEMPERATURE ||
                targetMode == ViewMode.CLOUD_COVER
        // Google keeps 72 h of hourly; a later day is fetched now (HourlyOnDemand).
        val onDemandHours =
            if (requiresHourly) {
                NoHourlyDayClickCoordinator.onDemandHours(database, stateManager, appWidgetId, date, lat, lon)
            } else {
                null
            }
        if (requiresHourly && (!hasHourly || onDemandHours != null)) {
            // Open the day's hourly view straight away, empty where data is missing, under a
            // "Fetching…" banner that the follow-up sync clears (handleRefreshComplete).
            val dayLabel = NoHourlyDayClickCoordinator.formatDayLabel(date)
            val pendingMessage =
                NoHourlyDayClickCoordinator.buildPendingMessage(context, dayLabel)
            stateManager.setTransientMessage(
                appWidgetId,
                pendingMessage,
                System.currentTimeMillis() + NoHourlyDayClickCoordinator.PENDING_MESSAGE_MAX_AGE_MS,
            )
            database.appLogDao().log(
                "CLICK_DAILY_NO_HOURLY",
                "phase=pending date=$date mode=$targetMode hasHourly=$hasHourly " +
                    "onDemandHours=$onDemandHours -> \"$pendingMessage\"",
            )
            // The fetch first: a paint that fails must not cost the data it is waiting for.
            WidgetWorkScheduler.enqueueRequiredNoHourlyFollowUp(
                context = context,
                appWidgetId = appWidgetId,
                date = date,
                lat = lat,
                lon = lon,
                targetSourceId = stateManager.getCurrentDisplaySource(appWidgetId).id,
            )
            WidgetIntentActionHandler.setView(context, appWidgetId, targetMode, targetOffset, repository)
            logTiming(database, appWidgetId, "hourly_fetching", date, receiveTimeMs)
            return
        }

        // The caller (WidgetIntentRouter.handleDayClick) already holds the per-widget mutex, so
        // call the lock-free action handler directly — re-entering runInteraction would deadlock.
        // Zoom is reset to WIDE inside setView (previous mode is always DAILY on the day-click
        // path), so the old explicit setZoomLevel(WIDE) here is gone.
        WidgetIntentActionHandler.setView(
            context,
            appWidgetId,
            targetMode,
            targetOffset,
            repository,
        )
        logTiming(database, appWidgetId, "hourly", date, receiveTimeMs)
    }

    private suspend fun logTiming(
        database: WeatherDatabase,
        appWidgetId: Int,
        branch: String,
        date: String,
        startMs: Long,
    ) {
        val totalMs = SystemClock.elapsedRealtime() - startMs
        database.appLogDao().log(
            "CLICK_TIMING",
            "widget=$appWidgetId branch=$branch total=${totalMs}ms",
        )
        if (totalMs > 500L) {
            database.appLogDao().log(
                "CLICK_SLOW",
                "widget=$appWidgetId branch=$branch total=${totalMs}ms date=$date",
            )
        }
    }

    private fun widgetId(intent: Intent): Int =
        intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )

    private const val EXTRA_DATE = "date"
    private const val EXTRA_IS_HISTORY = "isHistory"
    private const val EXTRA_SHOW_HISTORY = "showHistory"
    private const val EXTRA_INDEX = "index"
}
