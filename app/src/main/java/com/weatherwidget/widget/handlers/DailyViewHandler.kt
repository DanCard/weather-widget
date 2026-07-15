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
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
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
    private const val DELTA_VISIBILITY_THRESHOLD = DailyHeaderBinder.DELTA_VISIBILITY_THRESHOLD
    private const val DELTA_COLOR_HEX = "#FF6B35"
    // Extra vertical space (dp) added to widget height before dividing by cell height,
    // accounts for header and padding so the row count rounds more accurately.
    private const val GRAPH_HEIGHT_PADDING_DP = 25f
    // Minimum effective row count to switch from text to graph mode.
    // 2.2 (not 2.0) because the header consumes ~0.2 rows of vertical space,
    // so a 2-row widget needs slightly more than 2.0 to have room for the graph.
    private const val GRAPH_ROW_THRESHOLD = 2.2f
    private const val GRAPH_CONTENT_PADDING_DP = 24
    private const val TEXT_MODE_ROOT_LEFT_PADDING_DP = 2
    private const val TEXT_MODE_ROOT_TOP_PADDING_DP = 0
    private const val TEXT_MODE_ROOT_RIGHT_PADDING_DP = 8
    private const val TEXT_MODE_ROOT_BOTTOM_PADDING_DP = 0
    private const val TEXT_MODE_CONTENT_RIGHT_PADDING_DP = 18
    private const val HEADER_ICON_TINT = 0xAAFFFFFF.toInt()
    private const val NAV_BUTTON_PADDING_DP = 10
    // Only probe for incomplete-history backfill when the visible window is recent enough that
    // NWS observation history can still serve it. Older days are beyond the fetch horizon.
    private const val HISTORY_BACKFILL_VISIBLE_DAYS = 3L
    // Log tags for diagnostic database entries
    private const val LOG_TAG_WIDGET_ACTUAL = "WIDGET_ACTUAL"
    internal const val LOG_TAG_TODAY_BAR_DEBUG = "TODAY_BAR_DEBUG"
    internal const val LOG_TAG_TODAY_HIGH_PROVENANCE = "TODAY_HIGH_PROVENANCE"
    private const val LOG_TAG_DAILY_RENDER = "DAILY_RENDER"
    private const val LOG_TAG_DAILY_RENDER_EMPTY = "DAILY_RENDER_EMPTY"
    // Locale captured at class-load time is safe: Android restarts the process on locale change,
    // which re-initializes this singleton with the new default locale.
    internal val headerDateFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())

    internal class DailyRenderContext(
        val context: Context,
        val views: RemoteViews,
        val appWidgetId: Int,
        val now: LocalDateTime,
        val today: LocalDate,
        val displaySource: WeatherSource,
        val weatherByDate: Map<LocalDate, ForecastEntity>,
        val forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
        val hourlyForecasts: List<HourlyForecastEntity>,
        val currentTemps: List<ObservationEntity>,
        val dailyActuals: DailyActualMap,
        val climateNormals: Map<java.time.MonthDay, Pair<Float, Float>>,
        val numColumns: Int,
        val numRows: Int,
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
    )

    private data class DayIds(
        val container: Int,
        val label: Int,
        val icon: Int,
        val high: Int,
        val low: Int,
        val rain: Int,
    )

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
        weatherData: WeatherData,
        observationData: ObservationData,
        now: LocalDateTime,
        startupToken: String?,
        stateManagerNullable: WidgetStateManager?,
        repository: WeatherRepository?,
        partialPush: Boolean,
    ) {
        val weatherList = weatherData.weatherList
        val forecastSnapshots = weatherData.forecastSnapshots
        val hourlyForecasts = weatherData.hourlyForecasts
        val currentTemps = weatherData.currentTemps
        val dailyActualsBySource = weatherData.dailyActualsBySource
        val lastObservedTemp = observationData.lastObservedTemp
        val observedAt = observationData.observedAt
        val smoothedForecasts = observationData.smoothedForecasts

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
        val database = WeatherDatabase.getDatabase(context)
        val appLogDao = database.appLogDao()

        val lat = weatherList.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = weatherList.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val climateNormals = repository?.getHistoricalNormalsByMonthDay(lat, lon) ?: emptyMap()

        Log.d(
            TAG,
            "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, offset=$dateOffset, " +
                "skipYesterday=$skipYesterday, weatherCount=${weatherList.size}, actualsCount=${dailyActuals.size}, source=${displaySource.id}",
        )

        val yesterday = today.minusDays(1)
        val yesterdayActual = dailyActuals[yesterday]
        appLogDao.log(LOG_TAG_WIDGET_ACTUAL,
            "date=$yesterday src=${displaySource.id} low=${yesterdayActual?.lowTemp} " +
            "allDates=${dailyActuals.keys} allSources=${dailyActualsBySource.keys}",
            "DEBUG"
        )

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
            appWidgetManager.updateAppWidget(appWidgetId, views)
            return
        }

        // Build weather map: prefer the selected display source. GENERIC_GAP (climate-normal) filler
        // is only valid for long-term future days the API does not cover (date > today+2); it must never
        // stand in for history, today, +1, or +2 — those show real display-source data or render missing.
        val weatherByDate =
            weatherList
                .filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
                .groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
                .mapNotNull { (date, items) ->
                    val preferred = items.find { it.source == displaySource.id }
                    val allowGapFallback = date.isAfter(today.plusDays(2))

                    val chosen = if (allowGapFallback) {
                        // Long-term future: fall back to climate normals when the preferred source is
                        // absent or has missing temps. For Today we'd preserve the preferred source even
                        // if incomplete, but Today is never long-term so it's handled by the else branch.
                        if (preferred != null && (preferred.highTemp == null || preferred.lowTemp == null)) {
                            items.find { it.source == WeatherSource.GENERIC_GAP.id && it.highTemp != null && it.lowTemp != null }
                                ?: preferred
                        } else {
                            preferred ?: items.first()
                        }
                    } else {
                        // History / today / +1 / +2: real display-source only, never GENERIC_GAP filler.
                        // (Today's incomplete-source recovery lives in DailyViewLogic / DailyActualsEstimator.)
                        preferred
                    }
                    chosen?.let { date to it } ?: run {
                        Log.d(TAG, "weatherByDate: dropping date=$date (no renderable source, allowGapFallback=$allowGapFallback, items=${items.map { it.source }})")
                        null
                    }
                }
                .toMap()

        val sunInfo = SunPositionUtils.getSunInfo(now, lat, lon)

        val headerResolution = resolveAndBindHeader(
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
            smoothedForecasts = smoothedForecasts,
            sunInfo = sunInfo,
        )

        val currentTemp = headerResolution.state.currentTemp
        val formattedTemp = headerResolution.state.formattedTemp
        val iconRes = headerResolution.state.iconRes
        val precipProb = headerResolution.state.precipProb
        val isPrecipVisible = headerResolution.state.isPrecipVisible
        val precipTextSizeDp = headerResolution.state.precipTextSizeDp
        val delta = headerResolution.state.appliedDelta
        val deltaVisible = headerResolution.state.deltaVisible
        val apiSourceText = headerResolution.state.apiSourceText
        val disclosure = headerResolution.state.disclosure
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

        val availableDates = buildAvailableNavigationDates(weatherList, dailyActuals, displaySource)
        Log.d(TAG, "updateWidget: widgetId=$appWidgetId, widthDp=${dimensions.widthDp}, heightDp=${dimensions.heightDp}, cols=$numColumns, rows=$numRows, offset=$dateOffset, minDate=${availableDates.minOrNull()}, maxDate=${availableDates.maxOrNull()}")
        setupNavigationButtons(context, views, appWidgetId, stateManager, availableDates, numColumns, skipYesterday, today, useGraph)

        var prepareMs = 0L
        var renderMs = 0L

        val ctx = DailyRenderContext(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            now = now,
            today = today,
            displaySource = displaySource,
            weatherByDate = weatherByDate,
            forecastSnapshots = forecastSnapshots,
            hourlyForecasts = hourlyForecasts,
            currentTemps = currentTemps,
            dailyActuals = dailyActuals,
            climateNormals = climateNormals,
            numColumns = numColumns,
            numRows = numRows,
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
        )

        if (useGraph) {
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
            prepareMs = metrics.prepareMs
            renderMs = metrics.renderMs
        } else {
            val visibleDaysInfo = renderTextMode(ctx)
            val textTodayWeather = ctx.weatherByDate[today]
            val textTodayHasSnapshot = ctx.forecastSnapshots[today]
                ?.any { it.source == ctx.displaySource.id && it.highTemp != null && it.lowTemp != null }
                ?: false
            val textRefreshDecisions = computeMissingDataRefreshes(
                today = today,
                displaySource = displaySource,
                dailyActuals = dailyActuals,
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

        if (isIconWidth) {
            HeaderRemoteViewsBinder.hideIconWidthControls(views)
        }

        bindTransientMessage(views, stateManager, appWidgetId, callerTag = "DAILY")
        appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=DAILY state=data push=${if (partialPush) "partial" else "full"} thread=${Thread.currentThread().name}")
        com.weatherwidget.widget.WidgetPushDispatcher.push(
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            views = views,
            partialPush = partialPush,
            caller = "DAILY",
            appLogDao = appLogDao,
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
                "forecastCount" to weatherList.size,
                "hourlyCount" to hourlyForecasts.size,
                "totalMs" to totalMs,
            ),
            debugTag = TAG,
        )
    }

    /**
     * Whether the daily view should probe recent NWS observation coverage for a backfill. Only
     * NWS history is gap-fetchable, and only the recent window ([HISTORY_BACKFILL_VISIBLE_DAYS])
     * is still served by NWS — probing older navigation targets just wastes a DB query.
     */
    @VisibleForTesting
    internal fun shouldProbeHistoryBackfill(
        displaySource: WeatherSource,
        centerDate: LocalDate,
        today: LocalDate,
        visibleDays: Long = HISTORY_BACKFILL_VISIBLE_DAYS,
    ): Boolean =
        displaySource == WeatherSource.NWS && !centerDate.isBefore(today.minusDays(visibleDays))

    /**
     * Probe recent NWS observation coverage and enqueue the gap-aware observation backfill when
     * it is incomplete. This mirrors what the temperature graph does in [loadGraphHours]; without
     * it, a past day whose daily_history row exists but was computed from a partial day of
     * observations (e.g. the device was off during the afternoon) never gets repaired from the
     * daily view, because the presence-only [computeMissingDataRefreshes] check treats it as
     * already populated. The shared backfill cooldown key prevents double-fetching with the graph.
     */
    private suspend fun maybeBackfillIncompleteHistory(
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
    ) {
        if (repository == null) return
        if (!shouldProbeHistoryBackfill(displaySource, centerDate, today)) return

        val graphStart = now.minusHours(WeatherWidgetWorker.DEFAULT_OBSERVATION_BACKFILL_HOURS)
        val minEpoch = graphStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val maxEpoch = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val observations = repository.getObservationsInRange(minEpoch, maxEpoch, lat, lon)
        maybeEnqueueHourlyObservationBackfill(
            context = context,
            database = database,
            stateManager = stateManager,
            appWidgetId = appWidgetId,
            displaySource = displaySource,
            graphStart = graphStart,
            graphEnd = now,
            observations = observations,
            repositoryPresent = true,
        )
    }

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
    ) {
        if (!stateManager.shouldRefreshMissingData(appWidgetId, displaySource.id, refreshType, cooldownMs)) {
            return
        }
        stateManager.markMissingDataRefreshRequested(appWidgetId, displaySource.id, refreshType)
        appLogDao.log(logTag, message, "INFO")
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
        cloudDays: List<DailyForecastGraphRenderer.DayData>? = null,
        hourlyForecasts: List<HourlyForecastEntity>? = null,
    ) {
        val mode = if (useGraph) "GRAPH" else "TEXT"
        val datesSummary = visibleDates.joinToString(",").ifEmpty { "<none>" }
        val tag = if (visibleDates.isEmpty()) LOG_TAG_DAILY_RENDER_EMPTY else LOG_TAG_DAILY_RENDER
        val cloudSummary = if (cloudDays != null) {
            " " + buildCloudCoverDiagnostic(cloudDays, hourlyForecasts, displaySource)
        } else ""
        appLogDao.log(
            tag,
            "widget=$appWidgetId mode=$mode offset=$dateOffset cols=$numColumns rows=$numRows skipYesterday=$skipYesterday center=$centerDate source=${displaySource.id} days=${visibleDates.size} dates=$datesSummary$cloudSummary"
        )
    }

    /**
     * Persists why cloud-cover shading does/doesn't appear on the daily vertical bars while
     * navigating history. The shading is derived per displayed day from near-noon hourly
     * cloud cover (DailyViewLogic.resolveNoonCloudCoverRatio), so a bar can render without
     * shading if the in-memory hourly window does not reach that date — even though the data
     * exists in the DB. This logs, for each render: how many visible days resolved a cloud
     * ratio, which dates missed it (with daysFromToday), and the actual hourly window span for
     * the display source so a window/coverage gap is visible from app_logs alone.
     */
    internal fun buildCloudCoverDiagnostic(
        cloudDays: List<DailyForecastGraphRenderer.DayData>,
        hourlyForecasts: List<HourlyForecastEntity>?,
        displaySource: WeatherSource,
    ): String {
        val resolved = cloudDays.count { it.cloudCoverRatioOverride != null }
        val missing = cloudDays.filter { it.cloudCoverRatioOverride == null }
            .map { "${it.date}(d${it.daysFromToday})" }
        val missingStr = if (missing.isEmpty()) "-" else missing.joinToString(",")

        val zone = ZoneId.systemDefault()
        val sourceRows = hourlyForecasts
            ?.filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
            ?: emptyList()
        val withCloud = sourceRows.count { it.cloudCover != null }
        val dates = sourceRows.asSequence()
            .map { Instant.ofEpochMilli(it.dateTime).atZone(zone).toLocalDate() }
            .toList()
        val window = if (dates.isEmpty()) "none" else "${dates.min()}..${dates.max()}"

        return "cloud=$resolved/${cloudDays.size} cloudMissing=$missingStr " +
            "hourlyRows=${sourceRows.size} hourlyWithCloud=$withCloud hourlyWindow=$window"
    }

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

        val paddingPx = WidgetSizeCalculator.dpToPx(context, NAV_BUTTON_PADDING_DP)

        views.setViewVisibility(R.id.nav_left, View.VISIBLE)
        views.setViewVisibility(R.id.nav_left_zone, View.VISIBLE)
        views.setViewPadding(R.id.nav_left, paddingPx, 0, paddingPx, 0)
        bindNavDirection(
            views = views,
            buttonId = R.id.nav_left,
            zoneId = R.id.nav_left_zone,
            context = context,
            appWidgetId = appWidgetId,
            requestCode = WidgetRequestCodes.navLeft(appWidgetId),
            navAction = WidgetActions.ACTION_NAV_LEFT,
            canNavigate = canLeft,
            toastMessage = "No additional history available",
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
            toastMessage = "No more forecast available",
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
            val intent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = navAction
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val intent = Intent(context, WeatherWidgetProvider::class.java).apply {
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

    private fun updateTextMode(
        ctx: DailyRenderContext,
    ): List<DailyViewLogic.TextDayData> {
        val textCols = ctx.numColumns.coerceAtLeast(1)
        val dayDataList = DailyViewLogic.prepareTextDays(
            ctx.now, ctx.centerDate, ctx.today, ctx.weatherByDate, ctx.forecastSnapshots, ctx.hourlyForecasts, textCols,
            ctx.displaySource, ctx.skipHistory, ctx.stateManager, ctx.appWidgetId, ctx.precipProb, ctx.dailyActuals,
            ctx.climateNormals,
            ctx.currentTemps,
            currentTemp = ctx.currentTemp,
            observedAt = ctx.observedAt,
            todayLabel = ctx.context.getString(R.string.today)
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
        check(dayDataList.size <= dayIds.size) { "dayDataList has ${dayDataList.size} items but only ${dayIds.size} DayIds available" }

        dayDataList.forEachIndexed { index, data ->
            val ids = dayIds[index]
            if (data.isVisible) {
                ctx.views.setViewVisibility(ids.container, View.VISIBLE)
                populateDay(ctx.context, ctx.views, ctx.now, ids, data, ctx.hourlyForecasts, ctx.displaySource)
            } else {
                ctx.views.setViewVisibility(ids.container, View.GONE)
            }
        }

        if (dayDataList.any { it.isToday && it.rainSummary != null }) {
            ctx.stateManager.markRainShown(ctx.appWidgetId, ctx.today.format(DateTimeFormatter.ISO_LOCAL_DATE))
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

    private suspend fun renderTextMode(
        ctx: DailyRenderContext,
    ): List<DailyViewLogic.TextDayData> {
        DailyVisibilityManager.setTextModeViews(ctx.views)

        val textCols = ctx.numColumns.coerceAtLeast(1)
        val rootRightPaddingDp = if (ctx.isIconWidth) TEXT_MODE_ROOT_LEFT_PADDING_DP else TEXT_MODE_ROOT_RIGHT_PADDING_DP
        val contentRightPaddingDp = if (ctx.isIconWidth) 0 else TEXT_MODE_CONTENT_RIGHT_PADDING_DP
        ctx.views.setViewPadding(
            R.id.widget_root,
            WidgetSizeCalculator.dpToPx(ctx.context, TEXT_MODE_ROOT_LEFT_PADDING_DP),
            WidgetSizeCalculator.dpToPx(ctx.context, TEXT_MODE_ROOT_TOP_PADDING_DP),
            WidgetSizeCalculator.dpToPx(ctx.context, rootRightPaddingDp),
            WidgetSizeCalculator.dpToPx(ctx.context, TEXT_MODE_ROOT_BOTTOM_PADDING_DP),
        )
        val rightPaddingPx = WidgetSizeCalculator.dpToPx(ctx.context, contentRightPaddingDp)
        ctx.views.setViewPadding(R.id.text_container, 0, 0, rightPaddingPx, 0)

        val visibleDaysInfo = updateTextMode(ctx)

        visibleDaysInfo.find { it.isToday }?.let { todayDay ->
            ctx.appLogDao.log(
                LOG_TAG_TODAY_BAR_DEBUG,
                "widget=${ctx.appWidgetId} mode=TEXT high=${todayDay.highLabel} low=${todayDay.lowLabel} " +
                    "fallback=${todayDay.isTodayForecastFallback}",
                "DEBUG"
            )
        }

        logDailyRenderSummary(
            appLogDao = ctx.appLogDao,
            appWidgetId = ctx.appWidgetId,
            dateOffset = ctx.dateOffset,
            displaySource = ctx.displaySource,
            numColumns = ctx.numColumns,
            numRows = ctx.numRows,
            useGraph = false,
            skipYesterday = ctx.skipYesterday,
            centerDate = ctx.centerDate,
            visibleDates = visibleDaysInfo.map { it.date },
        )
        return visibleDaysInfo
    }

    internal data class RenderMetrics(
        val prepareMs: Long,
        val renderMs: Long,
    )

    internal data class HeaderState(
        val iconRes: Int,
        val currentTemp: Float?,
        val formattedTemp: String?,
        val estimatedTemp: Float?,
        val observedTemp: Float?,
        val appliedDelta: Float?,
        val deltaVisible: Boolean,
        val precipProb: Int?,
        val isPrecipVisible: Boolean,
        val precipTextSizeDp: Float?,
        val apiSourceText: String,
        val apiTextSizeDp: Float,
        val disclosure: HeaderDisclosureLevel,
        val headerScale: Float,
        val resolveMs: Long,
    )

    private data class HeaderResolution(
        val state: HeaderState,
        val precipPlacement: DailyHeaderBinder.HeaderPrecipPlacement,
    )

    private fun resolveAndBindHeader(
        context: Context,
        views: RemoteViews,
        displaySource: WeatherSource,
        now: LocalDateTime,
        lat: Double,
        lon: Double,
        weatherByDate: Map<LocalDate, ForecastEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTempHourlyForecasts: List<HourlyForecastEntity>,
        lastObservedTemp: Float?,
        observedAt: Long?,
        dimensions: WidgetDimensions,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        numColumns: Int,
        numRows: Int,
        useGraph: Boolean,
        smoothedForecasts: Map<Long, Float>?,
        sunInfo: SunInfo,
    ): HeaderResolution {
        val resolution = resolveHeaderState(
            context = context,
            displaySource = displaySource,
            now = now,
            lat = lat,
            lon = lon,
            weatherByDate = weatherByDate,
            hourlyForecasts = hourlyForecasts,
            currentTempHourlyForecasts = currentTempHourlyForecasts,
            lastObservedTemp = lastObservedTemp,
            observedAt = observedAt,
            dimensions = dimensions,
            stateManager = stateManager,
            appWidgetId = appWidgetId,
            numColumns = numColumns,
            numRows = numRows,
            useGraph = useGraph,
            smoothedForecasts = smoothedForecasts,
            sunInfo = sunInfo,
        )
        bindHeaderState(
            context = context,
            views = views,
            state = resolution.state,
            precipPlacement = resolution.precipPlacement,
            useGraph = useGraph,
            isIconWidth = dimensions.isIconWidth,
        )
        return resolution
    }

    private fun resolveHeaderState(
        context: Context,
        displaySource: WeatherSource,
        now: LocalDateTime,
        lat: Double,
        lon: Double,
        weatherByDate: Map<LocalDate, ForecastEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTempHourlyForecasts: List<HourlyForecastEntity>,
        lastObservedTemp: Float?,
        observedAt: Long?,
        dimensions: WidgetDimensions,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        numColumns: Int,
        numRows: Int,
        useGraph: Boolean,
        smoothedForecasts: Map<Long, Float>?,
        sunInfo: SunInfo,
    ): HeaderResolution {
        val today = now.toLocalDate()
        val isIconWidth = dimensions.isIconWidth

        val todayHeaderForecast = DailyHeaderBinder.resolveTodayHeaderForecast(
            now = now,
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
        )
        val iconRes =
            if (todayHeaderForecast != null) {
                WeatherIconMapper.getIconResource(
                    condition = todayHeaderForecast.condition,
                    isNight = sunInfo.isNight,
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

        val (currentTempResolution, resolveMs) =
            CurrentTempResolutionHelper.resolveAndPersistDelta(
                now = now,
                displaySource = displaySource,
                hourlyForecasts = currentTempHourlyForecasts.ifEmpty { hourlyForecasts },
                lastObservedTemp = lastObservedTemp,
                observedAt = observedAt,
                stateManager = stateManager,
                appWidgetId = appWidgetId,
                lat = lat,
                lon = lon,
                smoothedForecasts = smoothedForecasts,
            )
        val currentTemp = currentTempResolution.displayTemp

        val formattedTemp =
            currentTemp?.let {
                CurrentTemperatureResolver.formatDisplayTemperature(
                    temp = it,
                    numColumns = numColumns,
                    isStaleEstimate = currentTempResolution.isStaleEstimate,
                    useCelsius = stateManager.useCelsius(),
                )
            }

        val todayWeather = weatherByDate[today]
        val precipProb =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
                fallbackDailyProbability = todayWeather?.precipProbability,
                referenceTime = now,
            )
        val isPrecipVisible = HeaderTapTargetHelper.shouldShowPrecipTouchZone(precipProb)
        val isNightPrecip = precipProb != null && HeaderPrecipCalculator.isNext8HourPrecipPredominantlyNight(
            hourlyForecasts = hourlyForecasts,
            displaySource = displaySource,
            referenceTime = now,
            sunriseHour = sunInfo.sunTimes.sunriseHour,
            sunsetHour = sunInfo.sunTimes.sunsetHour,
        )
        val precipTextSizeDp = if (precipProb != null) {
            HeaderPrecipCalculator.getPrecipTextSize(precipProb) *
                if (isNightPrecip) HeaderPrecipCalculator.NIGHT_SCALE else 1f
        } else null

        val delta = currentTempResolution.appliedDelta
        val deltaVisible =
            currentTemp != null &&
            delta != null &&
            abs(delta) >= DELTA_VISIBILITY_THRESHOLD

        // Pick API label
        val apiSourceText = displaySource.shortDisplayName
        val apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows)
        val deltaTextForFit = if (deltaVisible) String.format("%+.1f", delta) else null
        val precipTextForFit = if (isPrecipVisible) "${precipProb}%" else null
        
        val disclosure = HeaderWidthChecker.resolveHeaderDisclosure(
            context = context,
            widthDp = dimensions.widthDp,
            apiSourceText = apiSourceText,
            apiTextSizeDp = apiTextSizeDp,
            currentTempText = formattedTemp,
            deltaText = deltaTextForFit,
            precipText = precipTextForFit,
            precipTextSizeDp = precipTextSizeDp,
        )

        val headerScale = HeaderWidthChecker.computeHeaderScale(
            context = context,
            widthDp = dimensions.widthDp,
            apiSourceText = apiSourceText,
            apiTextSizeDp = apiTextSizeDp,
            currentTempText = formattedTemp,
            deltaText = deltaTextForFit,
            precipText = precipTextForFit,
            precipTextSizeDp = precipTextSizeDp,
        )

        val widthDpForPrecip = dimensions.widthDp - GRAPH_CONTENT_PADDING_DP
        val dateText = if (numColumns >= HeaderConstants.DATE_MIN_COLUMNS) today.format(headerDateFormatter) else null
        val headerPrecipPlacement = DailyHeaderBinder.resolveHeaderPrecipPlacement(
            context = context,
            widthDp = widthDpForPrecip,
            numColumns = numColumns,
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

        val headerState = HeaderState(
            iconRes = iconRes,
            currentTemp = currentTemp,
            formattedTemp = formattedTemp,
            estimatedTemp = currentTempResolution.estimatedTemp,
            observedTemp = currentTempResolution.observedTemp,
            appliedDelta = delta,
            deltaVisible = deltaVisible,
            precipProb = precipProb,
            isPrecipVisible = isPrecipVisible,
            precipTextSizeDp = precipTextSizeDp,
            apiSourceText = apiSourceText,
            apiTextSizeDp = apiTextSizeDp,
            disclosure = disclosure,
            headerScale = headerScale,
            resolveMs = resolveMs,
        )
        return HeaderResolution(headerState, headerPrecipPlacement)
    }

    private fun bindHeaderState(
        context: Context,
        views: RemoteViews,
        state: HeaderState,
        precipPlacement: DailyHeaderBinder.HeaderPrecipPlacement,
        useGraph: Boolean,
        isIconWidth: Boolean,
    ) {

        // Set initial API source indicator (overwritten later once dual-source fit is decided)
        views.setTextViewText(R.id.api_source, state.apiSourceText)
        views.setTextViewText(R.id.text_mode_api_source, state.apiSourceText)

        if (useGraph) {
            views.setImageViewResource(R.id.weather_icon, state.iconRes)
            views.setViewVisibility(R.id.weather_icon, View.VISIBLE)
            views.setViewVisibility(R.id.current_weather_container, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.weather_icon, View.GONE)
            views.setViewVisibility(R.id.current_weather_container, View.GONE)
        }

        views.setViewVisibility(R.id.header_date_center, View.GONE)
        views.setViewVisibility(R.id.header_date_right, View.GONE)

        HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, state.isPrecipVisible)

        // Bind header elements with proper scale
        HeaderRemoteViewsBinder.bindApiSource(
            context = context,
            views = views,
            sourceText = state.apiSourceText,
            textSizeDp = state.apiTextSizeDp,
            scale = state.headerScale,
        )

        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = R.id.weather_icon,
            iconRes = state.iconRes,
            sizeDp = HeaderConstants.WEATHER_ICON_SIZE_DP,
            scale = state.headerScale,
        )
        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = R.id.settings_icon,
            iconRes = if (isIconWidth) 0 else R.drawable.ic_settings_gear,
            sizeDp = HeaderConstants.SETTINGS_ICON_SIZE_DP,
            scale = state.headerScale,
            tintColor = HEADER_ICON_TINT
        )
        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = R.id.text_mode_settings_icon,
            iconRes = if (isIconWidth) 0 else R.drawable.ic_settings_gear,
            sizeDp = HeaderConstants.SETTINGS_ICON_SIZE_DP,
            scale = state.headerScale,
            tintColor = HEADER_ICON_TINT
        )
        HeaderRemoteViewsBinder.bindCurrentTemp(
            context = context,
            views = views,
            formattedTemp = state.formattedTemp,
            hideDeltaOnNull = true,
            scale = state.headerScale
        )
        HeaderRemoteViewsBinder.bindPrecipProbability(
            context = context,
            views = views,
            precipText = if (state.isPrecipVisible) "${state.precipProb ?: 0}%" else null,
            textSizeDp = state.precipTextSizeDp ?: 0f,
            scale = state.headerScale,
        )
        HeaderRemoteViewsBinder.bindDelta(
            context = context,
            views = views,
            deltaText = if (state.deltaVisible) {
                val displayDelta = state.appliedDelta?.let { if (WidgetStateManager(context).useCelsius()) it / 1.8f else it }
                if (displayDelta != null) String.format("%+.1f", displayDelta) else null
            } else null,
            deltaVisible = state.deltaVisible,
            scale = state.headerScale,
        )

        if (useGraph && state.disclosure != HeaderDisclosureLevel.NONE) {
            HeaderRemoteViewsBinder.applyDisclosure(
                views,
                state.disclosure,
                isDeltaVisible = state.deltaVisible,
                isPrecipVisible = state.isPrecipVisible,
            )
        } else if (useGraph) {
            views.setViewVisibility(R.id.current_weather_container, View.GONE)
        }
    }
}
