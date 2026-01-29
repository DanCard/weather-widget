package com.weatherwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.work.*
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.WeatherEntity
import kotlinx.coroutines.launch
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
        Log.d(TAG, "onUpdate: Updating ${appWidgetIds.size} widgets")
        for (appWidgetId in appWidgetIds) {
            updateWidgetLoading(context, appWidgetManager, appWidgetId)
        }
        schedulePeriodicUpdate(context)
        // Trigger immediate update to fetch data
        triggerImmediateUpdate(context)
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

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val stateManager = WidgetStateManager(context)
        for (appWidgetId in appWidgetIds) {
            stateManager.clearWidgetState(appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d(TAG, "onReceive: action=${intent.action}")
        when (intent.action) {
            ACTION_REFRESH -> {
                Log.d(TAG, "onReceive: Triggering immediate update")
                triggerImmediateUpdate(context)
            }
            ACTION_NAV_LEFT, ACTION_NAV_RIGHT -> {
                val appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                Log.d(TAG, "onReceive: Navigation action for widget $appWidgetId")
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    handleNavigation(context, appWidgetId, intent.action == ACTION_NAV_LEFT)
                }
            }
            ACTION_TOGGLE_API -> {
                val appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                Log.d(TAG, "onReceive: Toggle API action for widget $appWidgetId")
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    // Use goAsync() and coroutine for immediate update without WorkManager delay
                    val pendingResult = goAsync()
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            handleToggleApiDirect(context, appWidgetId)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }

    private fun handleNavigation(context: Context, appWidgetId: Int, isLeft: Boolean) {
        val stateManager = WidgetStateManager(context)
        if (isLeft) {
            stateManager.navigateLeft(appWidgetId)
        } else {
            stateManager.navigateRight(appWidgetId)
        }
        // Trigger refresh to update widget with new offset
        triggerImmediateUpdate(context)
    }

    private suspend fun handleToggleApiDirect(context: Context, appWidgetId: Int) {
        val stateManager = WidgetStateManager(context)
        val newSource = stateManager.toggleDisplaySource(appWidgetId)
        Log.d(TAG, "handleToggleApiDirect: Toggled to $newSource for widget $appWidgetId")

        // Read weather data directly from database
        val database = WeatherDatabase.getDatabase(context)
        val weatherDao = database.weatherDao()
        val snapshotDao = database.forecastSnapshotDao()

        // Get location from latest weather data in database
        val latestWeather = weatherDao.getLatestWeather()
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        Log.d(TAG, "handleToggleApiDirect: Using location lat=$lat, lon=$lon")

        val yesterday = java.time.LocalDate.now().minusDays(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val twoWeeks = java.time.LocalDate.now().plusDays(14).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

        val weatherList = weatherDao.getWeatherRange(yesterday, twoWeeks, lat, lon)
        val forecastSnapshots = snapshotDao.getForecastsInRange(yesterday, twoWeeks, lat, lon)
            .groupBy { it.targetDate }

        Log.d(TAG, "handleToggleApiDirect: Got ${weatherList.size} weather entries")

        // Update widget directly
        val appWidgetManager = AppWidgetManager.getInstance(context)
        updateWidgetWithData(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots)
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
        Log.d(TAG, "triggerImmediateUpdate: Enqueueing worker")
        // No network constraint - worker will use cached data if network unavailable
        val workRequest = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
        Log.d(TAG, "triggerImmediateUpdate: Worker enqueued with id=${workRequest.id}")
    }

    private fun triggerUiOnlyUpdate(context: Context) {
        Log.d(TAG, "triggerUiOnlyUpdate: Enqueueing UI-only worker")
        val workRequest = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean(WeatherWidgetWorker.KEY_UI_ONLY_REFRESH, true)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
        Log.d(TAG, "triggerUiOnlyUpdate: Worker enqueued with id=${workRequest.id}")
    }

    companion object {
        const val WORK_NAME = "weather_widget_update"
        const val ACTION_REFRESH = "com.weatherwidget.ACTION_REFRESH"
        const val ACTION_NAV_LEFT = "com.weatherwidget.ACTION_NAV_LEFT"
        const val ACTION_NAV_RIGHT = "com.weatherwidget.ACTION_NAV_RIGHT"
        const val ACTION_TOGGLE_API = "com.weatherwidget.ACTION_TOGGLE_API"
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
            weatherList: List<WeatherEntity>,
            forecastSnapshots: Map<String, List<ForecastSnapshotEntity>> = emptyMap()
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            val (numColumns, numRows) = getWidgetSize(context, appWidgetManager, appWidgetId)
            val stateManager = WidgetStateManager(context)
            val dateOffset = stateManager.getDateOffset(appWidgetId)
            val accuracyMode = stateManager.getAccuracyDisplayMode()

            Log.d(TAG, "updateWidgetWithData: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, offset=$dateOffset, weatherCount=${weatherList.size}")

            // Set tap to open settings on content areas (not widget_root, to allow API toggle click)
            val settingsIntent = Intent(context, com.weatherwidget.ui.SettingsActivity::class.java)
            val settingsPendingIntent = PendingIntent.getActivity(
                context,
                0,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.text_container, settingsPendingIntent)
            views.setOnClickPendingIntent(R.id.graph_view, settingsPendingIntent)

            val today = LocalDate.now()
            val centerDate = today.plusDays(dateOffset.toLong())

            // Get the current display source for this widget
            val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)

            // Build weather map: prefer displaySource, but use any available data for dates without it
            val weatherByDate = mutableMapOf<String, WeatherEntity>()
            val sortedWeather = weatherList.sortedBy { it.date }

            // First pass: add all data from preferred source
            sortedWeather.filter { it.source == displaySource }.forEach { weather ->
                weatherByDate[weather.date] = weather
            }

            // Second pass: fill in gaps with data from other sources (for historical data)
            sortedWeather.filter { it.source != displaySource }.forEach { weather ->
                if (!weatherByDate.containsKey(weather.date)) {
                    weatherByDate[weather.date] = weather
                }
            }

            // Set API source indicator (shows current display source)
            val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val todayWeather = weatherByDate[todayStr]
            val apiSource = todayWeather?.source ?: displaySource
            views.setTextViewText(R.id.api_source, apiSource)

            // Set up API source toggle click handler
            setupApiToggle(context, views, appWidgetId, numRows)

            // Set up navigation click handlers
            setupNavigationButtons(context, views, appWidgetId, stateManager)

            // Use graph mode for 2+ rows
            val useGraph = numRows >= 2

            if (useGraph) {
                views.setViewVisibility(R.id.text_container, View.GONE)
                views.setViewVisibility(R.id.graph_view, View.VISIBLE)

                // Build day data for graph with offset
                val days = buildDayDataList(centerDate, today, weatherByDate, forecastSnapshots, numColumns, accuracyMode)

                // Calculate widget size in pixels (accounting for nav arrows)
                val widthPx = dpToPx(context, numColumns * CELL_WIDTH_DP) - dpToPx(context, 32)  // 16dp margin on each side
                val heightPx = dpToPx(context, numRows * CELL_HEIGHT_DP)

                // Render graph
                val bitmap = TemperatureGraphRenderer.renderGraph(context, days, widthPx, heightPx)
                views.setImageViewBitmap(R.id.graph_view, bitmap)
            } else {
                views.setViewVisibility(R.id.text_container, View.VISIBLE)
                views.setViewVisibility(R.id.graph_view, View.GONE)

                // Text mode - set visibility and populate
                updateTextMode(views, centerDate, today, weatherByDate, numColumns)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun setupNavigationButtons(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            stateManager: WidgetStateManager
        ) {
            // Left arrow
            val leftIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_NAV_LEFT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val leftPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId * 2, // Unique request code
                leftIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.nav_left, leftPendingIntent)

            // Right arrow
            val rightIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_NAV_RIGHT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val rightPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId * 2 + 1, // Unique request code
                rightIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.nav_right, rightPendingIntent)

            // Show/hide arrows based on navigation bounds
            val canLeft = stateManager.canNavigateLeft(appWidgetId)
            val canRight = stateManager.canNavigateRight(appWidgetId)
            views.setViewVisibility(R.id.nav_left, if (canLeft) View.VISIBLE else View.INVISIBLE)
            views.setViewVisibility(R.id.nav_right, if (canRight) View.VISIBLE else View.INVISIBLE)
        }

        private fun setupApiToggle(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            numRows: Int
        ) {
            val toggleIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_API
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId * 2 + 100, // Unique request code (offset from nav buttons)
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Set click on the container for larger touch target
            views.setOnClickPendingIntent(R.id.api_source_container, togglePendingIntent)

            // Scale text size based on widget rows
            val textSizeSp = when {
                numRows >= 3 -> 14f
                numRows >= 2 -> 12f
                else -> 10f
            }
            views.setTextViewTextSize(R.id.api_source, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        }

        private fun buildDayDataList(
            centerDate: LocalDate,
            today: LocalDate,
            weatherByDate: Map<String, WeatherEntity>,
            forecastSnapshots: Map<String, List<ForecastSnapshotEntity>>,
            numColumns: Int,
            accuracyMode: AccuracyDisplayMode
        ): List<TemperatureGraphRenderer.DayData> {
            val days = mutableListOf<TemperatureGraphRenderer.DayData>()
            Log.d(TAG, "buildDayDataList: numColumns=$numColumns, weatherByDate keys=${weatherByDate.keys}, centerDate=$centerDate, today=$today")

            // Determine which days to show based on columns (relative to center)
            // Note: We only have yesterday's data, so don't go back more than -1
            val dayOffsets = when {
                numColumns >= 8 -> listOf(-1L, 0L, 1L, 2L, 3L, 4L, 5L)  // 7 days (yesterday through +5)
                numColumns == 7 -> listOf(-1L, 0L, 1L, 2L, 3L, 4L)       // 6 days (yesterday through +4)
                numColumns == 6 -> listOf(-1L, 0L, 1L, 2L, 3L, 4L)        // 6 days
                numColumns == 5 -> listOf(-1L, 0L, 1L, 2L, 3L)             // 5 days
                numColumns == 4 -> listOf(-1L, 0L, 1L, 2L)                 // 4 days
                numColumns == 3 -> listOf(-1L, 0L, 1L)                     // 3 days
                numColumns == 2 -> listOf(0L, 1L)                          // 2 days
                else -> listOf(0L)                                          // 1 day
            }

            Log.d(TAG, "buildDayDataList: For $numColumns columns, showing ${dayOffsets.size} days with offsets: $dayOffsets")

            dayOffsets.forEach { offset ->
                val date = centerDate.plusDays(offset)
                val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val weather = weatherByDate[dateStr]
                val forecasts = forecastSnapshots[dateStr] ?: emptyList()
                Log.d(TAG, "buildDayDataList: Looking for $dateStr, found=${weather != null}, high=${weather?.highTemp}, forecasts=${forecasts.size}")

                // Extract forecasts by source
                val nwsForecast = forecasts.find { it.source == "NWS" }
                val meteoForecast = forecasts.find { it.source == "OPEN_METEO" }

                val label = when {
                    date == today -> "Today"
                    else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }

                // Determine if we should show forecast comparison (only for past dates with forecast data)
                val isPastDate = date.isBefore(today)
                val showComparison = isPastDate && forecasts.isNotEmpty() && accuracyMode != AccuracyDisplayMode.NONE

                days.add(
                    TemperatureGraphRenderer.DayData(
                        label = label,
                        high = weather?.highTemp ?: 0,
                        low = weather?.lowTemp ?: 0,
                        isToday = date == today,
                        forecastHighNWS = if (showComparison) nwsForecast?.highTemp else null,
                        forecastLowNWS = if (showComparison) nwsForecast?.lowTemp else null,
                        forecastHighOpenMeteo = if (showComparison) meteoForecast?.highTemp else null,
                        forecastLowOpenMeteo = if (showComparison) meteoForecast?.lowTemp else null,
                        accuracyMode = if (showComparison) accuracyMode else AccuracyDisplayMode.NONE
                    )
                )
            }

            return days
        }

        private fun updateTextMode(
            views: RemoteViews,
            centerDate: LocalDate,
            today: LocalDate,
            weatherByDate: Map<String, WeatherEntity>,
            numColumns: Int
        ) {
            // Calculate dates relative to center
            val day1Date = centerDate.minusDays(1)
            val day2Date = centerDate
            val day3Date = centerDate.plusDays(1)
            val day4Date = centerDate.plusDays(2)
            val day5Date = centerDate.plusDays(3)

            val day1Str = day1Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val day2Str = day2Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val day3Str = day3Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val day4Str = day4Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val day5Str = day5Date.format(DateTimeFormatter.ISO_LOCAL_DATE)

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

            // Helper to get label for a date
            fun getLabelForDate(date: LocalDate): String {
                return if (date == today) "Today"
                else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            }

            // Populate days
            populateDay(views, R.id.day1_label, R.id.day1_high, R.id.day1_low, getLabelForDate(day1Date), weatherByDate[day1Str])
            populateDay(views, R.id.day2_label, R.id.day2_high, R.id.day2_low, getLabelForDate(day2Date), weatherByDate[day2Str])
            populateDay(views, R.id.day3_label, R.id.day3_high, R.id.day3_low, getLabelForDate(day3Date), weatherByDate[day3Str])
            populateDay(views, R.id.day4_label, R.id.day4_high, R.id.day4_low, getLabelForDate(day4Date), weatherByDate[day4Str])
            populateDay(views, R.id.day5_label, R.id.day5_high, R.id.day5_low, getLabelForDate(day5Date), weatherByDate[day5Str])
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
