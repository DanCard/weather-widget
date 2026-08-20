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
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.ClimateGapFiller
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.actuals.YesterdayDeltaCalculator
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
import com.weatherwidget.widget.WidgetActionReceiver
import com.weatherwidget.widget.WidgetActions
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
    private const val NAV_BUTTON_PADDING_DP = 10
    // Only probe for incomplete-history backfill when the visible window is recent enough that
    // NWS observation history can still serve it. Older days are beyond the fetch horizon.
    private const val HISTORY_BACKFILL_VISIBLE_DAYS = 3L
    // Log tags for diagnostic database entries
    private const val LOG_TAG_WIDGET_ACTUAL = "WIDGET_ACTUAL"
    internal const val LOG_TAG_TODAY_BAR_DEBUG = DailyTextRenderer.LOG_TAG_TODAY_BAR_DEBUG
    internal const val LOG_TAG_TODAY_HIGH_PROVENANCE = "TODAY_HIGH_PROVENANCE"
    private const val LOG_TAG_DAILY_RENDER = "DAILY_RENDER"
    private const val LOG_TAG_DAILY_RENDER_EMPTY = "DAILY_RENDER_EMPTY"
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
        val weatherByDate: Map<LocalDate, ForecastEntity>,
        val forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
        val hourlyForecasts: List<HourlyForecastEntity>,
        val currentTemps: List<ObservationEntity>,
        val dailyActuals: DailyActualMap,
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
        origin: com.weatherwidget.widget.WidgetPushDispatcher.Origin,
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
        // Eligibility is based on the post-widening date count. At a far navigation fringe Today
        // can be the tenth/rightmost date but disappear when the count drops to nine; retain the
        // ordinary ten-column layout there rather than enabling an overlay with no Today column.
        val overlayCandidateColumns = (numColumns - 1).coerceAtLeast(1)
        val overlayVisibleRange =
            NavigationUtils.getVisibleDateRange(today, dateOffset, overlayCandidateColumns, skipYesterday)
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
                            // Prefer a complete preferred row; otherwise a complete Climate Normal row;
                            // otherwise the preferred (even if incomplete). Avoids items.first() which
                            // is non-deterministic when multiple rows share the same source.
                            preferred
                                ?: items.find { it.source == WeatherSource.GENERIC_GAP.id && it.highTemp != null && it.lowTemp != null }
                                ?: items.firstOrNull()
                        }
                    } else {
                        // History / today / +1 / +2: real display-source only, never GENERIC_GAP filler.
                        // (Today's incomplete-source recovery lives in DailyViewLogic / DailyActualsEstimator.)
                        preferred
                    }
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

        val sunInfo = SunPositionUtils.getSunInfoOrUnknown(now, lat, lon)

        // Header yesterday-delta: one observation range query per render, shared with the
        // today-column overlay (same window) via ctx.headerObservations.
        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val headerObservations = repository?.getObservationsInRange(
            nowMs - DailyGraphRenderer.OVERLAY_OBSERVATION_LOOKBACK_MS,
            nowMs,
            lat,
            lon,
        )
        val deltaFromYesterday = headerObservations?.let { observations ->
            YesterdayDeltaCalculator.computeDelta(
                observations = observations.map { it.toReading() },
                hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
                displaySourceId = displaySource.id,
                userLat = lat,
                userLon = lon,
                observedAtMs = observedAt,
                currentObservedTemp = lastObservedTemp,
                personalStationWeight = stateManager.getPersonalStationWeight(),
                zoneId = ZoneId.systemDefault(),
            )
        }

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
            smoothedForecasts = smoothedForecasts,
            sunInfo = sunInfo,
            headerDateFormatter = headerDateFormatter(),
            deltaFromYesterday = deltaFromYesterday,
            dateOffset = dateOffset,
            skipYesterday = skipYesterday,
        )

        val currentTemp = headerResolution.state.currentTemp
        val formattedTemp = headerResolution.state.formattedTemp
        val iconRes = headerResolution.state.iconRes
        val precipProb = headerResolution.state.precipProb
        val isPrecipVisible = headerResolution.state.isPrecipVisible
        val precipTextSizeDp = headerResolution.state.precipTextSizeDp
        val delta = headerResolution.state.appliedDelta
        val yesterdayDelta = headerResolution.state.yesterdayDelta
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

        val availableDates = buildAvailableNavigationDates(weatherList, dailyActuals, displaySource)
        Log.d(TAG, "updateWidget: widgetId=$appWidgetId, widthDp=${dimensions.widthDp}, heightDp=${dimensions.heightDp}, cols=$numColumns, rows=$numRows, offset=$dateOffset, minDate=${availableDates.minOrNull()}, maxDate=${availableDates.maxOrNull()}")
        setupNavigationButtons(context, views, appWidgetId, stateManager, availableDates, displayNumColumns, skipYesterday, today, useGraph, dateOffset)

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
            val visibleDaysInfo = DailyTextRenderer.render(ctx)
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
            observationsLat = lat,
            observationsLon = lon,
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
        appLogDao.log(logTag, message, "INFO")
        WidgetWorkScheduler.enqueueRedundantImmediateSync(
            context = context,
            forceRefresh = forceRefresh,
            reason = reason,
        )
        // Mark after the trigger succeeds so a failure doesn't consume the cooldown.
        stateManager.markMissingDataRefreshRequested(appWidgetId, displaySource.id, refreshType)
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
        cloudDays: List<CloudCoverDiagnosticRow>? = null,
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
        cloudDays: List<CloudCoverDiagnosticRow>,
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
            val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = navAction
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val intent = Intent(context, WidgetActionReceiver::class.java).apply {
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
        val apiSourceText: String,
        val apiTextSizeDp: Float,
        val disclosure: HeaderDisclosureLevel,
        val headerScale: Float,
        val resolveMs: Long,
        /** Whether today or yesterday is inside the visible day window; drives [iconCount]. */
        val observationsInView: Boolean = true,
        /**
         * Live count of daily header buttons: forecast history always, current observations only
         * when [observationsInView] (today OR yesterday on screen — the station-history affordance
         * behind the button is date-independent). Never a constant — the reserved width follows it.
         */
        val iconCount: Int = 0,
        val iconPlacement: DailyIconPlacement = DailyIconPlacement.HIDDEN,
        /**
         * Which of the header date / "from yest" caption survives when both cannot fit. Resolved
         * once here so the bitmap renderer and the RemoteViews bind cannot disagree.
         */
        val preferDateOverLabel: Boolean = true,
    )
}
