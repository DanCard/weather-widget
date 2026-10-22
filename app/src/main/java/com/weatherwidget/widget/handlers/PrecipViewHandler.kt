package com.weatherwidget.widget.handlers

import com.weatherwidget.data.model.ForecastProduct
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.shared.util.HeaderContent
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.PrecipitationGraphRenderer
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.widget.WidgetActionReceiver
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.GraphRepaintGate
import com.weatherwidget.widget.ObservationWatermark
import java.time.Instant
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Handler for the precipitation view mode.
 */
object PrecipViewHandler {
    private const val TAG = "PrecipViewHandler"
    private const val CELL_HEIGHT_DP = 90

    /**
     * Update widget with precipitation data.
     */
    suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        precipProbability: Int? = null,
        lastObservedTemp: Float? = null,
        observedAt: Long? = null,
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
        startupToken: String? = null,
        uiOnly: Boolean = false,
        // Background (worker-driven) repaints push partially — no launcher re-inflate flash.
        // See WidgetViewHandler.
        partialPush: Boolean = false,
        origin: com.weatherwidget.widget.WidgetPushDispatcher.Origin = com.weatherwidget.widget.WidgetPushDispatcher.Origin.UNSPECIFIED,
        // See CloudCoverViewHandler: a stale source snapshot in the loader, not a real data gap.
        sourceMissingFromLoad: Boolean = false,
        // Newest observation time among the rows this source draws; drives GraphRepaintGate's
        // data-changed check. See ObservationWatermark.
        //
        // Null means "this caller did not measure it" — the interaction path (nav taps, refresh)
        // renders unconditionally and never consults the gate, so it has no watermark to offer.
        // Such a render must PRESERVE the stored value rather than stamp NONE over it: the stored
        // one was measured by the same query the next gated pass will use, and overwriting it with
        // a value from a different query makes the two incomparable.
        dataWatermarkMs: Long? = null,
        // A repaint was skipped outright for screen-off; force a full rebuild. See WidgetPaintCoordinator.
        paintOwed: Boolean = false,
    ) {
        val handlerStartMs = SystemClock.elapsedRealtime()
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        setupDeadZoneCatchAll(context, views, appWidgetId)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val numRows = dimensions.rows
        val isIconWidth = dimensions.isIconWidth

        val stateManager = WidgetStateManager(context)
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()

        // Gate bitmap rebuilds on real change for opportunistic UI-only repaints.
        if (uiOnly) {
            val zoom = stateManager.getZoomWindow(appWidgetId)
            val lastRender = stateManager.getLastGraphRender(appWidgetId)
            val bitmapDims = WidgetSizeCalculator.computeBitmapDimensions(context, dimensions.widthDp, dimensions.heightDp)
            val windowSpanMinutes = zoom.totalSpanHours * 60
            val gateDecision = GraphRepaintGate.shouldRebuildBitmap(
                displayedTemp = null,
                currentDisplayedTemp = null,
                lastRenderMs = lastRender?.renderMs ?: 0L,
                nowMs = SystemClock.elapsedRealtime(),
                windowSpanMinutes = windowSpanMinutes,
                bitmapWidthPx = bitmapDims.widthPx,
                lastWatermarkMs = lastRender?.dataWatermarkMs,
                currentWatermarkMs = dataWatermarkMs ?: ObservationWatermark.NONE,
                paintOwed = paintOwed,
            )
            if (!gateDecision.shouldRebuild) {
                appLogDao.log(
                    WidgetPerfLogger.TAG_WIDGET_PAINT,
                    "widget=$appWidgetId caller=PRECIPITATION state=skipped reason=${gateDecision.reason} thread=${Thread.currentThread().name}",
                )
                return
            }
        }

        Log.d(TAG, "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, hourlyCount=${hourlyForecasts.size}")

        views.setViewVisibility(R.id.header_date_center, View.GONE)
        views.setViewVisibility(R.id.header_date_right, View.GONE)
        // Reset sticky visibility from DailyViewHandler
        DailyViewHandler.bindTransientMessage(views, stateManager, appWidgetId, callerTag = "PRECIPITATION")

        // Hide graph day zones (not used in precipitation mode)
        views.setViewVisibility(R.id.graph_day_zones, View.GONE)
        views.setViewVisibility(R.id.graph_night_rain_zones, View.GONE)

        // Set up zoom tap zones
        val zoom = stateManager.getZoomWindow(appWidgetId)
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
        setupZoomTapZones(context, views, appWidgetId, zoom, hourlyOffset)

        // Setup navigation buttons
        setupNavigationButtons(context, views, appWidgetId, stateManager)

        // Temperature header taps toggle back to DAILY view
        HeaderTapTargetHelper.bindToggleTemperatureHeader(context, views, appWidgetId)
        HeaderTapTargetHelper.bindPrecipitationHeader(context, views, appWidgetId)

        // Get current display source
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        if (
            ApiSourceWarningHelper.checkAndRenderBlockingWarning(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                numRows = numRows,
                appLogDao = appLogDao,
                displaySource = displaySource,
                hasSelectedSourceData = hourlyForecasts.any { it.source == displaySource.id },
                callerTag = "PRECIP",
            )
        ) {
            appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=PRECIPITATION origin=${origin.name} state=warning thread=${Thread.currentThread().name}")
            com.weatherwidget.widget.WidgetPushDispatcher.push(
                appWidgetManager = appWidgetManager,
                appWidgetId = appWidgetId,
                views = views,
                partialPush = false,
                caller = "PRECIPITATION_WARNING",
                appLogDao = appLogDao,
                origin = origin,
            )
            return
        }

        // Set weather icon
        val now = LocalDateTime.now()
        // NaN, never a hardcoded coordinate: this is derived from the rows about to be drawn, so it
        // only fires when there are none. NaN degrades honestly downstream (sun shading falls back to
        // UNKNOWN_LOCATION, IDW distance weights drop out) instead of silently rendering Google HQ.
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: Double.NaN
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: Double.NaN

        // Weather icon + bottom graph zone → cloud cover view
        val goCloudIntent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActions.ACTION_SET_VIEW
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(WidgetActions.EXTRA_TARGET_VIEW, com.weatherwidget.widget.ViewMode.CLOUD_COVER.name)
        }
        val goCloudPending = PendingIntent.getBroadcast(
            context, WidgetRequestCodes.iconViewToggle(appWidgetId), goCloudIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.weather_icon, goCloudPending)

        views.setViewVisibility(R.id.current_temp_delta, View.GONE)
        views.setViewVisibility(R.id.current_temp_delta_label, View.GONE)

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
            )
        val currentTemp = currentTempResolution.displayTemp
        // Icon, temp and rain %: the shared header rule (desktop draws the same content).
        val content = HeaderContent.build(
            view = HeaderContent.View.PRECIPITATION,
            hourly = hourlyForecasts.map(HourlyForecastEntity::toHourlyForecast),
            displaySourceId = displaySource.id,
            dailyFallback = null,
            fallbackDailyPrecip = precipProbability,
            currentTemp = currentTemp,
            isStaleEstimate = currentTempResolution.isStaleEstimate,
            numColumns = numColumns,
            deltaFromYesterday = null,
            now = now,
            centerTime = centerTime,
            lat = lat,
            lon = lon,
            useCelsius = stateManager.useCelsius(),
            widthDp = dimensions.widthDp,
        )
        val iconRes = content.iconName?.let(WeatherIconMapper::fromIconName) ?: 0
        val sourceIndicator = content.sourceLabel
        val formattedTemp = content.tempText
        val headerPrecipProbability = content.precipProbability
        val isPrecipVisible = headerPrecipProbability != null
        val precipTextSizeDp = content.precipFontScale?.let { HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP * it }

        val today = LocalDateTime.now().toLocalDate()
        val isToday = centerTime.toLocalDate() == today

        val headerResult = HourlyHeaderBinder.bindHourlyHeader(
            context = context,
            views = views,
            iconRes = iconRes,
            formattedTemp = formattedTemp,
            isPrecipVisible = isPrecipVisible,
            headerPrecipProbability = headerPrecipProbability,
            precipTextSizeDp = precipTextSizeDp,
            widthDp = dimensions.widthDp,
            numRows = numRows,
            sourceIndicator = sourceIndicator,
            showStations = isToday,
        )
        val headerScale = headerResult.headerScale
        val disclosure = headerResult.disclosure

        setupHomeShortcut(context, views, appWidgetId, scale = headerScale)
        if (!isIconWidth) {
            setupSettingsShortcut(context, views, appWidgetId)
        }
        setupHistoryShortcut(context, views, appWidgetId, centerTime, hourlyForecasts, displaySource, scale = headerScale)
        setupWeatherStationsShortcut(context, views, appWidgetId, scale = headerScale)

        setupGraphSelectorShortcut(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            currentViewMode = com.weatherwidget.widget.ViewMode.PRECIPITATION,
            widthDp = dimensions.widthDp,
            isPrecipVisible = isPrecipVisible && disclosure.showsPrecip(),
            scale = headerScale,
        )

        // Setup API toggle (skipped at 1 icon wide — target is hidden)
        if (!isIconWidth) {
            setupApiToggle(context, views, appWidgetId, numRows, scale = headerScale)
        }

        positionCenterIcons(views, dimensions.widthDp, context.resources.displayMetrics.density, isPrecipVisible && disclosure.showsPrecip(), isToday, headerResult.inlineZoneWidthDp)

// Use graph mode for 2+ rows, text mode for 1 row
        val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
        val useGraph = rawRows >= 1.4f
        var buildHoursMs = 0L
        var renderMs = 0L
        var graphHoursCount = 0

        if (useGraph) {
            views.setViewVisibility(R.id.text_container, View.GONE)
            views.setViewVisibility(R.id.graph_view, View.VISIBLE)
            views.setViewVisibility(R.id.graph_day_zones, View.GONE)
            views.setViewVisibility(R.id.graph_interaction_container, View.VISIBLE)

            // Build precipitation hour data list
            val buildHoursStartMs = SystemClock.elapsedRealtime()
            val actualPrecipByHour = loadActualPrecipByHourForGraph(
                context = context,
                hourlyForecasts = hourlyForecasts,
                centerTime = centerTime,
                zoom = zoom,
                displaySource = displaySource,
            )
            val hours = buildPrecipHourDataList(
                hourlyForecasts = hourlyForecasts,
                centerTime = centerTime,
                numColumns = numColumns,
                displaySource = displaySource,
                zoom = zoom,
                actualPrecipByHour = actualPrecipByHour,
            )
            buildHoursMs = SystemClock.elapsedRealtime() - buildHoursStartMs
            graphHoursCount = hours.size

            val (startHour, endHour) = computePrecipGraphWindow(centerTime, zoom)
            val totalWindowHours = java.time.temporal.ChronoUnit.HOURS.between(startHour, endHour).toInt() + 1
            val missingHours = (totalWindowHours - hours.size).coerceAtLeast(0)
            if (missingHours > 0) {
                appLogDao.log(
                    "PRECIP_COVER_GAPS",
                    "widget=$appWidgetId source=${displaySource.id} missing=$missingHours total=$totalWindowHours " +
                        "sourceMissingFromLoad=$sourceMissingFromLoad",
                )
                // The one gap-fill: only a fetch that can cover a day in view, that source alone.
                if (!sourceMissingFromLoad) {
                    com.weatherwidget.widget.WidgetDayClickCoordinator.fillHourlyGaps(context, appWidgetId, "precip_gaps")
                }
            }

            if (hours.isEmpty() && hourlyForecasts.isNotEmpty()) {
                Log.w(TAG, "buildPrecipHourDataList returned empty despite ${hourlyForecasts.size} hourly rows — " +
                    "centerTime=$centerTime zoom=$zoom offset=$hourlyOffset, skipping bitmap update")
                com.weatherwidget.widget.WidgetPushDispatcher.push(
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    views = views,
                    partialPush = partialPush,
                    caller = "PRECIPITATION_DEGENERATE",
                    appLogDao = appLogDao,
                    origin = origin,
                )
                return
            }

            val bitmapDims = WidgetSizeCalculator.computeBitmapDimensions(context, dimensions.widthDp, dimensions.heightDp)

            // Render precipitation graph
            val isNarrow = zoom.stage == com.weatherwidget.widget.ZoomStage.NARROW
            val hourLabelSpacingDp = if (isNarrow) 18f else 28f
            val rainAmountWindowHours = hours.size
            // WIDE: split rain into wettest day (8a-8p) + wettest night (8p-8a) regions with a divider.
            // NARROW: per-hour Pred/Act for the first few hours where rain exists.
            val rainLabelMode = if (isNarrow) {
                com.weatherwidget.shared.graph.RainPeriodSelection.Mode.PER_HOUR
            } else {
                com.weatherwidget.shared.graph.RainPeriodSelection.Mode.DAY_NIGHT
            }
            val renderStartMs = SystemClock.elapsedRealtime()
            val renderLogs = mutableListOf<String>()
            val simLoopLocation = stateManager.getWidgetLocation(appWidgetId)
            // Open-Meteo shown → its own rain map (global, pin = graph); any other source → WeatherNext,
            // offered only on its map (a gone host still shows it: the viewer offers Open-Meteo).
            val simLoopIsOpenMeteo = displaySource == WeatherSource.OPEN_METEO
            val simLoopOffered = SimLoopTouchTarget.supported() && simLoopLocation != null && (
                simLoopIsOpenMeteo ||
                    com.weatherwidget.data.remote.weathernext.WeatherNextAvailability.showPill(
                        simLoopLocation.first, simLoopLocation.second,
                    )
                )
            // Radar: observed, source-independent — offered wherever the graph has a location.
            val radarOffered = SimLoopTouchTarget.supported() && simLoopLocation != null
            var simLoopPill: android.graphics.RectF? = null
            var radarPill: android.graphics.RectF? = null
            var simLoopPlotBottom = 0f
            val bitmap = PrecipitationGraphRenderer.renderGraph(
                context = context,
                hours = hours,
                widthPx = bitmapDims.widthPx,
                heightPx = bitmapDims.heightPx,
                currentTime = now,
                bitmapScale = bitmapDims.bitmapScale,
                smoothIterations = zoom.smoothIterations,
                hourLabelSpacingDp = hourLabelSpacingDp,
                rainAmountWindowHours = rainAmountWindowHours,
                rainLabelMode = rainLabelMode,
                numColumns = numColumns,
                job = coroutineContext[Job],
                onDebugLog = { renderLogs.add(it) },
                showErrorWatermark = stateManager.viewWatermark(displaySource, ForecastProduct.HOURLY).show,
                errorSourceLabel = displaySource.displayName,
                errorCode = stateManager.viewWatermark(displaySource, ForecastProduct.HOURLY).errorCode,
                errorFailureTimeMs = stateManager.viewWatermark(displaySource, ForecastProduct.HOURLY).failureTimeMs,
                errorBannerSinceMs = stateManager.viewWatermark(displaySource, ForecastProduct.HOURLY).bannerSinceMs,
                simLoopLabel = context.getString(R.string.sim_loop_button).takeIf { simLoopOffered },
                radarLabel = context.getString(R.string.radar_button).takeIf { radarOffered },
                onPillsPlaced = { radar, simLoop, plotBottom ->
                    radarPill = radar
                    simLoopPill = simLoop
                    simLoopPlotBottom = plotBottom
                },
                nowcastSlots = nowcastBandSlots(context, lat, lon, displaySource),
            )
            renderLogs.forEach { appLogDao.log("PrecipGraph", it) }
            renderMs = SystemClock.elapsedRealtime() - renderStartMs
            views.setImageViewBitmap(R.id.graph_view, bitmap)

            // Body zones always zoom; bottom-row zones use icon-dependent routing
            val hourIcons = hours.map { it.iconRes }
            setupZoomTapZones(context, views, appWidgetId, zoom, hourlyOffset)
            HourlyBottomZoneHelper.setup(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                hourIconResources = hourIcons,
                currentViewMode = com.weatherwidget.widget.ViewMode.PRECIPITATION,
                zoom = zoom,
                hourlyOffset = hourlyOffset,
            )
            ErrorPillTouchTargetHelper.setupErrorPillTouchTarget(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                showErrorWatermark = stateManager.viewWatermark(displaySource, ForecastProduct.HOURLY).show,
                errorCode = stateManager.viewWatermark(displaySource, ForecastProduct.HOURLY).errorCode,
                bannerSinceMs = stateManager.viewWatermark(displaySource, ForecastProduct.HOURLY).bannerSinceMs,
                sourceId = displaySource.id,
            )

            val radarZone = SimLoopTouchTarget.apply(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                pillPx = radarPill,
                plotBottomPx = simLoopPlotBottom,
                bitmapWidthPx = bitmapDims.widthPx,
                bitmapHeightPx = bitmapDims.heightPx,
                widgetWidthDp = dimensions.widthDp,
                widgetHeightDp = dimensions.heightDp,
                location = simLoopLocation,
                target = com.weatherwidget.ui.RadarActivity::class.java,
                zoneId = R.id.radar_touch_zone,
                requestCode = WidgetRequestCodes.radar(appWidgetId),
            )
            val simLoopZone = SimLoopTouchTarget.apply(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                pillPx = simLoopPill,
                plotBottomPx = simLoopPlotBottom,
                bitmapWidthPx = bitmapDims.widthPx,
                bitmapHeightPx = bitmapDims.heightPx,
                widgetWidthDp = dimensions.widthDp,
                widgetHeightDp = dimensions.heightDp,
                location = simLoopLocation,
                target = if (simLoopIsOpenMeteo) com.weatherwidget.ui.OpenMeteoRainLoopActivity::class.java else com.weatherwidget.ui.RainForecastActivity::class.java,
                avoidDp = radarZone,
            )
            appLogDao.log(
                "PrecipGraph",
                "RADAR_PILL zoneDp=${radarZone?.let { "${it.left.toInt()},${it.top.toInt()},${it.right.toInt()},${it.bottom.toInt()}" }} " +
                    "reason=${when { !SimLoopTouchTarget.supported() -> "api<31"; !radarOffered -> "no_location"; radarPill == null -> "no_space"; else -> "placed" }}",
            )
            appLogDao.log(
                "PrecipGraph",
                "SIM_LOOP_PILL zoneDp=${simLoopZone?.let { "${it.left.toInt()},${it.top.toInt()},${it.right.toInt()},${it.bottom.toInt()}" }} " +
                    "reason=${when { !SimLoopTouchTarget.supported() -> "api<31"; !simLoopOffered -> "unavailable"; simLoopPill == null -> "no_space"; else -> "placed" }} map=${if (simLoopIsOpenMeteo) "open_meteo" else "weathernext"}",
            )
        } else {
            views.setViewVisibility(R.id.sim_loop_touch_zone, View.GONE)
            views.setViewVisibility(R.id.radar_touch_zone, View.GONE)
            views.setViewVisibility(R.id.error_pill_touch_zone, View.GONE)
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_hour_zones, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_reserved_space, View.VISIBLE)

            // Text mode: show precip percentages
            updatePrecipTextMode(views, hourlyForecasts, centerTime, numColumns, displaySource)
        }

        if (isIconWidth) {
            HeaderRemoteViewsBinder.hideIconWidthControls(views)
        }

        appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=PRECIPITATION origin=${origin.name} state=data push=${if (partialPush) "partial" else "full"} thread=${Thread.currentThread().name}")
        com.weatherwidget.widget.WidgetPushDispatcher.push(
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            views = views,
            partialPush = partialPush,
            caller = "PRECIPITATION",
            appLogDao = appLogDao,
            origin = origin,
        )

        // Persist render metadata for the GraphRepaintGate on future uiOnly cycles.
        stateManager.setLastGraphRender(
            appWidgetId,
            com.weatherwidget.widget.WidgetStateManager.LastGraphRenderState(
                renderMs = SystemClock.elapsedRealtime(),
                displayedTemp = null,
                dataWatermarkMs = dataWatermarkMs
                    ?: stateManager.getLastGraphRender(appWidgetId)?.dataWatermarkMs,
            ),
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
                "view" to "PRECIPITATION",
                "useGraph" to useGraph,
                "resolveMs" to resolveMs,
                "buildHoursMs" to buildHoursMs,
                "renderMs" to renderMs,
                "hourlyCount" to hourlyForecasts.size,
                "graphHours" to graphHoursCount,
                "totalMs" to totalMs,
            ),
            debugTag = TAG,
        )
    }

    @androidx.annotation.VisibleForTesting
    internal fun buildPrecipHourDataList(
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
        zoom: com.weatherwidget.widget.ZoomWindow = com.weatherwidget.widget.ZoomStage.WIDE.window(),
        actualPrecipByHour: Map<LocalDateTime, Float> = emptyMap(),
        now: LocalDateTime = LocalDateTime.now(),
    ): List<PrecipitationGraphRenderer.PrecipHourData> {
        val hours = mutableListOf<PrecipitationGraphRenderer.PrecipHourData>()
        // NaN, never a hardcoded coordinate: derived from the rows about to be drawn, so it only
        // fires when there are none. Sun shading degrades to UNKNOWN_LOCATION downstream.
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: Double.NaN
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: Double.NaN

        val forecastsByTime =
            hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    entry.value.find { it.source == displaySource.id }
                }

        val (startHour, endHour) = computePrecipGraphWindow(centerTime, zoom)

        // Narrow widgets widen the marker cadence to fit the inline footer groups: WIDE 6h vs 4h,
        // and NARROW every other hour once its span is widened past 6h. Wide widgets keep the
        // default at both zooms. Matches the temperature graph.
        val labelInterval = com.weatherwidget.shared.graph.HourlyZoomRules.footerLabelInterval(
            zoom,
            narrowWidget = com.weatherwidget.widget.HourlyFooterRenderer.isNarrowWidget(numColumns),
        )
        var currentHour = startHour
        var hourIndex = 0
        val zoneId = ZoneId.systemDefault()

        // On multi-day windows switch the footer to one date label per day ("Tue 23"), matching the
        // temperature graph (shared rule in HourlyGraphViewCommon.resolveHourLabel).
        val dateMode = com.weatherwidget.shared.graph.HourlyZoomRules.isDateMode(zoom.totalSpanHours)
        val dateLabelMillis = if (dateMode) dateLabelMillis(startHour, endHour, zoneId) else emptySet()

        // End-INCLUSIVE, same as the temperature graph's shared ActualTemperatureSeriesBuilder: an
        // n-hour window spans start..start+n and needs n+1 marks, or the drawn axis is an hour
        // narrower than the Hourly Zoom setting promises.
        while (!currentHour.isAfter(endHour)) {
            val hourMs = currentHour.atZone(zoneId).toInstant().toEpochMilli()
            val forecast = forecastsByTime[hourMs]

            if (forecast != null) {
                val p = HourlyGraphViewCommon.resolveHourPresentation(
                    currentHour, forecast, now, lat, lon, labelInterval, hourIndex,
                    hourMs = hourMs, dateMode = dateMode, dateLabelMillis = dateLabelMillis,
                )
                hours.add(
                    PrecipitationGraphRenderer.PrecipHourData(
                        dateTime = currentHour,
                        precipProbability = forecast.precipProbability ?: 0,
                        label = p.label,
                        iconRes = p.iconRes,
                        isNight = p.isNight,
                        isTwilight = p.isTwilight,
                        isSunBoundary = p.isSunBoundary,
                        isSunny = p.isSunny,
                        isRainy = p.isRainy,
                        isMixed = p.isMixed,
                        isCurrentHour = p.isCurrentHour,
                        showLabel = p.showLabel,
                        isDateLabel = p.isDateLabel,
                        precipAmountMm = forecast.precipAmountMm,
                        actualPrecipAmountMm =
                            if (currentHour.isBefore(now)) {
                                actualPrecipByHour[currentHour]
                            } else {
                                null
                            },
                    ),
                )
                hourIndex++
            }

            currentHour = currentHour.plusHours(1)
        }

        return hours
    }

    @androidx.annotation.VisibleForTesting
    internal fun computePrecipGraphWindow(
        centerTime: LocalDateTime,
        zoom: com.weatherwidget.widget.ZoomWindow,
    ): Pair<LocalDateTime, LocalDateTime> {
        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        return alignedCenter.minusHours(zoom.backHours) to alignedCenter.plusHours(zoom.forwardHours)
    }

    /** Open-Meteo's 15-minute nowcast for the band; empty unless Open-Meteo is displayed with native coverage. */
    private fun nowcastBandSlots(
        context: Context,
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
    ): List<com.weatherwidget.shared.nowcast.NowcastSlot> {
        if (lat.isNaN() || lon.isNaN()) return emptyList()
        val nowMs = System.currentTimeMillis()
        val series = com.weatherwidget.shared.nowcast.NowcastDisplay.series(
            com.weatherwidget.widget.AndroidNowcast.store(context), lat, lon, displaySource.id, nowMs,
        )
        return com.weatherwidget.shared.nowcast.NowcastDisplay.bandSlots(series, nowMs)
    }

    private suspend fun loadActualPrecipByHourForGraph(
        context: Context,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        zoom: com.weatherwidget.widget.ZoomWindow,
        displaySource: WeatherSource,
    ): Map<LocalDateTime, Float> {
        if (hourlyForecasts.isEmpty()) return emptyMap()
        val (startHour, endHour) = computePrecipGraphWindow(centerTime, zoom)
        val now = LocalDateTime.now()
        if (!startHour.isBefore(now)) return emptyMap()

        val zoneId = ZoneId.systemDefault()
        val queryStartMs = startHour.atZone(zoneId).toInstant().toEpochMilli()
        val queryEndHour = minOf(endHour.plusHours(1), now.plusHours(1))
        val queryEndMs = queryEndHour.atZone(zoneId).toInstant().toEpochMilli()
        if (queryEndMs <= queryStartMs) return emptyMap()

        val lat = hourlyForecasts.first().locationLat
        val lon = hourlyForecasts.first().locationLon
        val observations = WeatherDatabase.getDatabase(context).observationDao()
            // Unscoped: precip actuals are the hybrid set (measured NWS rows alongside the
            // provider's own reports), so this is not the temperature blend's scope.
            .getObservationsInRange(queryStartMs, queryEndMs, lat, lon, apis = null)
        val actualPrecipByHour = buildActualPrecipByHour(observations, displaySource, zoneId)
        if (actualPrecipByHour.isNotEmpty()) {
            Log.d(
                TAG,
                "loadActualPrecipByHourForGraph: source=${displaySource.id} " +
                    "window=$startHour..$endHour actualHours=${actualPrecipByHour.size}",
            )
        }
        return actualPrecipByHour
    }

    @androidx.annotation.VisibleForTesting
    internal fun buildActualPrecipByHour(
        observations: List<ObservationEntity>,
        displaySource: WeatherSource,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Map<LocalDateTime, Float> =
        // One amount per hour for the site: stations are combined, not added (ObservedPrecip).
        com.weatherwidget.shared.actuals.ObservedPrecip.byHour(
            com.weatherwidget.shared.actuals.ObservedPrecip.rowsFor(
                observations.map { it.toReading() },
                displaySource,
            ),
            zoneId,
        )

    private fun updatePrecipTextMode(
        views: RemoteViews,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
    ) {
        HourlyGraphViewCommon.bindHourlyTextMode(
            views, hourlyForecasts, centerTime, numColumns, displaySource,
        ) { forecast -> if (forecast != null) "${forecast.precipProbability ?: 0}%" else "--%" }
    }
}
