package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.widget.BatteryFetchStrategy
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Router for handling widget intent actions.
 * Delegates to appropriate handlers based on the action type.
 */
object WidgetIntentRouter {
    private const val TAG = "WidgetIntentRouter"
    private val HOUR_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")
    @Volatile
    private var disableRefreshForTesting = false

    // Intent actions
    const val ACTION_NAV_LEFT = "com.weatherwidget.ACTION_NAV_LEFT"
    const val ACTION_NAV_RIGHT = "com.weatherwidget.ACTION_NAV_RIGHT"
    const val ACTION_TOGGLE_API = "com.weatherwidget.ACTION_TOGGLE_API"
    const val ACTION_TOGGLE_VIEW = "com.weatherwidget.ACTION_TOGGLE_VIEW"
    const val ACTION_TOGGLE_PRECIP = "com.weatherwidget.ACTION_TOGGLE_PRECIP"
    const val ACTION_CYCLE_ZOOM = "com.weatherwidget.ACTION_CYCLE_ZOOM"
    const val ACTION_SET_VIEW = "com.weatherwidget.ACTION_SET_VIEW"
    const val ACTION_SHOW_TOAST = "com.weatherwidget.ACTION_SHOW_TOAST"
    const val EXTRA_TARGET_VIEW = "com.weatherwidget.EXTRA_TARGET_VIEW"
    const val EXTRA_TOAST_MESSAGE = "com.weatherwidget.EXTRA_TOAST_MESSAGE"

    fun setDisableRefreshForTesting(disabled: Boolean) {
        disableRefreshForTesting = disabled
    }

    /**
     * Handle navigation (left/right) action.
     */
    suspend fun handleNavigation(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
    ) {
        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)

        val direction = if (isLeft) "LEFT" else "RIGHT"
        Log.d(TAG, "handleNavigation: widget=$appWidgetId, direction=$direction, viewMode=$viewMode")

        if (viewMode == com.weatherwidget.widget.ViewMode.TEMPERATURE ||
            viewMode == com.weatherwidget.widget.ViewMode.PRECIPITATION ||
            viewMode == com.weatherwidget.widget.ViewMode.CLOUD_COVER
        ) {
            handleGraphNavigation(context, appWidgetId, isLeft)
        } else {
            handleDailyNavigation(context, appWidgetId, isLeft, repository)
        }
    }

    /**
     * Handle daily view navigation.
     */
    private suspend fun handleDailyNavigation(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
    ) {
        val stateManager = WidgetStateManager(context)
        val currentOffset = stateManager.getDateOffset(appWidgetId)
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)

        val database = WeatherDatabase.getDatabase(context)
        val forecastDao = database.forecastDao()
        val snapshotDao = database.forecastDao()
        val hourlyDao = database.hourlyForecastDao()
        val appLogDao = database.appLogDao()

        val latestWeather = forecastDao.getLatestWeather()
        refreshIfStale(context, latestWeather?.fetchedAt, "daily_nav")
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val thirtyDays = LocalDate.now().plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val weatherList = forecastDao.getForecastsInRange(historyStart, thirtyDays, lat, lon)

        val filteredWeatherList =
            weatherList.filter {
                it.source == displaySource.id || it.source == com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id
            }
                .groupBy { it.targetDate }
                .map { (_, items) -> items.find { it.source == displaySource.id } ?: items.first() }
        val weatherByDate = filteredWeatherList.associateBy { it.targetDate }

        val today = LocalDate.now()
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val (numColumns, _) = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val isEveningMode = NavigationUtils.isEveningMode()

        val availableForecastDates = weatherList.map { it.targetDate }.toSet()
        
        val dailyActuals = getDailyActuals(database, lat, lon)
        val availableObsDates = dailyActuals.keys
        
        val availableDates = (availableForecastDates + availableObsDates)
            .map { LocalDate.parse(it) }
            .distinct()
            .sorted()

        val minDate = availableDates.firstOrNull()
        val maxDate = availableDates.lastOrNull()

        val canNavigate: Boolean
        val navDebug: String
        if (isLeft) {
            val (newLeftmost, _) =
                NavigationUtils.getVisibleDateRange(
                    today = today,
                    dateOffset = currentOffset - 1,
                    numColumns = numColumns,
                    isEveningMode = isEveningMode,
                )
            canNavigate = minDate != null && minDate.isBefore(newLeftmost.plusDays(1))
            navDebug = "LEFT: newLeftmost=$newLeftmost, minDate=$minDate"
        } else {
            val (_, newRightmost) =
                NavigationUtils.getVisibleDateRange(
                    today = today,
                    dateOffset = currentOffset + 1,
                    numColumns = numColumns,
                    isEveningMode = isEveningMode,
                )
            canNavigate = maxDate != null && maxDate.isAfter(newRightmost.minusDays(1))
            navDebug = "RIGHT: newRightmost=$newRightmost, maxDate=$maxDate"
        }

        Log.d(TAG, "handleDailyNavigation: widget=$appWidgetId, offset=$currentOffset, " +
            "cols=$numColumns, evening=$isEveningMode, source=${displaySource.id}, " +
            "dates=${availableDates.size}(${minDate}..${maxDate}), " +
            "$navDebug, canNavigate=$canNavigate")
        appLogDao.log(
            "DAILY_NAV_ATTEMPT",
            "widget=$appWidgetId dir=${if (isLeft) "LEFT" else "RIGHT"} offset=$currentOffset cols=$numColumns rows=${WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId).rows} evening=$isEveningMode source=${displaySource.id} minDate=$minDate maxDate=$maxDate $navDebug canNavigate=$canNavigate"
        )

        if (!canNavigate) {
            appLogDao.log(
                "DAILY_NAV_BLOCKED",
                "widget=$appWidgetId dir=${if (isLeft) "LEFT" else "RIGHT"} offset=$currentOffset cols=$numColumns evening=$isEveningMode source=${displaySource.id} minDate=$minDate maxDate=$maxDate"
            )
            return
        }

        val newOffset =
            if (isLeft) {
                stateManager.navigateLeft(appWidgetId)
            } else {
                stateManager.navigateRight(appWidgetId)
            }
        Log.d(TAG, "handleDailyNavigation: Navigated to offset $newOffset for widget $appWidgetId")
        appLogDao.log(
            "DAILY_NAV_APPLY",
            "widget=$appWidgetId dir=${if (isLeft) "LEFT" else "RIGHT"} offset=$currentOffset->$newOffset source=${displaySource.id}"
        )

        val forecastSnapshots =
            forecastDao.getForecastsInRange(historyStart, thirtyDays, lat, lon)
                .groupBy { it.targetDate }

        val now = LocalDateTime.now()
        val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val ctCurrentTemps = database.currentTempDao().getCurrentTemps(todayStr, lat, lon)

        DailyViewHandler.updateWidget(
            context,
            appWidgetManager,
            appWidgetId,
            weatherList,
            forecastSnapshots,
            hourlyForecasts,
            ctCurrentTemps,
            dailyActuals,
        )
    }

    /**
     * Handle hourly/precipitation view navigation.
     */
    private suspend fun handleGraphNavigation(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
    ) {
        val stateManager = WidgetStateManager(context)

        val newOffset =
            if (isLeft) {
                stateManager.navigateHourlyLeft(appWidgetId)
            } else {
                stateManager.navigateHourlyRight(appWidgetId)
            }
        Log.d(TAG, "handleGraphNavigation: Navigated to offset $newOffset for widget $appWidgetId")

        val database = WeatherDatabase.getDatabase(context)
        val hourlyDao = database.hourlyForecastDao()
        val forecastDao = database.forecastDao()

        val latestWeather = forecastDao.getLatestWeather()
        refreshIfStale(context, latestWeather?.fetchedAt, "graph_nav")
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val zoom = stateManager.getZoomLevel(appWidgetId)
        val now = LocalDateTime.now()
        val centerTime = now.plusHours(newOffset.toLong())
        val hourlyForecasts =
            loadGraphWindowHourlyForecasts(
                hourlyDao = hourlyDao,
                lat = lat,
                lon = lon,
                centerTime = centerTime,
                zoom = zoom,
                now = now,
            )

        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        updateHourlyViewWithData(context, appWidgetId, hourlyForecasts, centerTime, displaySource, lat, lon, repository)
    }

    /**
     * Handle zoom level cycle action.
     */
    suspend fun handleCycleZoom(
        context: Context,
        appWidgetId: Int,
        zoomCenterOffset: Int? = null,
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
    ) {
        val stateManager = WidgetStateManager(context)
        val oldZoom = stateManager.getZoomLevel(appWidgetId)
        val newZoom = stateManager.cycleZoomLevel(appWidgetId)

        // When zooming IN (WIDE→NARROW) and a center offset was provided, re-center
        if (oldZoom == com.weatherwidget.widget.ZoomLevel.WIDE &&
            newZoom == com.weatherwidget.widget.ZoomLevel.NARROW &&
            zoomCenterOffset != null
        ) {
            // The zoomCenterOffset is the pre-calculated absolute offset of the tapped zone.
            stateManager.setHourlyOffset(appWidgetId, zoomCenterOffset)
            Log.d(TAG, "handleCycleZoom: Re-centered to absolute offset $zoomCenterOffset for widget $appWidgetId")
        }
        Log.d(TAG, "handleCycleZoom: Cycled $oldZoom→$newZoom for widget $appWidgetId")

        val database = WeatherDatabase.getDatabase(context)
        val hourlyDao = database.hourlyForecastDao()
        val forecastDao = database.forecastDao()

        val latestWeather = forecastDao.getLatestWeather()
        refreshIfStale(context, latestWeather?.fetchedAt, "cycle_zoom")
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val now = LocalDateTime.now()
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
        val centerTime = now.plusHours(hourlyOffset.toLong())
        val hourlyForecasts =
            loadGraphWindowHourlyForecasts(
                hourlyDao = hourlyDao,
                lat = lat,
                lon = lon,
                centerTime = centerTime,
                zoom = newZoom,
                now = now,
            )

        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        updateHourlyViewWithData(context, appWidgetId, hourlyForecasts, centerTime, displaySource, lat, lon, repository)
    }

    /**
     * Handle API source toggle action.
     */
    suspend fun handleToggleApi(
        context: Context,
        appWidgetId: Int,
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
    ) {
        val stateManager = WidgetStateManager(context)
        val newSource = stateManager.toggleDisplaySource(appWidgetId)
        val viewMode = stateManager.getViewMode(appWidgetId)
        Log.d(TAG, "handleToggleApi: Toggled to $newSource for widget $appWidgetId, viewMode=$viewMode")

        val database = WeatherDatabase.getDatabase(context)
        val forecastDao = database.forecastDao()
        val snapshotDao = database.forecastDao()
        val hourlyDao = database.hourlyForecastDao()

        val latestWeather = forecastDao.getLatestWeather()
        refreshIfStale(context, latestWeather?.fetchedAt, "daily_nav")
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val now = LocalDateTime.now()
        val currentGraphZoom =
            if (viewMode == com.weatherwidget.widget.ViewMode.TEMPERATURE ||
                viewMode == com.weatherwidget.widget.ViewMode.PRECIPITATION ||
                viewMode == com.weatherwidget.widget.ViewMode.CLOUD_COVER
            ) {
                stateManager.getZoomLevel(appWidgetId)
            } else {
                null
            }
        val currentGraphCenterTime =
            if (currentGraphZoom != null) {
                val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
                now.plusHours(hourlyOffset.toLong())
            } else {
                null
            }
        val missingDataForSelectedSource =
            sourceDataMissingForCurrentWindow(
                forecastDao = forecastDao,
                hourlyDao = hourlyDao,
                lat = lat,
                lon = lon,
                source = newSource,
                centerTime = currentGraphCenterTime,
                zoom = currentGraphZoom,
                now = now,
            )

        if (viewMode == com.weatherwidget.widget.ViewMode.TEMPERATURE ||
            viewMode == com.weatherwidget.widget.ViewMode.PRECIPITATION ||
            viewMode == com.weatherwidget.widget.ViewMode.CLOUD_COVER
        ) {
            val zoom = currentGraphZoom ?: stateManager.getZoomLevel(appWidgetId)
            val centerTime =
                currentGraphCenterTime
                    ?: now.plusHours(stateManager.getHourlyOffset(appWidgetId).toLong())
            val hourlyForecasts =
                loadGraphWindowHourlyForecasts(
                    hourlyDao = hourlyDao,
                    lat = lat,
                    lon = lon,
                    centerTime = centerTime,
                    zoom = zoom,
                    now = now,
                )

            updateHourlyViewWithData(context, appWidgetId, hourlyForecasts, centerTime, newSource, lat, lon, repository)
        } else {
            val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val twoWeeks = LocalDate.now().plusDays(14).format(DateTimeFormatter.ISO_LOCAL_DATE)

            val weatherList = forecastDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
            val forecastSnapshots =
                forecastDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                    .groupBy { it.targetDate }

            val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

            val ctTodayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val ctCurrentTemps = database.currentTempDao().getCurrentTemps(ctTodayStr, lat, lon)
            val dailyActuals = getDailyActuals(database, lat, lon)
            DailyViewHandler.updateWidget(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts, ctCurrentTemps, dailyActuals, repository)
        }

        if (missingDataForSelectedSource) {
            Log.d(TAG, "handleToggleApi: Missing cached data for $newSource, enqueueing forced refresh")
            enqueueForcedRefresh(context)
        }
    }

    /**
     * Handle view mode toggle action.
     */
    suspend fun handleToggleView(
        context: Context,
        appWidgetId: Int,
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
    ) {
        val stateManager = WidgetStateManager(context)
        val newMode = stateManager.toggleViewMode(appWidgetId)
        Log.d(TAG, "handleToggleView: Toggled to $newMode for widget $appWidgetId")

        val database = WeatherDatabase.getDatabase(context)
        val forecastDao = database.forecastDao()
        val hourlyDao = database.hourlyForecastDao()
        val snapshotDao = database.forecastDao()

        val latestWeather = forecastDao.getLatestWeather()
        refreshIfStale(context, latestWeather?.fetchedAt, "daily_nav")
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val appWidgetManager = AppWidgetManager.getInstance(context)

        if (newMode == com.weatherwidget.widget.ViewMode.TEMPERATURE) {
            val zoom = stateManager.getZoomLevel(appWidgetId)
            val now = LocalDateTime.now()
            val offset = stateManager.getHourlyOffset(appWidgetId)
            val centerTime = now.plusHours(offset.toLong())
            val hourlyForecasts =
                loadGraphWindowHourlyForecasts(
                    hourlyDao = hourlyDao,
                    lat = lat,
                    lon = lon,
                    centerTime = centerTime,
                    zoom = zoom,
                    now = now,
                )

            val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
            updateHourlyViewWithData(context, appWidgetId, hourlyForecasts, centerTime, displaySource, lat, lon, repository)
        } else {
            val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val twoWeeks = LocalDate.now().plusDays(14).format(DateTimeFormatter.ISO_LOCAL_DATE)

            val weatherList = forecastDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
            val forecastSnapshots =
                forecastDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                    .groupBy { it.targetDate }

            val now = LocalDateTime.now()
            val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

            val ctTodayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val ctCurrentTemps = database.currentTempDao().getCurrentTemps(ctTodayStr, lat, lon)
            val dailyActuals = getDailyActuals(database, lat, lon)
            DailyViewHandler.updateWidget(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts, ctCurrentTemps, dailyActuals, repository)
        }
    }

    private suspend fun getDailyActuals(
        database: WeatherDatabase,
        lat: Double,
        lon: Double,
    ): Map<String, com.weatherwidget.widget.ObservationResolver.DailyActual> {
        val local = ZoneId.systemDefault()
        val startTs = LocalDate.now().minusDays(30).atStartOfDay(local).toEpochSecond() * 1000
        val endTs = LocalDate.now().plusDays(1).atStartOfDay(local).toEpochSecond() * 1000
        val observations = database.observationDao().getObservationsInRange(startTs, endTs, lat, lon)
        return com.weatherwidget.widget.ObservationResolver.aggregateObservationsToDaily(observations).associateBy { it.date }
    }

    private suspend fun sourceDataMissingForCurrentWindow(
        forecastDao: com.weatherwidget.data.local.ForecastDao,
        hourlyDao: com.weatherwidget.data.local.HourlyForecastDao,
        lat: Double,
        lon: Double,
        source: WeatherSource,
        centerTime: LocalDateTime? = null,
        zoom: com.weatherwidget.widget.ZoomLevel? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val futureEnd = LocalDate.now().plusDays(14).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val sourceDaily = forecastDao.getForecastsInRangeBySource(historyStart, futureEnd, lat, lon, source.id)
        val maxDailyDate =
            sourceDaily.mapNotNull {
                runCatching { LocalDate.parse(it.targetDate) }.getOrNull()
            }.maxOrNull()
        val hasRequiredFutureCoverage = maxDailyDate != null && !maxDailyDate.isBefore(LocalDate.now().plusDays(2))

        val sourceHourly =
            if (centerTime != null && zoom != null) {
                loadGraphWindowHourlyForecastsBySource(
                    hourlyDao = hourlyDao,
                    lat = lat,
                    lon = lon,
                    centerTime = centerTime,
                    zoom = zoom,
                    now = now,
                    source = source,
                )
            } else {
                val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).format(HOUR_FORMATTER)
                val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).format(HOUR_FORMATTER)
                hourlyDao.getHourlyForecastsBySource(hourlyStart, hourlyEnd, lat, lon, source.id)
            }

        return sourceDaily.isEmpty() || sourceHourly.isEmpty() || !hasRequiredFutureCoverage
    }

    private fun enqueueForcedRefresh(context: Context, reason: String = "manual_refresh") {
        if (disableRefreshForTesting) {
            Log.d(TAG, "Skipping forced refresh in test mode (reason=$reason)")
            return
        }

        val workRequest =
            OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                .setInputData(
                    Data.Builder()
                        .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true)
                        .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, reason)
                        .build(),
                )
                .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WeatherWidgetProvider.WORK_NAME_ONE_TIME,
            ExistingWorkPolicy.REPLACE,
            workRequest,
        )
    }

    private fun refreshIfStale(context: Context, latestFetchedAt: Long?, reason: String) {
        if (disableRefreshForTesting) {
            return
        }
        if (BatteryFetchStrategy.shouldRefreshStaleData(latestFetchedAt, System.currentTimeMillis())) {
            val ageMin = (System.currentTimeMillis() - (latestFetchedAt ?: 0L)) / 1000 / 60
            Log.d(TAG, "STALE_REFRESH: Data is ${ageMin}min old, enqueueing refresh on $reason")
            enqueueForcedRefresh(context, reason = "stale_on_$reason")
        }
    }

    /**
     * Handle precipitation mode toggle action.
     */
    suspend fun handleTogglePrecip(
        context: Context,
        appWidgetId: Int,
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
    ) {
        val stateManager = WidgetStateManager(context)
        val newMode = stateManager.togglePrecipitationMode(appWidgetId)
        Log.d(TAG, "handleTogglePrecip: Toggled to $newMode for widget $appWidgetId")

        val database = WeatherDatabase.getDatabase(context)
        val forecastDao = database.forecastDao()
        val hourlyDao = database.hourlyForecastDao()
        val snapshotDao = database.forecastDao()

        val latestWeather = forecastDao.getLatestWeather()
        refreshIfStale(context, latestWeather?.fetchedAt, "daily_nav")
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val appWidgetManager = AppWidgetManager.getInstance(context)

        if (newMode == com.weatherwidget.widget.ViewMode.PRECIPITATION) {
            val zoom = stateManager.getZoomLevel(appWidgetId)
            val now = LocalDateTime.now()
            val offset = stateManager.getHourlyOffset(appWidgetId)
            val centerTime = now.plusHours(offset.toLong())
            val hourlyForecasts =
                loadGraphWindowHourlyForecasts(
                    hourlyDao = hourlyDao,
                    lat = lat,
                    lon = lon,
                    centerTime = centerTime,
                    zoom = zoom,
                    now = now,
                )

            val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
            updateHourlyViewWithData(context, appWidgetId, hourlyForecasts, centerTime, displaySource, lat, lon, repository)
        } else {
            val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val twoWeeks = LocalDate.now().plusDays(14).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val weatherList = forecastDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
            val forecastSnapshots =
                forecastDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                    .groupBy { it.targetDate }
            val now = LocalDateTime.now()
            val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

            val ctTodayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val ctCurrentTemps = database.currentTempDao().getCurrentTemps(ctTodayStr, lat, lon)
            val dailyActuals = getDailyActuals(database, lat, lon)
            DailyViewHandler.updateWidget(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts, ctCurrentTemps, dailyActuals, repository)
        }
    }

    /**
     * Handle set view mode action.
     */
    suspend fun handleSetView(
        context: Context,
        appWidgetId: Int,
        targetMode: com.weatherwidget.widget.ViewMode,
        targetOffset: Int = Int.MIN_VALUE,
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, targetMode)
        if (targetMode == com.weatherwidget.widget.ViewMode.DAILY) {
            stateManager.setZoomLevel(appWidgetId, com.weatherwidget.widget.ZoomLevel.WIDE)
        }
        if (targetMode == com.weatherwidget.widget.ViewMode.TEMPERATURE ||
            targetMode == com.weatherwidget.widget.ViewMode.PRECIPITATION ||
            targetMode == com.weatherwidget.widget.ViewMode.CLOUD_COVER
        ) {
            if (targetOffset != Int.MIN_VALUE) {
                stateManager.setHourlyOffset(appWidgetId, targetOffset)
            }
        }
        Log.d(TAG, "handleSetView: start mode=$targetMode offset=$targetOffset widget=$appWidgetId")

        val database = WeatherDatabase.getDatabase(context)
        val forecastDao = database.forecastDao()
        val hourlyDao = database.hourlyForecastDao()
        val snapshotDao = database.forecastDao()

        val latestWeather = forecastDao.getLatestWeather()
        refreshIfStale(context, latestWeather?.fetchedAt, "daily_nav")
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val appWidgetManager = AppWidgetManager.getInstance(context)

        val zoom = stateManager.getZoomLevel(appWidgetId)

        when (targetMode) {
            com.weatherwidget.widget.ViewMode.TEMPERATURE,
            com.weatherwidget.widget.ViewMode.PRECIPITATION,
            com.weatherwidget.widget.ViewMode.CLOUD_COVER -> {
                val now = LocalDateTime.now()
                val offset = stateManager.getHourlyOffset(appWidgetId)
                val centerTime = now.plusHours(offset.toLong())
                val hourlyForecasts =
                    loadGraphWindowHourlyForecasts(
                        hourlyDao = hourlyDao,
                        lat = lat,
                        lon = lon,
                        centerTime = centerTime,
                        zoom = zoom,
                        now = now,
                    )
                val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
                updateHourlyViewWithData(context, appWidgetId, hourlyForecasts, centerTime, displaySource, lat, lon, repository)
                Log.d(TAG, "handleSetView: ${targetMode.name} update complete in ${SystemClock.elapsedRealtime() - startMs}ms")
            }
            com.weatherwidget.widget.ViewMode.DAILY -> {
                val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
                val twoWeeks = LocalDate.now().plusDays(14).format(DateTimeFormatter.ISO_LOCAL_DATE)
                val weatherList = forecastDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                val forecastSnapshots =
                    forecastDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                        .groupBy { it.targetDate }
                val now = LocalDateTime.now()
                val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

                val ctTodayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val ctCurrentTemps = database.currentTempDao().getCurrentTemps(ctTodayStr, lat, lon)
                val dailyActuals = getDailyActuals(database, lat, lon)
                DailyViewHandler.updateWidget(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts, ctCurrentTemps, dailyActuals, repository)
                Log.d(TAG, "handleSetView: daily update complete in ${SystemClock.elapsedRealtime() - startMs}ms")
            }
        }
    }

    /**
     * Handle widget resize.
     */
    suspend fun handleResize(
        context: Context,
        appWidgetId: Int,
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
    ) {
        Log.d(TAG, "handleResize: Updating widget $appWidgetId after resize")

        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)

        val database = WeatherDatabase.getDatabase(context)
        val forecastDao = database.forecastDao()
        val snapshotDao = database.forecastDao()
        val hourlyDao = database.hourlyForecastDao()

        val latestWeather = forecastDao.getLatestWeather()
        refreshIfStale(context, latestWeather?.fetchedAt, "daily_nav")
        val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val appWidgetManager = AppWidgetManager.getInstance(context)
        logResizeDiagnostics(context, appWidgetManager, appWidgetId, viewMode.name, database.appLogDao())

        if (viewMode == com.weatherwidget.widget.ViewMode.TEMPERATURE ||
            viewMode == com.weatherwidget.widget.ViewMode.PRECIPITATION ||
            viewMode == com.weatherwidget.widget.ViewMode.CLOUD_COVER
        ) {
            val zoom = stateManager.getZoomLevel(appWidgetId)
            val now = LocalDateTime.now()
            val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
            val centerTime = now.plusHours(hourlyOffset.toLong())
            val hourlyForecasts =
                loadGraphWindowHourlyForecasts(
                    hourlyDao = hourlyDao,
                    lat = lat,
                    lon = lon,
                    centerTime = centerTime,
                    zoom = zoom,
                    now = now,
                )

            val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
            updateHourlyViewWithData(context, appWidgetId, hourlyForecasts, centerTime, displaySource, lat, lon, repository)
        } else {
            val historyStart = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val twoWeeks = LocalDate.now().plusDays(14).format(DateTimeFormatter.ISO_LOCAL_DATE)

            val weatherList = forecastDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
            val forecastSnapshots =
                forecastDao.getForecastsInRange(historyStart, twoWeeks, lat, lon)
                    .groupBy { it.targetDate }

            val now = LocalDateTime.now()
            val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

            val ctTodayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val ctCurrentTemps = database.currentTempDao().getCurrentTemps(ctTodayStr, lat, lon)
            val dailyActuals = getDailyActuals(database, lat, lon)
            DailyViewHandler.updateWidget(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts, ctCurrentTemps, dailyActuals, repository)
        }
    }

    private suspend fun updateHourlyViewWithData(
        context: Context,
        appWidgetId: Int,
        hourlyForecasts: List<com.weatherwidget.data.local.HourlyForecastEntity>,
        centerTime: LocalDateTime,
        displaySource: WeatherSource,
        lat: Double,
        lon: Double,
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
    ) {
        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)
        val appWidgetManager = AppWidgetManager.getInstance(context)

        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val database = WeatherDatabase.getDatabase(context)
        val weatherList = database.forecastDao().getForecastsInRange(todayStr, todayStr, lat, lon)
        val currentTemps = database.currentTempDao().getCurrentTemps(todayStr, lat, lon)

        val todayPrecip = weatherList.find { it.source == displaySource.id }?.precipProbability
        val observation = com.weatherwidget.widget.ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)
        logCurrentTempStalenessDebug(
            database = database,
            appWidgetId = appWidgetId,
            viewMode = viewMode.name,
            displaySource = displaySource,
            observation = observation,
            centerTime = centerTime,
        )

        when (viewMode) {
            com.weatherwidget.widget.ViewMode.PRECIPITATION -> {
                PrecipViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    centerTime = centerTime,
                    displaySource = displaySource,
                    precipProbability = todayPrecip,
                    observedCurrentTemp = observation?.temperature,
                    observedCurrentTempFetchedAt = observation?.observedAt,
                    repository = repository
                )
            }
            com.weatherwidget.widget.ViewMode.CLOUD_COVER -> {
                CloudCoverViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    centerTime = centerTime,
                    displaySource = displaySource,
                    precipProbability = todayPrecip,
                    observedCurrentTemp = observation?.temperature,
                    observedCurrentTempFetchedAt = observation?.observedAt,
                    repository = repository
                )
            }
            else -> {
                TemperatureViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    centerTime = centerTime,
                    displaySource = displaySource,
                    precipProbability = todayPrecip,
                    observedCurrentTemp = observation?.temperature,
                    observedCurrentTempFetchedAt = observation?.observedAt,
                    repository = repository
                )
            }
        }
    }
    private suspend fun logCurrentTempStalenessDebug(
        database: WeatherDatabase,
        appWidgetId: Int,
        viewMode: String,
        displaySource: WeatherSource,
        observation: com.weatherwidget.widget.ObservationResolver.ObservedCurrentTemperature?,
        centerTime: LocalDateTime,
    ) {
        if (viewMode != com.weatherwidget.widget.ViewMode.TEMPERATURE.name) return

        val appLogDao = database.appLogDao()
        val nowMs = System.currentTimeMillis()
        if (observation == null) {
            appLogDao.log(
                "CURR_STALE_DEBUG",
                "widget=$appWidgetId source=${displaySource.id} center=$centerTime observation=none",
                "VERBOSE",
            )
            return
        }

        val observedAgeMin = ((nowMs - observation.observedAt).coerceAtLeast(0L) / 1000.0 / 60.0)
        val fetchAgeMin = ((nowMs - observation.rowFetchedAt).coerceAtLeast(0L) / 1000.0 / 60.0)
        val message =
            "widget=$appWidgetId source=${displaySource.id} selectedSource=${observation.source} " +
                "temp=${String.format("%.1f", observation.temperature)} " +
                "obsAt=${formatEpochLocal(observation.observedAt)} obsAgeMin=${String.format("%.1f", observedAgeMin)} " +
                "rowFetchedAt=${formatEpochLocal(observation.rowFetchedAt)} rowFetchAgeMin=${String.format("%.1f", fetchAgeMin)} " +
                "center=$centerTime"
        appLogDao.log("CURR_STALE_DEBUG", message, "VERBOSE")
    }

    private fun formatEpochLocal(epochMs: Long): String {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }

    private suspend fun logResizeDiagnostics(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        viewMode: String,
        appLogDao: com.weatherwidget.data.local.AppLogDao,
    ) {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 40)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)

        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val graphWidthDp = (dimensions.widthDp - 24).coerceAtLeast(1)
        val graphHeightDp = (dimensions.heightDp - 16).coerceAtLeast(1)
        val rawWidthPx = WidgetSizeCalculator.dpToPx(context, graphWidthDp).coerceAtLeast(1)
        val rawHeightPx = WidgetSizeCalculator.dpToPx(context, graphHeightDp).coerceAtLeast(1)
        val (scaledWidthPx, scaledHeightPx) =
            WidgetSizeCalculator.getOptimalBitmapSize(context, graphWidthDp, graphHeightDp)
        val downscaled = rawWidthPx != scaledWidthPx || rawHeightPx != scaledHeightPx
        val orientation = context.resources.configuration.orientation

        val message =
            "widgetId=$appWidgetId view=$viewMode orient=$orientation " +
                "options=minW:$minWidth,minH:$minHeight,maxW:$maxWidth,maxH:$maxHeight " +
                "calc=cols:${dimensions.cols},rows:${dimensions.rows},widthDp:${dimensions.widthDp},heightDp:${dimensions.heightDp} " +
                "graphDp=${graphWidthDp}x$graphHeightDp rawPx=${rawWidthPx}x$rawHeightPx " +
                "scaledPx=${scaledWidthPx}x$scaledHeightPx downscaled=$downscaled"
        appLogDao.log("WIDGET_RESIZE", message, "VERBOSE")
    }

    private suspend fun loadGraphWindowHourlyForecasts(
        hourlyDao: com.weatherwidget.data.local.HourlyForecastDao,
        lat: Double,
        lon: Double,
        centerTime: LocalDateTime,
        zoom: com.weatherwidget.widget.ZoomLevel,
        now: LocalDateTime,
    ): List<com.weatherwidget.data.local.HourlyForecastEntity> {
        val window = buildGraphQueryWindow(centerTime, zoom, now)
        val centerRows =
            hourlyDao.getHourlyForecasts(
                window.centerStart.format(HOUR_FORMATTER),
                window.centerEnd.format(HOUR_FORMATTER),
                lat,
                lon,
            )

        val nowRows =
            if (window.nowStart != null && window.nowEnd != null) {
                hourlyDao.getHourlyForecasts(
                    window.nowStart.format(HOUR_FORMATTER),
                    window.nowEnd.format(HOUR_FORMATTER),
                    lat,
                    lon,
                )
            } else {
                emptyList()
            }

        return (centerRows + nowRows)
            .distinctBy { "${it.dateTime}|${it.source}|${it.locationLat}|${it.locationLon}" }
            .sortedBy { it.dateTime }
    }

    private suspend fun loadGraphWindowHourlyForecastsBySource(
        hourlyDao: com.weatherwidget.data.local.HourlyForecastDao,
        lat: Double,
        lon: Double,
        centerTime: LocalDateTime,
        zoom: com.weatherwidget.widget.ZoomLevel,
        now: LocalDateTime,
        source: WeatherSource,
    ): List<com.weatherwidget.data.local.HourlyForecastEntity> {
        val window = buildGraphQueryWindow(centerTime, zoom, now)
        val centerRows =
            hourlyDao.getHourlyForecastsBySource(
                window.centerStart.format(HOUR_FORMATTER),
                window.centerEnd.format(HOUR_FORMATTER),
                lat,
                lon,
                source.id,
            )

        val nowRows =
            if (window.nowStart != null && window.nowEnd != null) {
                hourlyDao.getHourlyForecastsBySource(
                    window.nowStart.format(HOUR_FORMATTER),
                    window.nowEnd.format(HOUR_FORMATTER),
                    lat,
                    lon,
                    source.id,
                )
            } else {
                emptyList()
            }

        return (centerRows + nowRows)
            .distinctBy { "${it.dateTime}|${it.source}|${it.locationLat}|${it.locationLon}" }
            .sortedBy { it.dateTime }
    }

    internal fun buildGraphQueryWindow(
        centerTime: LocalDateTime,
        zoom: com.weatherwidget.widget.ZoomLevel,
        now: LocalDateTime,
    ): GraphQueryWindow {
        val truncatedCenter = centerTime.truncatedTo(ChronoUnit.HOURS)
        val roundedCenter = if (centerTime.minute >= 30) truncatedCenter.plusHours(1) else truncatedCenter
        val centerStart = roundedCenter.minusHours(zoom.backHours)
        val centerEnd = roundedCenter.plusHours(zoom.forwardHours)

        val nowStart = now.truncatedTo(ChronoUnit.HOURS)
        val nowEnd = nowStart.plusHours(1)
        val overlaps = !nowEnd.isBefore(centerStart) && !nowStart.isAfter(centerEnd)

        return if (overlaps) {
            GraphQueryWindow(centerStart = centerStart, centerEnd = centerEnd, nowStart = null, nowEnd = null)
        } else {
            GraphQueryWindow(centerStart = centerStart, centerEnd = centerEnd, nowStart = nowStart, nowEnd = nowEnd)
        }
    }

    internal data class GraphQueryWindow(
        val centerStart: LocalDateTime,
        val centerEnd: LocalDateTime,
        val nowStart: LocalDateTime?,
        val nowEnd: LocalDateTime?,
    )
}
