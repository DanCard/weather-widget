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

    /**
     * An on-demand fetch ended ([HourlyOnDemandWorker]): drop the day's "Fetching…" banner if its
     * hours arrived, else say where the data ends. The caller repaints the widget.
     */
    suspend fun completeOnDemand(
        context: Context,
        appWidgetId: Int,
        date: String,
        lat: Double,
        lon: Double,
        announce: Boolean = true,
    ) {
        val nowMs = System.currentTimeMillis()
        val database = WeatherDatabase.getDatabase(context)
        val stateManager = WidgetStateManager(context)
        val dayLabel = NoHourlyDayClickCoordinator.formatDayLabel(date)
        val active = stateManager.getActiveTransientMessage(appWidgetId)
        val pending = NoHourlyDayClickCoordinator.buildPendingMessage(context, dayLabel)
        // A banner for a later day (the user panned on) is not this result's to replace or clear.
        if (active != null && active != pending) {
            database.appLogDao().log("CLICK_DAILY_NO_HOURLY", "phase=result date=$date superseded")
            return
        }
        // A paint's gap-fill ([fillHourlyGaps]) raised no banner, so it settles none either.
        if (!announce && active != pending) {
            database.appLogDao().log("CLICK_DAILY_NO_HOURLY", "phase=result date=$date quiet")
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
            // FetchBanner.clear also pushes the banner GONE: RemoteViews visibility is sticky.
            FetchBanner.clear(
                context,
                NoHourlyDayClickCoordinator.buildPendingMessage(context, dayLabel),
                intArrayOf(appWidgetId),
            )
            database.appLogDao().log("CLICK_DAILY_NO_HOURLY", "phase=result date=$date hasHourly=true -> cleared")
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
        val message = NoHourlyDayClickCoordinator.buildNoDataMessage(context, dayLabel, endLabel)
        FetchBanner.show(
            context, message, intArrayOf(appWidgetId), "clear_no_hourly_msg", nowMs,
            showMs = WidgetTransientMessagePolicy.NO_HOURLY_MESSAGE_DURATION_MS,
        )
        database.appLogDao().log(
            "CLICK_DAILY_NO_HOURLY",
            "phase=result date=$date hasHourly=false -> \"$message\"",
        )
    }

    /**
     * The hourly view settled on a day by ‹ › rather than a day tap: the same fetch under the
     * "Fetching…" banner for a day its source has not covered, or "No hourly forecast for {day} — data
     * ends …" for a future day no fetch can help ([HourlyOnDemand.panAction];
     * plans/261009-hourly-pan-into-empty-day-fetches.md). The follow-up settles first, so a run of
     * taps ends in one fetch.
     */
    suspend fun afterHourlyNavigate(context: Context, appWidgetId: Int, nowMs: Long = System.currentTimeMillis()) {
        val stateManager = WidgetStateManager(context)
        if (!stateManager.getViewMode(appWidgetId).isGraphMode) return
        val database = WeatherDatabase.getDatabase(context)
        val window = hourlyWindow(context, stateManager, database, appWidgetId, nowMs) ?: return
        val action = window.action
        val sourceId = window.sourceId
        val latest = window.latest
        val date = when (action) {
            is HourlyOnDemand.PanAction.Fetch -> action.date
            is HourlyOnDemand.PanAction.NoDataMessage -> action.date
            HourlyOnDemand.PanAction.Nothing -> null
        }
        database.appLogDao().log(
            "HOURLY_PAN",
            "widget=$appWidgetId window=${window.days.first()}..${window.days.last()} source=$sourceId action=$action",
        )
        if (date == null) return
        val dateStr = date.toString()
        val dayLabel = NoHourlyDayClickCoordinator.formatDayLabel(dateStr)
        val pendingMessage = NoHourlyDayClickCoordinator.buildPendingMessage(context, dayLabel)
        if (stateManager.getActiveTransientMessage(appWidgetId) == pendingMessage) return // already fetching it
        when (action) {
            is HourlyOnDemand.PanAction.Fetch -> {
                HourlyOnDemandWorker.enqueue(
                    context = context,
                    widgetId = appWidgetId,
                    date = dateStr,
                    sourceId = sourceId,
                    lat = latest.locationLat,
                    lon = latest.locationLon,
                    hours = action.hours,
                    afterPan = true,
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

    /**
     * The one hourly gap-fill (plans/261009-one-hourly-gap-fill.md). A paint of an hourly view
     * (temperature, precipitation, cloud) found hours missing in its window: ask the same rule as a
     * pan ([HourlyOnDemand.panAction]) and, only when a fetch can cover a day in view, fetch that
     * source alone — quietly, every [GAP_FILL_COOLDOWN_MS] at most. It replaced a forced full sync
     * per handler, which also fired on every pan beside the on-demand fetch and refetched sources
     * whose data simply ends.
     *
     * Quiet: no banner (a paint is not the user asking), and KEEP, so it never replaces a pan's or a
     * tap's announced fetch. It is what retries after a failed fetch with no tap or pan.
     */
    suspend fun fillHourlyGaps(
        context: Context,
        appWidgetId: Int,
        reason: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val stateManager = WidgetStateManager(context)
        if (!stateManager.getViewMode(appWidgetId).isGraphMode) return
        val database = WeatherDatabase.getDatabase(context)
        val window = hourlyWindow(context, stateManager, database, appWidgetId, nowMs) ?: return
        val fetch = window.action as? HourlyOnDemand.PanAction.Fetch
        val coolingDown = fetch != null &&
            !stateManager.shouldRefreshMissingData(appWidgetId, window.sourceId, GAP_FILL_REFRESH_TYPE, GAP_FILL_COOLDOWN_MS)
        database.appLogDao().log(
            "HOURLY_GAP_FILL",
            "widget=$appWidgetId reason=$reason window=${window.days.first()}..${window.days.last()} " +
                "source=${window.sourceId} action=${window.action}${if (coolingDown) " cooldown" else ""}",
        )
        if (fetch == null || coolingDown) return
        stateManager.markMissingDataRefreshRequested(appWidgetId, window.sourceId, GAP_FILL_REFRESH_TYPE)
        HourlyOnDemandWorker.enqueue(
            context = context,
            widgetId = appWidgetId,
            date = fetch.date.toString(),
            sourceId = window.sourceId,
            lat = window.latest.locationLat,
            lon = window.latest.locationLon,
            hours = fetch.hours,
            afterPan = true,
            announce = false,
        )
    }

    const val GAP_FILL_REFRESH_TYPE = "hourly_gaps"
    const val GAP_FILL_COOLDOWN_MS = 15 * 60 * 1000L

    private class HourlyWindow(
        val sourceId: String,
        val latest: com.weatherwidget.data.local.ForecastEntity,
        val days: List<LocalDate>,
        val action: HourlyOnDemand.PanAction,
    )

    /** The hourly view's window right now, and what [HourlyOnDemand.panAction] says about it. */
    private suspend fun hourlyWindow(
        context: Context,
        stateManager: WidgetStateManager,
        database: WeatherDatabase,
        appWidgetId: Int,
        nowMs: Long,
    ): HourlyWindow? {
        val zone = java.time.ZoneId.systemDefault()
        val zoom = stateManager.getZoomWindow(appWidgetId)
        val centerMs = nowMs + stateManager.getHourlyOffset(appWidgetId) * 3_600_000L
        val windowStartMs = centerMs - zoom.backHours * 3_600_000L
        val windowEndMs = centerMs + zoom.forwardHours * 3_600_000L
        val sourceId = stateManager.getCurrentDisplaySource(appWidgetId).id
        val latest = database.forecastDao().getLatestWeather() ?: return null
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
        return HourlyWindow(sourceId, latest, daysInView, action)
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
        // A day the source's stored hourly does not cover is fetched now, from that source alone
        // (HourlyOnDemand; any source). Its hourly view opens either way, empty where data is missing.
        val onDemandHours =
            if (requiresHourly) {
                NoHourlyDayClickCoordinator.onDemandHours(database, stateManager, appWidgetId, date, lat, lon)
            } else {
                null
            }
        if (onDemandHours != null) {
            val pendingMessage =
                NoHourlyDayClickCoordinator.buildPendingMessage(context, NoHourlyDayClickCoordinator.formatDayLabel(date))
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
            HourlyOnDemandWorker.enqueue(
                context = context,
                widgetId = appWidgetId,
                date = date,
                sourceId = stateManager.getCurrentDisplaySource(appWidgetId).id,
                lat = lat,
                lon = lon,
                hours = onDemandHours,
                afterPan = false,
            )
        } else if (requiresHourly && !hasHourly) {
            // Nothing a fetch can bring: say where the data ends, over the empty view.
            val message = NoHourlyDayClickCoordinator.buildNoDataMessage(
                context,
                NoHourlyDayClickCoordinator.formatDayLabel(date),
                NoHourlyDayClickCoordinator.lastHourlyEndLabelForSource(database, stateManager, appWidgetId, lat, lon),
            )
            stateManager.setTransientMessage(
                appWidgetId,
                message,
                System.currentTimeMillis() + WidgetTransientMessagePolicy.NO_HOURLY_MESSAGE_DURATION_MS,
            )
            WidgetWorkScheduler.enqueueDelayedUiRepaint(
                context = context,
                appWidgetId = appWidgetId,
                reason = "clear_no_hourly_msg",
                initialDelayMs =
                    WidgetTransientMessagePolicy.NO_HOURLY_MESSAGE_DURATION_MS +
                        WidgetTransientMessagePolicy.CLEAR_BUFFER_MS,
            )
            database.appLogDao().log("CLICK_DAILY_NO_HOURLY", "phase=no_data date=$date -> \"$message\"")
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
