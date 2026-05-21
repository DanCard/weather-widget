package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Router for handling widget intent actions.
 * Delegates to appropriate handlers based on the action type.
 */
object WidgetIntentRouter {
    private const val TAG = "WidgetIntentRouter"
    private const val DAILY_LOOKBACK_DAYS = 30L
    private const val DAILY_FORECAST_DAYS = 30L
    private const val SOURCE_CHECK_FORECAST_DAYS = 14L
    private const val SLOW_THRESHOLD_MS = 200L
    private const val RESIZE_DEBOUNCE_MS = 250L

    // Intent actions — defined in WidgetActions

    @VisibleForTesting
    internal data class LocationResult(
        val lat: Double,
        val lon: Double,
        val fetchedAt: Long?,
    )

    internal data class RefreshContext(
        val database: WeatherDatabase,
        val forecastDao: ForecastDao,
        val location: LocationResult,
    )

    private fun resolveLocation(latestWeather: ForecastEntity?): LocationResult {
        if (latestWeather == null) {
            Log.w(TAG, "resolveLocation: no weather data, falling back to default coordinates")
        }
        return LocationResult(
            lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT,
            lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON,
            fetchedAt = latestWeather?.fetchedAt,
        )
    }

    private suspend fun resolveRefreshContext(
        context: Context,
        staleReason: String,
        appLogDao: AppLogDao? = null,
    ): RefreshContext {
        val database = WeatherDatabase.getDatabase(context)
        val forecastDao = database.forecastDao()
        val latestWeather = forecastDao.getLatestWeather()
        val loc = resolveLocation(latestWeather)
        RefreshScheduler.refreshIfStale(context, loc.fetchedAt, staleReason, appLogDao)
        return RefreshContext(database, forecastDao, loc)
    }

    @VisibleForTesting
    fun setIsRefreshDisabledForTesting(disableRefreshFlag: Boolean) {
        RefreshScheduler.setIsRefreshDisabledForTesting(disableRefreshFlag)
    }

    /**
     * Handle navigation (left/right) action.
     */
suspend fun handleNavigation(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
        repository: WeatherRepository? = null,
    ) {
        try {
            handleNavigationInternal(context, appWidgetId, isLeft, repository)
        } catch (e: Exception) {
            Log.e(TAG, "handleNavigation failed for widget $appWidgetId", e)
        }
    }

    private suspend fun handleNavigationInternal(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
        repository: WeatherRepository? = null,
    ) {
        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)

        val direction = if (isLeft) "LEFT" else "RIGHT"
        Log.d(TAG, "handleNavigation: widget=$appWidgetId, direction=$direction, viewMode=$viewMode")

        if (viewMode.isGraphMode) {
            handleGraphNavigation(context, appWidgetId, isLeft, repository)
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
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val currentOffset = stateManager.getDateOffset(appWidgetId)
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)

        val ctx = resolveRefreshContext(context, "daily_nav")
        val appLogDao = ctx.database.appLogDao()

        val historyStart = LocalDate.now().minusDays(DAILY_LOOKBACK_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val forecastEnd = LocalDate.now().plusDays(DAILY_FORECAST_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY

        val weatherList = ctx.forecastDao.getForecastsInRange(historyStart, forecastEnd, ctx.location.lat, ctx.location.lon)

        val today = LocalDate.now()
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val skipYesterday = NavigationUtils.shouldSkipYesterday(numColumns = numColumns)

        val availableForecastDates = weatherList.map { LocalDate.ofEpochDay(it.targetDate / WeatherTimeUtils.MILLIS_PER_DAY) }.toSet()

        val dailyActuals = getDailyActuals(ctx.database, ctx.location.lat, ctx.location.lon)
        val availableObsDates = dailyActuals.values.flatMap { it.keys }.toSet()

        val availableDates = (availableForecastDates + availableObsDates)
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
                    skipYesterday = skipYesterday,
                )
            canNavigate = minDate != null && minDate.isBefore(newLeftmost.plusDays(1))
            navDebug = "LEFT: newLeftmost=$newLeftmost, minDate=$minDate"
        } else {
            val (_, newRightmost) =
                NavigationUtils.getVisibleDateRange(
                    today = today,
                    dateOffset = currentOffset + 1,
                    numColumns = numColumns,
                    skipYesterday = skipYesterday,
                )
            canNavigate = maxDate != null && maxDate.isAfter(newRightmost.minusDays(1))
            navDebug = "RIGHT: newRightmost=$newRightmost, maxDate=$maxDate"
        }

        appLogDao.log(
            "DAILY_NAV_ATTEMPT",
            "widget=$appWidgetId dir=${if (isLeft) "LEFT" else "RIGHT"} offset=$currentOffset cols=$numColumns rows=${dimensions.rows} skipYesterday=$skipYesterday source=${displaySource.id} minDate=$minDate maxDate=$maxDate $navDebug canNavigate=$canNavigate"
        )

        if (!canNavigate) {
            appLogDao.log(
                "DAILY_NAV_BLOCKED",
                "widget=$appWidgetId dir=${if (isLeft) "LEFT" else "RIGHT"} offset=$currentOffset cols=$numColumns skipYesterday=$skipYesterday source=${displaySource.id} minDate=$minDate maxDate=$maxDate"
            )
            return
        }

        val newOffset =
            if (isLeft) {
                stateManager.navigateLeft(appWidgetId)
            } else {
                stateManager.navigateRight(appWidgetId)
            }
        appLogDao.log(
            "DAILY_NAV_APPLY",
            "widget=$appWidgetId dir=${if (isLeft) "LEFT" else "RIGHT"} offset=$currentOffset->$newOffset source=${displaySource.id}"
        )

        refreshDailyView(
            context = context,
            appWidgetId = appWidgetId,
            database = ctx.database,
            lat = ctx.location.lat,
            lon = ctx.location.lon,
            repository = repository,
            weatherList = weatherList,
            dailyActuals = dailyActuals,
            startTimeMs = startMs,
            actionTag = "DAILY_NAV",
            extraMetadata = "dir=${if (isLeft) "LEFT" else "RIGHT"}",
        )
    }

    /**
     * Handle hourly/precipitation view navigation.
     */
    private suspend fun handleGraphNavigation(
        context: Context,
        appWidgetId: Int,
        isLeft: Boolean,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)

        val newOffset =
            if (isLeft) {
                stateManager.navigateHourlyLeft(appWidgetId)
            } else {
                stateManager.navigateHourlyRight(appWidgetId)
            }
        Log.d(TAG, "handleGraphNavigation: Navigated to offset $newOffset for widget $appWidgetId")

        val ctx = resolveRefreshContext(context, "graph_nav")

        refreshGraphView(
            context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
            startTimeMs = startMs, actionTag = "GRAPH_NAV", extraMetadata = "dir=${if (isLeft) "LEFT" else "RIGHT"}"
        )
    }

    /**
     * Handle zoom level cycle action.
     */
suspend fun handleCycleZoom(
        context: Context,
        appWidgetId: Int,
        zoomCenterOffset: Int? = null,
        repository: WeatherRepository? = null,
    ) {
        try {
            handleCycleZoomInternal(context, appWidgetId, zoomCenterOffset, repository)
        } catch (e: Exception) {
            Log.e(TAG, "handleCycleZoom failed for widget $appWidgetId", e)
        }
    }

    private suspend fun handleCycleZoomInternal(
        context: Context,
        appWidgetId: Int,
        zoomCenterOffset: Int? = null,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)
        Log.d(TAG, "handleCycleZoom: viewMode=$viewMode widget=$appWidgetId")
        if (viewMode == ViewMode.DAILY) {
            Log.w(TAG, "handleCycleZoom: ignoring — widget $appWidgetId is in DAILY mode (stale PendingIntent)")
            return
        }
        val oldZoom = stateManager.getZoomLevel(appWidgetId)
        val newZoom = stateManager.cycleZoomLevel(appWidgetId)

        // When a center offset is provided by a tap zone, re-center the view
        if (zoomCenterOffset != null) {
            // The zoomCenterOffset is the pre-calculated absolute offset of the tapped zone.
            stateManager.setHourlyOffset(appWidgetId, zoomCenterOffset)
            Log.d(TAG, "handleCycleZoom: Re-centered to absolute offset $zoomCenterOffset for widget $appWidgetId")
        }
        Log.d(TAG, "handleCycleZoom: $oldZoom -> $newZoom, zoomCenterOffset=$zoomCenterOffset widget=$appWidgetId")

        val ctx = resolveRefreshContext(context, "cycle_zoom")

        refreshGraphView(
            context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
            startTimeMs = startMs, actionTag = "CYCLE_ZOOM", extraMetadata = "zoom=${newZoom.name}"
        )
    }

    /**
     * Handle API source toggle action.
     */
suspend fun handleToggleApi(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        try {
            handleToggleApiInternal(context, appWidgetId, repository)
        } catch (e: Exception) {
            Log.e(TAG, "handleToggleApi failed for widget $appWidgetId", e)
        }
    }

    private suspend fun handleToggleApiInternal(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val newSource = stateManager.toggleDisplaySource(appWidgetId)
        val viewMode = stateManager.getViewMode(appWidgetId)
        Log.d(TAG, "handleToggleApi: Toggled to $newSource for widget $appWidgetId, viewMode=$viewMode")

        val ctx = resolveRefreshContext(context, "toggle_api")
        val hourlyDao = ctx.database.hourlyForecastDao()

        val now = LocalDateTime.now()
        val currentGraphZoom =
            if (viewMode.isGraphMode) {
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
                forecastDao = ctx.forecastDao,
                hourlyDao = hourlyDao,
                lat = ctx.location.lat,
                lon = ctx.location.lon,
                source = newSource,
                centerTime = currentGraphCenterTime,
                zoom = currentGraphZoom,
                now = now,
            )

        if (viewMode.isGraphMode) {
            refreshGraphView(
                context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                startTimeMs = startMs, actionTag = "TOGGLE_API", extraMetadata = "source=${newSource.id}"
            )
        } else {
            refreshDailyView(
                context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                startTimeMs = startMs, actionTag = "TOGGLE_API", extraMetadata = "source=${newSource.id}"
            )
        }

        if (missingDataForSelectedSource) {
            Log.d(TAG, "handleToggleApi: Missing cached data for $newSource, enqueueing forced refresh")
            RefreshScheduler.enqueueForcedRefresh(context)
        }
    }

    /**
     * Handle view mode toggle action.
     */
suspend fun handleToggleView(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        try {
            handleToggleViewInternal(context, appWidgetId, repository)
        } catch (e: Exception) {
            Log.e(TAG, "handleToggleView failed for widget $appWidgetId", e)
        }
    }

    private suspend fun handleToggleViewInternal(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val newMode = stateManager.toggleViewMode(appWidgetId)
        Log.d(TAG, "handleToggleView: Toggled to $newMode for widget $appWidgetId")

        val ctx = resolveRefreshContext(context, "toggle_view")

        if (newMode.isGraphMode) {
            refreshGraphView(
                context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                startTimeMs = startMs, actionTag = "TOGGLE_VIEW", extraMetadata = "mode=${newMode.name}"
            )
        } else {
            refreshDailyView(
                context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                startTimeMs = startMs, actionTag = "TOGGLE_VIEW", extraMetadata = "mode=${newMode.name}"
            )
        }
    }

    @VisibleForTesting
    internal suspend fun getDailyActuals(
        database: WeatherDatabase,
        lat: Double,
        lon: Double,
    ): DailyActualsBySource {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        // Past days: read from DB cache
        val startDate = today.minusDays(DAILY_LOOKBACK_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val endDate = today.minusDays(1).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val pastExtremes = database.dailyExtremeDao().getExtremesInRange(startDate, endDate, lat, lon)
        val pastActuals = ObservationResolver.extremesToDailyActualsBySource(pastExtremes, lat, lon)

        // Today: compute live from raw station observations (exclude synthetic NWS_BLEND).
        // Uses time-aligned IDW so the value matches what the live widget displayed.
        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayObs = database.observationDao().getObservationsInRange(todayStartMs, tomorrowMs, lat, lon)
            .filter { it.stationId != "NWS_BLEND" }
        val now = LocalDateTime.now()
        val hourlyLookbackStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).atZone(zone).toInstant().toEpochMilli()
        val hourlyLookaheadEnd = now.plusHours(WeatherWidgetProvider.HOURLY_GRAPH_LOOKAHEAD_HOURS).atZone(zone).toInstant().toEpochMilli()
        val hourlyForecasts = database.hourlyForecastDao().getHourlyForecasts(hourlyLookbackStart, hourlyLookaheadEnd, lat, lon)
        val todayActuals = ObservationResolver.aggregateObservationsToDailyBySource(todayObs, hourlyForecasts, lat, lon)

        // Today must stay live-only. Persisted daily_extremes can contain a different high
        // than the time-aligned live blender, which makes SET_VIEW renders disagree with
        // worker refreshes after returning from the temperature graph.
        return ObservationResolver.mergeDailyActualsBySource(
            primary = pastActuals,
            secondary = todayActuals,
        )
    }

    private suspend fun sourceDataMissingForCurrentWindow(
        forecastDao: ForecastDao,
        hourlyDao: HourlyForecastDao,
        lat: Double,
        lon: Double,
        source: WeatherSource,
        centerTime: LocalDateTime? = null,
        zoom: ZoomLevel? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        val historyStart = LocalDate.now().minusDays(DAILY_LOOKBACK_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val futureEnd = LocalDate.now().plusDays(SOURCE_CHECK_FORECAST_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val sourceDaily = forecastDao.getForecastsInRangeBySource(historyStart, futureEnd, lat, lon, source.id)
        val maxDailyDate =
            sourceDaily.map { LocalDate.ofEpochDay(it.targetDate / WeatherTimeUtils.MILLIS_PER_DAY) }.maxOrNull()
        val hasRequiredFutureCoverage = maxDailyDate != null && !maxDailyDate.isBefore(LocalDate.now().plusDays(2))

        val sourceHourly =
            if (centerTime != null && zoom != null) {
                GraphDataLoader.loadGraphWindowHourlyForecasts(
                    hourlyDao = hourlyDao,
                    lat = lat,
                    lon = lon,
                    centerTime = centerTime,
                    zoom = zoom,
                    now = now,
                    source = source,
                )
            } else {
                val zoneId = ZoneId.systemDefault()
                val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).atZone(zoneId).toInstant().toEpochMilli()
                val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS).atZone(zoneId).toInstant().toEpochMilli()
                hourlyDao.getHourlyForecastsBySource(hourlyStart, hourlyEnd, lat, lon, source.id)
            }

        return sourceDaily.isEmpty() || sourceHourly.isEmpty() || !hasRequiredFutureCoverage
    }

    /**
     * Handle dual-source bar toggle action.
     */
suspend fun handleToggleDualBars(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        try {
            handleToggleDualBarsInternal(context, appWidgetId, repository)
        } catch (e: Exception) {
            Log.e(TAG, "handleToggleDualBars failed for widget $appWidgetId", e)
        }
    }

    private suspend fun handleToggleDualBarsInternal(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val newState = !stateManager.isShowTwoBarsEnabled()
        stateManager.setShowTwoBarsEnabled(newState)
        Log.d(TAG, "handleToggleDualBars: widget=$appWidgetId showTwoBars=$newState")

        // Refresh every widget instance since the dual-bars preference is global,
        // not per-widget. The tapped widget still updates fastest because its
        // refresh runs synchronously below; others get a UI-only update.
        val ctx = resolveRefreshContext(context, "toggle_dual_bars")
        refreshDailyView(
            context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
            startTimeMs = startMs, actionTag = "TOGGLE_DUAL", extraMetadata = "showTwoBars=$newState",
        )
        WeatherWidgetProvider.triggerUiOnlyUpdate(context, reason = "dual_bars_toggle")
    }

    /**
     * Handle precipitation mode toggle action.
     */
suspend fun handleTogglePrecip(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        try {
            handleTogglePrecipInternal(context, appWidgetId, repository)
        } catch (e: Exception) {
            Log.e(TAG, "handleTogglePrecip failed for widget $appWidgetId", e)
        }
    }

    private suspend fun handleTogglePrecipInternal(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val newMode = stateManager.togglePrecipitationMode(appWidgetId)
        Log.d(TAG, "handleTogglePrecip: Toggled to $newMode for widget $appWidgetId")

        val ctx = resolveRefreshContext(context, "toggle_precip")

        if (newMode == ViewMode.PRECIPITATION) {
            refreshGraphView(
                context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                startTimeMs = startMs, actionTag = "TOGGLE_PRECIP"
            )
        } else {
            refreshDailyView(
                context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                startTimeMs = startMs, actionTag = "TOGGLE_PRECIP"
            )
        }
    }

    /**
     * Handle set view mode action.
     */
suspend fun handleSetView(
        context: Context,
        appWidgetId: Int,
        targetMode: ViewMode,
        targetOffset: Int = Int.MIN_VALUE,
        repository: WeatherRepository? = null,
    ) {
        try {
            handleSetViewInternal(context, appWidgetId, targetMode, targetOffset, repository)
        } catch (e: Exception) {
            Log.e(TAG, "handleSetView failed for widget $appWidgetId", e)
        }
    }

    private suspend fun handleSetViewInternal(
        context: Context,
        appWidgetId: Int,
        targetMode: ViewMode,
        targetOffset: Int = Int.MIN_VALUE,
        repository: WeatherRepository? = null,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val stateManager = WidgetStateManager(context)
        val previousMode = stateManager.getViewMode(appWidgetId)
        val previousZoom = stateManager.getZoomLevel(appWidgetId)
        val previousOffset = stateManager.getHourlyOffset(appWidgetId)
        stateManager.setViewMode(appWidgetId, targetMode)
        Log.d(
            TAG,
            "handleSetView: target=$targetMode previousMode=$previousMode previousZoom=$previousZoom " +
                "previousOffset=$previousOffset widget=$appWidgetId",
        )
        if (targetMode == ViewMode.DAILY) {
            stateManager.setZoomLevel(appWidgetId, ZoomLevel.WIDE)
        } else if (targetMode.isGraphMode) {
            // Reset to WIDE only when entering from daily — preserves zoom when navigating
            // between hourly view types (temperature ↔ precipitation ↔ cloud cover)
            if (previousMode == ViewMode.DAILY) {
                stateManager.setZoomLevel(appWidgetId, ZoomLevel.WIDE)
                Log.d(TAG, "handleSetView: RESET zoom to WIDE (was $previousZoom, previousMode=$previousMode)")
            }
            if (targetOffset != Int.MIN_VALUE) {
                stateManager.setHourlyOffset(appWidgetId, targetOffset)
                Log.d(TAG, "handleSetView: set hourlyOffset=$targetOffset (was $previousOffset)")
            }
        }
        val finalOffset = stateManager.getHourlyOffset(appWidgetId)
        Log.d(
            TAG,
            "handleSetView: FINISHED mode=$targetMode targetOffset=$targetOffset " +
                "finalStoredOffset=$finalOffset widget=$appWidgetId",
        )

        val ctx = resolveRefreshContext(context, "set_view")

        when (targetMode) {
            ViewMode.DAILY -> {
                refreshDailyView(
                    context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                    startTimeMs = startMs, actionTag = "SET_VIEW", extraMetadata = "mode=${targetMode.name}"
                )
            }
            else -> {
                refreshGraphView(
                    context, appWidgetId, ctx.database, ctx.location.lat, ctx.location.lon, repository,
                    startTimeMs = startMs, actionTag = "SET_VIEW", extraMetadata = "mode=${targetMode.name}"
                )
            }
        }
    }

    /**
     * Handle widget resize.
     */
suspend fun handleResize(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        try {
            handleResizeInternal(context, appWidgetId, repository)
        } catch (e: Exception) {
            Log.e(TAG, "handleResize failed for widget $appWidgetId", e)
        }
    }

    private suspend fun handleResizeInternal(
        context: Context,
        appWidgetId: Int,
        repository: WeatherRepository? = null,
    ) {
        kotlinx.coroutines.delay(RESIZE_DEBOUNCE_MS) // Debounce rapid resize events
        val startMs = SystemClock.elapsedRealtime()
        val database = WeatherDatabase.getDatabase(context)
        database.appLogDao().log("WIDGET_LIFECYCLE", "phase=handleResize_entry widget=$appWidgetId thread=${Thread.currentThread().name}")

        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        logResizeDiagnostics(context, appWidgetManager, appWidgetId, viewMode.name, database.appLogDao())

        refreshWidget(
            context, appWidgetId, "resize", repository,
            startTimeMs = startMs, actionTag = "RESIZE"
        )
    }

    /**
     * Central dispatcher for refreshing the widget based on the current view mode.
     */
    private suspend fun refreshWidget(
        context: Context,
        appWidgetId: Int,
        reason: String,
        repository: WeatherRepository? = null,
        startTimeMs: Long = SystemClock.elapsedRealtime(),
        actionTag: String = "REFRESH",
        extraMetadata: String = "",
    ) {
        val ctx = resolveRefreshContext(context, reason)

        val viewMode = WidgetStateManager(context).getViewMode(appWidgetId)
        if (!viewMode.isGraphMode) {
            refreshDailyView(
                context = context,
                appWidgetId = appWidgetId,
                database = ctx.database,
                lat = ctx.location.lat,
                lon = ctx.location.lon,
                repository = repository,
                startTimeMs = startTimeMs,
                actionTag = actionTag,
                extraMetadata = extraMetadata,
            )
        } else {
            refreshGraphView(
                context = context,
                appWidgetId = appWidgetId,
                database = ctx.database,
                lat = ctx.location.lat,
                lon = ctx.location.lon,
                repository = repository,
                startTimeMs = startTimeMs,
                actionTag = actionTag,
                extraMetadata = extraMetadata,
            )
        }
    }

    /**
     * Refreshes the daily view by loading all necessary forecast and observation data.
     */
    private suspend fun refreshDailyView(
        context: Context,
        appWidgetId: Int,
        database: WeatherDatabase,
        lat: Double,
        lon: Double,
        repository: WeatherRepository? = null,
        weatherList: List<ForecastEntity>? = null,
        dailyActuals: DailyActualsBySource? = null,
        startTimeMs: Long = SystemClock.elapsedRealtime(),
        actionTag: String = "DAILY_REFRESH",
        extraMetadata: String = "",
    ) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val today = LocalDate.now()
        val historyStart = today.minusDays(DAILY_LOOKBACK_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val forecastEnd = today.plusDays(DAILY_FORECAST_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val pastSnapshotEnd = today.minusDays(2).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val recentSnapshotStart = today.minusDays(1).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY

        val forecastDao = database.forecastDao()
        val hourlyDao = database.hourlyForecastDao()

        val finalWeatherList = weatherList ?: forecastDao.getForecastsInRange(historyStart, forecastEnd, lat, lon)
        val pastSnapshots = forecastDao.getLatestForecastsInRange(historyStart, pastSnapshotEnd, lat, lon)
        val recentSnapshots = forecastDao.getAllForecastsInRange(recentSnapshotStart, forecastEnd, lat, lon)
        val forecastSnapshots =
            (pastSnapshots + recentSnapshots)
                .groupBy { LocalDate.ofEpochDay(it.targetDate / WeatherTimeUtils.MILLIS_PER_DAY) }

        val now = LocalDateTime.now()
        val zoneId = ZoneId.systemDefault()
        val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS).atZone(zoneId).toInstant().toEpochMilli()
        val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_GRAPH_LOOKAHEAD_HOURS).atZone(zoneId).toInstant().toEpochMilli()
        val hourlyForecasts = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, lat, lon)

        val todayStartMs = LocalDate.now().atStartOfDay(zoneId).toInstant().toEpochMilli()
        val ctCurrentTemps = repository?.getMainObservationsWithComputedNwsBlend(lat, lon, todayStartMs)
            ?: database.observationDao().getLatestMainObservations(lat, lon, todayStartMs)
        val finalDailyActuals = dailyActuals ?: getDailyActuals(database, lat, lon)
        val currentTempHourlyForecasts =
            GraphDataLoader.loadCurrentTempResolutionHourlyForecasts(
                hourlyDao = hourlyDao,
                lat = lat,
                lon = lon,
                now = now,
            )

        val stateManager = WidgetStateManager(context)
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val zoom = stateManager.getZoomLevel(appWidgetId)
        
        val graphStyleObs = CurrentTempResolver.resolveGraphStyleCurrentTemp(
            repository = repository,
            lat = lat,
            lon = lon,
            displaySource = displaySource,
            hourlyForecasts = currentTempHourlyForecasts,
            now = now,
        )

        val smoothedForecasts = computeSmoothedForecasts(
            hourlyForecasts, displaySource
        )

        DailyViewHandler.updateWidget(
            context,
            appWidgetManager,
            appWidgetId,
            finalWeatherList,
            forecastSnapshots,
            hourlyForecasts,
            ctCurrentTemps,
            finalDailyActuals,
            repository,
            lastObservedTemp = graphStyleObs?.temperature,
            observedAt = graphStyleObs?.observedAt,
            now = LocalDateTime.now(),
            smoothedForecasts = smoothedForecasts,
            stateManagerNullable = stateManager,
        )

        val totalMs = SystemClock.elapsedRealtime() - startTimeMs
        val metadataString = if (extraMetadata.isNotEmpty()) " $extraMetadata" else ""
        database.appLogDao().log("${actionTag}_TIMING", "widget=$appWidgetId total=${totalMs}ms$metadataString")
        if (totalMs > SLOW_THRESHOLD_MS) {
            database.appLogDao().log("${actionTag}_SLOW", "widget=$appWidgetId total=${totalMs}ms$metadataString")
        }
    }

    /**
     * Refreshes the graph view by loading hourly forecast data for the current window.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun refreshGraphView(
        context: Context,
        appWidgetId: Int,
        database: WeatherDatabase,
        lat: Double,
        lon: Double,
        repository: WeatherRepository? = null,
        startTimeMs: Long = SystemClock.elapsedRealtime(),
        actionTag: String = "GRAPH_REFRESH",
        extraMetadata: String = "",
    ) {
        val stateManager = WidgetStateManager(context)
        val zoom = stateManager.getZoomLevel(appWidgetId)
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
        val now = LocalDateTime.now()
        val centerTime = now.plusHours(hourlyOffset.toLong())
        android.util.Log.d(
            TAG,
            "refreshGraphView: widget=$appWidgetId view=${stateManager.getViewMode(appWidgetId)} " +
                "zoom=$zoom offset=$hourlyOffset now=$now centerTime=$centerTime source=${displaySource.id}",
        )

        val hourlyForecasts =
            GraphDataLoader.loadGraphWindowHourlyForecasts(
                hourlyDao = database.hourlyForecastDao(),
                lat = lat,
                lon = lon,
                centerTime = centerTime,
                zoom = zoom,
                now = now,
            )

        updateHourlyViewWithData(context, appWidgetId, hourlyForecasts, centerTime, displaySource, lat, lon, repository, now)

        val totalMs = SystemClock.elapsedRealtime() - startTimeMs
        val metadataString = if (extraMetadata.isNotEmpty()) " $extraMetadata" else ""
        database.appLogDao().log("${actionTag}_TIMING", "widget=$appWidgetId total=${totalMs}ms$metadataString")
        if (totalMs > SLOW_THRESHOLD_MS) {
            database.appLogDao().log("${actionTag}_SLOW", "widget=$appWidgetId total=${totalMs}ms$metadataString")
        }
    }

    private suspend fun updateHourlyViewWithData(
        context: Context,
        appWidgetId: Int,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        displaySource: WeatherSource,
        lat: Double,
        lon: Double,
        repository: WeatherRepository? = null,
        now: LocalDateTime,
    ) {
        val stateManager = WidgetStateManager(context)
        val viewMode = stateManager.getViewMode(appWidgetId)
        val appWidgetManager = AppWidgetManager.getInstance(context)

        val todayEpoch = LocalDate.now().toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val database = WeatherDatabase.getDatabase(context)
        val weatherList = database.forecastDao().getForecastsInRange(todayEpoch, todayEpoch, lat, lon)
        val todayStartMs = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val currentTemps = repository?.getMainObservationsWithComputedNwsBlend(lat, lon, todayStartMs) ?: emptyList()
        val currentTempHourlyForecasts =
            GraphDataLoader.loadCurrentTempResolutionHourlyForecasts(
                hourlyDao = database.hourlyForecastDao(),
                lat = lat,
                lon = lon,
                now = now,
            )

        val todayPrecip = weatherList.find { it.source == displaySource.id }?.precipProbability
        val zoom = stateManager.getZoomLevel(appWidgetId)
        
        // Resolve current temperature using the graph's IDW + forward extrapolation logic for consistency.
        val graphStyleObs = CurrentTempResolver.resolveGraphStyleCurrentTemp(
            repository = repository,
            lat = lat,
            lon = lon,
            displaySource = displaySource,
            hourlyForecasts = currentTempHourlyForecasts,
            now = now,
        )
        
        val observation = graphStyleObs ?: ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)
        
        logCurrentTempStalenessDebug(
            database = database,
            appWidgetId = appWidgetId,
            viewMode = viewMode.name,
            displaySource = displaySource,
            observation = observation,
            centerTime = centerTime,
        )

        when (viewMode) {
            ViewMode.PRECIPITATION -> {
                PrecipViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    centerTime = centerTime,
                    precipProbability = todayPrecip,
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
                    repository = repository
                )
            }
            ViewMode.CLOUD_COVER -> {
                CloudCoverViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    centerTime = centerTime,
                    displaySource = displaySource,
                    precipProbability = todayPrecip,
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
                    repository = repository
                )
            }
            else -> {
                TemperatureViewHandler.updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    hourlyForecasts = hourlyForecasts,
                    currentTempHourlyForecasts = currentTempHourlyForecasts,
                    centerTime = centerTime,
                    displaySource = displaySource,
                    precipProbability = todayPrecip,
                    lastObservedTemp = observation?.temperature,
                    observedAt = observation?.observedAt,
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
        observation: ObservationResolver.ObservedCurrentTemperature?,
        centerTime: LocalDateTime,
    ) {
        if (viewMode != ViewMode.TEMPERATURE.name) return

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
        appLogDao: AppLogDao,
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
}
