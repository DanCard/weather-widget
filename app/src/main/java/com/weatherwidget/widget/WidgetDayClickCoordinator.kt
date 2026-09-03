package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
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
        val hasHourly =
            NoHourlyDayClickCoordinator.hasHourlyForTappedDay(
                database = database,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                dateStr = date,
                lat = lat,
                lon = lon,
            )
        val endLabel =
            if (hasHourly) {
                null
            } else {
                NoHourlyDayClickCoordinator.lastHourlyEndLabelForSource(
                    database = database,
                    stateManager = stateManager,
                    appWidgetId = appWidgetId,
                    lat = lat,
                    lon = lon,
                )
            }
        val message =
            NoHourlyDayClickCoordinator.buildResultMessage(
                context = context,
                dayLabel = dayLabel,
                hasHourlyAfterRefresh = hasHourly,
                endLabel = endLabel,
            )
        stateManager.setTransientMessage(
            appWidgetId,
            message,
            nowMs + WidgetTransientMessagePolicy.NO_HOURLY_MESSAGE_DURATION_MS,
        )
        database.appLogDao().log(
            "CLICK_DAILY_NO_HOURLY",
            "phase=result date=$date hasHourly=$hasHourly -> \"$message\"",
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
        if (!hasHourly && requiresHourly) {
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
                "phase=pending date=$date mode=$targetMode -> \"$pendingMessage\"",
            )
            WidgetWorkScheduler.enqueueUiRepaint(context, "show_no_hourly_pending")
            WidgetWorkScheduler.enqueueRequiredNoHourlyFollowUp(
                context = context,
                appWidgetId = appWidgetId,
                date = date,
                lat = lat,
                lon = lon,
                targetSourceId = stateManager.getCurrentDisplaySource(appWidgetId).id,
            )
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
