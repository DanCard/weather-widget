package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.work.*
import com.weatherwidget.R
import com.weatherwidget.data.local.WeatherEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidgetLoading(context, appWidgetManager, appWidgetId)
        }
        schedulePeriodicUpdate(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // Trigger update when widget is resized
        triggerImmediateUpdate(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        schedulePeriodicUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            triggerImmediateUpdate(context)
        }
    }

    private fun schedulePeriodicUpdate(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<WeatherWidgetWorker>(
            1, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun triggerImmediateUpdate(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
    }

    companion object {
        const val WORK_NAME = "weather_widget_update"
        const val ACTION_REFRESH = "com.weatherwidget.ACTION_REFRESH"

        private const val CELL_WIDTH_DP = 73 // Approximate cell width

        private fun getNumColumns(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int): Int {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 40)
            return (minWidth / CELL_WIDTH_DP).coerceAtLeast(1)
        }

        fun updateWidgetLoading(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            views.setTextViewText(R.id.day2_label, "Today")
            views.setTextViewText(R.id.day2_high, "--°")
            views.setTextViewText(R.id.day2_low, "Loading...")
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private const val TAG = "WeatherWidgetProvider"

        fun updateWidgetWithData(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            weatherList: List<WeatherEntity>
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            val numColumns = getNumColumns(context, appWidgetManager, appWidgetId)
            Log.d(TAG, "updateWidgetWithData: widgetId=$appWidgetId, numColumns=$numColumns, weatherCount=${weatherList.size}")

            val today = LocalDate.now()
            val yesterday = today.minusDays(1)
            val tomorrow = today.plusDays(1)

            // Sort weather by date
            val sortedWeather = weatherList.sortedBy { it.date }

            // Find weather for each day
            val weatherByDate = sortedWeather.associateBy { it.date }

            val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val tomorrowStr = tomorrow.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val day4Str = today.plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val day5Str = today.plusDays(3).format(DateTimeFormatter.ISO_LOCAL_DATE)

            // Determine visibility based on columns
            // 1 col = today only
            // 2 cols = today + tomorrow
            // 3 cols = yesterday + today + tomorrow
            // 4+ cols = add more forecast days

            when {
                numColumns >= 5 -> {
                    views.setViewVisibility(R.id.day1_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day2_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day3_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day4_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day5_container, View.VISIBLE)
                }
                numColumns == 4 -> {
                    views.setViewVisibility(R.id.day1_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day2_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day3_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day4_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day5_container, View.GONE)
                }
                numColumns == 3 -> {
                    views.setViewVisibility(R.id.day1_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day2_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day3_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day4_container, View.GONE)
                    views.setViewVisibility(R.id.day5_container, View.GONE)
                }
                numColumns == 2 -> {
                    views.setViewVisibility(R.id.day1_container, View.GONE)
                    views.setViewVisibility(R.id.day2_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day3_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day4_container, View.GONE)
                    views.setViewVisibility(R.id.day5_container, View.GONE)
                }
                else -> {
                    views.setViewVisibility(R.id.day1_container, View.GONE)
                    views.setViewVisibility(R.id.day2_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day3_container, View.GONE)
                    views.setViewVisibility(R.id.day4_container, View.GONE)
                    views.setViewVisibility(R.id.day5_container, View.GONE)
                }
            }

            // Populate Day 1 (Yesterday)
            weatherByDate[yesterdayStr]?.let { weather ->
                views.setTextViewText(R.id.day1_label, "Yest")
                views.setTextViewText(R.id.day1_high, "${weather.highTemp}°")
                views.setTextViewText(R.id.day1_low, "${weather.lowTemp}°")
            } ?: run {
                views.setTextViewText(R.id.day1_label, "Yest")
                views.setTextViewText(R.id.day1_high, "--°")
                views.setTextViewText(R.id.day1_low, "--°")
            }

            // Populate Day 2 (Today)
            weatherByDate[todayStr]?.let { weather ->
                views.setTextViewText(R.id.day2_label, "Today")
                views.setTextViewText(R.id.day2_high, "${weather.highTemp}°")
                views.setTextViewText(R.id.day2_low, "${weather.lowTemp}°")
            } ?: run {
                views.setTextViewText(R.id.day2_label, "Today")
                views.setTextViewText(R.id.day2_high, "--°")
                views.setTextViewText(R.id.day2_low, "--°")
            }

            // Populate Day 3 (Tomorrow)
            weatherByDate[tomorrowStr]?.let { weather ->
                views.setTextViewText(R.id.day3_label, "Tmrw")
                views.setTextViewText(R.id.day3_high, "${weather.highTemp}°")
                views.setTextViewText(R.id.day3_low, "${weather.lowTemp}°")
            } ?: run {
                views.setTextViewText(R.id.day3_label, "Tmrw")
                views.setTextViewText(R.id.day3_high, "--°")
                views.setTextViewText(R.id.day3_low, "--°")
            }

            // Populate Day 4
            val day4Date = today.plusDays(2)
            weatherByDate[day4Str]?.let { weather ->
                views.setTextViewText(R.id.day4_label, day4Date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()))
                views.setTextViewText(R.id.day4_high, "${weather.highTemp}°")
                views.setTextViewText(R.id.day4_low, "${weather.lowTemp}°")
            } ?: run {
                views.setTextViewText(R.id.day4_label, day4Date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()))
                views.setTextViewText(R.id.day4_high, "--°")
                views.setTextViewText(R.id.day4_low, "--°")
            }

            // Populate Day 5
            val day5Date = today.plusDays(3)
            weatherByDate[day5Str]?.let { weather ->
                views.setTextViewText(R.id.day5_label, day5Date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()))
                views.setTextViewText(R.id.day5_high, "${weather.highTemp}°")
                views.setTextViewText(R.id.day5_low, "${weather.lowTemp}°")
            } ?: run {
                views.setTextViewText(R.id.day5_label, day5Date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()))
                views.setTextViewText(R.id.day5_high, "--°")
                views.setTextViewText(R.id.day5_low, "--°")
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
