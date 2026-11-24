/**
 * Handler for the daily forecast view mode.
 */
package com.weatherwidget.widget.handlers

import com.weatherwidget.shared.util.HeaderContent
import com.weatherwidget.shared.util.HeaderFit
import com.weatherwidget.shared.util.DailyColumnSource
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.ClimateGapFiller
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.DailyForecastIconResolver
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.util.SunInfo
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.DailyActualMap
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetInteractionReceiver
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.WidgetWorkScheduler
import kotlin.math.abs
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale


object DailyViewHandler : WidgetViewHandler {
    private const val TAG = "DailyViewHandler"
    private const val CELL_HEIGHT_DP = 90
    // Extra vertical space (dp) added to widget height before dividing by cell height,
    // accounts for header and padding so the row count rounds more accurately.
    private const val GRAPH_HEIGHT_PADDING_DP = 25f
    // Minimum effective row count to switch from text to graph mode.
    // 2.2 (not 2.0) because the header consumes ~0.2 rows of vertical space,
    // so a 2-row widget needs slightly more than 2.0 to have room for the graph.
    private const val GRAPH_ROW_THRESHOLD = 2.2f
    /**
     * Nav arrow padding, matching `widget_weather.xml`: none on the widget-edge side, so the
     * `_edge` chevron's ink sits on the edge; the 40dp button keeps its hit area on the inner side.
     */
    private const val NAV_BUTTON_OUTER_PADDING_DP = 0
    private const val NAV_BUTTON_INNER_PADDING_DP = 16
    // Only probe for incomplete-history backfill when the visible window is recent enough that
    // NWS observation history can still serve it. Older days are beyond the fetch horizon.
    private const val HISTORY_BACKFILL_VISIBLE_DAYS = 3L
    // Log tags for diagnostic database entries
    private const val LOG_TAG_WIDGET_ACTUAL = "WIDGET_ACTUAL"
    internal const val LOG_TAG_TODAY_BAR_DEBUG = DailyTextRenderer.LOG_TAG_TODAY_BAR_DEBUG
    internal const val LOG_TAG_TODAY_HIGH_PROVENANCE = "TODAY_HIGH_PROVENANCE"
    internal const val LOG_TAG_DAILY_RENDER = "DAILY_RENDER"
    internal const val LOG_TAG_DAILY_RENDER_EMPTY = "DAILY_RENDER_EMPTY"
    // Locale is resolved per call (not captured at class-load) so ACTION_LOCALE_CHANGED —
    // which does NOT restart the process — is honoured the next time the widget paints.
    // DateTimeFormatter.of_pattern allocation is microseconds; negligible per render.
    internal fun headerDateFormatter() = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())

    internal class DailyRenderContext(
        val context: Context,
        val views: RemoteViews,
        val appWidgetId: Int,
        val now: LocalDateTime,
        val today: LocalDate,
        val displaySource: WeatherSource,
        val centerLat: Double,
        val centerLon: Double,
        val weatherByDate: Map<LocalDate, ForecastEntity>,
        val forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
        val hourlyForecasts: List<HourlyForecastEntity>,
        val currentTemps: List<ObservationEntity>,
        val dailyActuals: DailyActualMap,
        /** See [WeatherData.actualsProvisional]. */
        val actualsProvisional: Boolean,
        val climateNormals: Map<java.time.MonthDay, Pair<Float, Float>>,
        val numColumns: Int,
        val launcherColumns: Int,
        val numRows: Int,
        val largeTodayOverlayEnabled: Boolean,
        val dateOffset: Int,
        val skipYesterday: Boolean,
        val skipHistory: Boolean,
        val centerDate: LocalDate,
        val currentTemp: Float?,
        val observedAt: Long?,
        val precipProb: Int?,
        val stateManager: WidgetStateManager,
        val appLogDao: AppLogDao,
        val isIconWidth: Boolean,
        val sunInfo: SunInfo,
        val database: WeatherDatabase,
        val repository: WeatherRepository?,
        /**
         * Observations for the last [DailyGraphRenderer.OVERLAY_OBSERVATION_LOOKBACK_MS], loaded once
         * per render for the header yesterday-delta and reused by the today-column overlay so the
         * two never issue duplicate range queries.
         */
        val headerObservations: List<ObservationEntity>?,
    )

    // Build weather map: one row per date by the shared rule (DailyColumnSource.chooseRow): the
    // display source's row, with GENERIC_GAP (climate-normal) filler only after today+2 and never in
    // place of a row that has its low. Shared with the months screen's column view
    // (MonthsViewActivity), so it draws the same rows.
    internal fun buildWeatherByDate(
        weatherList: List<ForecastEntity>,
        displaySource: WeatherSource,
        today: LocalDate,
    ): Map<LocalDate, ForecastEntity> =
        weatherList
            .filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
            .groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
            .mapNotNull { (date, items) ->
                val chosen = DailyColumnSource.chooseRow(
                    items, displaySource.id, date, today, { it.source }, { it.highTemp }, { it.lowTemp },
                )
                chosen?.let { date to it }
            }.also { result ->
                val dropped = weatherList.size - result.size
                if (dropped > 0) {
                    Log.i(
                        TAG,
                        "weatherByDate: dropped=$dropped entries (kept=${result.size}/${weatherList.size}, " +
                            "displaySource=${displaySource.id})",
                    )
                }
            }
            .toMap()

    override fun canHandle(
        stateManager: WidgetStateManager,
        appWidgetId: Int,
    ): Boolean {
        return stateManager.getViewMode(appWidgetId) == ViewMode.DAILY
    }

    /** The header phase of [updateWidget]: what the render context and the rest of the paint read. */
    private data class HeaderPhase(
        val weatherByDate: Map<LocalDate, ForecastEntity>,
        val sunInfo: SunInfo,
        val headerObservations: List<ObservationEntity>?,
        val resolution: DailyHeaderResolver.HeaderResolution,
    )

    /**
     * Resolves and binds the daily header (current temp, rain %, API toggle) and loads what the
     * today-column overlay shares with it. Split out of [updateWidget] (2026-11-19): the ~370-line
     * suspend function is the shape whose dex ART rejected in CloudCoverViewHandler
     * (plans/261119-split-oversized-android-suspend-functions.md).
     */
    private suspend fun resolveHeaderPhase(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        stateManager: WidgetStateManager,
        displaySource: WeatherSource,
        now: LocalDateTime,
        today: LocalDate,
        lat: Double,
        lon: Double,
        weatherList: List<ForecastEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        observationData: ObservationData,
        lastObservedTemp: Float?,
        observedAt: Long?,
        dimensions: WidgetDimensions,
        numColumns: Int,
        numRows: Int,
        useGraph: Boolean,
        dateOffset: Int,
        skipYesterday: Boolean,
        isIconWidth: Boolean,
        repository: WeatherRepository?,
    ): HeaderPhase {
        val weatherByDate = buildWeatherByDate(weatherList, displaySource, today)

        val sunInfo = SunPositionUtils.getSunInfoOrUnknown(now, lat, lon)

        // The today-column overlay's observations (ctx.headerObservations).
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Scoped through ActualsReadScope, NOT with a local set: the today-column overlay narrows
        // this same load and compares its re-derived `observedAt` to CurrentTempResolver's for exact
        // equality, so the two reads must admit identical rows or the dominant-station rows vanish.
        val headerObservations = repository?.getObservationsInRange(
            nowMs - DailyGraphRenderer.OVERLAY_OBSERVATION_LOOKBACK_MS,
            nowMs,
            lat,
            lon,
            ActualsReadScope.apisFor(displaySource),
        )
        // Taken with the observation that anchors it (`:shared` CurrentTempRun.observe).
        val deltaFromYesterday = observationData.deltaFromYesterday

        val headerResolution = DailyHeaderResolver.resolveAndBind(
            context = context,
            views = views,
            displaySource = displaySource,
            now = now,
            lat = lat,
            lon = lon,
            weatherByDate = weatherByDate,
            hourlyForecasts = hourlyForecasts,
            currentTempHourlyForecasts = observationData.currentTempHourlyForecasts,
            lastObservedTemp = lastObservedTemp,
            observedAt = observedAt,
            dimensions = dimensions,
            stateManager = stateManager,
            appWidgetId = appWidgetId,
            numColumns = numColumns,
            numRows = numRows,
            useGraph = useGraph,
            sunInfo = sunInfo,
            deltaFromYesterday = deltaFromYesterday,
            dateOffset = dateOffset,
            skipYesterday = skipYesterday,
            rainNow = com.weatherwidget.widget.AndroidRainNow.level(context, lat, lon, displaySource, where = "daily", nowMs = nowMs),
        )

        val currentTemp = headerResolution.state.currentTemp
        val formattedTemp = headerResolution.state.formattedTemp
        val iconRes = headerResolution.state.iconRes
        val precipProb = headerResolution.state.precipProb
        val isPrecipVisible = headerResolution.state.isPrecipVisible
        val delta = headerResolution.state.appliedDelta
        val yesterdayDelta = headerResolution.state.yesterdayDelta
        val deltaVisible = headerResolution.state.deltaVisible
        val apiSourceText = headerResolution.state.apiSourceText
        val headerScale = headerResolution.state.headerScale
        val resolveMs = headerResolution.state.resolveMs

        // Setup API source toggle click handler (skipped at 1 icon wide — target is hidden)
        if (!isIconWidth) {
            setupApiToggle(context, views, appWidgetId, numRows, includeTextMode = true, scale = headerScale)
        }

        Log.d(
            TAG,
            DailyHeaderBinder.buildHeaderStateLog(
                widgetId = appWidgetId,
                viewMode = ViewMode.DAILY,
                displaySource = displaySource,
                configuredLocation = stateManager.getWidgetLocation(appWidgetId),
                dataLat = lat,
                dataLon = lon,
                dimensions = dimensions,
                currentTemp = currentTemp,
                estimatedTemp = headerResolution.state.estimatedTemp,
                observedTemp = headerResolution.state.observedTemp,
                appliedDelta = delta,
                headerDelta = yesterdayDelta,
                deltaVisible = deltaVisible,
                deltaHiddenReason = DailyHeaderBinder.dailyDeltaHiddenReason(currentTemp, yesterdayDelta),
                precipVisible = isPrecipVisible,
                precipProbability = precipProb,
                isNowLineVisible = null,
                offset = dateOffset,
                zoom = null,
                resolveMs = resolveMs,
            ),
        )

        return HeaderPhase(weatherByDate, sunInfo, headerObservations, headerResolution)
    }

    /** Text mode (too short for the graph): the day list, plus the missing-data refreshes it owes. */
    private suspend fun bindTextMode(
        ctx: DailyRenderContext,
        context: Context,
        appLogDao: com.weatherwidget.data.local.AppLogDao,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        displaySource: WeatherSource,
        dailyActuals: Map<LocalDate, DailyHistory>,
        weatherData: WeatherData,
        today: LocalDate,
    ) {
        val visibleDaysInfo = DailyTextRenderer.render(ctx)
        val textTodayWeather = ctx.weatherByDate[today]
        val textTodayHasSnapshot = ctx.forecastSnapshots[today]
            ?.any { it.source == ctx.displaySource.id && it.highTemp != null && it.lowTemp != null }
            ?: false
        val textRefreshDecisions = computeMissingDataRefreshes(
            today = today,
            displaySource = displaySource,
            dailyActuals = dailyActuals,
            actualsProvisional = weatherData.actualsProvisional,
            visibleDates = visibleDaysInfo.map { it.date }.toSet(),
            todayHasSnapshot = textTodayHasSnapshot,
            todayHasForecast = textTodayWeather != null && textTodayWeather.highTemp != null && textTodayWeather.lowTemp != null,
        )
        for (decision in textRefreshDecisions) {
            requestMissingDataRefresh(
                context = context,
                appLogDao = appLogDao,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                displaySource = displaySource,
                refreshType = decision.refreshType,
                cooldownMs = decision.cooldownMs,
                logTag = decision.logTag,
                forceRefresh = decision.forceRefresh,
                reason = decision.reason,
                message = "widget=$appWidgetId source=${displaySource.id} ${decision.refreshType} refresh, enqueueing worker",
            )
        }
    }

    override suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        weatherData: WeatherData,
        observationData: ObservationData,
        now: LocalDateTime,
        startupToken: String?,
        stateManagerNullable: WidgetStateManager?,
        repository: WeatherRepository?,
        partialPush: Boolean,
        origin: com.weatherwidget.widget.WidgetPushDispatcher.Origin,
    ) {
        val weatherList = weatherData.weatherList
        val forecastSnapshots = weatherData.forecastSnapshots
        val hourlyForecasts = weatherData.hourlyForecasts
        val currentTemps = weatherData.currentTemps
        val dailyActualsBySource = weatherData.dailyActualsBySource
        val lastObservedTemp = observationData.lastObservedTemp
        val observedAt = observationData.observedAt

        Log.d(TAG, "updateWidget: [START] widgetId=$appWidgetId at time=$now")
        val handlerStartMs = SystemClock.elapsedRealtime()
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        setupDeadZoneCatchAll(context, views, appWidgetId)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val numRows = dimensions.rows
        val isIconWidth = dimensions.isIconWidth

        // Use graph mode for 2+ rows
        val rawRows = (dimensions.heightDp + GRAPH_HEIGHT_PADDING_DP) / CELL_HEIGHT_DP
        val useGraph = rawRows >= GRAPH_ROW_THRESHOLD

        val stateManager = stateManagerNullable ?: WidgetStateManager(context)
        val dateOffset = stateManager.getDateOffset(appWidgetId)

        val skipYesterday = NavigationUtils.shouldSkipYesterday(numColumns)

        // Single source of truth for time in this update cycle
        val today = now.toLocalDate()
        val skipHistory = NavigationUtils.shouldSkipHistory(skipYesterday, dateOffset)
        val centerDate = NavigationUtils.getDisplayCenterDate(today, dateOffset, skipYesterday)
        val overlayVisibleRange =
            NavigationUtils.getVisibleDateRange(today, dateOffset, numColumns, skipYesterday)
        val todayVisible =
            !today.isBefore(overlayVisibleRange.first) && !today.isAfter(overlayVisibleRange.second)
        val largeTodayDecision =
            DailyLargeTodayOverlayPolicy.resolve(
                launcherColumns = numColumns,
                launcherRows = numRows,
                useGraph = useGraph,
                todayVisible = todayVisible,
            )
        val displayNumColumns = largeTodayDecision.displayColumns

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
        val database = WeatherDatabase.getDatabase(context)
        val appLogDao = database.appLogDao()

        // Resolve the widget's location the SAME way WidgetRenderer does when it derives the current
        // observation (observedAt/lastObservedTemp): configured widget location first, then the fetched
        // data location. The today-column overlay re-derives the dominant station against this location
        // and requires it to match the producer's exactly — using only the data location here left the
        // two resolving against different observation sites after a location handoff, which dropped the
        // station rows with observed_at_skew. See
        // plans/260819-today-overlay-location-mismatch-after-handoff.md.
        //
        // NaN, never a hardcoded coordinate. Used for the cached climate-normals lookup below and for
        // sun position; a NaN key simply misses the cache, which leaves PARTIAL future rows partial
        // rather than completing them from another city's normals.
        val configuredLocation = stateManager.getWidgetLocation(appWidgetId)
        val lat = configuredLocation?.first
            ?: weatherList.firstOrNull()?.locationLat
            ?: Double.NaN
        val lon = configuredLocation?.second
            ?: weatherList.firstOrNull()?.locationLon
            ?: Double.NaN
        // Cache-only read. The repository's getHistoricalNormalsByMonthDay does an HTTP fetch on a
        // cache miss, which has no business on a widget render path; ClimateNormalsRepository
        // .warmBestEffort already warms this cache on every network fetch. Used solely to complete a
        // PARTIAL future row (see DailyViewLogic) — whole climate-normal days come from
        // ClimateGapFiller's GENERIC_GAP rows.
        val climateNormals = ClimateGapFiller(database.climateNormalDao())
            .cachedNormalsByMonthDay(lat, lon)

        Log.d(
            TAG,
            "updateWidget: widgetId=$appWidgetId, launcherCols=$numColumns displayCols=$displayNumColumns " +
                "rows=$numRows largeTodayOverlay=${largeTodayDecision.enabled} offset=$dateOffset, " +
                "skipYesterday=$skipYesterday, weatherCount=${weatherList.size}, actualsCount=${dailyActuals.size}, source=${displaySource.id}",
        )

        val yesterday = today.minusDays(1)
        val yesterdayActual = dailyActuals[yesterday]
        Log.v(TAG, "$LOG_TAG_WIDGET_ACTUAL date=$yesterday src=${displaySource.id} low=${yesterdayActual?.computedLowTemp} " +
            "allDates=${dailyActuals.keys} allSources=${dailyActualsBySource.keys}")

        // Past-day actuals are read from the daily_history cache, which can be wrong when a
        // day's observation coverage is incomplete (e.g. device powered off during the day).
        // Reuse the gap-aware hourly backfill — the same path the temperature graph uses — so
        // the daily view also fetches the missing NWS observations and recomputes the cache.
        maybeBackfillIncompleteHistory(
            context = context,
            database = database,
            repository = repository,
            stateManager = stateManager,
            appWidgetId = appWidgetId,
            displaySource = displaySource,
            lat = lat,
            lon = lon,
            centerDate = centerDate,
            today = today,
            now = now,
        )

        if (
            renderBlockingWarning(
                context, appWidgetManager, views, appWidgetId, numRows, appLogDao, stateManager, displaySource,
                weatherList, dateOffset, numColumns, skipYesterday, centerDate,
            )
        ) {
            return
        }

        val header = resolveHeaderPhase(
            context, views, appWidgetId, stateManager, displaySource, now, today, lat, lon, weatherList,
            hourlyForecasts, observationData, lastObservedTemp, observedAt, dimensions, numColumns, numRows,
            useGraph, dateOffset, skipYesterday, isIconWidth, repository,
        )
        val weatherByDate = header.weatherByDate
        val sunInfo = header.sunInfo
        val headerObservations = header.headerObservations
        val headerResolution = header.resolution
        HeaderTapTargetHelper.bindWeatherIconHeader(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            isRainingNow = headerResolution.state.isRainingNow,
        )
        val currentTemp = headerResolution.state.currentTemp
        val precipProb = headerResolution.state.precipProb
        val resolveMs = headerResolution.state.resolveMs

        val availableDates = buildAvailableNavigationDates(weatherList, dailyActuals, displaySource)
        Log.d(TAG, "updateWidget: widgetId=$appWidgetId, widthDp=${dimensions.widthDp}, heightDp=${dimensions.heightDp}, cols=$numColumns, rows=$numRows, offset=$dateOffset, minDate=${availableDates.minOrNull()}, maxDate=${availableDates.maxOrNull()}")
        setupNavigationButtons(context, views, appWidgetId, stateManager, availableDates, displayNumColumns, skipYesterday, today, useGraph, dateOffset)

        val ctx = DailyRenderContext(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            now = now,
            today = today,
            displaySource = displaySource,
            centerLat = lat,
            centerLon = lon,
            weatherByDate = weatherByDate,
            forecastSnapshots = forecastSnapshots,
            hourlyForecasts = hourlyForecasts,
            currentTemps = currentTemps,
            dailyActuals = dailyActuals,
            actualsProvisional = weatherData.actualsProvisional,
            climateNormals = climateNormals,
            numColumns = displayNumColumns,
            launcherColumns = numColumns,
            numRows = numRows,
            largeTodayOverlayEnabled = largeTodayDecision.enabled,
            dateOffset = dateOffset,
            skipYesterday = skipYesterday,
            skipHistory = skipHistory,
            centerDate = centerDate,
            currentTemp = currentTemp,
            observedAt = observedAt,
            precipProb = precipProb,
            stateManager = stateManager,
            appLogDao = appLogDao,
            isIconWidth = isIconWidth,
            sunInfo = sunInfo,
            database = database,
            repository = repository,
            headerObservations = headerObservations,
        )

        val (prepareMs, renderMs) = renderBody(
            ctx, headerResolution, dimensions, startupToken, resolveMs, lat, lon, useGraph, context, appLogDao,
            stateManager, appWidgetId, displaySource, dailyActuals, weatherData, today,
        )

        finishAndPush(
            appWidgetManager, views, appWidgetId, stateManager, appLogDao, isIconWidth, origin, partialPush,
            handlerStartMs, startupToken, useGraph, resolveMs, prepareMs, renderMs, weatherList.size,
            hourlyForecasts.size,
        )
    }

    /**
     * The blocking API-source warning: when it applies, paints and pushes it and returns true.
     * Split out of [updateWidget] (plans/261119-split-oversized-android-suspend-functions.md).
     */
    private suspend fun renderBlockingWarning(
        context: Context,
        appWidgetManager: AppWidgetManager,
        views: RemoteViews,
        appWidgetId: Int,
        numRows: Int,
        appLogDao: com.weatherwidget.data.local.AppLogDao,
        stateManager: WidgetStateManager,
        displaySource: WeatherSource,
        weatherList: List<ForecastEntity>,
        dateOffset: Int,
        numColumns: Int,
        skipYesterday: Boolean,
        centerDate: LocalDate,
    ): Boolean {
        if (
            !ApiSourceWarningHelper.checkAndRenderBlockingWarning(
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
            return false
        }
        logDailyRenderSummary(
            appLogDao = appLogDao,
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
        bindTransientMessage(views, stateManager, appWidgetId, callerTag = "DAILY")
        appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=DAILY state=warning thread=${Thread.currentThread().name}")
        if (com.weatherwidget.widget.TestModeWidgetGuard.allows(appWidgetId, caller = "DAILY_WARNING")) {
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
        return true
    }

    /** Graph or text body of [updateWidget]; returns (prepareMs, renderMs). */
    private suspend fun renderBody(
        ctx: DailyRenderContext,
        headerResolution: DailyHeaderResolver.HeaderResolution,
        dimensions: WidgetDimensions,
        startupToken: String?,
        resolveMs: Long,
        lat: Double,
        lon: Double,
        useGraph: Boolean,
        context: Context,
        appLogDao: com.weatherwidget.data.local.AppLogDao,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        displaySource: WeatherSource,
        dailyActuals: Map<LocalDate, DailyHistory>,
        weatherData: WeatherData,
        today: LocalDate,
    ): Pair<Long, Long> {
        return if (useGraph) {
            val metrics = DailyGraphRenderer.render(
                ctx = ctx,
                headerState = headerResolution.state,
                headerPrecipPlacement = headerResolution.precipPlacement,
                dimensions = dimensions,
                startupToken = startupToken,
                resolveMs = resolveMs,
                lat = lat,
                lon = lon,
            )
            metrics.prepareMs to metrics.renderMs
        } else {
            bindTextMode(ctx, context, appLogDao, stateManager, appWidgetId, displaySource, dailyActuals, weatherData, today)
            0L to 0L
        }
    }

    /** Final binds, the push, and the WIDGET_RENDER_PERF line of [updateWidget]. */
    private suspend fun finishAndPush(
        appWidgetManager: AppWidgetManager,
        views: RemoteViews,
        appWidgetId: Int,
        stateManager: WidgetStateManager,
        appLogDao: com.weatherwidget.data.local.AppLogDao,
        isIconWidth: Boolean,
        origin: com.weatherwidget.widget.WidgetPushDispatcher.Origin,
        partialPush: Boolean,
        handlerStartMs: Long,
        startupToken: String?,
        useGraph: Boolean,
        resolveMs: Long,
        prepareMs: Long,
        renderMs: Long,
        forecastCount: Int,
        hourlyCount: Int,
    ) {
        if (isIconWidth) {
            HeaderRemoteViewsBinder.hideIconWidthControls(views)
        }

        bindTransientMessage(views, stateManager, appWidgetId, callerTag = "DAILY")
        appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=DAILY origin=${origin.name} state=data push=${if (partialPush) "partial" else "full"} thread=${Thread.currentThread().name}")
        com.weatherwidget.widget.WidgetPushDispatcher.push(
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            views = views,
            partialPush = partialPush,
            caller = "DAILY",
            appLogDao = appLogDao,
            origin = origin,
        )
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
                // What none of the above explains. This was 798 of 893ms on 2026-09-07 and the
                // named fields all read fast; see WidgetPerfLogger.residualMs.
                "otherMs" to WidgetPerfLogger.residualMs(totalMs, resolveMs, prepareMs, renderMs),
                "forecastCount" to forecastCount,
                "hourlyCount" to hourlyCount,
                "totalMs" to totalMs,
            ),
            debugTag = TAG,
        )
    }

    /**
     * Whether the daily view should probe recent NWS observation coverage for a backfill, asked of
     * the **actuals provider** (Google inside NWS coverage → NWS). Only NWS history is
     * gap-fetchable, and only the recent window ([HISTORY_BACKFILL_VISIBLE_DAYS]) is still served
     * by NWS — probing older navigation targets just wastes a DB query.
     */
    @VisibleForTesting
    internal fun shouldProbeHistoryBackfill(
        actualsSource: WeatherSource,
        centerDate: LocalDate,
        today: LocalDate,
        visibleDays: Long = DailyHistoryBackfillCoordinator.HISTORY_BACKFILL_VISIBLE_DAYS,
    ): Boolean = DailyHistoryBackfillCoordinator.shouldProbeHistoryBackfill(actualsSource, centerDate, today, visibleDays)

    @VisibleForTesting
    internal suspend fun maybeBackfillIncompleteHistory(
        context: Context,
        database: WeatherDatabase,
        repository: WeatherRepository?,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        displaySource: WeatherSource,
        lat: Double,
        lon: Double,
        centerDate: LocalDate,
        today: LocalDate,
        now: LocalDateTime,
    ) = DailyHistoryBackfillCoordinator.maybeBackfillIncompleteHistory(
        context = context,
        database = database,
        repository = repository,
        stateManager = stateManager,
        appWidgetId = appWidgetId,
        displaySource = displaySource,
        lat = lat,
        lon = lon,
        centerDate = centerDate,
        today = today,
        now = now,
    )

    internal suspend fun requestMissingDataRefresh(
        context: Context,
        appLogDao: AppLogDao,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        displaySource: WeatherSource,
        refreshType: String,
        cooldownMs: Long,
        logTag: String,
        forceRefresh: Boolean,
        reason: String,
        message: String,
    ) = DailyHistoryBackfillCoordinator.requestMissingDataRefresh(
        context = context,
        appLogDao = appLogDao,
        stateManager = stateManager,
        appWidgetId = appWidgetId,
        displaySource = displaySource,
        refreshType = refreshType,
        cooldownMs = cooldownMs,
        logTag = logTag,
        forceRefresh = forceRefresh,
        reason = reason,
        message = message,
    )

    private fun setupCurrentTempToggle(context: Context, views: RemoteViews, appWidgetId: Int) {
        HeaderTapTargetHelper.bindToggleTemperatureHeader(context, views, appWidgetId)
        HeaderTapTargetHelper.bindPrecipitationHeader(context, views, appWidgetId)
    }

    /**
     * Shows the transient message banner (e.g. the no-hourly-data notice) if one is active for this
     * widget, otherwise hides it. Driven by [WidgetStateManager.getActiveTransientMessage], which
     * expires the message on its own; the caller schedules a delayed UI-only repaint so the banner
     * clears at expiry without keeping a process alive.
     */
    @VisibleForTesting
    internal fun bindTransientMessage(views: RemoteViews, stateManager: WidgetStateManager, appWidgetId: Int, callerTag: String) {
        val message = stateManager.getActiveTransientMessage(appWidgetId)
        if (message != null) {
            views.setTextViewText(R.id.widget_message_banner, message)
            views.setViewVisibility(R.id.widget_message_banner, View.VISIBLE)
            Log.d(TAG, "bindTransientMessage: widget=$appWidgetId caller=$callerTag showing banner=\"$message\"")
        } else {
            views.setViewVisibility(R.id.widget_message_banner, View.GONE)
        }
    }

    internal suspend fun logDailyRenderSummary(
        appLogDao: AppLogDao,
        appWidgetId: Int,
        dateOffset: Int,
        displaySource: WeatherSource,
        numColumns: Int,
        numRows: Int,
        useGraph: Boolean,
        skipYesterday: Boolean,
        centerDate: LocalDate,
        visibleDates: List<LocalDate>,
        cloudDays: List<CloudCoverDiagnosticRow>? = null,
        hourlyForecasts: List<HourlyForecastEntity>? = null,
    ) = DailyRenderLogger.logDailyRenderSummary(
        appLogDao = appLogDao,
        appWidgetId = appWidgetId,
        dateOffset = dateOffset,
        displaySource = displaySource,
        numColumns = numColumns,
        numRows = numRows,
        useGraph = useGraph,
        skipYesterday = skipYesterday,
        centerDate = centerDate,
        visibleDates = visibleDates,
        cloudDays = cloudDays,
        hourlyForecasts = hourlyForecasts,
    )

    internal fun buildCloudCoverDiagnostic(
        cloudDays: List<CloudCoverDiagnosticRow>,
        hourlyForecasts: List<HourlyForecastEntity>?,
        displaySource: WeatherSource,
    ): String = DailyRenderLogger.buildCloudCoverDiagnostic(
        cloudDays = cloudDays,
        hourlyForecasts = hourlyForecasts,
        displaySource = displaySource,
    )

    @VisibleForTesting
    internal fun buildAvailableNavigationDates(
        weatherList: List<ForecastEntity>,
        dailyActuals: DailyActualMap,
        displaySource: WeatherSource,
    ): Set<LocalDate> {
        // GENERIC_GAP (climate normals) is included so users can navigate to far-future dates
        // (> today+2) where the display source has no forecast. These render as climate-normal
        // bars in the graph. Closer dates still require the display source (see weatherByDate).
        val renderableForecastDates = weatherList
            .asSequence()
            .filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
            .map { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
            .toSet()
        return renderableForecastDates + dailyActuals.keys
    }

    private fun setupNavigationButtons(
        context: Context, views: RemoteViews, appWidgetId: Int,
        stateManager: WidgetStateManager, availableDates: Set<LocalDate>,
        numColumns: Int, skipYesterday: Boolean, today: LocalDate,
        useGraph: Boolean,
        dateOffset: Int,
    ) {
        val sortedDates = availableDates.sorted()
        val minDate = sortedDates.firstOrNull()
        val maxDate = sortedDates.lastOrNull()

        // Use the offset captured at the start of this update cycle (not a fresh prefs read) so
        // the nav bounds match the day window actually being rendered. One (left, right) pair
        // per direction instead of two single-edge calls.
        val (leftmost, _) = NavigationUtils.getVisibleDateRange(today, dateOffset - 1, numColumns, skipYesterday)
        val (_, rightmost) = NavigationUtils.getVisibleDateRange(today, dateOffset + 1, numColumns, skipYesterday)

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

        val outerPx = WidgetSizeCalculator.dpToPx(context, NAV_BUTTON_OUTER_PADDING_DP)
        val innerPx = WidgetSizeCalculator.dpToPx(context, NAV_BUTTON_INNER_PADDING_DP)

        views.setViewVisibility(R.id.nav_left, View.VISIBLE)
        views.setViewVisibility(R.id.nav_left_zone, View.VISIBLE)
        views.setViewPadding(R.id.nav_left, outerPx, 0, innerPx, 0)
        views.setViewPadding(R.id.nav_right, innerPx, 0, outerPx, 0)
        bindNavDirection(
            views = views,
            buttonId = R.id.nav_left,
            zoneId = R.id.nav_left_zone,
            context = context,
            appWidgetId = appWidgetId,
            requestCode = WidgetRequestCodes.navLeft(appWidgetId),
            navAction = WidgetActions.ACTION_NAV_LEFT,
            canNavigate = canLeft,
            toastMessage = context.getString(R.string.widget_nav_no_history),
        )

        views.setViewVisibility(R.id.nav_right, View.VISIBLE)
        views.setViewVisibility(R.id.nav_right_zone, View.VISIBLE)
        bindNavDirection(
            views = views,
            buttonId = R.id.nav_right,
            zoneId = R.id.nav_right_zone,
            context = context,
            appWidgetId = appWidgetId,
            requestCode = WidgetRequestCodes.navRight(appWidgetId),
            navAction = WidgetActions.ACTION_NAV_RIGHT,
            canNavigate = canRight,
            toastMessage = context.getString(R.string.widget_nav_no_forecast),
        )
    }

    private fun bindNavDirection(
        views: RemoteViews,
        buttonId: Int,
        zoneId: Int,
        context: Context,
        appWidgetId: Int,
        requestCode: Int,
        navAction: String,
        canNavigate: Boolean,
        toastMessage: String,
    ) {
        val pendingIntent = if (canNavigate) {
            val intent = Intent(context, WidgetInteractionReceiver::class.java).apply {
                action = navAction
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val intent = Intent(context, WidgetInteractionReceiver::class.java).apply {
                action = WidgetActions.ACTION_SHOW_TOAST
                putExtra(WidgetActions.EXTRA_TOAST_MESSAGE, toastMessage)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        views.setOnClickPendingIntent(buttonId, pendingIntent)
        views.setOnClickPendingIntent(zoneId, pendingIntent)
    }

    internal data class RenderMetrics(
        val prepareMs: Long,
        val renderMs: Long,
    )

    internal data class HeaderState(
        val iconRes: Int,
        /** True when [iconRes] is an active rain-now dot icon (`rainNow != null`). */
        val isRainingNow: Boolean = false,
        val currentTemp: Float?,
        val formattedTemp: String?,
        val estimatedTemp: Float?,
        val observedTemp: Float?,
        /** Forecast delta (observed − forecast at the current hour): ghost line + overlay, not the header. */
        val appliedDelta: Float?,
        /** Yesterday delta (observed − blended actual 24h earlier): the value shown in the header. */
        val yesterdayDelta: Float?,
        val deltaVisible: Boolean,
        val deltaText: String?,
        /** "from yest" caption after the header delta; non-null only when it fits. */
        val deltaLabelText: String? = null,
        val precipProb: Int?,
        val isPrecipVisible: Boolean,
        val precipTextSizeDp: Float?,
        /** The header's rain text — "40%", or with the nowcast "40% 14:15–"; null when hidden. */
        val precipText: String? = null,
        val apiSourceText: String,
        val apiTextSizeDp: Float,
        /** What the header shows, from the shared drop order ([HeaderFit]). */
        val fit: HeaderFit.Plan,
        /** What [fit] was resolved from; the button and date positions are read from both. */
        val measured: HeaderFit.Measured,
        val headerScale: Float,
        val resolveMs: Long,
        /** Whether today or yesterday is inside the visible day window; drives the base button count. */
        val observationsInView: Boolean = true,
        /** What the home button does; non-null exactly when [HeaderFit.Plan.homeShown]. */
        val homeAction: com.weatherwidget.shared.util.PreferredSourceHome.HomeAction? = null,
    )
}
