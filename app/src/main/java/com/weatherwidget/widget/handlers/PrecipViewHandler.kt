package com.weatherwidget.widget.handlers

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
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.HeaderFormatter
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.SunPhase
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.PrecipitationGraphRenderer
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.GraphRepaintGate
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

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
    ) {
        val handlerStartMs = SystemClock.elapsedRealtime()
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val numRows = dimensions.rows
        val isIconWidth = dimensions.isIconWidth

        val stateManager = WidgetStateManager(context)
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()

        // Gate bitmap rebuilds on real change for opportunistic UI-only repaints.
        if (uiOnly) {
            val zoom = stateManager.getZoomLevel(appWidgetId)
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

        // Hide graph day zones (not used in precipitation mode)
        views.setViewVisibility(R.id.graph_day_zones, View.GONE)
        views.setViewVisibility(R.id.graph_night_rain_zones, View.GONE)

        // Set up zoom tap zones
        val zoom = stateManager.getZoomLevel(appWidgetId)
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
            appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=PRECIPITATION state=warning thread=${Thread.currentThread().name}")
            appWidgetManager.updateAppWidget(appWidgetId, views)
            return
        }
        val sourceIndicator = HeaderFormatter.formatSourceIndicator(
            centerTime = centerTime,
            now = LocalDateTime.now(),
            sourceName = displaySource.shortDisplayName,
            widthDp = dimensions.widthDp
        )

        // Set weather icon
        val now = LocalDateTime.now()
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val sunInfo = SunPositionUtils.getSunInfo(now, lat, lon)
        val currentHourForecast = WeatherTimeUtils.getCurrentHourForecast(hourlyForecasts, displaySource)
        val iconRes = WeatherIconMapper.getIconResource(
            condition = currentHourForecast?.condition,
            isNight = sunInfo.isNight,
            cloudCover = currentHourForecast?.cloudCover,
            precipProbability = currentHourForecast?.precipProbability,
            isTwilight = sunInfo.phase == SunPhase.TWILIGHT,
            isSunBoundary = sunInfo.isSunBoundary,
        )

        // Weather icon + bottom graph zone → cloud cover view
        val goCloudIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
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
        val formattedTemp = if (currentTemp != null) {
            CurrentTemperatureResolver.formatDisplayTemperature(
                temp = currentTemp,
                numColumns = numColumns,
                isStaleEstimate = currentTempResolution.isStaleEstimate,
            )
        } else null

        val headerPrecipProbability =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
                fallbackDailyProbability = precipProbability,
                referenceTime = centerTime,
            )

        // Show precipitation probability next to current temp.
        // In precipitation mode, show even if 0% so the user gets confirmation.
        val isPrecipVisible = headerPrecipProbability != null
        val precipTextSizeDp = if (headerPrecipProbability != null) HeaderPrecipCalculator.getPrecipTextSize(headerPrecipProbability) else null

        val headerScale = HeaderWidthChecker.computeHeaderScale(
            context = context,
            widthDp = dimensions.widthDp,
            apiSourceText = sourceIndicator,
            apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows),
            currentTempText = formattedTemp,
            deltaText = null,
            precipText = if (isPrecipVisible) "$headerPrecipProbability%" else null,
            precipTextSizeDp = precipTextSizeDp,
        )

        HeaderRemoteViewsBinder.bindApiSource(
            context = context,
            views = views,
            sourceText = sourceIndicator,
            textSizeDp = HeaderConstants.apiTextSizeDp(numRows),
            scale = headerScale,
        )
        views.setViewVisibility(R.id.api_touch_zone, View.VISIBLE)
        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = R.id.settings_icon,
            iconRes = R.drawable.ic_settings_gear,
            sizeDp = HeaderConstants.SETTINGS_ICON_SIZE_DP,
            scale = headerScale,
            tintColor = 0xAAFFFFFF.toInt()
        )
        views.setViewVisibility(R.id.top_right_header_container, View.VISIBLE)

        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = R.id.weather_icon,
            iconRes = iconRes,
            sizeDp = HeaderConstants.WEATHER_ICON_SIZE_DP,
            scale = headerScale,
        )

        HeaderRemoteViewsBinder.bindCurrentTemp(
            context = context,
            views = views,
            formattedTemp = formattedTemp,
            scale = headerScale,
        )

        HeaderRemoteViewsBinder.bindPrecipProbability(
            context = context,
            views = views,
            precipText = if (isPrecipVisible) "$headerPrecipProbability%" else null,
            textSizeDp = precipTextSizeDp ?: 0f,
            scale = headerScale,
        )
HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, isPrecipVisible)

// Apply progressive disclosure for narrow widgets
val disclosure = HeaderWidthChecker.resolveHeaderDisclosure(
    context = context,
    widthDp = dimensions.widthDp,
    apiSourceText = sourceIndicator,
    apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows),
    currentTempText = formattedTemp,
    deltaText = null,
    precipText = if (isPrecipVisible) "$headerPrecipProbability%" else null,
    precipTextSizeDp = precipTextSizeDp,
)
HeaderRemoteViewsBinder.applyDisclosure(views, disclosure, isPrecipVisible = isPrecipVisible)

        val today = LocalDateTime.now().toLocalDate()
        val isToday = centerTime.toLocalDate() == today

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

        positionCenterIcons(views, dimensions.widthDp, context.resources.displayMetrics.density, isPrecipVisible && disclosure.showsPrecip(), isToday)

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
            if (hours.isEmpty() && hourlyForecasts.isNotEmpty()) {
                Log.w(TAG, "buildPrecipHourDataList returned empty despite ${hourlyForecasts.size} hourly rows — " +
                    "centerTime=$centerTime zoom=$zoom offset=$hourlyOffset, skipping bitmap update")
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            val bitmapDims = WidgetSizeCalculator.computeBitmapDimensions(context, dimensions.widthDp, dimensions.heightDp)

            // Render precipitation graph
            val isNarrow = zoom == com.weatherwidget.widget.ZoomLevel.NARROW
            val hourLabelSpacingDp = if (isNarrow) 18f else 28f
            val rainAmountWindowHours = hours.size
            // WIDE: split rain into wettest day (8a-8p) + wettest night (8p-8a) regions with a divider.
            // NARROW: per-hour Pred/Act for the first few hours where rain exists.
            val rainLabelMode = if (isNarrow) {
                PrecipitationGraphRenderer.RainLabelMode.PER_HOUR
            } else {
                PrecipitationGraphRenderer.RainLabelMode.DAY_NIGHT
            }
            val renderStartMs = SystemClock.elapsedRealtime()
            val renderLogs = mutableListOf<String>()
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
                showErrorWatermark = stateManager.isSourceErrored(displaySource),
                errorSourceLabel = displaySource.displayName,
                errorCode = stateManager.getSourceLastErrorCode(displaySource),
                errorFailureTimeMs = stateManager.getSourceLastFailureTime(displaySource),
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
        } else {
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

        appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=PRECIPITATION state=data thread=${Thread.currentThread().name}")
        appWidgetManager.updateAppWidget(appWidgetId, views)

        // Persist render metadata for the GraphRepaintGate on future uiOnly cycles.
        stateManager.setLastGraphRender(
            appWidgetId,
            com.weatherwidget.widget.WidgetStateManager.LastGraphRenderState(
                renderMs = SystemClock.elapsedRealtime(),
                displayedTemp = null,
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
        zoom: com.weatherwidget.widget.ZoomLevel = com.weatherwidget.widget.ZoomLevel.WIDE,
        actualPrecipByHour: Map<LocalDateTime, Float> = emptyMap(),
        now: LocalDateTime = LocalDateTime.now(),
    ): List<PrecipitationGraphRenderer.PrecipHourData> {
        val hours = mutableListOf<PrecipitationGraphRenderer.PrecipHourData>()
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        // Group by dateTime and prefer the selected source
        val forecastsByTime =
            hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    val preferred = entry.value.find { it.source == displaySource.id }
                    val gap = entry.value.find { it.source == WeatherSource.GENERIC_GAP.id }
                    preferred ?: gap ?: entry.value.firstOrNull()
                }

        val (startHour, endHour) = computePrecipGraphWindow(centerTime, zoom)

        // Narrow widgets widen the WIDE-zoom marker cadence (6h vs 4h) to fit the inline footer
        // groups; wide widgets keep the default. Matches the temperature graph.
        val labelInterval =
            if (zoom == com.weatherwidget.widget.ZoomLevel.WIDE &&
                com.weatherwidget.widget.GraphRenderUtils.isNarrowWidget(numColumns)
            ) {
                com.weatherwidget.shared.graph.HourlyGraphDefaults.NARROW_WIDE_LABEL_INTERVAL
            } else {
                zoom.labelInterval
            }
        var currentHour = startHour
        var hourIndex = 0
        val zoneId = ZoneId.systemDefault()

        while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
            val hourMs = currentHour.atZone(zoneId).toInstant().toEpochMilli()
            val forecast = forecastsByTime[hourMs]

            if (forecast != null) {
                val p = HourlyGraphViewCommon.resolveHourPresentation(
                    currentHour, forecast, now, lat, lon, labelInterval, hourIndex,
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
        zoom: com.weatherwidget.widget.ZoomLevel,
    ): Pair<LocalDateTime, LocalDateTime> {
        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        return alignedCenter.minusHours(zoom.backHours) to alignedCenter.plusHours(zoom.forwardHours)
    }

    private suspend fun loadActualPrecipByHourForGraph(
        context: Context,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        zoom: com.weatherwidget.widget.ZoomLevel,
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
            .getObservationsInRange(queryStartMs, queryEndMs, lat, lon)
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
        observations
            .asSequence()
            .filter { matchesActualPrecipSource(it, displaySource) }
            .mapNotNull { obs ->
                val amount = obs.precipAmountMm ?: return@mapNotNull null
                val hour = Instant.ofEpochMilli(obs.timestamp)
                    .atZone(zoneId)
                    .toLocalDateTime()
                    .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                hour to amount
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.sum() }

    private fun matchesActualPrecipSource(
        observation: ObservationEntity,
        displaySource: WeatherSource,
    ): Boolean = com.weatherwidget.util.ActualPrecipSource.matches(observation, displaySource)

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
