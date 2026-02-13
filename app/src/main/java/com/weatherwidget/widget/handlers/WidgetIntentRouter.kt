package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Router for handling widget intent actions.
 * Delegates to appropriate handlers based on the action type.
 */
object WidgetIntentRouter {
    private const val TAG = "WidgetIntentRouter"

    // Intent actions
    const val ACTION_NAV_LEFT = "com.weatherwidget.ACTION_NAV_LEFT"
    const val ACTION_NAV_RIGHT = "com.weatherwidget.ACTION_NAV_RIGHT"
    const val ACTION_TOGGLE_API = "com.weatherwidget.ACTION_TOGGLE_API"
    const val ACTION_TOGGLE_VIEW = "com.weatherwidget.ACTION_TOGGLE_VIEW"
    const val ACTION_TOGGLE_PRECIP = "com.weatherwidget.ACTION_TOGGLE_PRECIP"
    const val ACTION_SET_VIEW = "com.weatherwidget.ACTION_SET_VIEW"
    const val EXTRA_TARGET_VIEW = "com.weatherwidget.EXTRA_TARGET_VIEW"

    /**
     * Handle navigation (left/right) action.
     */
    suspend fun handleNavigation(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
    ) {
        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)

        if (viewMode == com.weatherwidget.widget.ViewMode.HOURLY ||
            viewMode == com.weatherwidget.widget.ViewMode.PRECIPITATION
        ) {
            handleHourlyNavigation(context, appWidgetId, isLeft)
        } else {
            handleDailyNavigation(context, appWidgetId, isLeft)
        }
    }

    /**
     * Handle daily view navigation.
     */
    private suspend fun handleDailyNavigation(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
    ) {
        val stateManager = WidgetStateManager(context)
        val currentOffset = stateManager.getDateOffset(appWidgetId)
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)

        val database = WeatherDatabase.getDatabase(context)
        val weatherDao = database.weatherDao()
        val snapshotDao = database.forecastSnapshotDao()
        val hourlyDao = database.hourlyForecastDao()

        val latestWeather = weatherDao.getLatestWeather()
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val thirtyDays = LocalDate.now().plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val weatherList = weatherDao.getWeatherRange(historyStart, thirtyDays, lat, lon)

        val filteredWeatherList =
            weatherList.filter {
                it.source == displaySource.id || it.source == com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id
            }
                .groupBy { it.date }
                .map { (_, items) -> items.find { it.source == displaySource.id } ?: items.first() }
        val weatherByDate = filteredWeatherList.associateBy { it.date }

        val today = LocalDate.now()
        val currentCenterDate = today.plusDays(currentOffset.toLong())

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val (numColumns, _) = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)

        val minOffset = com.weatherwidget.util.NavigationUtils.getMinOffset(numColumns)
        val maxOffset = com.weatherwidget.util.NavigationUtils.getMaxOffset(numColumns)

        val availableDates =
            weatherByDate.filter { (_, weather) ->
                weather.highTemp != null || weather.lowTemp != null
            }.keys.map { LocalDate.parse(it) }.sorted()

        val minDate = availableDates.firstOrNull()
        val maxDate = availableDates.lastOrNull()

        val canNavigate =
            if (isLeft) {
                val newLeftmost = currentCenterDate.minusDays(1).plusDays(minOffset.toLong())
                minDate != null && !minDate.isAfter(newLeftmost)
            } else {
                val newRightmost = currentCenterDate.plusDays(1).plusDays(maxOffset.toLong())
                maxDate != null && !maxDate.isBefore(newRightmost)
            }

        Log.d(TAG, "handleDailyNavigation: widgetId=$appWidgetId, canNavigate=$canNavigate")

        if (!canNavigate) {
            return
        }

        val newOffset =
            if (isLeft) {
                stateManager.navigateLeft(appWidgetId)
            } else {
                stateManager.navigateRight(appWidgetId)
            }
        Log.d(TAG, "handleDailyNavigation: Navigated to offset $newOffset for widget $appWidgetId")

        val forecastSnapshots =
            snapshotDao.getForecastsInRange(historyStart, thirtyDays, lat, lon)
                .groupBy { it.targetDate }

        val now = LocalDateTime.now()
        val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

        withContext(Dispatchers.Main) {
            DailyViewHandler.updateWidget(
                context,
                appWidgetManager,
                appWidgetId,
                weatherList,
                forecastSnapshots,
                hourlyForecasts,
            )
        }
    }

    /**
     * Handle hourly/precipitation view navigation.
     */
    private suspend fun handleHourlyNavigation(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
    ) {
        val stateManager = WidgetStateManager(context)

        val newOffset =
            if (isLeft) {
                stateManager.navigateHourlyLeft(appWidgetId)
            } else {
                stateManager.navigateHourlyRight(appWidgetId)
            }
        Log.d(TAG, "handleHourlyNavigation: Navigated to offset $newOffset for widget $appWidgetId")

        val database = WeatherDatabase.getDatabase(context)
        val hourlyDao = database.hourlyForecastDao()
        val weatherDao = database.weatherDao()

        val latestWeather = weatherDao.getLatestWeather()
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val now = LocalDateTime.now()
        val centerTime = now.plusHours(newOffset.toLong())
        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val roundedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        val startTime = roundedCenter.minusHours(8).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val endTime = roundedCenter.plusHours(16).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))

        val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val viewMode = stateManager.getViewMode(appWidgetId)
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val todayPrecip =
            weatherDao.getWeatherForDateBySource(todayStr, lat, lon, displaySource.id)
                ?.precipProbability

        withContext(Dispatchers.Main) {
            if (viewMode == com.weatherwidget.widget.ViewMode.PRECIPITATION) {
                PrecipViewHandler.updateWidget(
                    context,
                    appWidgetManager,
                    appWidgetId,
                    hourlyForecasts,
                    centerTime,
                    todayPrecip,
                )
            } else {
                HourlyViewHandler.updateWidget(
                    context,
                    appWidgetManager,
                    appWidgetId,
                    hourlyForecasts,
                    centerTime,
                    todayPrecip,
                )
            }
        }
    }

    /**
     * Handle API source toggle action.
     */
    suspend fun handleToggleApi(
        context: Context,
        appWidgetId: Int,
    ) {
        val stateManager = WidgetStateManager(context)
        val newSource = stateManager.toggleDisplaySource(appWidgetId)
        val viewMode = stateManager.getViewMode(appWidgetId)
        Log.d(TAG, "handleToggleApi: Toggled to $newSource for widget $appWidgetId, viewMode=$viewMode")

        val database = WeatherDatabase.getDatabase(context)
        val weatherDao = database.weatherDao()
        val snapshotDao = database.forecastSnapshotDao()
        val hourlyDao = database.hourlyForecastDao()

        val latestWeather = weatherDao.getLatestWeather()
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val appWidgetManager = AppWidgetManager.getInstance(context)

        if (viewMode == com.weatherwidget.widget.ViewMode.HOURLY ||
            viewMode == com.weatherwidget.widget.ViewMode.PRECIPITATION
        ) {
            val now = LocalDateTime.now()
            val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
            val centerTime = now.plusHours(hourlyOffset.toLong())
            val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            val roundedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
            val startTime = roundedCenter.minusHours(8).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = roundedCenter.plusHours(16).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val todayPrecip =
                weatherDao.getWeatherForDateBySource(todayStr, lat, lon, newSource.id)
                    ?.precipProbability

            withContext(Dispatchers.Main) {
                if (viewMode == com.weatherwidget.widget.ViewMode.PRECIPITATION) {
                    PrecipViewHandler.updateWidget(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime, todayPrecip)
                } else {
                    HourlyViewHandler.updateWidget(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime, todayPrecip)
                }
            }
        } else {
            val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val twoWeeks = LocalDate.now().plusDays(14).format(DateTimeFormatter.ISO_LOCAL_DATE)

            val weatherList = weatherDao.getWeatherRange(historyStart, twoWeeks, lat, lon)
            val forecastSnapshots =
                snapshotDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                    .groupBy { it.targetDate }

            val now = LocalDateTime.now()
            val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

            withContext(Dispatchers.Main) {
                DailyViewHandler.updateWidget(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts)
            }
        }
    }

    /**
     * Handle view mode toggle action.
     */
    suspend fun handleToggleView(
        context: Context,
        appWidgetId: Int,
    ) {
        val stateManager = WidgetStateManager(context)
        val newMode = stateManager.toggleViewMode(appWidgetId)
        Log.d(TAG, "handleToggleView: Toggled to $newMode for widget $appWidgetId")

        val database = WeatherDatabase.getDatabase(context)
        val weatherDao = database.weatherDao()
        val hourlyDao = database.hourlyForecastDao()
        val snapshotDao = database.forecastSnapshotDao()

        val latestWeather = weatherDao.getLatestWeather()
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val appWidgetManager = AppWidgetManager.getInstance(context)

        if (newMode == com.weatherwidget.widget.ViewMode.HOURLY) {
            val now = LocalDateTime.now()
            val startTime = now.minusHours(8).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = now.plusHours(16).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
            val todayPrecip =
                weatherDao.getWeatherForDateBySource(todayStr, lat, lon, displaySource.id)
                    ?.precipProbability

            withContext(Dispatchers.Main) {
                HourlyViewHandler.updateWidget(context, appWidgetManager, appWidgetId, hourlyForecasts, now, todayPrecip)
            }
        } else {
            val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val twoWeeks = LocalDate.now().plusDays(14).format(DateTimeFormatter.ISO_LOCAL_DATE)

            val weatherList = weatherDao.getWeatherRange(historyStart, twoWeeks, lat, lon)
            val forecastSnapshots =
                snapshotDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                    .groupBy { it.targetDate }

            val now = LocalDateTime.now()
            val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

            withContext(Dispatchers.Main) {
                DailyViewHandler.updateWidget(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts)
            }
        }
    }

    /**
     * Handle precipitation mode toggle action.
     */
    suspend fun handleTogglePrecip(
        context: Context,
        appWidgetId: Int,
    ) {
        val stateManager = WidgetStateManager(context)
        val newMode = stateManager.togglePrecipitationMode(appWidgetId)
        Log.d(TAG, "handleTogglePrecip: Toggled to $newMode for widget $appWidgetId")

        val database = WeatherDatabase.getDatabase(context)
        val weatherDao = database.weatherDao()
        val hourlyDao = database.hourlyForecastDao()
        val snapshotDao = database.forecastSnapshotDao()

        val latestWeather = weatherDao.getLatestWeather()
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val appWidgetManager = AppWidgetManager.getInstance(context)

        if (newMode == com.weatherwidget.widget.ViewMode.PRECIPITATION) {
            val now = LocalDateTime.now()
            val startTime = now.minusHours(8).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = now.plusHours(16).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
            val todayPrecip =
                weatherDao.getWeatherForDateBySource(todayStr, lat, lon, displaySource.id)
                    ?.precipProbability

            withContext(Dispatchers.Main) {
                PrecipViewHandler.updateWidget(context, appWidgetManager, appWidgetId, hourlyForecasts, now, todayPrecip)
            }
        } else {
            val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val twoWeeks = LocalDate.now().plusDays(14).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val weatherList = weatherDao.getWeatherRange(historyStart, twoWeeks, lat, lon)
            val forecastSnapshots =
                snapshotDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                    .groupBy { it.targetDate }
            val now = LocalDateTime.now()
            val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

            withContext(Dispatchers.Main) {
                DailyViewHandler.updateWidget(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts)
            }
        }
    }

    /**
     * Handle set view mode action.
     */
    suspend fun handleSetView(
        context: Context,
        appWidgetId: Int,
        targetMode: com.weatherwidget.widget.ViewMode,
    ) {
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, targetMode)
        if (targetMode == com.weatherwidget.widget.ViewMode.HOURLY ||
            targetMode == com.weatherwidget.widget.ViewMode.PRECIPITATION
        ) {
            stateManager.setHourlyOffset(appWidgetId, 0)
        }
        Log.d(TAG, "handleSetView: Set to $targetMode for widget $appWidgetId")

        val database = WeatherDatabase.getDatabase(context)
        val weatherDao = database.weatherDao()
        val hourlyDao = database.hourlyForecastDao()
        val snapshotDao = database.forecastSnapshotDao()

        val latestWeather = weatherDao.getLatestWeather()
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val appWidgetManager = AppWidgetManager.getInstance(context)

        when (targetMode) {
            com.weatherwidget.widget.ViewMode.HOURLY -> {
                val now = LocalDateTime.now()
                val startTime = now.minusHours(8).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                val endTime = now.plusHours(16).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)
                val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
                val todayPrecip =
                    weatherDao.getWeatherForDateBySource(todayStr, lat, lon, displaySource.id)
                        ?.precipProbability

                withContext(Dispatchers.Main) {
                    HourlyViewHandler.updateWidget(context, appWidgetManager, appWidgetId, hourlyForecasts, now, todayPrecip)
                }
            }
            com.weatherwidget.widget.ViewMode.PRECIPITATION -> {
                val now = LocalDateTime.now()
                val startTime = now.minusHours(8).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                val endTime = now.plusHours(16).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)
                val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
                val todayPrecip =
                    weatherDao.getWeatherForDateBySource(todayStr, lat, lon, displaySource.id)
                        ?.precipProbability

                withContext(Dispatchers.Main) {
                    PrecipViewHandler.updateWidget(context, appWidgetManager, appWidgetId, hourlyForecasts, now, todayPrecip)
                }
            }
            com.weatherwidget.widget.ViewMode.DAILY -> {
                val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
                val twoWeeks = LocalDate.now().plusDays(14).format(DateTimeFormatter.ISO_LOCAL_DATE)
                val weatherList = weatherDao.getWeatherRange(historyStart, twoWeeks, lat, lon)
                val forecastSnapshots =
                    snapshotDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                        .groupBy { it.targetDate }
                val now = LocalDateTime.now()
                val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

                withContext(Dispatchers.Main) {
                    DailyViewHandler.updateWidget(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts)
                }
            }
        }
    }

    /**
     * Handle widget resize.
     */
    suspend fun handleResize(
        context: Context,
        appWidgetId: Int,
    ) {
        Log.d(TAG, "handleResize: Updating widget $appWidgetId after resize")

        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)

        val database = WeatherDatabase.getDatabase(context)
        val weatherDao = database.weatherDao()
        val snapshotDao = database.forecastSnapshotDao()
        val hourlyDao = database.hourlyForecastDao()

        val latestWeather = weatherDao.getLatestWeather()
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val appWidgetManager = AppWidgetManager.getInstance(context)

        if (viewMode == com.weatherwidget.widget.ViewMode.HOURLY ||
            viewMode == com.weatherwidget.widget.ViewMode.PRECIPITATION
        ) {
            val now = LocalDateTime.now()
            val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
            val centerTime = now.plusHours(hourlyOffset.toLong())
            val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            val roundedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
            val startTime = roundedCenter.minusHours(8).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endTime = roundedCenter.plusHours(16).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTime, endTime, lat, lon)

            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
            val todayPrecip =
                weatherDao.getWeatherForDateBySource(todayStr, lat, lon, displaySource.id)
                    ?.precipProbability

            withContext(Dispatchers.Main) {
                if (viewMode == com.weatherwidget.widget.ViewMode.PRECIPITATION) {
                    PrecipViewHandler.updateWidget(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime, todayPrecip)
                } else {
                    HourlyViewHandler.updateWidget(context, appWidgetManager, appWidgetId, hourlyForecasts, centerTime, todayPrecip)
                }
            }
        } else {
            val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val twoWeeks = LocalDate.now().plusDays(14).format(DateTimeFormatter.ISO_LOCAL_DATE)

            val weatherList = weatherDao.getWeatherRange(historyStart, twoWeeks, lat, lon)
            val forecastSnapshots =
                snapshotDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                    .groupBy { it.targetDate }

            val now = LocalDateTime.now()
            val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

            withContext(Dispatchers.Main) {
                DailyViewHandler.updateWidget(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts)
            }
        }
    }
}
