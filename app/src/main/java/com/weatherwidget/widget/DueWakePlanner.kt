package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.remote.ForecastSchedule
import com.weatherwidget.data.repository.WeatherRepository

/**
 * Plans Android's due-time sync: the shared [ForecastSchedule] over the same plan a sync makes,
 * enqueued as one delayed sync ([WidgetWorkScheduler.enqueueDueWakeSync]) — desktop's forecast-loop
 * sleep (plans/261028-screen-off-cadence-and-due-time-wakes.md). Every full sync re-plans; the
 * current-temp loop plans only when nothing is pending, so an install or upgrade on a charger does not
 * wait for a screen-on or the 24 h backstop. Battery is covered by screen-on ([ScreenOnReceiver]).
 */
internal object DueWakePlanner {
    const val LOG_TAG = "FORECAST_DUE_WAKE"

    suspend fun plan(
        context: Context,
        weatherRepository: WeatherRepository,
        widgetStateManager: WidgetStateManager,
        appLogDao: AppLogDao,
        onlyIfMissing: Boolean,
    ) {
        if (onlyIfMissing && WidgetWorkScheduler.dueWakePending(context)) return
        val location = ActiveLocationResolver.current(context) ?: return
        val nowMs = System.currentTimeMillis()
        val next = weatherRepository.nextForecastDue(
            location.first, location.second, DataFreshness.deviceFetchContext(context, widgetStateManager),
        )
        val sleep = ForecastSchedule.sleep(next, nowMs, fetchFailed = false)
        WidgetWorkScheduler.enqueueDueWakeSync(context, sleep.ms)
        appLogDao.log(LOG_TAG, sleep.logLine() + if (onlyIfMissing) " trigger=missing" else "", "INFO")
    }
}
