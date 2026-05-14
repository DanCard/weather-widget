/**
 * Handler for the daily forecast view mode.
 */
package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
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
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import com.weatherwidget.widget.handlers.WidgetRequestCodes
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

object DailyViewHandler : WidgetViewHandler {
    private const val TAG = "DailyViewHandler"
    private const val CELL_HEIGHT_DP = 90
    private const val MISSING_ACTUALS_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L
    private const val MISSING_TODAY_SNAPSHOT_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L
    private const val DELTA_VISIBILITY_THRESHOLD = DailyHeaderBinder.DELTA_VISIBILITY_THRESHOLD
    private const val DELTA_COLOR_HEX = "#FF6B35"
    private const val GRAPH_HEIGHT_PADDING_DP = 25f
    private const val GRAPH_ROW_THRESHOLD = 2.2f
    private const val GRAPH_CONTENT_PADDING_DP = 24
    private const val TEXT_MODE_ROOT_LEFT_PADDING_DP = 2
    private const val TEXT_MODE_ROOT_TOP_PADDING_DP = 0
    private const val TEXT_MODE_ROOT_RIGHT_PADDING_DP = 8
    private const val TEXT_MODE_ROOT_BOTTOM_PADDING_DP = 0
    private const val TEXT_MODE_CONTENT_RIGHT_PADDING_DP = 18
    private val headerDateFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())

    private data class DayIds(
        val container: Int,
        val label: Int,
        val icon: Int,
        val high: Int,
        val low: Int,
        val rain: Int,
    )

    // Intent actions from WidgetActions

    internal const val NIGHT_RAIN_GRID_ROWS = NightRainGridMapper.GRID_ROWS
    internal const val NIGHT_RAIN_GRID_COLS = NightRainGridMapper.GRID_COLS

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
        lastObservedTemp: Float?,
        observedAt: Long?,
        now: LocalDateTime,
        startupToken: String?,
        smoothedForecasts: Map<Long, Float>?,
        stateManager: WidgetStateManager?,
    ) {
        Log.d(TAG, "updateWidget: [START] widgetId=$appWidgetId at time=$now")
        val handlerStartMs = SystemClock.elapsedRealtime()
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val numRows = dimensions.rows
        val isIconWidth = dimensions.isIconWidth

        // Use graph mode for 2+ rows
        val rawRows = (dimensions.heightDp + GRAPH_HEIGHT_PADDING_DP) / CELL_HEIGHT_DP
        val useGraph = rawRows >= GRAPH_ROW_THRESHOLD

        val stateManager = stateManager ?: WidgetStateManager(context)
        val dateOffset = stateManager.getDateOffset(appWidgetId)

        val skipYesterday = NavigationUtils.shouldSkipYesterday(now.toLocalTime(), numColumns)

        // Single source of truth for time in this update cycle
        val today = now.toLocalDate()
        val skipHistory = NavigationUtils.shouldSkipHistory(skipYesterday, dateOffset)
        val centerDate = NavigationUtils.getDisplayCenterDate(today, dateOffset, skipYesterday)

        // Setup common click actions.
        // At 1 icon wide, skip wiring API toggle and settings shortcut since the
        // tap targets are hidden (see hideIconWidthControls below).
        setupCurrentTempToggle(context, views, appWidgetId)
        if (!isIconWidth) {
            setupSettingsShortcut(context, views, appWidgetId, includeTextMode = true)
        }

        // Get the current display source for this widget
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val dailyActuals = dailyActualsBySource[displaySource.id].orEmpty()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()

        Log.d(
            TAG,
            "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, offset=$dateOffset, " +
                "skipYesterday=$skipYesterday, weatherCount=${weatherList.size}, actualsCount=${dailyActuals.size}, source=${displaySource.id}",
        )

        val yesterday = today.minusDays(1)
        val yesterdayActual = dailyActuals[yesterday]
        appLogDao.log("WIDGET_ACTUAL", 
            "date=$yesterday src=${displaySource.id} low=${yesterdayActual?.lowTemp} " +
            "allDates=${dailyActuals.keys} allSources=${dailyActualsBySource.keys}", 
            "DEBUG"
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
        if (
            ApiSourceWarningHelper.checkAndRenderBlockingWarning(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                numRows = numRows,
                appLogDao = appLogDao,
                displaySource = displaySource,
                hasSelectedSourceData = weatherList.any { it.source == displaySource.id && !it.isClimateNormal },
                callerTag = "DAILY",
                includeTextMode = true,
            )
        ) {
            logDailyRenderSummary(
                context = context,
                appWidgetId = appWidgetId,
                dateOffset = dateOffset,
                displaySource = displaySource,
                numColumns = numColumns,
                numRows = numRows,
                useGraph = false,
                skipYesterday = skipYesterday,
                centerDate = centerDate,
                visibleDates = emptyList(),
            )
            appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=DAILY state=warning thread=${Thread.currentThread().name}")
            appWidgetManager.updateAppWidget(appWidgetId, views)
            return
        }

        // Build weather map: prefer the selected display source, fallback to generic gap
        val weatherByDate =
            weatherList
                .filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
                .groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
                .mapValues { (date, items) ->
                    val preferred = items.find { it.source == displaySource.id }
                    val isToday = date == today

                    // For Today, we MUST preserve the preferred source even if incomplete (e.g. NWS evening drop),
                    // because DailyViewLogic/DailyActualsEstimator have specialized recovery for Today.
                    // For other days, we can fall back to climate normals if the preferred source is missing temps.
                    if (preferred != null && !isToday && (preferred.highTemp == null || preferred.lowTemp == null)) {
                        items.find { it.source == WeatherSource.GENERIC_GAP.id && it.highTemp != null && it.lowTemp != null } ?: preferred
                    } else {
                        preferred ?: items.first()
                    }
                }

        // Dual-source ("two bars") support: parallel per-date map for the NEXT API source.
        // Empty when the setting is off or only one source is visible.
        val showTwoBars = stateManager.isShowTwoBarsEnabled()
        val nextSource = if (showTwoBars) stateManager.getNextDisplaySource(appWidgetId) else displaySource
        val nextSourceWeatherByDate: Map<LocalDate, ForecastEntity> =
            if (showTwoBars && nextSource != displaySource) {
                weatherList.filter { it.source == nextSource.id }
                    .groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
                    .mapValues { (_, items) -> items.first() }
            } else emptyMap()

        // Set API source indicator (overwritten later once dual-source fit is decided)
        views.setTextViewText(R.id.api_source, displaySource.shortDisplayName)
        views.setTextViewText(R.id.text_mode_api_source, displaySource.shortDisplayName)

        // Set weather icon
        val climateNormals = repository?.getHistoricalNormalsByMonthDay(lat, lon) ?: emptyMap()

        val todayHeaderForecast = DailyHeaderBinder.resolveTodayHeaderForecast(
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

        val (currentTempResolution, resolveMs) =
            CurrentTempResolutionHelper.resolveAndPersistDelta(
                now = now,
                displaySource = displaySource,
                hourlyForecasts = hourlyForecasts,
                lastObservedTemp = lastObservedTemp,
                observedAt = observedAt,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                lat = lat,
                lon = lon,
                smoothedForecasts = smoothedForecasts,
            )
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

        HeaderRemoteViewsBinder.bindCurrentTemp(context, views, formattedTemp, hideDeltaOnNull = true)
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
        val precipTextSizeDp = if (precipProb != null) HeaderPrecipCalculator.getPrecipTextSize(precipProb) else null
        HeaderRemoteViewsBinder.bindPrecipProbability(
            context, views,
            if (isPrecipVisible) "${precipProb ?: 0}%" else null,
            precipTextSizeDp ?: 0f,
        )
        HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, isPrecipVisible)

        val delta = currentTempResolution.appliedDelta
        val deltaVisible =
            currentTemp != null &&
            delta != null &&
            abs(delta) >= DELTA_VISIBILITY_THRESHOLD
        HeaderRemoteViewsBinder.bindDelta(
            context, views,
            if (deltaVisible) String.format("%+.1f", delta) else null,
            deltaVisible,
        )

        // Pick API label: dual-source "<first> - <second>" if it fits at the same disclosure
        // level as the single-source label; otherwise fall back to single source.
        val singleApiText = displaySource.shortDisplayName
        val candidateDualApiText = if (showTwoBars && nextSource != displaySource)
            "$singleApiText - ${nextSource.shortDisplayName}" else null
        val apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows)
        val deltaTextForFit = if (deltaVisible) String.format("%+.1f", delta) else null
        val precipTextForFit = if (isPrecipVisible) "${precipProb}%" else null
        val singleDisclosure = HeaderWidthChecker.resolveHeaderDisclosure(
            context = context,
            widthDp = dimensions.widthDp,
            apiSourceText = singleApiText,
            apiTextSizeDp = apiTextSizeDp,
            currentTempText = formattedTemp,
            deltaText = deltaTextForFit,
            precipText = precipTextForFit,
            precipTextSizeDp = precipTextSizeDp,
        )
        val (apiSourceText, disclosure) = if (candidateDualApiText != null) {
            val dualLevel = HeaderWidthChecker.resolveHeaderDisclosure(
                context = context,
                widthDp = dimensions.widthDp,
                apiSourceText = candidateDualApiText,
                apiTextSizeDp = apiTextSizeDp,
                currentTempText = formattedTemp,
                deltaText = deltaTextForFit,
                precipText = precipTextForFit,
                precipTextSizeDp = precipTextSizeDp,
            )
            if (dualLevel == singleDisclosure) candidateDualApiText to dualLevel
            else singleApiText to singleDisclosure
        } else singleApiText to singleDisclosure
        views.setTextViewText(R.id.api_source, apiSourceText)
        views.setTextViewText(R.id.text_mode_api_source, apiSourceText)

        if (useGraph && disclosure != HeaderDisclosureLevel.NONE) {
            HeaderRemoteViewsBinder.applyDisclosure(
                views,
                disclosure,
                isDeltaVisible = deltaVisible,
                isPrecipVisible = isPrecipVisible,
            )
        } else if (useGraph) {
            views.setViewVisibility(R.id.current_weather_container, View.GONE)
        }

        // Setup API source toggle click handler (skipped at 1 icon wide — target is hidden)
        if (!isIconWidth) {
            setupApiToggle(context, views, appWidgetId, numRows, includeTextMode = true)
            setupDualToggle(context, views, appWidgetId)
        }

        // Dual-source toggle button: only meaningful when a distinct next source exists
        // (otherwise tapping would be a no-op) and there's room in the header to render
        // the glyph without crowding the date or API label.
        val nextSourceForButton = stateManager.getNextDisplaySource(appWidgetId)
        val hasDistinctSecondSource = nextSourceForButton != displaySource
        val showDualButton =
            useGraph &&
            hasDistinctSecondSource &&
            (disclosure == HeaderDisclosureLevel.FULL || disclosure == HeaderDisclosureLevel.NO_ICON)
        Log.d(
            TAG,
            DailyHeaderBinder.buildHeaderStateLog(
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
                deltaHiddenReason = DailyHeaderBinder.dailyDeltaHiddenReason(currentTemp, delta),
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
        views.setViewVisibility(R.id.forecast_history_activity_touch_zone, View.GONE)
        views.setViewVisibility(R.id.weather_stations_icon, View.GONE)
        views.setViewVisibility(R.id.weather_stations_touch_zone, View.GONE)

        // Set up navigation click handlers
        val availableDates = weatherList.map { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }.toSet() + dailyActuals.keys
        val sortedDates = availableDates.sorted()
        Log.d(TAG, "updateWidget: widgetId=$appWidgetId, widthDp=${dimensions.widthDp}, heightDp=${dimensions.heightDp}, cols=$numColumns, rows=$numRows, offset=$dateOffset, minDate=${sortedDates.firstOrNull()}, maxDate=${sortedDates.lastOrNull()}")
        setupNavigationButtons(context, views, appWidgetId, stateManager, availableDates, numColumns, skipYesterday, today, useGraph)

        // Use graph mode for 2+ rows
        var prepareMs = 0L
        var renderMs = 0L

        if (useGraph) {
            DailyVisibilityManager.setGraphModeViews(views)

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
                    numColumns, displaySource, skipYesterday, skipHistory,
                    hourlyForecasts, stateManager, appWidgetId, precipProb,
                    dailyActuals, climateNormals, currentTemps,
                    currentTemp = currentTemp,
                    observedAt = observedAt,
                    allowTodayRainChanceLabel = allowTodayRainChanceLabel,
                    nextSourceWeatherByDate = nextSourceWeatherByDate,
                    nextSource = if (showTwoBars && nextSource != displaySource) nextSource else null,
                )

            val days = prepareGraphDays(allowTodayRainChanceLabel = true)
            prepareMs = SystemClock.elapsedRealtime() - prepareStartMs

            days.find { it.isToday }?.let { todayDay ->
                appLogDao.log(
                    "TODAY_BAR_DEBUG",
                    "widget=$appWidgetId mode=GRAPH obsHigh=${todayDay.high} obsLow=${todayDay.low} " +
                        "fHigh=${todayDay.forecastHigh} fLow=${todayDay.forecastLow} " +
                        "trueHigh=${todayDay.trueActualHigh} bStackLow=${todayDay.bottomStackLow} " +
                        "sHigh=${todayDay.snapshotHigh} sLow=${todayDay.snapshotLow} " +
                        "fallback=${todayDay.isTodayForecastFallback}",
                    "DEBUG"
                )
            }

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

            val bitmapDims = WidgetSizeCalculator.computeBitmapDimensions(context, dimensions.widthDp, dimensions.heightDp)
            val widthDp = dimensions.widthDp - GRAPH_CONTENT_PADDING_DP
            val dateText = if (displayDays.size >= HeaderConstants.DATE_MIN_COLUMNS) today.format(headerDateFormatter) else null
            val headerPrecipPlacement = DailyHeaderBinder.resolveHeaderPrecipPlacement(
                context = context,
                widthDp = widthDp,
                numColumns = displayDays.size,
                currentTempText = formattedTemp,
                deltaText = if (deltaVisible && disclosure.showsDelta()) String.format("%+.1f", delta) else null,
                precipText = if (isPrecipVisible) "$precipProb%" else null,
                precipTextSizeDp = precipTextSizeDp,
                apiSourceText = apiSourceText,
                apiTextSizeDp = apiTextSizeDp,
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
                skipYesterday = skipYesterday,
                centerDate = centerDate,
                visibleDates = displayDays.map { it.date },
            )
            logGraphDayIconDetails(context, appWidgetId, displayDays)

            // Render graph (bitmapDims already computed above)

            // Build header data for bitmap rendering.
            // At 1 icon wide, blank out apiSourceText and zero settingsIconRes so
            // DailyForecastGraphRenderer.drawHeader skips drawing them.
            val headerRenderData = if (disclosure != HeaderDisclosureLevel.NONE) {
                DailyForecastGraphRenderer.HeaderRenderData(
                    iconRes = iconRes,
                    currentTempText = formattedTemp,
                    deltaText = if (deltaVisible) String.format("%+.1f", delta) else null,
                    precipText = if (isPrecipVisible) "$precipProb%" else null,
                    precipTextSizeDp = if (isPrecipVisible) HeaderPrecipCalculator.getPrecipTextSize(precipProb ?: 0) else HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP,
                    dateText = dateText,
                    apiSourceText = if (isIconWidth) null else apiSourceText,
                    apiTextSizeDp = apiTextSizeDp,
                    settingsIconRes = if (isIconWidth) 0 else R.drawable.ic_settings_gear,
                    showIcon = disclosure.showsIcon(),
                    showDelta = deltaVisible && disclosure.showsDelta(),
                    showPrecip = isPrecipVisible && headerPrecipPlacement.showHeaderPrecip,
                    showDualButton = showDualButton && !isIconWidth,
                    dualActive = showTwoBars,
                )
            } else null

            views.setViewVisibility(
                R.id.dual_touch_zone,
                if (showDualButton && !isIconWidth) View.VISIBLE else View.GONE,
            )

            val nightRainLabelDraws = mutableListOf<DailyForecastGraphRenderer.RainLabelDrawnDebug>()
            val renderStartMs = SystemClock.elapsedRealtime()
            val bitmap = DailyForecastGraphRenderer.renderGraph(
                context,
                displayDays,
                bitmapDims.widthPx,
                bitmapDims.heightPx,
                bitmapDims.bitmapScale,
                displayDays.size,
                job = coroutineContext[Job],
                onRainLabelDrawn = {
                    if (it.isNightLabel) {
                        nightRainLabelDraws.add(it)
                    }
                },
                headerData = headerRenderData,
            )
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
             NightRainGridMapper.setupNightRainClickHandlers(
                 context = context,
                 views = views,
                 appWidgetId = appWidgetId,
                 now = now,
                 days = displayDays,
                 lat = lat,
                 lon = lon,
                 displaySource = displaySource,
                 bitmapWidthPx = bitmapDims.widthPx,
                 bitmapHeightPx = bitmapDims.heightPx,
                 nightLabelDraws = nightRainLabelDraws,
                 buildClickIntent = { aid, di, d, ir, la, lo, ds, n, tmo, oo, cs ->
                     DailyClickHandlerFactory.buildDayClickIntent(context, aid, di, d, ir, la, lo, ds, n, tmo, oo, cs)
                 },
             )
        } else {
            DailyVisibilityManager.setTextModeViews(views)

            val textCols = numColumns.coerceAtLeast(1)
            // At 1 icon wide, the API/gear icons that consume the top-right are hidden,
            // so mirror the left padding on the right to keep the content centered.
            val rootRightPaddingDp = if (isIconWidth) TEXT_MODE_ROOT_LEFT_PADDING_DP else TEXT_MODE_ROOT_RIGHT_PADDING_DP
            val contentRightPaddingDp = if (isIconWidth) 0 else TEXT_MODE_CONTENT_RIGHT_PADDING_DP
            views.setViewPadding(
                R.id.widget_root,
                WidgetSizeCalculator.dpToPx(context, TEXT_MODE_ROOT_LEFT_PADDING_DP),
                WidgetSizeCalculator.dpToPx(context, TEXT_MODE_ROOT_TOP_PADDING_DP),
                WidgetSizeCalculator.dpToPx(context, rootRightPaddingDp),
                WidgetSizeCalculator.dpToPx(context, TEXT_MODE_ROOT_BOTTOM_PADDING_DP),
            )
            val rightPaddingPx = WidgetSizeCalculator.dpToPx(context, contentRightPaddingDp)
            views.setViewPadding(R.id.text_container, 0, 0, rightPaddingPx, 0)

            val visibleDaysInfo = updateTextMode(
                context, views, now, centerDate, today, weatherByDate,
                forecastSnapshots, hourlyForecasts, textCols, displaySource, skipHistory,
                stateManager, appWidgetId, precipProb, dailyActuals, climateNormals,
                currentTemps,
                currentTemp = currentTemp,
                observedAt = observedAt
            )

            visibleDaysInfo.find { it.isToday }?.let { todayDay ->
                appLogDao.log(
                    "TODAY_BAR_DEBUG",
                    "widget=$appWidgetId mode=TEXT high=${todayDay.highLabel} low=${todayDay.lowLabel} " +
                        "fallback=${todayDay.isTodayForecastFallback}",
                    "DEBUG"
                )
            }

            logDailyRenderSummary(
                context = context,
                appWidgetId = appWidgetId,
                dateOffset = dateOffset,
                displaySource = displaySource,
                numColumns = numColumns,
                numRows = numRows,
                useGraph = false,
                skipYesterday = skipYesterday,
                centerDate = centerDate,
                visibleDates = visibleDaysInfo.map { it.date },
            )

        }

        if (isIconWidth) {
            HeaderRemoteViewsBinder.hideIconWidthControls(views)
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
        skipYesterday: Boolean,
        centerDate: LocalDate,
        visibleDates: List<LocalDate>,
    ) {
        val mode = if (useGraph) "GRAPH" else "TEXT"
        val datesSummary = visibleDates.joinToString(",").ifEmpty { "<none>" }
        val tag = if (visibleDates.isEmpty()) "DAILY_RENDER_EMPTY" else "DAILY_RENDER"
        WeatherDatabase.getDatabase(context).appLogDao().log(
            tag,
            "widget=$appWidgetId mode=$mode offset=$dateOffset cols=$numColumns rows=$numRows skipYesterday=$skipYesterday center=$centerDate source=${displaySource.id} days=${visibleDates.size} dates=$datesSummary"
        )
    }

    private fun setupNavigationButtons(
        context: Context, views: RemoteViews, appWidgetId: Int,
        stateManager: WidgetStateManager, availableDates: Set<LocalDate>,
        numColumns: Int, skipYesterday: Boolean, today: LocalDate,
        useGraph: Boolean,
    ) {
        val sortedDates = availableDates.sorted()
        val minDate = sortedDates.firstOrNull()
        val maxDate = sortedDates.lastOrNull()

        val (leftmost, _) = NavigationUtils.getVisibleDateRange(today, stateManager.getDateOffset(appWidgetId) - 1, numColumns, skipYesterday)
        val (_, rightmost) = NavigationUtils.getVisibleDateRange(today, stateManager.getDateOffset(appWidgetId) + 1, numColumns, skipYesterday)

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
                action = WidgetActions.ACTION_NAV_LEFT
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
                action = WidgetActions.ACTION_SHOW_TOAST
                putExtra(WidgetActions.EXTRA_TOAST_MESSAGE, "No additional history available")
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
                action = WidgetActions.ACTION_NAV_RIGHT
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
                action = WidgetActions.ACTION_SHOW_TOAST
                putExtra(WidgetActions.EXTRA_TOAST_MESSAGE, "No more forecast available")
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
        forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
        hourlyForecasts: List<HourlyForecastEntity>, numColumns: Int,
        displaySource: WeatherSource, skipHistory: Boolean,
        stateManager: WidgetStateManager, appWidgetId: Int,
        todayNext8HourPrecipProbability: Int?,
        dailyActuals: DailyActualMap = emptyMap(),
        climateNormals: Map<java.time.MonthDay, Pair<Int, Int>> = emptyMap(),
        currentTemps: List<ObservationEntity> = emptyList(),
        currentTemp: Float? = null,
        observedAt: Long? = null
    ): List<DailyViewLogic.TextDayData> {
        val dayDataList = DailyViewLogic.prepareTextDays(
            now, centerDate, today, weatherByDate, forecastSnapshots, hourlyForecasts, numColumns,
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
            stateManager.markRainShown(appWidgetId, today.format(DateTimeFormatter.ISO_LOCAL_DATE))
        }

        return dayDataList.filter { it.isVisible }
    }

    private fun populateDay(
        context: Context, views: RemoteViews, now: LocalDateTime,
        ids: DayIds, data: DailyViewLogic.TextDayData,
        hourlyForecasts: List<HourlyForecastEntity>, displaySource: WeatherSource
    ) {
        views.setTextViewText(ids.label, data.label)
        views.setViewVisibility(ids.label, if (data.showLabel) View.VISIBLE else View.GONE)
        
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
            views.setTextViewText(ids.rain, data.rainSummary)
            views.setViewVisibility(ids.rain, View.VISIBLE)
        } else {
            views.setViewVisibility(ids.rain, View.GONE)
        }
    }

private fun setupGraphDayClickHandlers(
        context: Context, views: RemoteViews, appWidgetId: Int, now: LocalDateTime,
        days: List<DailyForecastGraphRenderer.DayData>, lat: Double, lon: Double, displaySource: WeatherSource,
        numColumns: Int
    ) {
        DailyClickHandlerFactory.setupGraphDayClickHandlers(
            context, views, appWidgetId, now, days, lat, lon, displaySource, numColumns
        )
    }

    private fun setupGraphBottomDayClickHandlers(
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
        DailyClickHandlerFactory.setupGraphBottomDayClickHandlers(
            context, views, appWidgetId, now, days, lat, lon, displaySource, numColumns
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
        DailyClickHandlerFactory.setupGraphZoneClickHandlers(
            context, views, appWidgetId, now, days, lat, lon, displaySource,
            numColumns, zoneIds, requestCodeOffset, resolveTargetMode,
        )
    }

}
