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
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.util.WeatherIconMapper
import java.time.LocalDateTime
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
        Log.d(TAG, "onAppWidgetOptionsChanged: widgetId=$appWidgetId")
        // Handle resize immediately using goAsync() to avoid blocking
        val pendingResult = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                handleResizeDirect(context, appWidgetManager, appWidgetId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        schedulePeriodicUpdate(context)

        // Schedule UI-only updates using AlarmManager
        val pendingResult = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val uiScheduler = UIUpdateScheduler(context)
                uiScheduler.scheduleNextUpdate()
            } finally {
                pendingResult.finish()
            }
        }

        // Schedule opportunistic updates using JobScheduler (Android 8+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            OpportunisticUpdateJobService.scheduleOpportunisticUpdate(context)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

        // Cancel scheduled UI updates
        val uiScheduler = UIUpdateScheduler(context)
        uiScheduler.cancelScheduledUpdates()

        // Cancel opportunistic updates
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            OpportunisticUpdateJobService.cancelOpportunisticUpdate(context)
        }
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
                Log.d(TAG, "onReceive: User triggered refresh")
                // Always update UI immediately for instant feedback
                triggerUiOnlyUpdate(context)

                // Check data staleness and fetch in background if needed
                val pendingResult = goAsync()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        if (DataFreshness.isDataStale(context)) {
                            Log.d(TAG, "onReceive: Data is stale, triggering background fetch")
                            triggerImmediateUpdate(context)
                        } else {
                            Log.d(TAG, "onReceive: Data is fresh, UI update only")
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            ACTION_NAV_LEFT, ACTION_NAV_RIGHT -> {
                val appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                Log.d(TAG, "onReceive: Navigation action for widget $appWidgetId")
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    // Use goAsync() and coroutine for immediate update without WorkManager delay
                    val pendingResult = goAsync()
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            handleNavigationDirect(context, appWidgetId, intent.action == ACTION_NAV_LEFT)
                        } finally {
                            pendingResult.finish()
                        }
                    }
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
            ACTION_TOGGLE_VIEW -> {
                val appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                Log.d(TAG, "onReceive: Toggle View action for widget $appWidgetId")
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val pendingResult = goAsync()
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            handleToggleViewDirect(context, appWidgetId)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleNavigationDirect(context: Context, appWidgetId: Int, isLeft: Boolean) {
        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)

        if (viewMode == ViewMode.HOURLY) {
            handleHourlyNavigationDirect(context, appWidgetId, isLeft)
        } else {
            handleDailyNavigationDirect(context, appWidgetId, isLeft)
        }
    }

    private suspend fun handleDailyNavigationDirect(context: Context, appWidgetId: Int, isLeft: Boolean) {
        val stateManager = WidgetStateManager(context)
        val currentOffset = stateManager.getDateOffset(appWidgetId)
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)

        // Read weather data directly from database
        val database = WeatherDatabase.getDatabase(context)
        val weatherDao = database.weatherDao()
        val snapshotDao = database.forecastSnapshotDao()
        val hourlyDao = database.hourlyForecastDao()

        // Get location from latest weather data
        val latestWeather = weatherDao.getLatestWeather()
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val historyStart = java.time.LocalDate.now().minusDays(30).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val twoWeeks = java.time.LocalDate.now().plusDays(14).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

        val weatherList = weatherDao.getWeatherRange(historyStart, twoWeeks, lat, lon)

        // Filter by current display source
        val filteredWeatherList = weatherList.filter { it.source == displaySource }
        val weatherByDate = filteredWeatherList.associateBy { it.date }

        val today = java.time.LocalDate.now()
        val currentCenterDate = today.plusDays(currentOffset.toLong())

        // Filter out incomplete future dates (lowTemp=0 means no night forecast yet)
        val availableDates = weatherByDate.filter { (dateStr, weather) ->
            val date = java.time.LocalDate.parse(dateStr)
            val isFutureDate = !date.isBefore(today)
            !(isFutureDate && weather.lowTemp == 0)
        }.keys.map { java.time.LocalDate.parse(it) }.sorted()

        val minDate = availableDates.firstOrNull()
        val maxDate = availableDates.lastOrNull()

        // Check if navigation would reveal new data
        val canNavigate = if (isLeft) {
            // Can go left only if there's data BEFORE the current center
            minDate != null && minDate < currentCenterDate
        } else {
            // Can go right only if there's data AFTER the current center
            maxDate != null && maxDate > currentCenterDate
        }

        if (!canNavigate) {
            Log.d(TAG, "handleDailyNavigationDirect: No more $displaySource data to ${if (isLeft) "left" else "right"}")
            // Do nothing - no toast, just silently ignore
            return
        }

        // Data exists, proceed with navigation
        val newOffset = if (isLeft) {
            stateManager.navigateLeft(appWidgetId)
        } else {
            stateManager.navigateRight(appWidgetId)
        }
        Log.d(TAG, "handleDailyNavigationDirect: Navigated to offset $newOffset for widget $appWidgetId")

        val forecastSnapshots = snapshotDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
            .groupBy { it.targetDate }

        // Get hourly forecasts for interpolation
        val now = java.time.LocalDateTime.now()
        val hourStart = now.minusHours(2).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val hourEnd = now.plusHours(2).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val hourlyForecasts = hourlyDao.getHourlyForecasts(hourStart, hourEnd, lat, lon)

        // Find today's condition from the current source
        val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val todayCondition = filteredWeatherList.find { it.date == todayStr }?.condition

        // Update widget directly
        val appWidgetManager = AppWidgetManager.getInstance(context)
        updateWidgetWithData(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts, todayCondition)
    }

    private suspend fun handleHourlyNavigationDirect(context: Context, appWidgetId: Int, isLeft: Boolean) {
        val stateManager = WidgetStateManager(context)

        // Update hourly offset
        val newOffset = if (isLeft) {
            stateManager.navigateHourlyLeft(appWidgetId)
        } else {
            stateManager.navigateHourlyRight(appWidgetId)
        }
        Log.d(TAG, "handleHourlyNavigationDirect: Navigated to offset $newOffset for widget $appWidgetId")

        // Read hourly data from database
        val database = WeatherDatabase.getDatabase(context)
        val hourlyDao = database.hourlyForecastDao()
        val weatherDao = database.weatherDao()

        // Get location
        val latestWeather = weatherDao.getLatestWeather()
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        // Calculate time window based on offset, rounded to nearest hour
        val now = java.time.LocalDateTime.now()
        val centerTime = now.plusHours(newOffset.toLong())
        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val roundedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        val startTime = roundedCenter.minusHours(8).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val endTime = roundedCenter.plusHours(16).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))

        val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

        // Find today's condition
        val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val todayCondition = weatherDao.getWeatherForDate(todayStr, lat, lon)?.condition

        // Update widget with hourly view
        val appWidgetManager = AppWidgetManager.getInstance(context)
        updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime, todayCondition)
    }

    private suspend fun handleToggleApiDirect(context: Context, appWidgetId: Int) {
        val stateManager = WidgetStateManager(context)
        val newSource = stateManager.toggleDisplaySource(appWidgetId)
        val viewMode = stateManager.getViewMode(appWidgetId)
        Log.d(TAG, "handleToggleApiDirect: Toggled to $newSource for widget $appWidgetId, viewMode=$viewMode")

        // Read weather data directly from database
        val database = WeatherDatabase.getDatabase(context)
        val weatherDao = database.weatherDao()
        val snapshotDao = database.forecastSnapshotDao()
        val hourlyDao = database.hourlyForecastDao()

        // Get location from latest weather data in database
        val latestWeather = weatherDao.getLatestWeather()
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        Log.d(TAG, "handleToggleApiDirect: Using location lat=$lat, lon=$lon")

        val appWidgetManager = AppWidgetManager.getInstance(context)

        // Find today's condition
        val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val todayCondition = weatherDao.getWeatherForDate(todayStr, lat, lon)?.condition

        if (viewMode == ViewMode.HOURLY) {
            // Hourly mode: get extended hourly data (24 hours centered on current offset)
            val now = java.time.LocalDateTime.now()
            val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
            val centerTime = now.plusHours(hourlyOffset.toLong())
            // Round to nearest hour (same as display logic)
            val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            val roundedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
            // Query 8 hours back + 16 hours forward to match display range
            val startTime = roundedCenter.minusHours(8).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = roundedCenter.plusHours(16).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

            Log.d(TAG, "handleToggleApiDirect: Hourly mode - Got ${hourlyForecasts.size} hourly forecasts from $startTime to $endTime")
            updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime, todayCondition)
        } else {
            // Daily mode: get daily data
            val historyStart = java.time.LocalDate.now().minusDays(30).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val twoWeeks = java.time.LocalDate.now().plusDays(14).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

            val weatherList = weatherDao.getWeatherRange(historyStart, twoWeeks, lat, lon)
            val forecastSnapshots = snapshotDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                .groupBy { it.targetDate }

            // Get hourly forecasts for current temp interpolation
            val now = java.time.LocalDateTime.now()
            val hourlyStart = now.minusHours(3).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyEnd = now.plusHours(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

            Log.d(TAG, "handleToggleApiDirect: Daily mode - Got ${weatherList.size} weather entries, ${forecastSnapshots.size} forecast dates, ${hourlyForecasts.size} hourly")
            updateWidgetWithData(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts, todayCondition)
        }
    }

    private suspend fun handleResizeDirect(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        Log.d(TAG, "handleResizeDirect: Updating widget $appWidgetId after resize")

        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)

        // Read weather data directly from database
        val database = WeatherDatabase.getDatabase(context)
        val weatherDao = database.weatherDao()
        val snapshotDao = database.forecastSnapshotDao()
        val hourlyDao = database.hourlyForecastDao()

        // Get location from latest weather data in database
        val latestWeather = weatherDao.getLatestWeather()
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        // Find today's condition
        val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val todayCondition = weatherDao.getWeatherForDate(todayStr, lat, lon)?.condition

        if (viewMode == ViewMode.HOURLY) {
            // Hourly mode: get extended hourly data
            val now = java.time.LocalDateTime.now()
            val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
            val centerTime = now.plusHours(hourlyOffset.toLong())
            // Round to nearest hour (same as display logic)
            val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            val roundedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
            val startTime = roundedCenter.minusHours(8).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = roundedCenter.plusHours(16).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

            updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime, todayCondition)
        } else {
            // Daily mode: get daily data
            val historyStart = java.time.LocalDate.now().minusDays(30).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val twoWeeks = java.time.LocalDate.now().plusDays(14).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

            val weatherList = weatherDao.getWeatherRange(historyStart, twoWeeks, lat, lon)
            val forecastSnapshots = snapshotDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                .groupBy { it.targetDate }

            // Get hourly forecasts for interpolation
            val now = java.time.LocalDateTime.now()
            val hourlyStart = now.minusHours(3).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyEnd = now.plusHours(3).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

            Log.d(TAG, "handleResizeDirect: Got ${weatherList.size} weather entries, ${forecastSnapshots.size} forecast dates, ${hourlyForecasts.size} hourly")

            updateWidgetWithData(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts, todayCondition)
        }
    }

    private suspend fun handleToggleViewDirect(context: Context, appWidgetId: Int) {
        val stateManager = WidgetStateManager(context)
        val newMode = stateManager.toggleViewMode(appWidgetId)
        Log.d(TAG, "handleToggleViewDirect: Toggled to $newMode for widget $appWidgetId")

        // Read data from database
        val database = WeatherDatabase.getDatabase(context)
        val weatherDao = database.weatherDao()
        val hourlyDao = database.hourlyForecastDao()
        val snapshotDao = database.forecastSnapshotDao()

        // Get location
        val latestWeather = weatherDao.getLatestWeather()
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        // Find today's condition
        val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val todayCondition = weatherDao.getWeatherForDate(todayStr, lat, lon)?.condition

        val appWidgetManager = AppWidgetManager.getInstance(context)

        if (newMode == ViewMode.HOURLY) {
            // Switched to hourly mode: fetch 24-hour window (8h history + 16h forecast)
            val now = java.time.LocalDateTime.now()
            val startTime = now.minusHours(8).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = now.plusHours(16).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

            updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, hourlyForecasts, now, todayCondition)
        } else {
            // Switched to daily mode: fetch daily data
            val historyStart = java.time.LocalDate.now().minusDays(30).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val twoWeeks = java.time.LocalDate.now().plusDays(14).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

            val weatherList = weatherDao.getWeatherRange(historyStart, twoWeeks, lat, lon)
            val forecastSnapshots = snapshotDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                .groupBy { it.targetDate }

            // Get hourly forecasts for current temp interpolation
            val now = java.time.LocalDateTime.now()
            val hourlyStart = now.minusHours(3).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyEnd = now.plusHours(3).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

            updateWidgetWithData(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts, todayCondition)
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
        const val ACTION_TOGGLE_VIEW = "com.weatherwidget.ACTION_TOGGLE_VIEW"
        private const val TAG = "WeatherWidgetProvider"

        private const val CELL_WIDTH_DP = 70
        private const val CELL_HEIGHT_DP = 90

        private fun getWidgetSize(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int): Pair<Int, Int> {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 40)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
            
            // Standard Android widget size formula: (size + 30) / cell_size
            // This is more robust against device-specific padding and aligns better with launcher grids.
            val cols = ((minWidth + 30) / CELL_WIDTH_DP).coerceAtLeast(1)
            val rows = ((minHeight + 30) / CELL_HEIGHT_DP).coerceAtLeast(1)
            
            Log.d(TAG, "getWidgetSize: widgetId=$appWidgetId, minWidth=$minWidth, minHeight=$minHeight -> cols=$cols, rows=$rows")
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
            forecastSnapshots: Map<String, List<ForecastSnapshotEntity>> = emptyMap(),
            hourlyForecasts: List<HourlyForecastEntity> = emptyList(),
            currentCondition: String? = null
        ) {
            val stateManager = WidgetStateManager(context)
            val viewMode = stateManager.getViewMode(appWidgetId)

            // Route to appropriate renderer based on view mode
            if (viewMode == ViewMode.HOURLY) {
                // Fetch extended hourly data if not already provided
                if (hourlyForecasts.size < 20) {  // Need more than just ±3 hours
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val database = WeatherDatabase.getDatabase(context)
                        val hourlyDao = database.hourlyForecastDao()
                        val weatherDao = database.weatherDao()

                        val latestWeather = weatherDao.getLatestWeather()
                        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
                        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

                        val now = LocalDateTime.now()
                        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
                        val centerTime = now.plusHours(hourlyOffset.toLong())
                        // Query 8 hours back + 16 hours forward to match display range
                        val startTime = centerTime.minusHours(8).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                        val endTime = centerTime.plusHours(16).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                        Log.d(TAG, "updateWidgetWithData: Fetching hourly from $startTime to $endTime")
                        val extendedHourly = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

                        updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, extendedHourly, centerTime, currentCondition)
                    }
                } else {
                    val now = LocalDateTime.now()
                    val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
                    val centerTime = now.plusHours(hourlyOffset.toLong())
                    updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime, currentCondition)
                }
                return
            }

            // Daily mode
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            val (numColumns, numRows) = getWidgetSize(context, appWidgetManager, appWidgetId)
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

            // Setup current temp click to toggle view mode
            setupCurrentTempToggle(context, views, appWidgetId)

            val today = LocalDate.now()
            val centerDate = today.plusDays(dateOffset.toLong())

            // Get the current display source for this widget
            val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)

            // Build weather map: only use data from the selected display source
            val weatherByDate = weatherList
                .filter { it.source == displaySource }
                .associateBy { it.date }

            // Set API source indicator (shows current display source with accuracy score)
            val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val todayWeather = weatherByDate[todayStr]

            // Format display name - always show the user's selected displaySource, not the data's source
            val displayName = if (displaySource == "Open-Meteo") "Meteo" else displaySource

            Log.d(TAG, "updateWidgetWithData: displaySource='$displaySource', displayName='$displayName'")
            views.setTextViewText(R.id.api_source, displayName)

            // Set weather icon
            val iconRes = WeatherIconMapper.getIconResource(currentCondition ?: todayWeather?.condition)
            views.setImageViewResource(R.id.weather_icon, iconRes)
            views.setViewVisibility(R.id.weather_icon, View.VISIBLE)

            // Set current temperature - always use interpolation from hourly forecasts for accuracy
            var currentTemp: Float? = null
            if (hourlyForecasts.isNotEmpty()) {
                val interpolator = TemperatureInterpolator()
                currentTemp = interpolator.getInterpolatedTemperature(hourlyForecasts, LocalDateTime.now(), displaySource)
                if (currentTemp != null) {
                    Log.d(TAG, "updateWidgetWithData: Using interpolated temp: $currentTemp from source $displaySource")
                }
            }

            // Fallback to API currentTemp if interpolation unavailable
            if (currentTemp == null) {
                currentTemp = weatherList
                    .filter { it.date == todayStr && it.currentTemp != null && it.currentTemp != 0 }
                    .maxByOrNull { it.fetchedAt }
                    ?.currentTemp?.toFloat()
                if (currentTemp != null) {
                    Log.d(TAG, "updateWidgetWithData: Using API currentTemp fallback: $currentTemp")
                }
            }

            if (currentTemp != null) {
                // Format with decimal on 2+ columns, without decimal on 1 column
                val formattedTemp = when {
                    numColumns >= 2 -> String.format("%.1f°", currentTemp)  // "72.5°"
                    else -> String.format("%.0f°", currentTemp)              // "73°"
                }
                views.setTextViewText(R.id.current_temp, formattedTemp)
                views.setViewVisibility(R.id.current_temp, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.current_temp, View.GONE)
            }

            // Set up API source toggle click handler
            setupApiToggle(context, views, appWidgetId, numRows)

            // Filter out incomplete future dates (lowTemp=0 means no night forecast yet)
            val availableDates = weatherByDate.filter { (dateStr, weather) ->
                val date = LocalDate.parse(dateStr)
                val isFutureDate = !date.isBefore(today)
                !(isFutureDate && weather.lowTemp == 0)
            }.keys

            // Set up navigation click handlers with available dates
            setupNavigationButtons(context, views, appWidgetId, stateManager, availableDates)

            // Use graph mode for 2+ rows
            val useGraph = numRows >= 2

            if (useGraph) {
                views.setViewVisibility(R.id.text_container, View.GONE)
                views.setViewVisibility(R.id.graph_view, View.VISIBLE)

                // Build day data for graph with offset
                val days = buildDayDataList(centerDate, today, weatherByDate, forecastSnapshots, numColumns, accuracyMode, displaySource)

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
            stateManager: WidgetStateManager,
            availableDates: Set<String> = emptySet()
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
            views.setOnClickPendingIntent(R.id.nav_left_zone, leftPendingIntent)

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
            views.setOnClickPendingIntent(R.id.nav_right_zone, rightPendingIntent)

            // Show/hide arrows and touch zones based on navigation bounds and view mode
            val viewMode = stateManager.getViewMode(appWidgetId)
            val canLeft: Boolean
            val canRight: Boolean

            if (viewMode == ViewMode.HOURLY) {
                canLeft = stateManager.canNavigateHourlyLeft(appWidgetId)
                canRight = stateManager.canNavigateHourlyRight(appWidgetId)
            } else {
                // Check if navigating would reveal new data
                val today = LocalDate.now()
                val currentOffset = stateManager.getDateOffset(appWidgetId)
                val currentCenterDate = today.plusDays(currentOffset.toLong())

                // Find min/max available dates
                val sortedDates = availableDates.map { LocalDate.parse(it) }.sorted()
                val minDate = sortedDates.firstOrNull()
                val maxDate = sortedDates.lastOrNull()

                // Can go left only if there's data BEFORE the current center
                canLeft = minDate != null && minDate < currentCenterDate

                // Can go right only if there's data AFTER the current center
                canRight = maxDate != null && maxDate > currentCenterDate
            }

            views.setViewVisibility(R.id.nav_left, if (canLeft) View.VISIBLE else View.INVISIBLE)
            views.setViewVisibility(R.id.nav_left_zone, if (canLeft) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.nav_right, if (canRight) View.VISIBLE else View.INVISIBLE)
            views.setViewVisibility(R.id.nav_right_zone, if (canRight) View.VISIBLE else View.GONE)
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
                numRows >= 3 -> 18f
                numRows >= 2 -> 16f
                else -> 14f
            }
            views.setTextViewTextSize(R.id.api_source, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        }

        private fun buildDayDataList(
            centerDate: LocalDate,
            today: LocalDate,
            weatherByDate: Map<String, WeatherEntity>,
            forecastSnapshots: Map<String, List<ForecastSnapshotEntity>>,
            numColumns: Int,
            accuracyMode: AccuracyDisplayMode,
            displaySource: String  // "NWS" or "Open-Meteo"
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

                // Skip days without data
                if (weather == null) {
                    Log.d(TAG, "buildDayDataList: Skipping $dateStr - no data available")
                    return@forEach
                }

                // Skip future days with incomplete data (lowTemp=0 means NWS hasn't published night forecast yet)
                val isFutureDate = !date.isBefore(today)
                if (isFutureDate && weather.lowTemp == 0) {
                    Log.d(TAG, "buildDayDataList: Skipping $dateStr - incomplete forecast (low=0)")
                    return@forEach
                }

                val forecasts = forecastSnapshots[dateStr] ?: emptyList()
                Log.d(TAG, "buildDayDataList: Including $dateStr, high=${weather.highTemp}, low=${weather.lowTemp}, forecasts=${forecasts.size}")

                // Get forecast for the display source only
                val sourceName = if (displaySource == "NWS") "NWS" else "OPEN_METEO"
                val forecast = forecasts.find { it.source == sourceName }

                val label = when {
                    date == today -> "Today"
                    else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }

                // Determine if we should show forecast comparison (only for past dates with forecast data)
                val isPastDate = date.isBefore(today)
                val showComparison = isPastDate && forecast != null && accuracyMode != AccuracyDisplayMode.NONE

                days.add(
                    TemperatureGraphRenderer.DayData(
                        label = label,
                        high = weather.highTemp,
                        low = weather.lowTemp,
                        isToday = date == today,
                        isPast = isPastDate,
                        forecastHigh = if (showComparison) forecast?.highTemp else null,
                        forecastLow = if (showComparison) forecast?.lowTemp else null,
                        forecastSource = if (showComparison) displaySource else null,
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
            val day6Date = centerDate.plusDays(4)

            val day1Str = day1Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val day2Str = day2Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val day3Str = day3Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val day4Str = day4Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val day5Str = day5Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val day6Str = day6Date.format(DateTimeFormatter.ISO_LOCAL_DATE)

            // Check data availability for each day (exclude incomplete future dates with lowTemp=0)
            fun hasCompleteData(date: LocalDate, dateStr: String): Boolean {
                val weather = weatherByDate[dateStr] ?: return false
                val isFutureDate = !date.isBefore(today)
                return !(isFutureDate && weather.lowTemp == 0)
            }

            val hasDay1 = hasCompleteData(day1Date, day1Str)
            val hasDay2 = hasCompleteData(day2Date, day2Str)
            val hasDay3 = hasCompleteData(day3Date, day3Str)
            val hasDay4 = hasCompleteData(day4Date, day4Str)
            val hasDay5 = hasCompleteData(day5Date, day5Str)
            val hasDay6 = hasCompleteData(day6Date, day6Str)

            // Set visibility based on columns AND data availability
            when {
                numColumns >= 6 -> {
                    views.setViewVisibility(R.id.day1_container, if (hasDay1) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day4_container, if (hasDay4) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day5_container, if (hasDay5) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day6_container, if (hasDay6) View.VISIBLE else View.GONE)
                }
                numColumns == 5 -> {
                    views.setViewVisibility(R.id.day1_container, if (hasDay1) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day4_container, if (hasDay4) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day5_container, if (hasDay5) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day6_container, View.GONE)
                }
                numColumns == 4 -> {
                    views.setViewVisibility(R.id.day1_container, if (hasDay1) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day4_container, if (hasDay4) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day5_container, View.GONE)
                    views.setViewVisibility(R.id.day6_container, View.GONE)
                }
                numColumns == 3 -> {
                    views.setViewVisibility(R.id.day1_container, if (hasDay1) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day4_container, View.GONE)
                    views.setViewVisibility(R.id.day5_container, View.GONE)
                    views.setViewVisibility(R.id.day6_container, View.GONE)
                }
                numColumns == 2 -> {
                    views.setViewVisibility(R.id.day1_container, View.GONE)
                    views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day4_container, View.GONE)
                    views.setViewVisibility(R.id.day5_container, View.GONE)
                    views.setViewVisibility(R.id.day6_container, View.GONE)
                }
                else -> {
                    views.setViewVisibility(R.id.day1_container, View.GONE)
                    views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                    views.setViewVisibility(R.id.day3_container, View.GONE)
                    views.setViewVisibility(R.id.day4_container, View.GONE)
                    views.setViewVisibility(R.id.day5_container, View.GONE)
                    views.setViewVisibility(R.id.day6_container, View.GONE)
                }
            }

            // Helper to get label for a date
            fun getLabelForDate(date: LocalDate): String {
                return if (date == today) "Today"
                else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            }

            // Populate days (only if data exists)
            if (hasDay1) populateDay(views, R.id.day1_label, R.id.day1_icon, R.id.day1_high, R.id.day1_low, getLabelForDate(day1Date), weatherByDate[day1Str])
            if (hasDay2) populateDay(views, R.id.day2_label, R.id.day2_icon, R.id.day2_high, R.id.day2_low, getLabelForDate(day2Date), weatherByDate[day2Str])
            if (hasDay3) populateDay(views, R.id.day3_label, R.id.day3_icon, R.id.day3_high, R.id.day3_low, getLabelForDate(day3Date), weatherByDate[day3Str])
            if (hasDay4) populateDay(views, R.id.day4_label, R.id.day4_icon, R.id.day4_high, R.id.day4_low, getLabelForDate(day4Date), weatherByDate[day4Str])
            if (hasDay5) populateDay(views, R.id.day5_label, R.id.day5_icon, R.id.day5_high, R.id.day5_low, getLabelForDate(day5Date), weatherByDate[day5Str])
            if (hasDay6) populateDay(views, R.id.day6_label, R.id.day6_icon, R.id.day6_high, R.id.day6_low, getLabelForDate(day6Date), weatherByDate[day6Str])
        }

        private fun populateDay(
            views: RemoteViews,
            labelId: Int,
            iconId: Int,
            highId: Int,
            lowId: Int,
            label: String,
            weather: WeatherEntity?
        ) {
            views.setTextViewText(labelId, label)
            
            // Set weather icon
            val iconRes = WeatherIconMapper.getIconResource(weather?.condition)
            views.setImageViewResource(iconId, iconRes)
            views.setViewVisibility(iconId, View.VISIBLE)
            
            views.setTextViewText(highId, weather?.let { "${it.highTemp}°" } ?: "--°")
            views.setTextViewText(lowId, weather?.let { "${it.lowTemp}°" } ?: "--°")
        }

        private fun updateWidgetWithHourlyData(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            hourlyForecasts: List<HourlyForecastEntity>,
            centerTime: LocalDateTime,
            currentCondition: String? = null
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            val (numColumns, numRows) = getWidgetSize(context, appWidgetManager, appWidgetId)
            val stateManager = WidgetStateManager(context)

            Log.d(TAG, "updateWidgetWithHourlyData: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, hourlyCount=${hourlyForecasts.size}")

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

            // Setup navigation buttons
            setupNavigationButtons(context, views, appWidgetId, stateManager)

            // Setup current temp click to toggle view
            setupCurrentTempToggle(context, views, appWidgetId)

            // Get current display source
            val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
            val displayName = if (displaySource == "Open-Meteo") "Meteo" else displaySource
            views.setTextViewText(R.id.api_source, displayName)

            // Set weather icon
            val iconRes = WeatherIconMapper.getIconResource(currentCondition)
            views.setImageViewResource(R.id.weather_icon, iconRes)
            views.setViewVisibility(R.id.weather_icon, View.VISIBLE)

            // Setup API toggle
            setupApiToggle(context, views, appWidgetId, numRows)

            // Set current temperature (interpolated from hourly data)
            val now = LocalDateTime.now()
            if (hourlyForecasts.isNotEmpty()) {
                val interpolator = TemperatureInterpolator()
                val currentTemp = interpolator.getInterpolatedTemperature(hourlyForecasts, now, displaySource)
                if (currentTemp != null) {
                    val formattedTemp = when {
                        numColumns >= 2 -> String.format("%.1f°", currentTemp)
                        else -> String.format("%.0f°", currentTemp)
                    }
                    views.setTextViewText(R.id.current_temp, formattedTemp)
                    views.setViewVisibility(R.id.current_temp, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.current_temp, View.GONE)
                }
            } else {
                views.setViewVisibility(R.id.current_temp, View.GONE)
            }

            // Use graph mode for 2+ rows, text mode for 1 row
            val useGraph = numRows >= 2

            if (useGraph) {
                views.setViewVisibility(R.id.text_container, View.GONE)
                views.setViewVisibility(R.id.graph_view, View.VISIBLE)

                // Build hour data list for graph (24 hours visible)
                val hours = buildHourDataList(hourlyForecasts, centerTime, numColumns, displaySource)

                // Calculate widget size in pixels
                val widthPx = dpToPx(context, numColumns * CELL_WIDTH_DP) - dpToPx(context, 32)
                val heightPx = dpToPx(context, numRows * CELL_HEIGHT_DP)

                // Render hourly graph
                val bitmap = HourlyGraphRenderer.renderGraph(context, hours, widthPx, heightPx, now)
                views.setImageViewBitmap(R.id.graph_view, bitmap)
            } else {
                views.setViewVisibility(R.id.text_container, View.VISIBLE)
                views.setViewVisibility(R.id.graph_view, View.GONE)

                // Text mode: show hourly data as text
                updateHourlyTextMode(views, hourlyForecasts, centerTime, numColumns, displaySource)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun setupCurrentTempToggle(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int
        ) {
            val toggleIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_VIEW
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId * 2 + 200, // Unique request code
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.current_temp, togglePendingIntent)
            views.setOnClickPendingIntent(R.id.current_temp_zone, togglePendingIntent)
        }

        private fun buildHourDataList(
            hourlyForecasts: List<HourlyForecastEntity>,
            centerTime: LocalDateTime,
            numColumns: Int,
            displaySource: String
        ): List<HourlyGraphRenderer.HourData> {
            val hours = mutableListOf<HourlyGraphRenderer.HourData>()
            val now = LocalDateTime.now()

            // Group by dateTime and prefer the selected source
            val sourceName = if (displaySource == "NWS") "NWS" else "OPEN_METEO"
            val forecastsByTime = hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    entry.value.find { it.source == sourceName } ?: entry.value.firstOrNull()
                }

            // Determine how many hours to show (24 total, with "now" at 1/3 position)
            // 8 hours history + 16 hours forecast
            // Round to nearest hour (if >= 30 min, round up)
            val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
            val startHour = alignedCenter.minusHours(8)
            val endHour = alignedCenter.plusHours(16)

            // Determine label frequency based on widget size
            // For 24 hours displayed, aim for ~4-6 visible labels to avoid overlap
            val labelInterval = when {
                numColumns >= 7 -> 3  // Every 3 hours (8 labels)
                numColumns >= 5 -> 4  // Every 4 hours (6 labels)
                else -> 6              // Every 6 hours (4 labels)
            }

            var currentHour = startHour
            var hourIndex = 0
            
            // Debug logging for time range
            Log.d(TAG, "buildHourDataList: now=$now, centerTime=$centerTime, alignedCenter=$alignedCenter")
            Log.d(TAG, "buildHourDataList: startHour=$startHour, endHour=$endHour (${startHour.hour}:00 to ${endHour.hour}:00)")
            Log.d(TAG, "buildHourDataList: forecastsByTime has ${forecastsByTime.size} entries, labelInterval=$labelInterval")
            Log.d(TAG, "buildHourDataList: forecastsByTime keys=${forecastsByTime.keys.sorted()}")

            while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
                val hourKey = currentHour.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                val forecast = forecastsByTime[hourKey]

                if (forecast != null) {
                    val diffMinutes = java.time.Duration.between(currentHour, now).toMinutes()
                    val absDiff = kotlin.math.abs(diffMinutes)
                    val isClosest = absDiff <= 30
                    val showLabel = isClosest || (hourIndex % labelInterval == 0)

                    if (isClosest || hourIndex == 0 || showLabel) {
                        Log.d(TAG, "buildHourDataList: hour[$hourIndex] $currentHour label=${formatHourLabel(currentHour)} showLabel=$showLabel isClosest=$isClosest")
                    }

                    hours.add(
                        HourlyGraphRenderer.HourData(
                            dateTime = currentHour,
                            temperature = forecast.temperature,
                            label = formatHourLabel(currentHour),
                            isCurrentHour = isClosest,
                            showLabel = showLabel
                        )
                    )
                    hourIndex++
                } else {
                    Log.d(TAG, "buildHourDataList: MISSING data for $currentHour (key=$hourKey)")
                }

                currentHour = currentHour.plusHours(1)
            }

            Log.d(TAG, "buildHourDataList: Built ${hours.size} hours, first=${hours.firstOrNull()?.dateTime}, last=${hours.lastOrNull()?.dateTime}")
            return hours
        }

        private fun formatHourLabel(time: LocalDateTime): String {
            val hour = time.hour
            return when {
                hour == 0 -> "12a"
                hour < 12 -> "${hour}a"
                hour == 12 -> "12p"
                else -> "${hour - 12}p"
            }
        }

        private fun updateHourlyTextMode(
            views: RemoteViews,
            hourlyForecasts: List<HourlyForecastEntity>,
            centerTime: LocalDateTime,
            numColumns: Int,
            displaySource: String
        ) {
            // Group by dateTime and prefer the selected source
            val sourceName = if (displaySource == "NWS") "NWS" else "OPEN_METEO"
            val forecastsByTime = hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    entry.value.find { it.source == sourceName } ?: entry.value.firstOrNull()
                }

            // Determine which time points to show based on columns
            // Show: Now, +3h, +6h, +9h, +12h, +15h
            val timeOffsets = when {
                numColumns >= 6 -> listOf(0, 3, 6, 9, 12, 15)
                numColumns == 5 -> listOf(0, 3, 6, 9, 12)
                numColumns == 4 -> listOf(0, 3, 6, 9)
                numColumns == 3 -> listOf(0, 3, 6)
                numColumns == 2 -> listOf(0, 6)
                else -> listOf(0)
            }

            // Map offsets to containers
            val containerIds = listOf(
                R.id.day1_container to Quad(R.id.day1_label, R.id.day1_icon, R.id.day1_high, R.id.day1_low),
                R.id.day2_container to Quad(R.id.day2_label, R.id.day2_icon, R.id.day2_high, R.id.day2_low),
                R.id.day3_container to Quad(R.id.day3_label, R.id.day3_icon, R.id.day3_high, R.id.day3_low),
                R.id.day4_container to Quad(R.id.day4_label, R.id.day4_icon, R.id.day4_high, R.id.day4_low),
                R.id.day5_container to Quad(R.id.day5_label, R.id.day5_icon, R.id.day5_high, R.id.day5_low),
                R.id.day6_container to Quad(R.id.day6_label, R.id.day6_icon, R.id.day6_high, R.id.day6_low)
            )

            containerIds.forEachIndexed { index, (containerId, ids) ->
                if (index < timeOffsets.size) {
                    val offset = timeOffsets[index]
                    val targetTime = centerTime.plusHours(offset.toLong())
                    val hourKey = targetTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                    val forecast = forecastsByTime[hourKey]

                    views.setViewVisibility(containerId, View.VISIBLE)

                    val label = if (offset == 0) "Now" else "+${offset}h"
                    views.setTextViewText(ids.first, label)
                    
                    // Hide icon for hourly text mode as condition data is not yet in HourlyForecastEntity
                    views.setViewVisibility(ids.second, View.GONE)

                    if (forecast != null) {
                        val temp = String.format("%.0f°", forecast.temperature)
                        views.setTextViewText(ids.third, temp)
                        views.setTextViewText(ids.fourth, "")  // No low temp in hourly mode
                    } else {
                        views.setTextViewText(ids.third, "--°")
                        views.setTextViewText(ids.fourth, "")
                    }
                } else {
                    views.setViewVisibility(containerId, View.GONE)
                }
            }
        }

        // Helper data class for updateHourlyTextMode
        private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

        /**
         * Calculate a quick accuracy score (0-5) from recent forecast data.
         * Returns null if insufficient data available.
         */
        private fun calculateQuickAccuracyScore(
            weatherByDate: Map<String, WeatherEntity>,
            forecastSnapshots: Map<String, List<ForecastSnapshotEntity>>,
            apiSource: String,
            today: LocalDate
        ): Double? {
            val sourceName = if (apiSource == "NWS") "NWS" else "OPEN_METEO"
            val errors = mutableListOf<Int>()

            // Look at last 7 days of actual data
            for (daysAgo in 1..7) {
                val date = today.minusDays(daysAgo.toLong())
                val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val weather = weatherByDate[dateStr]

                if (weather != null && weather.isActual) {
                    val forecasts = forecastSnapshots[dateStr] ?: emptyList()
                    val forecastDateStr = date.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

                    // Find 1-day-ahead forecast
                    val forecast = forecasts.find {
                        it.source == sourceName && it.forecastDate == forecastDateStr
                    }

                    if (forecast != null) {
                        val highError = kotlin.math.abs(weather.highTemp - forecast.highTemp)
                        val lowError = kotlin.math.abs(weather.lowTemp - forecast.lowTemp)
                        errors.add(highError)
                        errors.add(lowError)
                    }
                }
            }

            if (errors.isEmpty()) {
                return null
            }

            // Calculate average error
            val avgError = errors.average()

            // Convert to 0-5 score (same algorithm as AccuracyCalculator)
            return when {
                avgError <= 1.0 -> 5.0
                avgError <= 2.0 -> 5.0 - ((avgError - 1.0) * 0.5)
                avgError <= 3.0 -> 4.5 - ((avgError - 2.0) * 0.5)
                avgError <= 4.0 -> 4.0 - ((avgError - 3.0) * 0.5)
                else -> kotlin.math.max(0.0, 3.5 - ((avgError - 4.0) * 0.5))
            }
        }
    }
}
