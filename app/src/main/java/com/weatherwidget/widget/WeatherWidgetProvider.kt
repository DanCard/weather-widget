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

        // Check if there's data for the target date
        val proposedOffset = if (isLeft) currentOffset - 1 else currentOffset + 1
        val targetCenterDate = java.time.LocalDate.now().plusDays(proposedOffset.toLong())
        val targetDateStr = targetCenterDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = weatherList.associateBy { it.date }

        // Check if we have data for the target center date
        if (weatherByDate[targetDateStr] == null) {
            Log.d(TAG, "handleDailyNavigationDirect: No data for $targetDateStr, showing toast")
            // Show toast on main thread
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "No more data", android.widget.Toast.LENGTH_SHORT).show()
            }
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

        // Update widget directly
        val appWidgetManager = AppWidgetManager.getInstance(context)
        updateWidgetWithData(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts)
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

        // Calculate time window based on offset
        val now = java.time.LocalDateTime.now()
        val centerTime = now.plusHours(newOffset.toLong())
        val startTime = centerTime.minusHours(12).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val endTime = centerTime.plusHours(12).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))

        val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

        // Update widget with hourly view
        val appWidgetManager = AppWidgetManager.getInstance(context)
        updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime)
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

        if (viewMode == ViewMode.HOURLY) {
            // Hourly mode: get extended hourly data (24 hours centered on current offset)
            val now = java.time.LocalDateTime.now()
            val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
            val centerTime = now.plusHours(hourlyOffset.toLong())
            val startTime = centerTime.minusHours(12).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = centerTime.plusHours(12).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

            Log.d(TAG, "handleToggleApiDirect: Hourly mode - Got ${hourlyForecasts.size} hourly forecasts")
            updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime)
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
            val hourlyEnd = now.plusHours(3).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

            Log.d(TAG, "handleToggleApiDirect: Daily mode - Got ${weatherList.size} weather entries, ${forecastSnapshots.size} forecast dates, ${hourlyForecasts.size} hourly")
            updateWidgetWithData(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts)
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

        if (viewMode == ViewMode.HOURLY) {
            // Hourly mode: get extended hourly data
            val now = java.time.LocalDateTime.now()
            val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
            val centerTime = now.plusHours(hourlyOffset.toLong())
            val startTime = centerTime.minusHours(12).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = centerTime.plusHours(12).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

            updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime)
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

            updateWidgetWithData(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts)
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

        val appWidgetManager = AppWidgetManager.getInstance(context)

        if (newMode == ViewMode.HOURLY) {
            // Switched to hourly mode: fetch 24-hour window
            val now = java.time.LocalDateTime.now()
            val startTime = now.minusHours(12).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = now.plusHours(12).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

            updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, hourlyForecasts, now)
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

            updateWidgetWithData(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts)
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

        private const val CELL_WIDTH_DP = 73
        private const val CELL_HEIGHT_DP = 93  // Adjusted: 187dp/2rows=93.5, 294dp/3rows=98 → 93 balances both

        private fun getWidgetSize(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int): Pair<Int, Int> {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 40)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
            val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
            val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)
            val cols = (minWidth / CELL_WIDTH_DP).coerceAtLeast(1)
            val rows = (minHeight / CELL_HEIGHT_DP).coerceAtLeast(1)
            Log.d(TAG, "getWidgetSize: widgetId=$appWidgetId, minWidth=$minWidth, minHeight=$minHeight, maxWidth=$maxWidth, maxHeight=$maxHeight -> cols=$cols, rows=$rows")
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
            hourlyForecasts: List<HourlyForecastEntity> = emptyList()
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
                        val startTime = centerTime.minusHours(12).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                        val endTime = centerTime.plusHours(12).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                        val extendedHourly = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

                        updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, extendedHourly, centerTime)
                    }
                } else {
                    val now = LocalDateTime.now()
                    val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
                    val centerTime = now.plusHours(hourlyOffset.toLong())
                    updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime)
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

            // Set API source indicator (shows current display source with accuracy score)
            val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val todayWeather = weatherByDate[todayStr]
            val apiSource = todayWeather?.source ?: displaySource

            // Format display name
            val displayName = if (apiSource == "Open-Meteo") "Meteo" else apiSource

            Log.d(TAG, "updateWidgetWithData: apiSource='$apiSource', displayName='$displayName'")
            views.setTextViewText(R.id.api_source, displayName)

            // Set current temperature - always use interpolation from hourly forecasts for accuracy
            var currentTemp: Float? = null
            if (hourlyForecasts.isNotEmpty()) {
                val interpolator = TemperatureInterpolator()
                currentTemp = interpolator.getInterpolatedTemperature(hourlyForecasts, LocalDateTime.now())
                if (currentTemp != null) {
                    Log.d(TAG, "updateWidgetWithData: Using interpolated temp: $currentTemp")
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

            // Set up navigation click handlers
            setupNavigationButtons(context, views, appWidgetId, stateManager)

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
            val canLeft = if (viewMode == ViewMode.HOURLY) {
                stateManager.canNavigateHourlyLeft(appWidgetId)
            } else {
                stateManager.canNavigateLeft(appWidgetId)
            }
            val canRight = if (viewMode == ViewMode.HOURLY) {
                stateManager.canNavigateHourlyRight(appWidgetId)
            } else {
                stateManager.canNavigateRight(appWidgetId)
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
                val forecasts = forecastSnapshots[dateStr] ?: emptyList()
                Log.d(TAG, "buildDayDataList: Looking for $dateStr, found=${weather != null}, high=${weather?.highTemp}, forecasts=${forecasts.size}")

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
                        high = weather?.highTemp ?: 0,
                        low = weather?.lowTemp ?: 0,
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

            // Set visibility based on columns
            when {
                numColumns >= 6 -> {
                    views.setViewVisibility(R.id.day1_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day2_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day3_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day4_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day5_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day6_container, View.VISIBLE)
                }
                numColumns == 5 -> {
                    views.setViewVisibility(R.id.day1_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day2_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day3_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day4_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day5_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day6_container, View.GONE)
                }
                numColumns == 4 -> {
                    views.setViewVisibility(R.id.day1_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day2_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day3_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day4_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day5_container, View.GONE)
                    views.setViewVisibility(R.id.day6_container, View.GONE)
                }
                numColumns == 3 -> {
                    views.setViewVisibility(R.id.day1_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day2_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day3_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day4_container, View.GONE)
                    views.setViewVisibility(R.id.day5_container, View.GONE)
                    views.setViewVisibility(R.id.day6_container, View.GONE)
                }
                numColumns == 2 -> {
                    views.setViewVisibility(R.id.day1_container, View.GONE)
                    views.setViewVisibility(R.id.day2_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day3_container, View.VISIBLE)
                    views.setViewVisibility(R.id.day4_container, View.GONE)
                    views.setViewVisibility(R.id.day5_container, View.GONE)
                    views.setViewVisibility(R.id.day6_container, View.GONE)
                }
                else -> {
                    views.setViewVisibility(R.id.day1_container, View.GONE)
                    views.setViewVisibility(R.id.day2_container, View.VISIBLE)
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

            // Populate days
            populateDay(views, R.id.day1_label, R.id.day1_high, R.id.day1_low, getLabelForDate(day1Date), weatherByDate[day1Str])
            populateDay(views, R.id.day2_label, R.id.day2_high, R.id.day2_low, getLabelForDate(day2Date), weatherByDate[day2Str])
            populateDay(views, R.id.day3_label, R.id.day3_high, R.id.day3_low, getLabelForDate(day3Date), weatherByDate[day3Str])
            populateDay(views, R.id.day4_label, R.id.day4_high, R.id.day4_low, getLabelForDate(day4Date), weatherByDate[day4Str])
            populateDay(views, R.id.day5_label, R.id.day5_high, R.id.day5_low, getLabelForDate(day5Date), weatherByDate[day5Str])
            populateDay(views, R.id.day6_label, R.id.day6_high, R.id.day6_low, getLabelForDate(day6Date), weatherByDate[day6Str])
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

        private fun updateWidgetWithHourlyData(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            hourlyForecasts: List<HourlyForecastEntity>,
            centerTime: LocalDateTime
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            val (numColumns, numRows) = getWidgetSize(context, appWidgetManager, appWidgetId)
            val stateManager = WidgetStateManager(context)

            Log.d(TAG, "updateWidgetWithHourlyData: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, hourlyCount=${hourlyForecasts.size}")

            // Setup navigation buttons
            setupNavigationButtons(context, views, appWidgetId, stateManager)

            // Setup current temp click to toggle view
            setupCurrentTempToggle(context, views, appWidgetId)

            // Get current display source
            val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
            val displayName = if (displaySource == "Open-Meteo") "Meteo" else displaySource
            views.setTextViewText(R.id.api_source, displayName)

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

            // Determine how many hours to show (24 total, centered on centerTime)
            val startHour = centerTime.minusHours(12)
            val endHour = centerTime.plusHours(12)

            // Determine label frequency based on widget size
            // For 24 hours displayed, aim for ~4-6 visible labels to avoid overlap
            val labelInterval = when {
                numColumns >= 7 -> 3  // Every 3 hours (8 labels)
                numColumns >= 5 -> 4  // Every 4 hours (6 labels)
                else -> 6              // Every 6 hours (4 labels)
            }

            var currentHour = startHour
            var hourIndex = 0
            while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
                val hourKey = currentHour.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                val forecast = forecastsByTime[hourKey]

                if (forecast != null) {
                    hours.add(
                        HourlyGraphRenderer.HourData(
                            dateTime = currentHour,
                            temperature = forecast.temperature,
                            label = formatHourLabel(currentHour),
                            isCurrentHour = currentHour.hour == now.hour && currentHour.toLocalDate() == now.toLocalDate(),
                            showLabel = hourIndex % labelInterval == 0
                        )
                    )
                    hourIndex++
                }

                currentHour = currentHour.plusHours(1)
            }

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
                R.id.day1_container to Triple(R.id.day1_label, R.id.day1_high, R.id.day1_low),
                R.id.day2_container to Triple(R.id.day2_label, R.id.day2_high, R.id.day2_low),
                R.id.day3_container to Triple(R.id.day3_label, R.id.day3_high, R.id.day3_low),
                R.id.day4_container to Triple(R.id.day4_label, R.id.day4_high, R.id.day4_low),
                R.id.day5_container to Triple(R.id.day5_label, R.id.day5_high, R.id.day5_low),
                R.id.day6_container to Triple(R.id.day6_label, R.id.day6_high, R.id.day6_low)
            )

            containerIds.forEachIndexed { index, (containerId, labelIds) ->
                if (index < timeOffsets.size) {
                    val offset = timeOffsets[index]
                    val targetTime = centerTime.plusHours(offset.toLong())
                    val hourKey = targetTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                    val forecast = forecastsByTime[hourKey]

                    views.setViewVisibility(containerId, View.VISIBLE)

                    val label = if (offset == 0) "Now" else "+${offset}h"
                    views.setTextViewText(labelIds.first, label)

                    if (forecast != null) {
                        val temp = String.format("%.0f°", forecast.temperature)
                        views.setTextViewText(labelIds.second, temp)
                        views.setTextViewText(labelIds.third, "")  // No low temp in hourly mode
                    } else {
                        views.setTextViewText(labelIds.second, "--°")
                        views.setTextViewText(labelIds.third, "")
                    }
                } else {
                    views.setViewVisibility(containerId, View.GONE)
                }
            }
        }

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
