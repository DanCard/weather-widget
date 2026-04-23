/**
 * Handler for the daily forecast view mode.
 */
package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.ui.SettingsActivity
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.DailyForecastIconResolver
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.DailyActualMap
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import com.weatherwidget.widget.handlers.WidgetRequestCodes
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

object DailyViewHandler : WidgetViewHandler {
    private const val TAG = "DailyViewHandler"
    private const val CELL_HEIGHT_DP = 90
    private const val MISSING_ACTUALS_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L
    private const val MISSING_TODAY_SNAPSHOT_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L
    private const val DELTA_VISIBILITY_THRESHOLD = 0.1f
    private const val DELTA_COLOR_HEX = "#FF6B35"
    private const val GRAPH_HEIGHT_PADDING_DP = 25f
    private const val GRAPH_ROW_THRESHOLD = 2.2f
    private const val TEXT_MODE_ROOT_LEFT_PADDING_DP = 2
    private const val TEXT_MODE_ROOT_TOP_PADDING_DP = 0
    private const val TEXT_MODE_ROOT_RIGHT_PADDING_DP = 8
    private const val TEXT_MODE_ROOT_BOTTOM_PADDING_DP = 0
    private const val TEXT_MODE_CONTENT_RIGHT_PADDING_DP = 18
    private val headerDateFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())

    @VisibleForTesting
    internal enum class HeaderDatePlacement {
        CENTER,
        RIGHT,
    }

    @VisibleForTesting
    internal data class HeaderPrecipPlacement(
        val showHeaderPrecip: Boolean,
        val allowTodayColumnPrecip: Boolean,
    )

    private data class DayIds(
        val container: Int,
        val label: Int,
        val icon: Int,
        val high: Int,
        val low: Int,
        val rain: Int,
    )

    // Intent actions from WeatherWidgetProvider
    private const val ACTION_NAV_LEFT = "com.weatherwidget.ACTION_NAV_LEFT"
    private const val ACTION_NAV_RIGHT = "com.weatherwidget.ACTION_NAV_RIGHT"
    private const val ACTION_TOGGLE_API = "com.weatherwidget.ACTION_TOGGLE_API"
    private const val ACTION_TOGGLE_VIEW = "com.weatherwidget.ACTION_TOGGLE_VIEW"
    private const val ACTION_TOGGLE_PRECIP = "com.weatherwidget.ACTION_TOGGLE_PRECIP"
    private const val ACTION_DAY_CLICK = "com.weatherwidget.ACTION_DAY_CLICK"
    private const val EXTRA_TARGET_VIEW = "com.weatherwidget.EXTRA_TARGET_VIEW"
    private const val EXTRA_HOURLY_OFFSET = "com.weatherwidget.EXTRA_HOURLY_OFFSET"

    override fun canHandle(
        stateManager: WidgetStateManager,
        appWidgetId: Int,
    ): Boolean {
        return stateManager.getViewMode(appWidgetId) == ViewMode.DAILY
    }

    override suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        weatherList: List<ForecastEntity>,
        forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTemps: List<ObservationEntity>,
        dailyActualsBySource: DailyActualsBySource,
        repository: WeatherRepository?,
    ) {
        updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            weatherList = weatherList,
            forecastSnapshots = forecastSnapshots,
            hourlyForecasts = hourlyForecasts,
            currentTemps = currentTemps,
            dailyActualsBySource = dailyActualsBySource,
            repository = repository,
            now = LocalDateTime.now(),
        )
    }

    suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        weatherList: List<ForecastEntity>,
        forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTemps: List<ObservationEntity>,
        dailyActualsBySource: DailyActualsBySource,
        repository: WeatherRepository?,
        lastObservedTemp: Float? = null,
        observedAt: Long? = null,
        startupToken: String? = null,
        smoothedForecasts: Map<Long, Float>? = null,
    ) {
        updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            weatherList = weatherList,
            forecastSnapshots = forecastSnapshots,
            hourlyForecasts = hourlyForecasts,
            currentTemps = currentTemps,
            dailyActualsBySource = dailyActualsBySource,
            repository = repository,
            lastObservedTemp = lastObservedTemp,
            observedAt = observedAt,
            now = LocalDateTime.now(),
            startupToken = startupToken,
            smoothedForecasts = smoothedForecasts,
        )
    }

    @VisibleForTesting
    suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        weatherList: List<ForecastEntity>,
        forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTemps: List<ObservationEntity>,
        dailyActualsBySource: DailyActualsBySource,
        repository: WeatherRepository?,
        lastObservedTemp: Float? = null,
        observedAt: Long? = null,
        now: LocalDateTime,
        startupToken: String? = null,
        smoothedForecasts: Map<Long, Float>? = null,
    ) {
        Log.d(TAG, "updateWidget: [START] widgetId=$appWidgetId at time=$now")
        val handlerStartMs = SystemClock.elapsedRealtime()
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val numRows = dimensions.rows

        // Use graph mode for 2+ rows
        val rawRows = (dimensions.heightDp + GRAPH_HEIGHT_PADDING_DP) / CELL_HEIGHT_DP
        val useGraph = rawRows >= GRAPH_ROW_THRESHOLD

        val stateManager = WidgetStateManager(context)
        val dateOffset = stateManager.getDateOffset(appWidgetId)

        val isEveningMode = NavigationUtils.isEveningMode(now.toLocalTime())
        
        // Single source of truth for time in this update cycle
        val today = now.toLocalDate()
        val skipHistory = NavigationUtils.shouldSkipHistory(isEveningMode, dateOffset)
        val centerDate = NavigationUtils.getDisplayCenterDate(today, dateOffset, isEveningMode)

        // Setup common click actions
        setupCurrentTempToggle(context, views, appWidgetId)
        setupSettingsShortcut(context, views, appWidgetId)

        // Get the current display source for this widget
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val dailyActuals = dailyActualsBySource[displaySource.id].orEmpty()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()

        Log.d(
            TAG,
            "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, offset=$dateOffset, " +
                "isEveningMode=$isEveningMode, weatherCount=${weatherList.size}, actualsCount=${dailyActuals.size}, source=${displaySource.id}",
        )

        val earlyRefreshDecisions = computeMissingDataRefreshes(
            today = today,
            displaySource = displaySource,
            dailyActuals = dailyActuals,
        )
        for (decision in earlyRefreshDecisions) {
            requestMissingDataRefresh(
                context = context,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                displaySource = displaySource,
                refreshType = decision.refreshType,
                cooldownMs = if (decision.forceRefresh) MISSING_ACTUALS_REFRESH_COOLDOWN_MS else MISSING_TODAY_SNAPSHOT_REFRESH_COOLDOWN_MS,
                logTag = if (decision.forceRefresh) "MISSING_ACTUALS_FETCH" else "MISSING_TODAY_SNAPSHOT_FETCH",
                forceRefresh = decision.forceRefresh,
                reason = decision.reason,
                message = "widget=$appWidgetId source=${displaySource.id} ${decision.refreshType} refresh, enqueueing worker",
            )
        }

        val lat = weatherList.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = weatherList.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val warning = ApiSourceWarningHelper.resolveBlockingSourceWarning(
            appLogDao = appLogDao,
            displaySource = displaySource,
            hasSelectedSourceData = weatherList.any { it.source == displaySource.id && !it.isClimateNormal },
        )
        if (warning != null) {
            ApiSourceWarningHelper.renderSourceWarningState(context, views, appWidgetId, warning)
            setupApiToggle(context, views, appWidgetId, numRows)
            logDailyRenderSummary(
                context = context,
                appWidgetId = appWidgetId,
                dateOffset = dateOffset,
                displaySource = displaySource,
                numColumns = numColumns,
                numRows = numRows,
                useGraph = false,
                isEveningMode = isEveningMode,
                centerDate = centerDate,
                visibleDates = emptyList(),
            )
            appLogDao.log(
                "DAILY_SOURCE_BLOCKED",
                "widget=$appWidgetId source=${displaySource.id} message=${warning.toastMessage}",
                "WARN",
            )
            appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=DAILY state=warning thread=${Thread.currentThread().name}")
            appWidgetManager.updateAppWidget(appWidgetId, views)
            return
        }

        ApiSourceWarningHelper.hideSourceWarning(views)

        // Build weather map: prefer the selected display source, fallback to generic gap
        val weatherByDate =
            weatherList
                .filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
                .groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
                .mapValues { (_, items) -> items.find { it.source == displaySource.id } ?: items.first() }

        // Set API source indicator
        views.setTextViewText(R.id.api_source, displaySource.shortDisplayName)
        views.setTextViewText(R.id.text_mode_api_source, displaySource.shortDisplayName)

        // Set weather icon
        val climateNormals = repository?.getHistoricalNormalsByMonthDay(lat, lon) ?: emptyMap()

        val todayHeaderForecast = resolveTodayHeaderForecast(
            now = now,
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
        )
        val iconRes =
            if (todayHeaderForecast != null) {
                WeatherIconMapper.getIconResource(
                    condition = todayHeaderForecast.condition,
                    isNight = SunPositionUtils.getSunInfo(now, lat, lon).isNight,
                    cloudCover = todayHeaderForecast.cloudCover,
                    precipProbability = todayHeaderForecast.precipProbability,
                )
            } else {
                DailyForecastIconResolver.resolveIcon(
                    weather = weatherByDate[today],
                    targetDate = today,
                    now = now,
                    latitude = lat,
                    longitude = lon,
                )
            }
        
        if (useGraph) {
            views.setImageViewResource(R.id.weather_icon, iconRes)
            views.setViewVisibility(R.id.weather_icon, View.VISIBLE)
            views.setViewVisibility(R.id.current_weather_container, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.weather_icon, View.GONE)
            views.setViewVisibility(R.id.current_weather_container, View.GONE)
        }

        val resolveStartMs = SystemClock.elapsedRealtime()
        val currentTempResolution =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = displaySource,
                hourlyForecasts = hourlyForecasts,
                lastObservedTemp = lastObservedTemp,
                observedAt = observedAt,
                storedDeltaState = stateManager.getCurrentTempDeltaState(appWidgetId, displaySource),
                currentLat = lat,
                currentLon = lon,
                smoothedForecasts = smoothedForecasts,
            )
        val resolveMs = SystemClock.elapsedRealtime() - resolveStartMs
        if (currentTempResolution.shouldClearStoredDelta) {
            stateManager.clearCurrentTempDeltaState(appWidgetId, displaySource)
        }
        currentTempResolution.updatedDeltaState?.let { stateManager.setCurrentTempDeltaState(appWidgetId, displaySource, it) }
        val currentTemp = currentTempResolution.displayTemp
        val configuredLocation = stateManager.getWidgetLocation(appWidgetId)

        val formattedTemp =
            currentTemp?.let {
                CurrentTemperatureResolver.formatDisplayTemperature(
                    temp = it,
                    numColumns = numColumns,
                    isStaleEstimate = currentTempResolution.isStaleEstimate,
                )
            }

        if (formattedTemp != null) {
            views.setTextViewText(R.id.current_temp, formattedTemp)
            val tempPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP, context.resources.displayMetrics)
            views.setTextViewTextSize(R.id.current_temp, TypedValue.COMPLEX_UNIT_PX, tempPx)
            views.setViewVisibility(R.id.current_temp, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.current_temp, View.GONE)
            views.setViewVisibility(R.id.current_temp_delta, View.GONE)
        }
        views.setViewVisibility(R.id.header_date_center, View.GONE)
        views.setViewVisibility(R.id.header_date_right, View.GONE)

        // Show precipitation probability next to current temp when rain is expected
        val todayWeather = weatherByDate[today]
        val precipProb =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
                fallbackDailyProbability = todayWeather?.precipProbability,
                referenceTime = now,
            )
        val isPrecipVisible = HeaderTapTargetHelper.shouldShowPrecipTouchZone(precipProb)
        if (isPrecipVisible) {
            val prob = precipProb ?: 0
            views.setTextViewText(R.id.precip_probability, "$prob%")
            val textSizeDp = HeaderPrecipCalculator.getPrecipTextSize(prob)
            val precipPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, textSizeDp, context.resources.displayMetrics)
            views.setTextViewTextSize(R.id.precip_probability, TypedValue.COMPLEX_UNIT_PX, precipPx)
            views.setViewVisibility(R.id.precip_probability, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.precip_probability, View.GONE)
        }
        HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, isPrecipVisible)

        val delta = currentTempResolution.appliedDelta
        val deltaVisible =
            currentTemp != null &&
            delta != null &&
            kotlin.math.abs(delta) >= DELTA_VISIBILITY_THRESHOLD
        if (deltaVisible) {
            val deltaText = String.format("%+.1f", delta)
            val deltaColor = Color.parseColor(DELTA_COLOR_HEX)
            views.setTextViewText(R.id.current_temp_delta, deltaText)
            views.setTextColor(R.id.current_temp_delta, deltaColor)
            val deltaPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, HeaderConstants.DELTA_TEXT_SIZE_DP, context.resources.displayMetrics)
            views.setTextViewTextSize(R.id.current_temp_delta, TypedValue.COMPLEX_UNIT_PX, deltaPx)
            views.setViewVisibility(R.id.current_temp_delta, View.VISIBLE)
} else {
    views.setViewVisibility(R.id.current_temp_delta, View.GONE)
}

val precipTextSizeDp = if (isPrecipVisible) HeaderPrecipCalculator.getPrecipTextSize(precipProb ?: 0) else null
val disclosure = HeaderWidthChecker.resolveHeaderDisclosure(
    context = context,
    widthDp = dimensions.widthDp,
    apiSourceText = displaySource.shortDisplayName,
    apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows),
    currentTempText = formattedTemp,
    deltaText = if (deltaVisible) String.format("%+.1f", delta) else null,
    precipText = if (isPrecipVisible) "${precipProb}%" else null,
    precipTextSizeDp = precipTextSizeDp,
)

if (useGraph && disclosure != HeaderDisclosureLevel.NONE) {
    views.setViewVisibility(R.id.weather_icon, if (disclosure.showsIcon()) View.VISIBLE else View.GONE)
    views.setViewVisibility(R.id.current_temp_delta, if (deltaVisible && disclosure.showsDelta()) View.VISIBLE else View.GONE)
    views.setViewVisibility(R.id.precip_probability, if (isPrecipVisible && disclosure.showsPrecip()) View.VISIBLE else View.GONE)
    HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, isPrecipVisible && disclosure.showsPrecip())
} else if (useGraph) {
    views.setViewVisibility(R.id.current_weather_container, View.GONE)
}

// Setup API source toggle click handler
setupApiToggle(context, views, appWidgetId, numRows)
        Log.d(
            TAG,
            buildHeaderStateLog(
                widgetId = appWidgetId,
                viewMode = ViewMode.DAILY,
                displaySource = displaySource,
                configuredLocation = configuredLocation,
                dataLat = lat,
                dataLon = lon,
                dimensions = dimensions,
                currentTemp = currentTemp,
                estimatedTemp = currentTempResolution.estimatedTemp,
                observedTemp = currentTempResolution.observedTemp,
                appliedDelta = delta,
                deltaVisible = deltaVisible,
                deltaHiddenReason = dailyDeltaHiddenReason(currentTemp, delta),
                precipVisible = isPrecipVisible,
                precipProbability = precipProb,
                isNowLineVisible = null,
                offset = dateOffset,
                zoom = null,
                resolveMs = resolveMs,
            ),
        )
        
        // Hide history icon and delta badge in daily mode
        views.setViewVisibility(R.id.home_icon, View.GONE)
        views.setViewVisibility(R.id.home_touch_zone, View.GONE)
        views.setViewVisibility(R.id.history_icon, View.GONE)
        views.setViewVisibility(R.id.history_touch_zone, View.GONE)
        views.setViewVisibility(R.id.weather_stations_icon, View.GONE)
        views.setViewVisibility(R.id.weather_stations_touch_zone, View.GONE)

        // Set up navigation click handlers
        val availableDates = weatherList.map { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }.toSet() + dailyActuals.keys
        val sortedDates = availableDates.sorted()
        Log.d(TAG, "updateWidget: widgetId=$appWidgetId, widthDp=${dimensions.widthDp}, heightDp=${dimensions.heightDp}, cols=$numColumns, rows=$numRows, offset=$dateOffset, minDate=${sortedDates.firstOrNull()}, maxDate=${sortedDates.lastOrNull()}")
        setupNavigationButtons(context, views, appWidgetId, stateManager, availableDates, numColumns, isEveningMode, today, useGraph)

        // Use graph mode for 2+ rows
        var prepareMs = 0L
        var renderMs = 0L

        if (useGraph) {
            setGraphModeViews(views)

            val prepareStartMs = SystemClock.elapsedRealtime()
            val todayActual = dailyActuals[today]
            val sourceCurrentTemps = currentTemps.filter {
                ObservationResolver.inferSource(it.stationId) == displaySource.id ||
                    ObservationResolver.inferSource(it.stationId) == WeatherSource.GENERIC_GAP.id
            }
            val currentTempSpan =
                if (sourceCurrentTemps.isEmpty()) {
                    "none"
                } else {
                    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                    val firstTs = sourceCurrentTemps.minOf { it.timestamp }
                    val lastTs = sourceCurrentTemps.maxOf { it.timestamp }
                    val firstLocal = Instant.ofEpochMilli(firstTs).atZone(ZoneId.systemDefault()).toLocalDateTime().format(formatter)
                    val lastLocal = Instant.ofEpochMilli(lastTs).atZone(ZoneId.systemDefault()).toLocalDateTime().format(formatter)
                    "$firstLocal..$lastLocal"
                }
            Log.d(
                TAG,
                "dailyTodayInputs: widget=$appWidgetId source=${displaySource.id} date=$today " +
                    "dailyActual.high=${todayActual?.highTemp} dailyActual.low=${todayActual?.lowTemp} " +
                    "currentTempResolution=$currentTemp observedAt=$observedAt " +
                    "sourceCurrentRows=${sourceCurrentTemps.size} sourceCurrentSpan=$currentTempSpan " +
                    "hourlyRows=${hourlyForecasts.count { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }}",
            )
            fun prepareGraphDays(allowTodayRainChanceLabel: Boolean): List<DailyForecastGraphRenderer.DayData> =
                DailyViewLogic.prepareGraphDays(
                    now, centerDate, today, weatherByDate, forecastSnapshots,
                    numColumns, displaySource, isEveningMode, skipHistory,
                    hourlyForecasts, stateManager, appWidgetId, precipProb,
                    dailyActuals, climateNormals, currentTemps,
                    currentTemp = currentTemp,
                    observedAt = observedAt,
                    allowTodayRainChanceLabel = allowTodayRainChanceLabel,
                )

            val days = prepareGraphDays(allowTodayRainChanceLabel = true)
            prepareMs = SystemClock.elapsedRealtime() - prepareStartMs

            // Stabilize column count: at offset 0 (home view), record days.size as the
            // baseline.  When navigating away, cap to that baseline so the grid doesn't
            // gain or lose a column as data availability shifts.
            fun stabilizeDisplayDays(preparedDays: List<DailyForecastGraphRenderer.DayData>): List<DailyForecastGraphRenderer.DayData> = if (dateOffset == 0) {
                stateManager.setDailyColumnCount(appWidgetId, preparedDays.size)
                preparedDays
            } else {
                val baseline = stateManager.getDailyColumnCount(appWidgetId)
                if (baseline > 0 && preparedDays.size > baseline) preparedDays.take(baseline) else preparedDays
            }
            var displayDays = stabilizeDisplayDays(days)
            Log.d(TAG, "updateWidget: Graph mode - prepared ${days.size} days, displaying ${displayDays.size} for $numColumns columns (offset=$dateOffset).")

            if (displayDays.isEmpty() && weatherList.isNotEmpty()) {
                Log.w(TAG, "displayDays is empty despite ${weatherList.size} weather entries, skipping bitmap update")
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            val widthDp = dimensions.widthDp - 24
            val heightDp = dimensions.heightDp - 16
            val dateText = if (displayDays.size >= HeaderConstants.DATE_MIN_COLUMNS) today.format(headerDateFormatter) else null
            val headerPrecipPlacement = resolveHeaderPrecipPlacement(
                context = context,
                widthDp = widthDp,
                numColumns = displayDays.size,
                currentTempText = formattedTemp,
                deltaText = if (deltaVisible && disclosure.showsDelta()) String.format("%+.1f", delta) else null,
                precipText = if (isPrecipVisible) "$precipProb%" else null,
                precipTextSizeDp = precipTextSizeDp,
                apiSourceText = displaySource.shortDisplayName,
                apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows),
                dateText = dateText,
                headerCanShowPrecip = disclosure.showsPrecip(),
                includeIcon = disclosure.showsIcon(),
            )
            val graphRefreshDecisions = computeMissingDataRefreshes(
                today = today,
                displaySource = displaySource,
                dailyActuals = dailyActuals,
                displayDays = displayDays,
            )
            for (decision in graphRefreshDecisions) {
                requestMissingDataRefresh(
                    context = context,
                    stateManager = stateManager,
                    appWidgetId = appWidgetId,
                    displaySource = displaySource,
                    refreshType = decision.refreshType,
                    cooldownMs = if (decision.forceRefresh) MISSING_ACTUALS_REFRESH_COOLDOWN_MS else MISSING_TODAY_SNAPSHOT_REFRESH_COOLDOWN_MS,
                    logTag = if (decision.forceRefresh) "MISSING_ACTUALS_FETCH" else "MISSING_TODAY_SNAPSHOT_FETCH",
                    forceRefresh = decision.forceRefresh,
                    reason = decision.reason,
                    message = "widget=$appWidgetId source=${displaySource.id} ${decision.refreshType} refresh, enqueueing worker",
                )
            }

            // Mark rain as shown if today's rain is in the list
            if (displayDays.any { it.isToday && it.rainData.rainSummary != null }) {
                stateManager.markRainShown(appWidgetId, todayStr)
            }

            logDailyRenderSummary(
                context = context,
                appWidgetId = appWidgetId,
                dateOffset = dateOffset,
                displaySource = displaySource,
                numColumns = numColumns,
                numRows = numRows,
                useGraph = true,
                isEveningMode = isEveningMode,
                centerDate = centerDate,
                visibleDates = displayDays.map { it.date },
            )
            logGraphDayIconDetails(context, appWidgetId, displayDays)

            // Render graph
            val (widthPx, heightPx) = WidgetSizeCalculator.getOptimalBitmapSize(context, widthDp, heightDp)
            val rawWidthPx = WidgetSizeCalculator.dpToPx(context, widthDp).coerceAtLeast(1)
            val rawHeightPx = WidgetSizeCalculator.dpToPx(context, heightDp).coerceAtLeast(1)
            val bitmapScale = min(widthPx.toFloat() / rawWidthPx.toFloat(), heightPx.toFloat() / rawHeightPx.toFloat())

            // Build header data for bitmap rendering
            val headerRenderData = if (disclosure != HeaderDisclosureLevel.NONE) {
                DailyForecastGraphRenderer.HeaderRenderData(
                    iconRes = iconRes,
                    currentTempText = formattedTemp,
                    deltaText = if (deltaVisible) String.format("%+.1f", delta) else null,
                    precipText = if (isPrecipVisible) "$precipProb%" else null,
                    precipTextSizeDp = if (isPrecipVisible) HeaderPrecipCalculator.getPrecipTextSize(precipProb ?: 0) else HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP,
                    dateText = dateText,
                    apiSourceText = displaySource.shortDisplayName,
                    apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows),
                    settingsIconRes = R.drawable.ic_settings_gear,
                    showIcon = disclosure.showsIcon(),
                    showDelta = deltaVisible && disclosure.showsDelta(),
                    showPrecip = isPrecipVisible && headerPrecipPlacement.showHeaderPrecip,
                )
            } else null

            val renderStartMs = SystemClock.elapsedRealtime()
            val bitmap = DailyForecastGraphRenderer.renderGraph(context, displayDays, widthPx, heightPx, bitmapScale, displayDays.size, job = coroutineContext[Job], headerData = headerRenderData)
            renderMs = SystemClock.elapsedRealtime() - renderStartMs
            views.setImageViewBitmap(R.id.graph_view, bitmap)

            // Hide RemoteViews header text — now rendered in bitmap
            views.setViewVisibility(R.id.current_temp, View.INVISIBLE)
            views.setViewVisibility(R.id.current_temp_delta, View.INVISIBLE)
            views.setViewVisibility(R.id.precip_probability, View.INVISIBLE)
            views.setViewVisibility(R.id.weather_icon, View.INVISIBLE)
            views.setViewVisibility(R.id.api_source, View.INVISIBLE)
            views.setViewVisibility(R.id.settings_icon, View.INVISIBLE)
            views.setViewVisibility(R.id.header_date_center, View.GONE)
            views.setViewVisibility(R.id.header_date_right, View.GONE)

            setupGraphDayClickHandlers(context, views, appWidgetId, now, displayDays, lat, lon, displaySource, displayDays.size)
            setupGraphBottomDayClickHandlers(context, views, appWidgetId, now, displayDays, lat, lon, displaySource, displayDays.size)
        } else {
            setTextModeViews(views)

            val textCols = numColumns.coerceAtLeast(1)
            views.setViewPadding(
                R.id.widget_root,
                WidgetSizeCalculator.dpToPx(context, TEXT_MODE_ROOT_LEFT_PADDING_DP),
                WidgetSizeCalculator.dpToPx(context, TEXT_MODE_ROOT_TOP_PADDING_DP),
                WidgetSizeCalculator.dpToPx(context, TEXT_MODE_ROOT_RIGHT_PADDING_DP),
                WidgetSizeCalculator.dpToPx(context, TEXT_MODE_ROOT_BOTTOM_PADDING_DP),
            )
            val rightPaddingPx = WidgetSizeCalculator.dpToPx(context, TEXT_MODE_CONTENT_RIGHT_PADDING_DP)
            views.setViewPadding(R.id.text_container, 0, 0, rightPaddingPx, 0)

            val visibleDaysInfo = updateTextMode(
                context, views, now, centerDate, today, weatherByDate,
                hourlyForecasts, textCols, displaySource, skipHistory,
                stateManager, appWidgetId, precipProb, dailyActuals, climateNormals,
                currentTemps,
                currentTemp = currentTemp,
                observedAt = observedAt
            )

            logDailyRenderSummary(
                context = context,
                appWidgetId = appWidgetId,
                dateOffset = dateOffset,
                displaySource = displaySource,
                numColumns = numColumns,
                numRows = numRows,
                useGraph = false,
                isEveningMode = isEveningMode,
                centerDate = centerDate,
                visibleDates = visibleDaysInfo.map { it.date },
            )

            // setupTextDayClickHandlers(context, views, appWidgetId, now, visibleDaysInfo, lat, lon, displaySource)
        }

        appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=DAILY state=data thread=${Thread.currentThread().name}")
        appWidgetManager.updateAppWidget(appWidgetId, views)
        val totalMs = SystemClock.elapsedRealtime() - handlerStartMs
        WidgetPerfLogger.logIfSlow(
            appLogDao = appLogDao,
            thresholdMs = WidgetPerfLogger.WIDGET_RENDER_SLOW_MS,
            totalMs = totalMs,
            appLogTag = WidgetPerfLogger.TAG_WIDGET_RENDER_PERF,
            message = WidgetPerfLogger.kv(
                "token" to startupToken,
                "widget" to appWidgetId,
                "view" to "DAILY",
                "useGraph" to useGraph,
                "resolveMs" to resolveMs,
                "prepareMs" to prepareMs,
                "renderMs" to renderMs,
                "forecastCount" to weatherList.size,
                "hourlyCount" to hourlyForecasts.size,
                "totalMs" to totalMs,
            ),
            debugTag = TAG,
        )
    }

    private fun setGraphModeViews(views: RemoteViews) {
        views.setViewVisibility(R.id.text_container, View.GONE)
        views.setViewVisibility(R.id.graph_view, View.VISIBLE)
        views.setViewVisibility(R.id.graph_day_zones, View.VISIBLE)
        views.setViewVisibility(R.id.graph_hour_zones, View.GONE)
        views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_hour_zones, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_reserved_space, View.VISIBLE)
        views.setViewVisibility(R.id.graph_bottom_day_zones, View.VISIBLE)
        setSingleRowControlsVisible(views, false)
        views.setViewVisibility(R.id.api_source_container, View.VISIBLE)
        views.setViewVisibility(R.id.api_touch_zone, View.VISIBLE)
        views.setViewVisibility(R.id.settings_icon, View.VISIBLE)
        views.setViewVisibility(R.id.settings_touch_zone, View.VISIBLE)
    }

    private fun setTextModeViews(views: RemoteViews) {
        views.setViewVisibility(R.id.text_container, View.VISIBLE)
        views.setViewVisibility(R.id.graph_view, View.GONE)
        views.setViewVisibility(R.id.header_date_center, View.GONE)
        views.setViewVisibility(R.id.header_date_right, View.GONE)
        views.setViewVisibility(R.id.graph_day_zones, View.GONE)
        views.setViewVisibility(R.id.graph_hour_zones, View.GONE)
        views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_hour_zones, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_hour_footer_zones, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_reserved_space, View.VISIBLE)
        views.setViewVisibility(R.id.graph_bottom_day_zones, View.GONE)

        views.setViewVisibility(R.id.nav_left, View.GONE)
        views.setViewVisibility(R.id.nav_left_zone, View.GONE)
        views.setViewVisibility(R.id.nav_right, View.GONE)
        views.setViewVisibility(R.id.nav_right_zone, View.GONE)
        views.setViewVisibility(R.id.home_icon, View.GONE)
        views.setViewVisibility(R.id.home_touch_zone, View.GONE)
        views.setViewVisibility(R.id.home_touch_zone_inline, View.GONE)
        views.setViewVisibility(R.id.history_icon, View.GONE)
        views.setViewVisibility(R.id.history_touch_zone, View.GONE)
        views.setViewVisibility(R.id.history_touch_zone_inline, View.GONE)
        views.setViewVisibility(R.id.weather_stations_icon, View.GONE)
        views.setViewVisibility(R.id.weather_stations_touch_zone, View.GONE)
        views.setViewVisibility(R.id.weather_stations_touch_zone_inline, View.GONE)

        views.setViewVisibility(R.id.current_temp_zone, View.GONE)
        views.setViewVisibility(R.id.precip_touch_zone, View.GONE)
        views.setViewVisibility(R.id.api_source_container, View.GONE)
        views.setViewVisibility(R.id.api_touch_zone, View.GONE)
        views.setViewVisibility(R.id.settings_icon, View.GONE)
        views.setViewVisibility(R.id.settings_touch_zone, View.GONE)
        setSingleRowControlsVisible(views, true)
    }

    private fun setSingleRowControlsVisible(views: RemoteViews, visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.text_mode_api_source_container, visibility)
        views.setViewVisibility(R.id.text_mode_api_touch_zone, visibility)
        views.setViewVisibility(R.id.text_mode_settings_icon, visibility)
        views.setViewVisibility(R.id.text_mode_settings_touch_zone, visibility)
    }

    private fun logGraphDayIconDetails(
        context: Context,
        appWidgetId: Int,
        displayDays: List<DailyForecastGraphRenderer.DayData>,
    ) {
        displayDays.forEachIndexed { index, day ->
            val colIndex = day.columnIndex ?: index
            val iconRes = day.iconRes
            val iconName =
                iconRes?.let {
                    runCatching { context.resources.getResourceEntryName(it) }.getOrNull()
                } ?: "null"
            Log.d(
                TAG,
                "graphDay widget=$appWidgetId col=${colIndex + 1} date=${day.date} " +
                    "isToday=${day.isToday} iconRes=$iconRes iconName=$iconName " +
                    "isRainy=${iconRes?.let(WeatherIconMapper::isPrecipitation) ?: false} " +
                    "isCloudEligible=${iconRes?.let(WeatherIconMapper::isCloudForecastEligible) ?: false} " +
                    "hasRainForecast=${day.rainData.hasRainForecast}",
            )
        }
    }

    private suspend fun requestMissingDataRefresh(
        context: Context,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        displaySource: WeatherSource,
        refreshType: String,
        cooldownMs: Long,
        logTag: String,
        forceRefresh: Boolean,
        reason: String,
        message: String,
    ) {
        if (!stateManager.shouldRefreshMissingData(appWidgetId, displaySource.id, refreshType, cooldownMs)) {
            return
        }
        stateManager.markMissingDataRefreshRequested(appWidgetId, displaySource.id, refreshType)
        WeatherDatabase.getDatabase(context).appLogDao().log(logTag, message, "INFO")
        WeatherWidgetProvider.triggerImmediateUpdate(
            context = context,
            forceRefresh = forceRefresh,
            reason = reason,
        )
    }

    private fun setupCurrentTempToggle(context: Context, views: RemoteViews, appWidgetId: Int) {
        HeaderTapTargetHelper.bindToggleTemperatureHeader(context, views, appWidgetId)
        HeaderTapTargetHelper.bindPrecipitationHeader(context, views, appWidgetId)
    }

    private suspend fun logDailyRenderSummary(
        context: Context,
        appWidgetId: Int,
        dateOffset: Int,
        displaySource: WeatherSource,
        numColumns: Int,
        numRows: Int,
        useGraph: Boolean,
        isEveningMode: Boolean,
        centerDate: LocalDate,
        visibleDates: List<LocalDate>,
    ) {
        val mode = if (useGraph) "GRAPH" else "TEXT"
        val datesSummary = visibleDates.joinToString(",").ifEmpty { "<none>" }
        val tag = if (visibleDates.isEmpty()) "DAILY_RENDER_EMPTY" else "DAILY_RENDER"
        WeatherDatabase.getDatabase(context).appLogDao().log(
            tag,
            "widget=$appWidgetId mode=$mode offset=$dateOffset cols=$numColumns rows=$numRows evening=$isEveningMode center=$centerDate source=${displaySource.id} days=${visibleDates.size} dates=$datesSummary"
        )
    }

    private fun setupSettingsShortcut(context: Context, views: RemoteViews, appWidgetId: Int) {
        val settingsIntent = Intent(context, SettingsActivity::class.java)
        val settingsPendingIntent = PendingIntent.getActivity(
            context, WidgetRequestCodes.settings(appWidgetId), settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.settings_icon, settingsPendingIntent)
        views.setOnClickPendingIntent(R.id.settings_touch_zone, settingsPendingIntent)
        views.setOnClickPendingIntent(R.id.text_mode_settings_icon, settingsPendingIntent)
        views.setOnClickPendingIntent(R.id.text_mode_settings_touch_zone, settingsPendingIntent)
    }

    private fun setupApiToggle(context: Context, views: RemoteViews, appWidgetId: Int, numRows: Int) {
        val toggleIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_API
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context, WidgetRequestCodes.apiToggle(appWidgetId), toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.api_source_container, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.api_touch_zone, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.text_mode_api_source_container, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.text_mode_api_touch_zone, togglePendingIntent)

        val textSizeDp = HeaderConstants.apiTextSizeDp(numRows)
        val apiPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, textSizeDp, context.resources.displayMetrics)
        views.setTextViewTextSize(R.id.api_source, TypedValue.COMPLEX_UNIT_PX, apiPx)
        views.setTextViewTextSize(R.id.text_mode_api_source, TypedValue.COMPLEX_UNIT_PX, apiPx)
    }

    private fun bindHeaderDate(
        context: Context,
        views: RemoteViews,
        widthDp: Int,
        numColumns: Int,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        apiSourceText: String,
        apiTextSizeDp: Float,
        dateText: String,
    ) {
        views.setViewVisibility(R.id.header_date_center, View.GONE)
        views.setViewVisibility(R.id.header_date_right, View.GONE)
        if (dateText.isBlank()) return

        val placement =
            resolveHeaderDatePlacement(
                context = context,
                widthDp = widthDp,
                numColumns = numColumns,
                currentTempText = currentTempText,
                deltaText = deltaText,
                precipText = precipText,
                precipTextSizeDp = precipTextSizeDp,
                apiSourceText = apiSourceText,
                apiTextSizeDp = apiTextSizeDp,
                dateText = dateText,
            ) ?: return

        val targetId =
            when (placement) {
                HeaderDatePlacement.CENTER -> R.id.header_date_center
                HeaderDatePlacement.RIGHT -> R.id.header_date_right
            }
        views.setTextViewText(targetId, dateText)
        val datePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, HeaderConstants.DATE_TEXT_SIZE_DP, context.resources.displayMetrics)
        views.setTextViewTextSize(targetId, TypedValue.COMPLEX_UNIT_PX, datePx)
        views.setViewVisibility(targetId, View.VISIBLE)
    }

    @VisibleForTesting
    internal fun resolveHeaderDatePlacement(
        context: Context,
        widthDp: Int,
        numColumns: Int,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        apiSourceText: String,
        apiTextSizeDp: Float,
        dateText: String,
        includeIcon: Boolean = true,
    ): HeaderDatePlacement? {
        if (numColumns < HeaderConstants.DATE_MIN_COLUMNS) return null

        val widthPx = WidgetSizeCalculator.dpToPx(context, widthDp).toFloat()
        val leftClusterRight =
            resolveLeftHeaderClusterRightPx(
                context = context,
                currentTempText = currentTempText,
                deltaText = deltaText,
                precipText = precipText,
                precipTextSizeDp = precipTextSizeDp,
                includeIcon = includeIcon,
            )
        val apiLeft = resolveApiLeftPx(context, widthPx, apiSourceText, apiTextSizeDp)
        val dateWidth = textWidthPx(context, dateText, HeaderConstants.DATE_TEXT_SIZE_DP)
        val gapPx = dpToPx(context, HeaderConstants.DATE_HORIZONTAL_GAP_DP)
        val rightMarginPx = dpToPx(context, HeaderConstants.DATE_RIGHT_MARGIN_DP)

        return resolveHeaderDatePlacementFromBounds(
            numColumns = numColumns,
            widthPx = widthPx,
            leftClusterRight = leftClusterRight,
            apiLeft = apiLeft,
            dateWidth = dateWidth,
            gapPx = gapPx,
            rightMarginPx = rightMarginPx,
        )
    }

    @VisibleForTesting
    internal fun resolveHeaderDatePlacementFromBounds(
        numColumns: Int,
        widthPx: Float,
        leftClusterRight: Float,
        apiLeft: Float,
        dateWidth: Float,
        gapPx: Float,
        rightMarginPx: Float,
    ): HeaderDatePlacement? {
        if (numColumns < HeaderConstants.DATE_MIN_COLUMNS) return null
        val centerLeft = (widthPx - dateWidth) / 2f
        val centerRight = centerLeft + dateWidth
        if (centerLeft >= leftClusterRight + gapPx && centerRight <= apiLeft - gapPx) {
            return HeaderDatePlacement.CENTER
        }

        val rightCenter = widthPx - rightMarginPx
        val rightLeft = rightCenter - dateWidth / 2f
        val rightRight = rightCenter + dateWidth / 2f
        if (rightLeft >= leftClusterRight + gapPx && rightRight <= apiLeft - gapPx) {
            return HeaderDatePlacement.RIGHT
        }

        return null
    }

    @VisibleForTesting
    internal fun resolveHeaderPrecipPlacement(
        context: Context,
        widthDp: Int,
        numColumns: Int,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        apiSourceText: String,
        apiTextSizeDp: Float,
        dateText: String?,
        headerCanShowPrecip: Boolean,
        includeIcon: Boolean,
    ): HeaderPrecipPlacement {
        if (precipText.isNullOrBlank() || precipTextSizeDp == null) {
            return HeaderPrecipPlacement(showHeaderPrecip = false, allowTodayColumnPrecip = false)
        }
        if (dateText.isNullOrBlank() || numColumns < HeaderConstants.DATE_MIN_COLUMNS) {
            return HeaderPrecipPlacement(showHeaderPrecip = headerCanShowPrecip, allowTodayColumnPrecip = false)
        }

        val dateFitsWithPrecip =
            headerCanShowPrecip &&
                resolveHeaderDatePlacement(
                    context = context,
                    widthDp = widthDp,
                    numColumns = numColumns,
                    currentTempText = currentTempText,
                    deltaText = deltaText,
                    precipText = precipText,
                    precipTextSizeDp = precipTextSizeDp,
                    apiSourceText = apiSourceText,
                    apiTextSizeDp = apiTextSizeDp,
                    dateText = dateText,
                    includeIcon = includeIcon,
                ) != null
        if (dateFitsWithPrecip) {
            return HeaderPrecipPlacement(showHeaderPrecip = true, allowTodayColumnPrecip = false)
        }

        val dateFitsWithoutPrecip =
            resolveHeaderDatePlacement(
                context = context,
                widthDp = widthDp,
                numColumns = numColumns,
                currentTempText = currentTempText,
                deltaText = deltaText,
                precipText = null,
                precipTextSizeDp = null,
                apiSourceText = apiSourceText,
                apiTextSizeDp = apiTextSizeDp,
                dateText = dateText,
                includeIcon = includeIcon,
            ) != null

        return HeaderPrecipPlacement(
            showHeaderPrecip = headerCanShowPrecip && !dateFitsWithoutPrecip,
            allowTodayColumnPrecip = false,
        )
    }

    private fun resolveLeftHeaderClusterRightPx(
        context: Context,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        includeIcon: Boolean = true,
    ): Float {
        var width = if (includeIcon) dpToPx(context, HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP) else 0f
        if (!currentTempText.isNullOrBlank()) {
            width += currentTempTextWidthPx(context, currentTempText)
        }
        if (!deltaText.isNullOrBlank()) {
            width += dpToPx(context, HeaderConstants.DELTA_MARGIN_START_DP) + textWidthPx(context, deltaText, HeaderConstants.DELTA_TEXT_SIZE_DP)
        }
        if (!precipText.isNullOrBlank() && precipTextSizeDp != null) {
            width += dpToPx(context, HeaderConstants.PRECIP_MARGIN_START_DP) + textWidthPx(context, precipText, precipTextSizeDp)
        }
        return width
    }

    private fun resolveApiLeftPx(
        context: Context,
        widthPx: Float,
        apiSourceText: String,
        apiTextSizeDp: Float,
    ): Float {
        val apiContainerWidth = dpToPx(context, HeaderConstants.API_SOURCE_CONTAINER_PADDING_DP) + textWidthPx(context, apiSourceText, apiTextSizeDp)
        return widthPx - dpToPx(context, HeaderConstants.API_SOURCE_MARGIN_END_DP) - apiContainerWidth
    }

    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun textWidthPx(context: Context, text: String, textSizeDp: Float): Float {
        measurePaint.textSize =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                textSizeDp,
                context.resources.displayMetrics,
            )
        return measurePaint.measureText(text)
    }

    private fun currentTempTextWidthPx(context: Context, text: String): Float {
        measurePaint.textSize = currentTempTextSizePx(context)
        return measurePaint.measureText(text)
    }

    @VisibleForTesting
    internal fun currentTempTextSizePx(context: Context): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
            context.resources.displayMetrics,
        )

    private fun dpToPx(context: Context, dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)

    @VisibleForTesting
    internal fun resolveTodayHeaderForecast(
        now: LocalDateTime,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
    ): HourlyForecastEntity? {
        val today = now.toLocalDate()
        val forecastsByTime = resolveForecastsByTime(hourlyForecasts, displaySource)

        val candidateTimes =
            listOf(
                now.plusHours(1).takeIf { it.toLocalDate() == today },
                now,
            ).filterNotNull()

        return candidateTimes.firstNotNullOfOrNull { candidateTime ->
            forecastsByTime[WeatherTimeUtils.toHourlyForecastKeyMs(candidateTime)]
        }
    }

    private fun setupNavigationButtons(
        context: Context, views: RemoteViews, appWidgetId: Int,
        stateManager: WidgetStateManager, availableDates: Set<LocalDate>,
        numColumns: Int, isEveningMode: Boolean, today: LocalDate,
        useGraph: Boolean,
    ) {
        val sortedDates = availableDates.sorted()
        val minDate = sortedDates.firstOrNull()
        val maxDate = sortedDates.lastOrNull()

        val (leftmost, _) = NavigationUtils.getVisibleDateRange(today, stateManager.getDateOffset(appWidgetId) - 1, numColumns, isEveningMode)
        val (_, rightmost) = NavigationUtils.getVisibleDateRange(today, stateManager.getDateOffset(appWidgetId) + 1, numColumns, isEveningMode)

        val canLeft = minDate != null && !minDate.isAfter(leftmost)
        val canRight = maxDate != null && !maxDate.isBefore(rightmost)
        
        Log.d(TAG, "setupNavigationButtons: id=$appWidgetId, leftmostVisibleIfNavLeft=$leftmost, minAvailableDate=$minDate, canLeft=$canLeft")
        Log.d(TAG, "setupNavigationButtons: id=$appWidgetId, rightmostVisibleIfNavRight=$rightmost, maxAvailableDate=$maxDate, canRight=$canRight")

        if (!useGraph) {
            views.setViewVisibility(R.id.nav_left, View.GONE)
            views.setViewVisibility(R.id.nav_left_zone, View.GONE)
            views.setViewVisibility(R.id.nav_right, View.GONE)
            views.setViewVisibility(R.id.nav_right_zone, View.GONE)
            return
        }

        views.setViewVisibility(R.id.nav_left, View.VISIBLE)
        views.setViewVisibility(R.id.nav_left_zone, View.VISIBLE)
        val paddingPx = WidgetSizeCalculator.dpToPx(context, 10)
        views.setViewPadding(R.id.nav_left, paddingPx, 0, paddingPx, 0)

        if (canLeft) {
            val leftIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_NAV_LEFT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val leftPendingIntent = PendingIntent.getBroadcast(
                context, WidgetRequestCodes.navLeft(appWidgetId), leftIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.nav_left, leftPendingIntent)
            views.setOnClickPendingIntent(R.id.nav_left_zone, leftPendingIntent)
        } else {
            val toastIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_SHOW_TOAST
                putExtra(WeatherWidgetProvider.EXTRA_TOAST_MESSAGE, "No additional history available")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val toastPendingIntent = PendingIntent.getBroadcast(
                context, WidgetRequestCodes.navLeft(appWidgetId), toastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.nav_left, toastPendingIntent)
            views.setOnClickPendingIntent(R.id.nav_left_zone, toastPendingIntent)
        }

        views.setViewVisibility(R.id.nav_right, View.VISIBLE)
        views.setViewVisibility(R.id.nav_right_zone, View.VISIBLE)

        if (canRight) {
            val rightIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_NAV_RIGHT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val rightPendingIntent = PendingIntent.getBroadcast(
                context, WidgetRequestCodes.navRight(appWidgetId), rightIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.nav_right, rightPendingIntent)
            views.setOnClickPendingIntent(R.id.nav_right_zone, rightPendingIntent)
        } else {
            val toastIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_SHOW_TOAST
                putExtra(WeatherWidgetProvider.EXTRA_TOAST_MESSAGE, "No more forecast available")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val toastPendingIntent = PendingIntent.getBroadcast(
                context, WidgetRequestCodes.navRight(appWidgetId), toastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.nav_right, toastPendingIntent)
            views.setOnClickPendingIntent(R.id.nav_right_zone, toastPendingIntent)
        }
    }

    private fun updateTextMode(
        context: Context, views: RemoteViews, now: LocalDateTime, centerDate: LocalDate,
        today: LocalDate, weatherByDate: Map<LocalDate, ForecastEntity>,
        hourlyForecasts: List<HourlyForecastEntity>, numColumns: Int,
        displaySource: WeatherSource, skipHistory: Boolean,
        stateManager: WidgetStateManager?, appWidgetId: Int,
        todayNext8HourPrecipProbability: Int?,
        dailyActuals: DailyActualMap = emptyMap(),
        climateNormals: Map<java.time.MonthDay, Pair<Int, Int>> = emptyMap(),
        currentTemps: List<ObservationEntity> = emptyList(),
        currentTemp: Float? = null,
        observedAt: Long? = null
    ): List<DailyViewLogic.TextDayData> {
        val dayDataList = DailyViewLogic.prepareTextDays(
            now, centerDate, today, weatherByDate, hourlyForecasts, numColumns,
            displaySource, skipHistory, stateManager, appWidgetId, todayNext8HourPrecipProbability, dailyActuals,
            climateNormals,
            currentTemps,
            currentTemp = currentTemp,
            observedAt = observedAt
        )

        val dayIds = listOf(
            DayIds(R.id.day1_container, R.id.day1_label, R.id.day1_icon, R.id.day1_high, R.id.day1_low, R.id.day1_rain),
            DayIds(R.id.day2_container, R.id.day2_label, R.id.day2_icon, R.id.day2_high, R.id.day2_low, R.id.day2_rain),
            DayIds(R.id.day3_container, R.id.day3_label, R.id.day3_icon, R.id.day3_high, R.id.day3_low, R.id.day3_rain),
            DayIds(R.id.day4_container, R.id.day4_label, R.id.day4_icon, R.id.day4_high, R.id.day4_low, R.id.day4_rain),
            DayIds(R.id.day5_container, R.id.day5_label, R.id.day5_icon, R.id.day5_high, R.id.day5_low, R.id.day5_rain),
            DayIds(R.id.day6_container, R.id.day6_label, R.id.day6_icon, R.id.day6_high, R.id.day6_low, R.id.day6_rain),
            DayIds(R.id.day7_container, R.id.day7_label, R.id.day7_icon, R.id.day7_high, R.id.day7_low, R.id.day7_rain),
            DayIds(R.id.day8_container, R.id.day8_label, R.id.day8_icon, R.id.day8_high, R.id.day8_low, R.id.day8_rain),
        )

        dayDataList.forEachIndexed { index, data ->
            val ids = dayIds[index]
            if (data.isVisible) {
                views.setViewVisibility(ids.container, View.VISIBLE)
                populateDay(context, views, now, ids, data, hourlyForecasts, displaySource)
            } else {
                views.setViewVisibility(ids.container, View.GONE)
            }
        }

        if (dayDataList.any { it.isToday && it.rainSummary != null }) {
            stateManager?.markRainShown(appWidgetId, today.format(DateTimeFormatter.ISO_LOCAL_DATE))
        }

        return dayDataList.filter { it.isVisible }
    }

    private fun populateDay(
        context: Context, views: RemoteViews, now: LocalDateTime,
        ids: DayIds, data: DailyViewLogic.TextDayData,
        hourlyForecasts: List<HourlyForecastEntity>, displaySource: WeatherSource
    ) {
        views.setTextViewText(ids.label, data.label)
        
        val iconRes = data.iconRes
        views.setImageViewResource(ids.icon, iconRes)

        if (!WeatherIconMapper.isPrecipitation(iconRes) && !WeatherIconMapper.isMixed(iconRes)) {
            val tintColor = if (WeatherIconMapper.isSunny(iconRes)) {
                context.getColor(R.color.sunny_yellow)
            } else {
                context.getColor(R.color.weather_icon_tint_default)
            }
            views.setInt(ids.icon, "setColorFilter", tintColor)
        } else {
            // Clear tint to show natural colors (e.g. blue raindrops)
            views.setInt(ids.icon, "setColorFilter", 0)
        }

        views.setViewVisibility(ids.icon, View.VISIBLE)
        views.setTextViewText(ids.high, data.highLabel ?: "--°")
        views.setTextViewText(ids.low, data.lowLabel ?: "--°")

        if (data.showRain && !data.rainSummary.isNullOrEmpty()) {
            views.setTextViewText(ids.rain, "💧 ${data.rainSummary}")
            views.setViewVisibility(ids.rain, View.VISIBLE)
        } else {
            views.setViewVisibility(ids.rain, View.GONE)
        }
    }

    @VisibleForTesting
    internal fun buildDayClickIntent(
        context: Context, appWidgetId: Int, dayIndex: Int, date: LocalDate,
        iconRes: Int?, lat: Double, lon: Double,
        displaySource: WeatherSource,
        now: LocalDateTime = LocalDateTime.now(),
        targetModeOverride: ViewMode? = null,
    ): Intent {
        val isHistory = date.isBefore(now.toLocalDate())
        val showHistory = DayClickHelper.shouldShowHistory(isHistory)

        return Intent(context, WeatherWidgetProvider::class.java).apply {
            action = ACTION_DAY_CLICK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra("date", date.toString())
            putExtra("isHistory", isHistory)
            putExtra("showHistory", showHistory)
            putExtra("index", dayIndex)
            putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
            putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
            putExtra(ForecastHistoryActivity.EXTRA_SOURCE, displaySource.displayName)

            if (!showHistory) {
                val targetMode = targetModeOverride ?: DayClickHelper.resolveDailyTargetViewMode(iconRes)
                val offset = DayClickHelper.calculatePrecipitationOffset(now, date)
                putExtra(EXTRA_TARGET_VIEW, targetMode.name)
                putExtra(EXTRA_HOURLY_OFFSET, offset)
            }
        }
    }

    private fun setupTextDayClickHandlers(
        context: Context, views: RemoteViews, appWidgetId: Int, now: LocalDateTime,
        visibleDays: List<DailyViewLogic.TextDayData>, lat: Double, lon: Double, displaySource: WeatherSource
    ) {
        val containerIds = listOf(R.id.day1_container, R.id.day2_container, R.id.day3_container, R.id.day4_container, R.id.day5_container, R.id.day6_container, R.id.day7_container, R.id.day8_container)
        visibleDays.forEach { day ->
            val intent = buildDayClickIntent(context, appWidgetId, day.dayIndex, day.date, day.iconRes, lat, lon, displaySource, now)
            val pendingIntent = PendingIntent.getBroadcast(context, WidgetRequestCodes.dayClick(appWidgetId, day.dayIndex), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(containerIds[day.dayIndex - 1], pendingIntent)
        }
    }

    @VisibleForTesting
    internal fun setupGraphDayClickHandlers(
        context: Context, views: RemoteViews, appWidgetId: Int, now: LocalDateTime,
        days: List<DailyForecastGraphRenderer.DayData>, lat: Double, lon: Double, displaySource: WeatherSource,
        numColumns: Int
    ) {
        val zoneIds = listOf(
            R.id.graph_day1_zone, R.id.graph_day2_zone, R.id.graph_day3_zone, R.id.graph_day4_zone,
            R.id.graph_day5_zone, R.id.graph_day6_zone, R.id.graph_day7_zone, R.id.graph_day8_zone,
            R.id.graph_day9_zone, R.id.graph_day10_zone
        )
        setupGraphZoneClickHandlers(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            now = now,
            days = days,
            lat = lat,
            lon = lon,
            displaySource = displaySource,
            numColumns = numColumns,
            zoneIds = zoneIds,
            requestCodeOffset = 0,
        )
    }

    @VisibleForTesting
    internal fun setupGraphBottomDayClickHandlers(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        now: LocalDateTime,
        days: List<DailyForecastGraphRenderer.DayData>,
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
        numColumns: Int,
    ) {
        val zoneIds = listOf(
            R.id.graph_bottom_day1_zone, R.id.graph_bottom_day2_zone, R.id.graph_bottom_day3_zone, R.id.graph_bottom_day4_zone,
            R.id.graph_bottom_day5_zone, R.id.graph_bottom_day6_zone, R.id.graph_bottom_day7_zone, R.id.graph_bottom_day8_zone,
            R.id.graph_bottom_day9_zone, R.id.graph_bottom_day10_zone,
        )
        setupGraphZoneClickHandlers(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            now = now,
            days = days,
            lat = lat,
            lon = lon,
            displaySource = displaySource,
            numColumns = numColumns,
            zoneIds = zoneIds,
            requestCodeOffset = 100,
            resolveTargetMode = { iconRes -> DayClickHelper.resolveBottomRowTargetViewMode(iconRes) },
        )
    }

    private fun setupGraphZoneClickHandlers(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        now: LocalDateTime,
        days: List<DailyForecastGraphRenderer.DayData>,
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
        numColumns: Int,
        zoneIds: List<Int>,
        requestCodeOffset: Int = 0,
        resolveTargetMode: ((Int?) -> ViewMode)? = null,
    ) {
        for (i in zoneIds.indices) {
            val zoneId = zoneIds[i]
            if (i < numColumns) {
                views.setViewVisibility(zoneId, View.VISIBLE)
                views.setOnClickPendingIntent(zoneId, null)
            } else {
                views.setViewVisibility(zoneId, View.GONE)
            }
        }

        days.forEachIndexed { index, dayData ->
            val colIndex = dayData.columnIndex ?: index
            val zoneId = zoneIds.getOrNull(colIndex) ?: return@forEachIndexed
            val targetModeOverride = resolveTargetMode?.invoke(dayData.iconRes)
            val intent = buildDayClickIntent(
                context = context,
                appWidgetId = appWidgetId,
                dayIndex = colIndex + 1,
                date = dayData.date,
                iconRes = dayData.iconRes,
                lat = lat,
                lon = lon,
                displaySource = displaySource,
                now = now,
                targetModeOverride = targetModeOverride,
            )
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WidgetRequestCodes.graphClick(appWidgetId, colIndex + requestCodeOffset),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(zoneId, pendingIntent)
        }
    }

    private fun dailyDeltaHiddenReason(
        currentTemp: Float?,
        appliedDelta: Float?,
    ): String? =
        when {
            currentTemp == null -> "current_temp_missing"
            appliedDelta == null -> "no_delta"
            kotlin.math.abs(appliedDelta) < DELTA_VISIBILITY_THRESHOLD -> "below_threshold"
            else -> null
        }

    private fun buildHeaderStateLog(
        widgetId: Int,
        viewMode: ViewMode,
        displaySource: WeatherSource,
        configuredLocation: Pair<Double, Double>?,
        dataLat: Double,
        dataLon: Double,
        dimensions: WidgetDimensions,
        currentTemp: Float?,
        estimatedTemp: Float?,
        observedTemp: Float?,
        appliedDelta: Float?,
        deltaVisible: Boolean,
        deltaHiddenReason: String?,
        precipVisible: Boolean,
        precipProbability: Int?,
        isNowLineVisible: Boolean?,
        offset: Int,
        zoom: ZoomLevel?,
        resolveMs: Long,
    ): String =
        "headerState widget=$widgetId mode=${viewMode.name} source=${displaySource.id} " +
            "configuredLoc=${formatLocation(configuredLocation)} dataLoc=${formatLocation(dataLat to dataLon)} " +
            "cols=${dimensions.cols} rows=${dimensions.rows} sizeDp=${dimensions.widthDp}x${dimensions.heightDp} " +
            "currentTemp=${formatTemp(currentTemp)} estimatedTemp=${formatTemp(estimatedTemp)} " +
            "observedTemp=${formatTemp(observedTemp)} appliedDelta=${formatTemp(appliedDelta)} " +
            "deltaVisible=$deltaVisible deltaHiddenReason=${deltaHiddenReason ?: "none"} " +
            "precipVisible=$precipVisible precipProbability=${precipProbability ?: "none"} " +
            "isNowLineVisible=${isNowLineVisible ?: "n/a"} offset=$offset zoom=${zoom?.name ?: "n/a"} resolveMs=$resolveMs"

    private fun formatLocation(location: Pair<Double, Double>?): String {
        if (location == null) return "none"
        return String.format("%.5f,%.5f", location.first, location.second)
    }

    private fun formatTemp(value: Float?): String = value?.let { String.format("%.2f", it) } ?: "none"
}
