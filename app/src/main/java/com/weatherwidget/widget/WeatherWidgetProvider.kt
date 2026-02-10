package com.weatherwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.work.*
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.NavigationUtils
import java.time.LocalDateTime
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate: Updating ${appWidgetIds.size} widgets")
        
        val pendingResult = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val database = WeatherDatabase.getDatabase(context)
                val weatherDao = database.weatherDao()
                val snapshotDao = database.forecastSnapshotDao()
                val hourlyDao = database.hourlyForecastDao()
                val appLogDao = database.appLogDao()

                // 1. Get latest data from DB to see if we can skip loading state
                val latestWeather = weatherDao.getLatestWeather()
                
                if (latestWeather == null) {
                    // No data at all, show loading for all widgets
                    logToDb(context, "WIDGET_UPDATE", "DB is empty, showing loading")
                    for (appWidgetId in appWidgetIds) {
                        updateWidgetLoading(context, appWidgetManager, appWidgetId)
                    }
                    Log.d(TAG, "onUpdate: No data in DB, showing loading and triggering fetch")
                    triggerImmediateUpdate(context)
                } else {
                    // We have some data, refresh all widgets from cache immediately
                    val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val thirtyDays = LocalDate.now().plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
                    
                    val weatherList = weatherDao.getWeatherRange(historyStart, thirtyDays, latestWeather.locationLat, latestWeather.locationLon)
                    val forecastSnapshots = snapshotDao.getForecastsInRange(historyStart, thirtyDays, latestWeather.locationLat, latestWeather.locationLon)
                        .groupBy { it.targetDate }
                    
                    logToDb(context, "WIDGET_UPDATE", "Updating with ${weatherList.size} weather entries")

                    // Get hourly forecasts for interpolation
                    val now = LocalDateTime.now()
                    val hourlyStart = now.minusHours(24).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                    val hourlyEnd = now.plusHours(24).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                    val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, latestWeather.locationLat, latestWeather.locationLon)

                    for (appWidgetId in appWidgetIds) {
                        updateWidgetWithData(
                            context = context,
                            appWidgetManager = appWidgetManager,
                            appWidgetId = appWidgetId,
                            weatherList = weatherList,
                            forecastSnapshots = forecastSnapshots,
                            hourlyForecasts = hourlyForecasts
                        )
                    }

                    // 2. Check if data is stale and needs background fetch
                    if (DataFreshness.isDataStale(context)) {
                        Log.d(TAG, "onUpdate: Data is stale, triggering background fetch")
                        triggerImmediateUpdate(context)
                    } else {
                        Log.d(TAG, "onUpdate: Data is fresh, skipped fetch")
                    }
                }
                
                schedulePeriodicUpdate(context)
            } catch (e: Exception) {
                logToDb(context, "WIDGET_EXCEPTION", "${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "onUpdate: Error during update", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun logToDb(context: Context, tag: String, message: String) {
        try {
            val db = WeatherDatabase.getDatabase(context)
            db.appLogDao().insert(AppLogEntity(tag = tag, message = message))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log to DB", e)
        }
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
                val uiOnly = intent.getBooleanExtra(EXTRA_UI_ONLY, false)
                Log.d(TAG, "onReceive: Refresh triggered (uiOnly=$uiOnly)")
                
                // Always update UI immediately for instant feedback
                triggerUiOnlyUpdate(context)

                if (uiOnly) return

                // Check data staleness and fetch in background if needed
                val pendingResult = goAsync()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        if (DataFreshness.isDataStale(context)) {
                            Log.d(TAG, "onReceive: Data is stale, triggering background fetch")
                            triggerImmediateUpdate(context, forceRefresh = true)
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
            ACTION_TOGGLE_PRECIP -> {
                val appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                Log.d(TAG, "onReceive: Toggle Precip action for widget $appWidgetId")
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val pendingResult = goAsync()
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            handleTogglePrecipDirect(context, appWidgetId)
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

        if (viewMode == ViewMode.HOURLY || viewMode == ViewMode.PRECIPITATION) {
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
        val thirtyDays = java.time.LocalDate.now().plusDays(30).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

        val weatherList = weatherDao.getWeatherRange(historyStart, thirtyDays, lat, lon)

        // Filter by current display source + generic gap
        val filteredWeatherList = weatherList.filter { it.source == displaySource || it.source == WidgetStateManager.SOURCE_GENERIC_GAP }
            .groupBy { it.date }
            .map { (_, items) -> items.find { it.source == displaySource } ?: items.first() }
        val weatherByDate = filteredWeatherList.associateBy { it.date }

        val today = java.time.LocalDate.now()
        val currentCenterDate = today.plusDays(currentOffset.toLong())

        // Get actual widget size to determine navigation bounds
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val (numColumns, _) = getWidgetSize(context, appWidgetManager, appWidgetId)

        // Calculate offsets based on widget width using NavigationUtils
        val minOffset = NavigationUtils.getMinOffset(numColumns)
        val maxOffset = NavigationUtils.getMaxOffset(numColumns)

        // Filter for any dates that have at least some data (high OR low)
        val availableDates = weatherByDate.filter { (dateStr, weather) ->
            weather.highTemp != null || weather.lowTemp != null
        }.keys.map { java.time.LocalDate.parse(it) }.sorted()

        val minDate = availableDates.firstOrNull()
        val maxDate = availableDates.lastOrNull()

        // Check if navigation would reveal new data for the rightmost/leftmost column
        val canNavigate = if (isLeft) {
            val newLeftmost = currentCenterDate.minusDays(1).plusDays(minOffset.toLong())
            minDate != null && !minDate.isAfter(newLeftmost)
        } else {
            val newRightmost = currentCenterDate.plusDays(1).plusDays(maxOffset.toLong())
            maxDate != null && !maxDate.isBefore(newRightmost)
        }

        Log.d(TAG, "handleDailyNavigationDirect: widgetId=$appWidgetId, cols=$numColumns, center=$currentCenterDate, maxOffset=$maxOffset, maxDate=$maxDate, canNavigate=$canNavigate")

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

        val forecastSnapshots = snapshotDao.getForecastsInRange(historyStart, thirtyDays, lat, lon)
            .groupBy { it.targetDate }

        // Get hourly forecasts for interpolation
        val now = java.time.LocalDateTime.now()
        val hourStart = now.minusHours(2).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val hourEnd = now.plusHours(2).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val hourlyForecasts = hourlyDao.getHourlyForecasts(hourStart, hourEnd, lat, lon)

        // Update widget directly
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

        // Calculate time window based on offset, rounded to nearest hour
        val now = java.time.LocalDateTime.now()
        val centerTime = now.plusHours(newOffset.toLong())
        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val roundedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        val startTime = roundedCenter.minusHours(8).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val endTime = roundedCenter.plusHours(16).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))

        val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

        // Update widget with appropriate view
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val viewMode = stateManager.getViewMode(appWidgetId)
        if (viewMode == ViewMode.PRECIPITATION) {
            // Get today's precip probability for the top-left display
            val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
            val todayPrecip = weatherDao.getWeatherForDateBySource(todayStr, lat, lon, displaySource)
                ?.precipProbability
            updateWidgetWithPrecipData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime, todayPrecip)
        } else {
            updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime)
        }
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

        if (viewMode == ViewMode.HOURLY || viewMode == ViewMode.PRECIPITATION) {
            // Hourly/Precip mode: get extended hourly data (24 hours centered on current offset)
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

            Log.d(TAG, "handleToggleApiDirect: ${viewMode.name} mode - Got ${hourlyForecasts.size} hourly forecasts from $startTime to $endTime")
            if (viewMode == ViewMode.PRECIPITATION) {
                val todayStr = java.time.LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val todayPrecip = weatherDao.getWeatherForDateBySource(todayStr, lat, lon, newSource)
                    ?.precipProbability
                updateWidgetWithPrecipData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime, todayPrecip)
            } else {
                updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime)
            }
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

        if (viewMode == ViewMode.HOURLY || viewMode == ViewMode.PRECIPITATION) {
            // Hourly/Precip mode: get extended hourly data
            val now = java.time.LocalDateTime.now()
            val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
            val centerTime = now.plusHours(hourlyOffset.toLong())
            // Round to nearest hour (same as display logic)
            val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            val roundedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
            val startTime = roundedCenter.minusHours(8).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = roundedCenter.plusHours(16).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

            if (viewMode == ViewMode.PRECIPITATION) {
                val todayStr = java.time.LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
                val todayPrecip = weatherDao.getWeatherForDateBySource(todayStr, lat, lon, displaySource)
                    ?.precipProbability
                updateWidgetWithPrecipData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime, todayPrecip)
            } else {
                updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime)
            }
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
            // Switched to hourly mode: fetch 24-hour window (8h history + 16h forecast)
            val now = java.time.LocalDateTime.now()
            val startTime = now.minusHours(8).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = now.plusHours(16).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
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

    private suspend fun handleTogglePrecipDirect(context: Context, appWidgetId: Int) {
        val stateManager = WidgetStateManager(context)
        val newMode = stateManager.togglePrecipitationMode(appWidgetId)
        Log.d(TAG, "handleTogglePrecipDirect: Toggled to $newMode for widget $appWidgetId")

        val database = WeatherDatabase.getDatabase(context)
        val weatherDao = database.weatherDao()
        val hourlyDao = database.hourlyForecastDao()
        val snapshotDao = database.forecastSnapshotDao()

        val latestWeather = weatherDao.getLatestWeather()
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val appWidgetManager = AppWidgetManager.getInstance(context)

        if (newMode == ViewMode.PRECIPITATION) {
            val now = java.time.LocalDateTime.now()
            val startTime = now.minusHours(8).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = now.plusHours(16).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

            // Get today's precip probability for the top-left display
            val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
            val todayPrecip = weatherDao.getWeatherForDateBySource(todayStr, lat, lon, displaySource)
                ?.precipProbability

            updateWidgetWithPrecipData(context, appWidgetManager, appWidgetId, hourlyForecasts, now, todayPrecip)
        } else {
            // Switched back to daily mode
            val historyStart = java.time.LocalDate.now().minusDays(30).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val twoWeeks = java.time.LocalDate.now().plusDays(14).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val weatherList = weatherDao.getWeatherRange(historyStart, twoWeeks, lat, lon)
            val forecastSnapshots = snapshotDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                .groupBy { it.targetDate }
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

    private fun triggerImmediateUpdate(context: Context, forceRefresh: Boolean = false) {
        Log.d(TAG, "triggerImmediateUpdate: Enqueueing worker (force=$forceRefresh)")
        // No network constraint - worker will use cached data if network unavailable
        val workRequest = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, forceRefresh)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_ONE_TIME,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
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

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_ONE_TIME + "_ui",
            ExistingWorkPolicy.REPLACE, // UI-only updates should replace each other to show latest toggles
            workRequest
        )
        Log.d(TAG, "triggerUiOnlyUpdate: Worker enqueued with id=${workRequest.id}")
    }

    companion object {
        const val WORK_NAME = "weather_widget_update"
        const val WORK_NAME_ONE_TIME = "weather_widget_one_time"
        const val ACTION_REFRESH = "com.weatherwidget.ACTION_REFRESH"
        const val ACTION_NAV_LEFT = "com.weatherwidget.ACTION_NAV_LEFT"
        const val ACTION_NAV_RIGHT = "com.weatherwidget.ACTION_NAV_RIGHT"
        const val ACTION_TOGGLE_API = "com.weatherwidget.ACTION_TOGGLE_API"
        const val ACTION_TOGGLE_VIEW = "com.weatherwidget.ACTION_TOGGLE_VIEW"
        const val ACTION_TOGGLE_PRECIP = "com.weatherwidget.ACTION_TOGGLE_PRECIP"
        const val EXTRA_UI_ONLY = "com.weatherwidget.EXTRA_UI_ONLY"
        private const val TAG = "WeatherWidgetProvider"
        private const val MAX_BITMAP_PIXELS = 225_000 // Limit bitmap to ~900KB (ARGB_8888 is 4 bytes/px)

        private const val CELL_WIDTH_DP = 70
        private const val CELL_HEIGHT_DP = 90

        /**
         * Get the weather condition for the current hour from hourly forecasts.
         * This ensures consistency between the current temperature icon and the hourly forecast graph.
         *
         * @param hourlyForecasts List of hourly forecast entities
         * @param displaySource The currently selected API source ("NWS" or "Open-Meteo")
         * @return The condition string for the current hour, or null if not available
         */
        private fun getCurrentHourCondition(
            hourlyForecasts: List<HourlyForecastEntity>,
            displaySource: String
        ): String? {
            val now = LocalDateTime.now()
            val currentHourKey = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val sourceName = if (displaySource == "NWS") "NWS" else "OPEN_METEO"

            val currentHourForecast = hourlyForecasts
                .filter { it.dateTime == currentHourKey }
                .let { forecasts ->
                    forecasts.find { it.source == sourceName }
                        ?: forecasts.find { it.source == WidgetStateManager.SOURCE_GENERIC_GAP }
                        ?: forecasts.firstOrNull()
                }

            if (currentHourForecast == null) {
                Log.d(TAG, "getCurrentHourCondition: No hourly forecast found for current hour $currentHourKey")
            } else {
                Log.d(TAG, "getCurrentHourCondition: Using condition '${currentHourForecast.condition}' from source ${currentHourForecast.source}")
            }

            return currentHourForecast?.condition
        }

        private fun getWidgetSize(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int): WidgetDimensions {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 40)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
            val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
            val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)

            // Android reports both min and max widget dimensions:
            //   Portrait:  actual size ≈ minWidth × maxHeight
            //   Landscape: actual size ≈ maxWidth × minHeight
            val isPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val width = if (isPortrait) minWidth else maxWidth
            val height = if (isPortrait) maxHeight else minHeight

            // Standard Android widget size formula: (size + padding) / cell_size with rounding
            // Using +15/+25 padding and proper rounding to handle widgets that are "almost" N rows/cols
            val cols = ((width + 15).toFloat() / CELL_WIDTH_DP).roundToInt().coerceAtLeast(1)
            val rows = ((height + 25).toFloat() / CELL_HEIGHT_DP).roundToInt().coerceAtLeast(1)

            Log.d(TAG, "getWidgetSize: widgetId=$appWidgetId, minW=$minWidth, minH=$minHeight, maxW=$maxWidth, maxH=$maxHeight, isPortrait=$isPortrait -> using ${width}x${height}, cols=$cols, rows=$rows")
            return WidgetDimensions(cols, rows, width, height)
        }

        data class WidgetDimensions(val cols: Int, val rows: Int, val widthDp: Int, val heightDp: Int)

        private fun getOptimalBitmapSize(context: Context, widthDp: Int, heightDp: Int): Pair<Int, Int> {
            val rawWidth = dpToPx(context, widthDp)
            val rawHeight = dpToPx(context, heightDp)
            val rawPixels = rawWidth * rawHeight
            val rawMemoryKB = rawPixels * 4 / 1024

            return if (rawPixels > MAX_BITMAP_PIXELS) {
                val scale = kotlin.math.sqrt(MAX_BITMAP_PIXELS.toFloat() / rawPixels)
                val newWidth = (rawWidth * scale).roundToInt()
                val newHeight = (rawHeight * scale).roundToInt()
                val newPixels = newWidth * newHeight
                val newMemoryKB = newPixels * 4 / 1024
                Log.d(TAG, "getOptimalBitmapSize: ${widthDp}dp×${heightDp}dp → Downscaling from ${rawWidth}x${rawHeight}px (${rawMemoryKB}KB) to ${newWidth}x${newHeight}px (${newMemoryKB}KB), scale=$scale, rawPixels=$rawPixels")
                newWidth to newHeight
            } else {
                Log.d(TAG, "getOptimalBitmapSize: ${widthDp}dp×${heightDp}dp → No downscaling needed: ${rawWidth}x${rawHeight}px (${rawMemoryKB}KB), rawPixels=$rawPixels")
                rawWidth to rawHeight
            }
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
            if (viewMode == ViewMode.HOURLY || viewMode == ViewMode.PRECIPITATION) {
                val now = LocalDateTime.now()
                val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
                val centerTime = now.plusHours(hourlyOffset.toLong())
                // Get today's precip probability for display
                val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
                val todayPrecip = weatherList
                    .find { it.date == todayStr && it.source == displaySource }
                    ?.precipProbability
                if (viewMode == ViewMode.PRECIPITATION) {
                    updateWidgetWithPrecipData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime, todayPrecip)
                } else {
                    updateWidgetWithHourlyData(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime, todayPrecip)
                }
                return
            }

            // Daily mode
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            val dimensions = getWidgetSize(context, appWidgetManager, appWidgetId)
            val numColumns = dimensions.cols
            val numRows = dimensions.rows
            
            val dateOffset = stateManager.getDateOffset(appWidgetId)
            val accuracyMode = stateManager.getAccuracyDisplayMode()

            Log.d(TAG, "updateWidgetWithData: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, offset=$dateOffset, weatherCount=${weatherList.size}")

            // Setup current temp click to toggle view mode
            setupCurrentTempToggle(context, views, appWidgetId)

            val today = LocalDate.now()
            val centerDate = today.plusDays(dateOffset.toLong())

            // Get the current display source for this widget
            val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)

            // Build weather map: prefer the selected display source, fallback to generic gap
            val weatherByDate = weatherList
                .filter { it.source == displaySource || it.source == WidgetStateManager.SOURCE_GENERIC_GAP }
                .groupBy { it.date }
                .mapValues { (_, items) -> items.find { it.source == displaySource } ?: items.first() }

            // Set API source indicator (shows current display source with accuracy score)
            val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val todayWeather = weatherByDate[todayStr]

            // Format display name - always show the user's selected displaySource, not the data's source
            val displayName = if (displaySource == "Open-Meteo") "Meteo" else displaySource

            Log.d(TAG, "updateWidgetWithData: displaySource='$displaySource', displayName='$displayName'")
            views.setTextViewText(R.id.api_source, displayName)

            // Set weather icon - use hourly forecast condition for current hour for consistency
            val now = LocalDateTime.now()
            val lat = weatherList.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
            val lon = weatherList.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
            val isNight = SunPositionUtils.isNight(now, lat, lon)

            // Get current hour's condition from hourly forecasts for consistency with hourly graph
            val currentHourCondition = getCurrentHourCondition(hourlyForecasts, displaySource)
                ?: todayWeather?.condition  // Fallback to daily condition if hourly not available

            val iconRes = WeatherIconMapper.getIconResource(currentHourCondition, isNight)
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

            // Show precipitation probability next to current temp when rain is expected
            val precipProb = todayWeather?.precipProbability
            if (precipProb != null && precipProb > 0) {
                views.setTextViewText(R.id.precip_probability, "${precipProb}%")
                views.setViewVisibility(R.id.precip_probability, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.precip_probability, View.GONE)
            }

            // Setup API source toggle click handler
            setupApiToggle(context, views, appWidgetId, numRows)

            // Get available dates (allow partial data: high OR low)
            val availableDates = weatherByDate.filter { (_, weather) ->
                weather.highTemp != null || weather.lowTemp != null
            }.keys

            // Set up navigation click handlers with available dates and widget width
            setupNavigationButtons(context, views, appWidgetId, stateManager, availableDates, numColumns)

            // Use graph mode for 2+ rows (using a lower threshold for devices like Pixel 7 Pro)
            val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
            val useGraph = rawRows >= 1.4f

            if (useGraph) {
                views.setViewVisibility(R.id.text_container, View.GONE)
                views.setViewVisibility(R.id.graph_view, View.VISIBLE)
                views.setViewVisibility(R.id.graph_day_zones, View.VISIBLE)

                // Build day data for graph with offset
                val days = buildDayDataList(centerDate, today, weatherByDate, forecastSnapshots, numColumns, accuracyMode, displaySource)

                // Use actual widget dimensions for bitmap to match ImageView size
                // Root padding: 8dp×2=16dp, ImageView margins: 4dp×2=8dp → total 24dp horizontal, 16dp vertical
                val widthDp = dimensions.widthDp - 24
                val heightDp = dimensions.heightDp - 16

                val (widthPx, heightPx) = getOptimalBitmapSize(context, widthDp, heightDp)

                // Render graph
                val bitmap = DailyForecastGraphRenderer.renderGraph(context, days, widthPx, heightPx)
                views.setImageViewBitmap(R.id.graph_view, bitmap)

                // Setup per-day click handlers for graph mode
                setupGraphDayClickHandlers(context, views, appWidgetId, days, lat, lon, displaySource)
            } else {
                views.setViewVisibility(R.id.text_container, View.VISIBLE)
                views.setViewVisibility(R.id.graph_view, View.GONE)
                views.setViewVisibility(R.id.graph_day_zones, View.GONE)

                // Text mode - set visibility and populate
                val visibleDates = updateTextMode(views, centerDate, today, weatherByDate, numColumns)

                // Setup per-day click handlers for text mode
                setupTextDayClickHandlers(context, views, appWidgetId, visibleDates, lat, lon, displaySource)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun setupNavigationButtons(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            stateManager: WidgetStateManager,
            availableDates: Set<String> = emptySet(),
            numColumns: Int = 3
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

            if (viewMode == ViewMode.HOURLY || viewMode == ViewMode.PRECIPITATION) {
                canLeft = stateManager.canNavigateHourlyLeft(appWidgetId)
                canRight = stateManager.canNavigateHourlyRight(appWidgetId)
            } else {
                // Check if navigating would reveal new data for ALL visible columns
                val today = LocalDate.now()
                val currentOffset = stateManager.getDateOffset(appWidgetId)
                val currentCenterDate = today.plusDays(currentOffset.toLong())

                // Find min/max available dates
                val sortedDates = availableDates.map { LocalDate.parse(it) }.sorted()
                val minDate = sortedDates.firstOrNull()
                val maxDate = sortedDates.lastOrNull()

                // Calculate offsets based on widget width using NavigationUtils
                val minOffset = NavigationUtils.getMinOffset(numColumns)
                val maxOffset = NavigationUtils.getMaxOffset(numColumns)

                // Can go left if there's data for the new leftmost day after navigation
                // newLeftmost = (currentCenter - 1) + minOffset
                val newLeftmost = currentCenterDate.minusDays(1).plusDays(minOffset.toLong())
                canLeft = minDate != null && !minDate.isAfter(newLeftmost)

                // Can go right if there's data for the new rightmost day after navigation
                // newRightmost = (currentCenter + 1) + maxOffset
                val newRightmost = currentCenterDate.plusDays(1).plusDays(maxOffset.toLong())
                canRight = maxDate != null && !maxDate.isBefore(newRightmost)

                Log.d(TAG, "setupNavigationButtons: center=$currentCenterDate, numCols=$numColumns, minOffset=$minOffset, maxOffset=$maxOffset")
                Log.d(TAG, "setupNavigationButtons: minDate=$minDate, maxDate=$maxDate, newLeftmost=$newLeftmost, newRightmost=$newRightmost")
                Log.d(TAG, "setupNavigationButtons: canLeft=$canLeft, canRight=$canRight")
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
        ): List<DailyForecastGraphRenderer.DayData> {
            val days = mutableListOf<DailyForecastGraphRenderer.DayData>()
            Log.d(TAG, "buildDayDataList: numColumns=$numColumns, weatherByDate keys=${weatherByDate.keys}, centerDate=$centerDate, today=$today")

            // Determine which days to show based on columns (relative to center) using NavigationUtils
            val dayOffsets = NavigationUtils.getDayOffsets(numColumns)

            Log.d(TAG, "buildDayDataList: For $numColumns columns, showing ${dayOffsets.size} days with offsets: $dayOffsets")

            dayOffsets.forEach { offset ->
                val date = centerDate.plusDays(offset)
                val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val weather = weatherByDate[dateStr]

                // Skip days without any data
                if (weather == null || (weather.highTemp == null && weather.lowTemp == null)) {
                    Log.d(TAG, "buildDayDataList: Skipping $dateStr - no data")
                    return@forEach
                }

                val forecasts = forecastSnapshots[dateStr] ?: emptyList()
                Log.d(TAG, "buildDayDataList: Including $dateStr, high=${weather.highTemp}, low=${weather.lowTemp}, forecasts=${forecasts.size}")

                // Get forecast for the display source, falling back to generic gap
                val sourceName = if (displaySource == "NWS") "NWS" else "OPEN_METEO"
                val forecast = forecasts.find { it.source == sourceName } ?: forecasts.find { it.source == WidgetStateManager.SOURCE_GENERIC_GAP }

                val label = when {
                    date == today -> "Today"
                    else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }

                // Determine if we should show forecast comparison (only for past dates with forecast data)
                val isPastDate = date.isBefore(today)
                val showComparison = isPastDate && forecast != null && accuracyMode != AccuracyDisplayMode.NONE

                val iconRes = WeatherIconMapper.getIconResource(weather.condition)
                val isSunny = iconRes == R.drawable.ic_weather_clear ||
                             iconRes == R.drawable.ic_weather_partly_cloudy ||
                             iconRes == R.drawable.ic_weather_mostly_clear
                val isRainy = iconRes == R.drawable.ic_weather_rain ||
                             iconRes == R.drawable.ic_weather_storm
                val isMixed = iconRes == R.drawable.ic_weather_mostly_cloudy ||
                             iconRes == R.drawable.ic_weather_partly_cloudy ||
                             iconRes == R.drawable.ic_weather_mostly_clear

                days.add(
                    DailyForecastGraphRenderer.DayData(
                        date = dateStr,
                        label = label,
                        high = weather.highTemp,
                        low = weather.lowTemp,
                        iconRes = iconRes,
                        isSunny = isSunny,
                        isRainy = isRainy,
                        isMixed = isMixed,
                        isToday = date == today,
                        isPast = isPastDate,
                        isClimateNormal = weather.isClimateNormal,
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
        ): List<Pair<Int, String>> {
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

            // Check data availability for each day (must have both high and low)
            fun hasCompleteData(date: LocalDate, dateStr: String): Boolean {
                val weather = weatherByDate[dateStr] ?: return false
                return weather.highTemp != null && weather.lowTemp != null
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

            // Return list of visible day indices and their dates for click handler setup
            val visibleDays = mutableListOf<Pair<Int, String>>()
            if (hasDay1) visibleDays.add(1 to day1Str)
            if (hasDay2) visibleDays.add(2 to day2Str)
            if (hasDay3) visibleDays.add(3 to day3Str)
            if (hasDay4) visibleDays.add(4 to day4Str)
            if (hasDay5) visibleDays.add(5 to day5Str)
            if (hasDay6) visibleDays.add(6 to day6Str)
            return visibleDays
        }

        private fun setupTextDayClickHandlers(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            visibleDays: List<Pair<Int, String>>,
            lat: Double,
            lon: Double,
            displaySource: String
        ) {
            val containerIds = listOf(
                R.id.day1_container,
                R.id.day2_container,
                R.id.day3_container,
                R.id.day4_container,
                R.id.day5_container,
                R.id.day6_container
            )

            // Calculate midpoint for left/right split
            val midpoint = visibleDays.size / 2

            visibleDays.forEachIndexed { index, (dayIndex, dateStr) ->
                val containerId = containerIds[dayIndex - 1]

                val intent = if (index < midpoint) {
                    // Left half -> Forecast History
                    Intent(context, ForecastHistoryActivity::class.java).apply {
                        putExtra(ForecastHistoryActivity.EXTRA_TARGET_DATE, dateStr)
                        putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
                        putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
                        putExtra(ForecastHistoryActivity.EXTRA_SOURCE, displaySource)
                    }
                } else {
                    // Right half -> Settings
                    Intent(context, com.weatherwidget.ui.SettingsActivity::class.java)
                }

                val pendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId * 100 + dayIndex,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(containerId, pendingIntent)
            }
        }

        private fun setupGraphDayClickHandlers(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            days: List<DailyForecastGraphRenderer.DayData>,
            lat: Double,
            lon: Double,
            displaySource: String
        ) {
            val zoneIds = listOf(
                R.id.graph_day1_zone,
                R.id.graph_day2_zone,
                R.id.graph_day3_zone,
                R.id.graph_day4_zone,
                R.id.graph_day5_zone,
                R.id.graph_day6_zone
            )

            // Calculate midpoint for left/right split
            val midpoint = days.size / 2

            days.forEachIndexed { index, dayData ->
                val zoneId = zoneIds.getOrNull(index) ?: return@forEachIndexed

                // Show this zone
                views.setViewVisibility(zoneId, View.VISIBLE)

                val dateStr = dayData.date

                val intent = if (index < midpoint) {
                    // Left half -> Forecast History
                    Intent(context, ForecastHistoryActivity::class.java).apply {
                        putExtra(ForecastHistoryActivity.EXTRA_TARGET_DATE, dateStr)
                        putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
                        putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
                        putExtra(ForecastHistoryActivity.EXTRA_SOURCE, displaySource)
                    }
                } else {
                    // Right half -> Settings
                    Intent(context, com.weatherwidget.ui.SettingsActivity::class.java)
                }

                val pendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId * 100 + 50 + index,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(zoneId, pendingIntent)
            }

            // Hide unused zones
            for (i in days.size until zoneIds.size) {
                views.setViewVisibility(zoneIds[i], View.GONE)
            }
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
            
            // Apply tint: Yellow for sunny, Grey for most, none for rain/storm/mixed (native vector colors)
            val isSunny = iconRes == R.drawable.ic_weather_clear ||
                         iconRes == R.drawable.ic_weather_partly_cloudy ||
                         iconRes == R.drawable.ic_weather_mostly_clear
            val isRainy = iconRes == R.drawable.ic_weather_rain ||
                         iconRes == R.drawable.ic_weather_storm
            val isMixed = iconRes == R.drawable.ic_weather_mostly_cloudy ||
                         iconRes == R.drawable.ic_weather_partly_cloudy ||
                         iconRes == R.drawable.ic_weather_mostly_clear

            if (!isRainy && !isMixed) {
                val tintColor = if (isSunny) android.graphics.Color.parseColor("#FFD60A") else android.graphics.Color.parseColor("#AAAAAA")
                views.setInt(iconId, "setColorFilter", tintColor)
            }
            
            views.setViewVisibility(iconId, View.VISIBLE)
            
            views.setTextViewText(highId, weather?.highTemp?.let { "${it}°" } ?: "--°")
            views.setTextViewText(lowId, weather?.lowTemp?.let { "${it}°" } ?: "--°")
        }

        private fun updateWidgetWithHourlyData(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            hourlyForecasts: List<HourlyForecastEntity>,
            centerTime: LocalDateTime,
            precipProbability: Int? = null
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            val dimensions = getWidgetSize(context, appWidgetManager, appWidgetId)
            val numColumns = dimensions.cols
            val numRows = dimensions.rows
            
            val stateManager = WidgetStateManager(context)

            Log.d(TAG, "updateWidgetWithHourlyData: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, hourlyCount=${hourlyForecasts.size}")

            // Hourly mode: hide graph day zones, keep settings click on graph_view
            views.setViewVisibility(R.id.graph_day_zones, View.GONE)

            // Set tap to open settings on graph_view (hourly mode doesn't have per-day clicks)
            val settingsIntent = Intent(context, com.weatherwidget.ui.SettingsActivity::class.java)
            val settingsPendingIntent = PendingIntent.getActivity(
                context,
                0,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.graph_view, settingsPendingIntent)

            // Setup navigation buttons
            setupNavigationButtons(context, views, appWidgetId, stateManager)

            // Setup current temp click to toggle view
            setupCurrentTempToggle(context, views, appWidgetId)

            // Get current display source
            val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
            val displayName = if (displaySource == "Open-Meteo") "Meteo" else displaySource
            views.setTextViewText(R.id.api_source, displayName)

            // Set weather icon - use hourly forecast condition for current hour
            val now = LocalDateTime.now()
            val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
            val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
            val isNight = SunPositionUtils.isNight(now, lat, lon)

            // Get current hour's condition from hourly forecasts
            val currentHourCondition = getCurrentHourCondition(hourlyForecasts, displaySource)
            val iconRes = WeatherIconMapper.getIconResource(currentHourCondition, isNight)
            views.setImageViewResource(R.id.weather_icon, iconRes)
            views.setViewVisibility(R.id.weather_icon, View.VISIBLE)

            // Setup API toggle
            setupApiToggle(context, views, appWidgetId, numRows)

            // Set current temperature (interpolated from hourly data)
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

            // Show precipitation probability next to current temp when rain is expected
            if (precipProbability != null && precipProbability > 0) {
                views.setTextViewText(R.id.precip_probability, "${precipProbability}%")
                views.setViewVisibility(R.id.precip_probability, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.precip_probability, View.GONE)
            }

            // Use graph mode for 2+ rows, text mode for 1 row
            val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
            val useGraph = rawRows >= 1.4f

            if (useGraph) {
                views.setViewVisibility(R.id.text_container, View.GONE)
                views.setViewVisibility(R.id.graph_view, View.VISIBLE)

                // Build hour data list for graph (24 hours visible)
                val hours = buildHourDataList(hourlyForecasts, centerTime, numColumns, displaySource)

                // Use actual widget dimensions for bitmap to match ImageView size
                val widthDp = dimensions.widthDp - 24
                val heightDp = dimensions.heightDp - 16

                val (widthPx, heightPx) = getOptimalBitmapSize(context, widthDp, heightDp)

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

        private fun updateWidgetWithPrecipData(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            hourlyForecasts: List<HourlyForecastEntity>,
            centerTime: LocalDateTime,
            precipProbability: Int? = null
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            val dimensions = getWidgetSize(context, appWidgetManager, appWidgetId)
            val numColumns = dimensions.cols
            val numRows = dimensions.rows

            val stateManager = WidgetStateManager(context)

            Log.d(TAG, "updateWidgetWithPrecipData: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, hourlyCount=${hourlyForecasts.size}")

            // Hide graph day zones (not used in precipitation mode)
            views.setViewVisibility(R.id.graph_day_zones, View.GONE)

            // Set tap to open settings on graph_view
            val settingsIntent = Intent(context, com.weatherwidget.ui.SettingsActivity::class.java)
            val settingsPendingIntent = PendingIntent.getActivity(
                context,
                0,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
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
            val now = LocalDateTime.now()
            val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
            val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
            val isNight = SunPositionUtils.isNight(now, lat, lon)
            val currentHourCondition = getCurrentHourCondition(hourlyForecasts, displaySource)
            val iconRes = WeatherIconMapper.getIconResource(currentHourCondition, isNight)
            views.setImageViewResource(R.id.weather_icon, iconRes)
            views.setViewVisibility(R.id.weather_icon, View.VISIBLE)

            // Setup API toggle
            setupApiToggle(context, views, appWidgetId, numRows)

            // Set current temperature (interpolated from hourly data)
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

            // Show precipitation probability next to current temp
            if (precipProbability != null && precipProbability > 0) {
                views.setTextViewText(R.id.precip_probability, "${precipProbability}%")
                views.setViewVisibility(R.id.precip_probability, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.precip_probability, View.GONE)
            }

            // Use graph mode for 2+ rows, text mode for 1 row
            val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
            val useGraph = rawRows >= 1.4f

            if (useGraph) {
                views.setViewVisibility(R.id.text_container, View.GONE)
                views.setViewVisibility(R.id.graph_view, View.VISIBLE)

                // Build precipitation hour data list
                val hours = buildPrecipHourDataList(hourlyForecasts, centerTime, numColumns, displaySource)

                // Use actual widget dimensions for bitmap
                val widthDp = dimensions.widthDp - 24
                val heightDp = dimensions.heightDp - 16
                val (widthPx, heightPx) = getOptimalBitmapSize(context, widthDp, heightDp)

                // Render precipitation graph
                val bitmap = PrecipitationGraphRenderer.renderGraph(context, hours, widthPx, heightPx, now)
                views.setImageViewBitmap(R.id.graph_view, bitmap)
            } else {
                views.setViewVisibility(R.id.text_container, View.VISIBLE)
                views.setViewVisibility(R.id.graph_view, View.GONE)

                // Text mode: show precip percentages
                updatePrecipTextMode(views, hourlyForecasts, centerTime, numColumns, displaySource)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun buildPrecipHourDataList(
            hourlyForecasts: List<HourlyForecastEntity>,
            centerTime: LocalDateTime,
            numColumns: Int,
            displaySource: String
        ): List<PrecipitationGraphRenderer.PrecipHourData> {
            val hours = mutableListOf<PrecipitationGraphRenderer.PrecipHourData>()
            val now = LocalDateTime.now()

            // Group by dateTime and prefer the selected source
            val sourceName = if (displaySource == "NWS") "NWS" else "OPEN_METEO"
            val forecastsByTime = hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    val preferred = entry.value.find { it.source == sourceName }
                    val gap = entry.value.find { it.source == WidgetStateManager.SOURCE_GENERIC_GAP }
                    preferred ?: gap ?: entry.value.firstOrNull()
                }

            // Same time window as hourly graph: 8h back + 16h forward
            val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
            val startHour = alignedCenter.minusHours(8)
            val endHour = alignedCenter.plusHours(16)

            val labelInterval = 4
            var currentHour = startHour
            var hourIndex = 0

            while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
                val hourKey = currentHour.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                val forecast = forecastsByTime[hourKey]

                if (forecast != null) {
                    val diffMinutes = java.time.Duration.between(currentHour, now).toMinutes()
                    val absDiff = kotlin.math.abs(diffMinutes)
                    val isClosest = absDiff <= 30
                    val showLabel = isClosest || (hourIndex % labelInterval == 0)

                    hours.add(
                        PrecipitationGraphRenderer.PrecipHourData(
                            dateTime = currentHour,
                            precipProbability = forecast.precipProbability ?: 0,
                            label = formatHourLabel(currentHour),
                            isCurrentHour = isClosest,
                            showLabel = showLabel
                        )
                    )
                    hourIndex++
                }

                currentHour = currentHour.plusHours(1)
            }

            Log.d(TAG, "buildPrecipHourDataList: Built ${hours.size} hours, first=${hours.firstOrNull()?.dateTime}, last=${hours.lastOrNull()?.dateTime}")
            return hours
        }

        private fun updatePrecipTextMode(
            views: RemoteViews,
            hourlyForecasts: List<HourlyForecastEntity>,
            centerTime: LocalDateTime,
            numColumns: Int,
            displaySource: String
        ) {
            val sourceName = if (displaySource == "NWS") "NWS" else "OPEN_METEO"
            val forecastsByTime = hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    entry.value.find { it.source == sourceName }
                        ?: entry.value.find { it.source == WidgetStateManager.SOURCE_GENERIC_GAP }
                        ?: entry.value.firstOrNull()
                }

            val timeOffsets = when {
                numColumns >= 6 -> listOf(0, 3, 6, 9, 12, 15)
                numColumns == 5 -> listOf(0, 3, 6, 9, 12)
                numColumns == 4 -> listOf(0, 3, 6, 9)
                numColumns == 3 -> listOf(0, 3, 6)
                numColumns == 2 -> listOf(0, 6)
                else -> listOf(0)
            }

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
                    views.setViewVisibility(ids.second, View.GONE)

                    if (forecast != null) {
                        val precip = forecast.precipProbability ?: 0
                        views.setTextViewText(ids.third, "${precip}%")
                        views.setTextViewText(ids.fourth, "")
                    } else {
                        views.setTextViewText(ids.third, "--%")
                        views.setTextViewText(ids.fourth, "")
                    }
                } else {
                    views.setViewVisibility(containerId, View.GONE)
                }
            }
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

            // Wire precip probability click to toggle precipitation graph
            val precipIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_PRECIP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val precipPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId * 2 + 300, // Unique request code
                precipIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.precip_probability, precipPendingIntent)
        }

        private fun buildHourDataList(
            hourlyForecasts: List<HourlyForecastEntity>,
            centerTime: LocalDateTime,
            numColumns: Int,
            displaySource: String
        ): List<HourlyGraphRenderer.HourData> {
            val hours = mutableListOf<HourlyGraphRenderer.HourData>()
            val now = LocalDateTime.now()

            // Group by dateTime and prefer the selected source, fallback to generic gap
            val sourceName = if (displaySource == "NWS") "NWS" else "OPEN_METEO"
            val forecastsByTime = hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    val preferred = entry.value.find { it.source == sourceName }
                    val gap = entry.value.find { it.source == WidgetStateManager.SOURCE_GENERIC_GAP }
                    val fallback = entry.value.firstOrNull()
                    val chosen = preferred ?: gap ?: fallback
                    if (preferred == null && chosen != null) {
                        Log.d(TAG, "buildHourDataList: FALLBACK for ${entry.key}: wanted=$sourceName, got=${chosen.source} temp=${chosen.temperature} (available: ${entry.value.map { "${it.source}:${it.temperature}" }})")
                    }
                    chosen
                }

            // Determine how many hours to show (24 total, with "now" at 1/3 position)
            // 8 hours history + 16 hours forecast
            // Round to nearest hour (if >= 30 min, round up)
            val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
            val startHour = alignedCenter.minusHours(8)
            val endHour = alignedCenter.plusHours(16)

            // Label every 4 hours (~6 labels across 24h span)
            // The renderer's minHourLabelSpacing (28dp) still acts as a safety valve on narrow widgets
            val labelInterval = 4

            var currentHour = startHour
            var hourIndex = 0
            
            // Debug logging for time range
            Log.d(TAG, "buildHourDataList: now=$now, centerTime=$centerTime, alignedCenter=$alignedCenter")
            Log.d(TAG, "buildHourDataList: startHour=$startHour, endHour=$endHour (${startHour.hour}:00 to ${endHour.hour}:00)")
            Log.d(TAG, "buildHourDataList: forecastsByTime has ${forecastsByTime.size} entries, labelInterval=$labelInterval")
            Log.d(TAG, "buildHourDataList: forecastsByTime keys=${forecastsByTime.keys.sorted()}")

            val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
            val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

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

                    val isNight = SunPositionUtils.isNight(currentHour, lat, lon)
                    val iconRes = WeatherIconMapper.getIconResource(forecast.condition, isNight)
                    val isSunny = iconRes == R.drawable.ic_weather_clear ||
                        iconRes == R.drawable.ic_weather_partly_cloudy ||
                        iconRes == R.drawable.ic_weather_mostly_clear
                    val isRainy = iconRes == R.drawable.ic_weather_rain ||
                        iconRes == R.drawable.ic_weather_storm
                    val isMixed = iconRes == R.drawable.ic_weather_mostly_cloudy ||
                        iconRes == R.drawable.ic_weather_partly_cloudy ||
                        iconRes == R.drawable.ic_weather_mostly_clear

                    hours.add(
                        HourlyGraphRenderer.HourData(
                            dateTime = currentHour,
                            temperature = forecast.temperature,
                            label = formatHourLabel(currentHour),
                            iconRes = iconRes,
                            isNight = isNight,
                            isSunny = isSunny,
                            isRainy = isRainy,
                            isMixed = isMixed,
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
            // Group by dateTime and prefer the selected source, fallback to generic gap
            val sourceName = if (displaySource == "NWS") "NWS" else "OPEN_METEO"
            val forecastsByTime = hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    entry.value.find { it.source == sourceName } ?: entry.value.find { it.source == WidgetStateManager.SOURCE_GENERIC_GAP } ?: entry.value.firstOrNull()
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
                        val weatherHigh = weather.highTemp
                        val forecastHigh = forecast.highTemp
                        val weatherLow = weather.lowTemp
                        val forecastLow = forecast.lowTemp

                        if (weatherHigh != null && forecastHigh != null) {
                            errors.add(kotlin.math.abs(weatherHigh - forecastHigh))
                        }
                        if (weatherLow != null && forecastLow != null) {
                            errors.add(kotlin.math.abs(weatherLow - forecastLow))
                        }
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
