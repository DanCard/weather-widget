package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
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
        private const val TAG = "WeatherWidgetProvider"

        private const val CELL_WIDTH_DP = 73
        private const val CELL_HEIGHT_DP = 68  // Slightly smaller to detect 2-row widgets correctly

        private fun getWidgetSize(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int): Pair<Int, Int> {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 40)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
            Log.d(TAG, "getWidgetSize: minWidth=$minWidth, minHeight=$minHeight")
            val cols = (minWidth / CELL_WIDTH_DP).coerceAtLeast(1)
            val rows = (minHeight / CELL_HEIGHT_DP).coerceAtLeast(1)
            return cols to rows
        }

        private fun dpToPx(context: Context, dp: Int): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        fun updateWidgetLoading(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)
            views.setTextViewText(R.id.day2_label, "Today")
            views.setTextViewText(R.id.day2_high, "--°")
            views.setTextViewText(R.id.day2_low, "Loading...")
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun updateWidgetWithData(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            weatherList: List<WeatherEntity>
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            val (numColumns, numRows) = getWidgetSize(context, appWidgetManager, appWidgetId)
            Log.d(TAG, "updateWidgetWithData: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, weatherCount=${weatherList.size}")

            val today = LocalDate.now()
            val sortedWeather = weatherList.sortedBy { it.date }
            val weatherByDate = sortedWeather.associateBy { it.date }

            // Use graph mode for 2+ rows
            val useGraph = numRows >= 2

            if (useGraph) {
                views.setViewVisibility(R.id.text_container, View.GONE)
                views.setViewVisibility(R.id.graph_view, View.VISIBLE)

                // Build day data for graph
                val days = buildDayDataList(today, weatherByDate, numColumns)

                // Calculate widget size in pixels
                val widthPx = dpToPx(context, numColumns * CELL_WIDTH_DP)
                val heightPx = dpToPx(context, numRows * CELL_HEIGHT_DP)

                // Render graph
                val bitmap = TemperatureGraphRenderer.renderGraph(context, days, widthPx, heightPx)
                views.setImageViewBitmap(R.id.graph_view, bitmap)
            } else {
                views.setViewVisibility(R.id.text_container, View.VISIBLE)
                views.setViewVisibility(R.id.graph_view, View.GONE)

                // Text mode - set visibility and populate
                updateTextMode(views, today, weatherByDate, numColumns)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun buildDayDataList(
            today: LocalDate,
            weatherByDate: Map<String, WeatherEntity>,
            numColumns: Int
        ): List<TemperatureGraphRenderer.DayData> {
            val days = mutableListOf<TemperatureGraphRenderer.DayData>()

            // Determine which days to show based on columns
            val dayOffsets = when {
                numColumns >= 5 -> listOf(-1L, 0L, 1L, 2L, 3L)
                numColumns == 4 -> listOf(-1L, 0L, 1L, 2L)
                numColumns == 3 -> listOf(-1L, 0L, 1L)
                numColumns == 2 -> listOf(0L, 1L)
                else -> listOf(0L)
            }

            dayOffsets.forEach { offset ->
                val date = today.plusDays(offset)
                val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val weather = weatherByDate[dateStr]

                val label = when (offset) {
                    -1L -> "Yest"
                    0L -> "Today"
                    1L -> "Tmrw"
                    else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }

                days.add(
                    TemperatureGraphRenderer.DayData(
                        label = label,
                        high = weather?.highTemp ?: 0,
                        low = weather?.lowTemp ?: 0,
                        isToday = offset == 0L
                    )
                )
            }

            return days
        }

        private fun updateTextMode(
            views: RemoteViews,
            today: LocalDate,
            weatherByDate: Map<String, WeatherEntity>,
            numColumns: Int
        ) {
            val yesterdayStr = today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val tomorrowStr = today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val day4Str = today.plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val day5Str = today.plusDays(3).format(DateTimeFormatter.ISO_LOCAL_DATE)

            // Set visibility based on columns
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

            // Populate days
            populateDay(views, R.id.day1_label, R.id.day1_high, R.id.day1_low, "Yest", weatherByDate[yesterdayStr])
            populateDay(views, R.id.day2_label, R.id.day2_high, R.id.day2_low, "Today", weatherByDate[todayStr])
            populateDay(views, R.id.day3_label, R.id.day3_high, R.id.day3_low, "Tmrw", weatherByDate[tomorrowStr])

            val day4Label = today.plusDays(2).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            populateDay(views, R.id.day4_label, R.id.day4_high, R.id.day4_low, day4Label, weatherByDate[day4Str])

            val day5Label = today.plusDays(3).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            populateDay(views, R.id.day5_label, R.id.day5_high, R.id.day5_low, day5Label, weatherByDate[day5Str])
        }

        private fun populateDay(
            views: RemoteViews,
            labelId: Int,
            highId: Int,
            lowId: Int,
            label: String,
            weather: WeatherEntity?
        ) {
            views.setTextViewText(labelId, label)
            views.setTextViewText(highId, weather?.let { "${it.highTemp}°" } ?: "--°")
            views.setTextViewText(lowId, weather?.let { "${it.lowTemp}°" } ?: "--°")
        }
    }
}
