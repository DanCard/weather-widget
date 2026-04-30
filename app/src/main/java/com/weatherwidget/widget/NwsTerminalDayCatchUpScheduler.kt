package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object NwsTerminalDayCatchUpScheduler {
    private const val TAG = "NwsTerminalCatchUp"
    private const val COOLDOWN_MS = 15 * 60 * 1000L
    private const val SOURCE_KEY = "NWS_TERMINAL_DAY"
    private const val REFRESH_TYPE = "nws_terminal_catch_up"
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

    fun evaluateCatchUpNeed(
        forecasts: List<com.weatherwidget.data.local.ForecastEntity>,
        today: LocalDate = LocalDate.now(),
    ): CatchUpDecision {
        val terminalDay = NwsTerminalDayCatchUpPolicy.detectTerminalDayMissingHigh(forecasts, today)
        return if (terminalDay != null) {
            CatchUpDecision(
                isNeeded = true,
                terminalDayInfo = terminalDay,
                reason = "terminal_nws_day_missing_high date=${terminalDay.date} low=${terminalDay.lowTemp}",
            )
        } else {
            CatchUpDecision(
                isNeeded = false,
                terminalDayInfo = null,
                reason = "no_missing_terminal_day",
            )
        }
    }

    suspend fun evaluateAndMaybeEnqueue(
        context: Context,
        isCharging: Boolean,
        isScreenInteractive: Boolean,
        trigger: String,
        now: LocalTime = LocalTime.now(),
    ) {
        val inWindow = NwsTerminalDayCatchUpPolicy.isInCatchUpWindow(now)
        if (!NwsTerminalDayCatchUpPolicy.shouldScheduleCatchUp(isCharging, isScreenInteractive, inWindow)) {
            WeatherDatabase.getDatabase(context).appLogDao().log(
                "NWS_TERMINAL_CATCH_UP_EVAL",
                "trigger=$trigger result=policy_skip charging=$isCharging interactive=$isScreenInteractive inWindow=$inWindow",
                "INFO",
            )
            return
        }

        val database = WeatherDatabase.getDatabase(context)
        val latestForecast = database.forecastDao().getLatestWeather()
        val lat = latestForecast?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestForecast?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, WeatherWidgetProvider::class.java),
        )

        database.appLogDao().log(
            "NWS_TERMINAL_CATCH_UP_EVAL",
            "trigger=$trigger result=eligible widgetCount=${widgetIds.size} lat=$lat lon=$lon",
            "INFO",
        )

        if (widgetIds.isEmpty()) {
            return
        }

        val stateManager = WidgetStateManager(context)
        for (wid in widgetIds) {
            maybeEnqueueCatchUp(
                context = context,
                database = database,
                stateManager = stateManager,
                appWidgetId = wid,
                lat = lat,
                lon = lon,
                now = now,
            )
        }
    }

    suspend fun maybeEnqueueCatchUp(
        context: Context,
        database: WeatherDatabase,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        lat: Double,
        lon: Double,
        now: LocalTime = LocalTime.now(),
    ) {
        if (!NwsTerminalDayCatchUpPolicy.isInCatchUpWindow(now)) {
            return
        }

        val startDate = LocalDate.now().toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endDate = LocalDate.now().plusDays(14).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val forecasts = database.forecastDao().getLatestForecastsInRangeBySource(
            startDate, endDate, lat, lon, com.weatherwidget.data.model.WeatherSource.NWS.id,
        )

        val decision = evaluateCatchUpNeed(forecasts)
        if (!decision.isNeeded) {
            database.appLogDao().log(
                "NWS_TERMINAL_CATCH_UP_SKIP",
                "widget=$appWidgetId reason=${decision.reason}",
                "INFO",
            )
            return
        }

        if (!stateManager.shouldRefreshMissingData(appWidgetId, SOURCE_KEY, REFRESH_TYPE, COOLDOWN_MS)) {
            database.appLogDao().log(
                "NWS_TERMINAL_CATCH_UP_SKIP",
                "widget=$appWidgetId reason=cooldown ${decision.reason}",
                "INFO",
            )
            return
        }

        val request =
            OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                .setInputData(
                    androidx.work.Data.Builder()
                        .putBoolean(WeatherWidgetWorker.KEY_NWS_TERMINAL_CATCH_UP, true)
                        .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LAT, lat)
                        .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LON, lon)
                        .build(),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WeatherWidgetProvider.WORK_NAME_NWS_TERMINAL_CATCHUP,
            ExistingWorkPolicy.KEEP,
            request,
        )
        stateManager.markMissingDataRefreshRequested(appWidgetId, SOURCE_KEY, REFRESH_TYPE)
        database.appLogDao().log(
            "NWS_TERMINAL_CATCH_UP_REQ",
            "widget=$appWidgetId ${decision.reason}",
            "INFO",
        )
    }

    fun scheduleNextCatchUpAttempt(context: Context) {
        runCatching {
            val delayMs = NwsTerminalDayCatchUpPolicy.computeJitteredDelay()
            val constraints =
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val workRequest =
                OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setInputData(
                        androidx.work.Data.Builder()
                            .putBoolean(WeatherWidgetWorker.KEY_NWS_TERMINAL_CATCH_UP, true)
                            .build(),
                    )
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WeatherWidgetProvider.WORK_NAME_NWS_TERMINAL_CATCHUP,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
            val dueAtMs = System.currentTimeMillis() + delayMs
            logSchedulerEvent(
                context = context,
                tag = "NWS_TERMINAL_CATCH_UP_SCHEDULED",
                message = "delayMs=$delayMs dueAt=${formatTime(dueAtMs)} workId=${workRequest.id}",
            )
            Log.d(TAG, "scheduleNextCatchUpAttempt: delayMs=$delayMs id=${workRequest.id}")
        }.onFailure { e ->
            Log.e(TAG, "scheduleNextCatchUpAttempt failed: ${e.message}", e)
        }
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(WeatherWidgetProvider.WORK_NAME_NWS_TERMINAL_CATCHUP)
            logSchedulerEvent(
                context = context,
                tag = "NWS_TERMINAL_CATCH_UP_CANCELLED",
                message = "name=${WeatherWidgetProvider.WORK_NAME_NWS_TERMINAL_CATCHUP}",
            )
            Log.d(TAG, "cancel: canceled ${WeatherWidgetProvider.WORK_NAME_NWS_TERMINAL_CATCHUP}")
        }.onFailure { e ->
            Log.e(TAG, "cancel failed: ${e.message}", e)
        }
    }

    private fun logSchedulerEvent(
        context: Context,
        tag: String,
        message: String,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            WeatherDatabase.getDatabase(context).appLogDao().log(tag, message, "INFO")
        }
    }

    private fun formatTime(timestampMs: Long): String =
        timestampFormatter.format(Instant.ofEpochMilli(timestampMs))
}
