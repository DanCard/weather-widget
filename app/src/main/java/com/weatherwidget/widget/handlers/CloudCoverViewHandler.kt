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
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.HeaderFormatter
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.SunPhase
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.CloudCoverGraphRenderer
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.GraphRepaintGate
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Handler for the cloud cover view mode.
 */
object CloudCoverViewHandler {
    private const val TAG = "CloudCoverViewHandler"
    private const val CELL_HEIGHT_DP = 90



    @androidx.annotation.VisibleForTesting
    internal fun smoothingIterationsFor(zoom: com.weatherwidget.widget.ZoomLevel): Int =
        when (zoom) {
            com.weatherwidget.widget.ZoomLevel.WIDE -> zoom.smoothIterations
            com.weatherwidget.widget.ZoomLevel.THREE_DAY -> zoom.smoothIterations
            com.weatherwidget.widget.ZoomLevel.NARROW -> (zoom.smoothIterations - 1).coerceAtLeast(0)
        }

    /**
     * Look up the most likely upstream reason for missing cloud cover data by checking
     * recent app_logs entries written by NwsForecastMapper. Returns a short human phrase
     * suitable for display in the graph diagnostic, or null if no recent matching event.
     */
    private suspend fun resolveMissingDataReason(
        appLogDao: com.weatherwidget.data.local.AppLogDao,
        lookbackMs: Long = 4 * 60 * 60 * 1000L,
    ): String? {
        val cutoff = System.currentTimeMillis() - lookbackMs
        val gridFail = appLogDao.getLogsByTag("NWS_GRIDPOINTS_FAIL", limit = 1).firstOrNull()
        if (gridFail != null && gridFail.timestamp >= cutoff) return "NWS gridpoints fetch failed"
        val skyEmpty = appLogDao.getLogsByTag("NWS_SKYCOVER_EMPTY", limit = 1).firstOrNull()
        if (skyEmpty != null && skyEmpty.timestamp >= cutoff) return "NWS sky cover unavailable"
        return null
    }

    /**
     * Build the set of epoch-ms keys for every hour in the visible cloud cover window
     * around [centerTime] given the current [zoom]. Used to count how many hours in the
     * window have cloud cover data, so the renderer can flag missing data honestly
     * without silently switching weather sources.
     */
    @androidx.annotation.VisibleForTesting
    internal fun buildWindowHourKeys(
        centerTime: LocalDateTime,
        zoom: com.weatherwidget.widget.ZoomLevel,
    ): Set<Long> {
        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        val startHour = alignedCenter.minusHours(zoom.backHours)
        val endHour = alignedCenter.plusHours(zoom.forwardHours)
        val zoneId = ZoneId.systemDefault()
        return buildSet {
            var currentHour = startHour
            while (currentHour.isBefore(endHour)) {
                add(currentHour.atZone(zoneId).toInstant().toEpochMilli())
                currentHour = currentHour.plusHours(1)
            }
        }
    }

    suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        displaySource: WeatherSource,
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
                    "widget=$appWidgetId caller=CLOUD_COVER state=skipped reason=${gateDecision.reason} thread=${Thread.currentThread().name}",
                )
                return
            }
        }

        val sourceRows = hourlyForecasts.count { it.source == displaySource.id }
        val sourceRowsWithCloudCover = hourlyForecasts.count { it.source == displaySource.id && it.cloudCover != null }
        Log.d(
            TAG,
            "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, hourlyCount=${hourlyForecasts.size}, " +
                "source=$displaySource sourceRows=$sourceRows sourceRowsWithCloudCover=$sourceRowsWithCloudCover",
        )

        views.setViewVisibility(R.id.header_date_center, View.GONE)
        views.setViewVisibility(R.id.header_date_right, View.GONE)
        // Reset sticky visibility from DailyViewHandler
        DailyViewHandler.bindTransientMessage(views, stateManager, appWidgetId, callerTag = "CLOUD_COVER")

        views.setViewVisibility(R.id.graph_day_zones, View.GONE)
        views.setViewVisibility(R.id.graph_night_rain_zones, View.GONE)

        val zoom = stateManager.getZoomLevel(appWidgetId)
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
        val windowHourKeys = buildWindowHourKeys(centerTime, zoom)
        val effectiveDisplaySource = displaySource
        setupZoomTapZones(context, views, appWidgetId, zoom, hourlyOffset)

        setupNavigationButtons(context, views, appWidgetId, stateManager)

        // Temperature header taps toggle back to DAILY view
        HeaderTapTargetHelper.bindToggleTemperatureHeader(context, views, appWidgetId)
        HeaderTapTargetHelper.bindPrecipitationHeader(context, views, appWidgetId)

        if (
            ApiSourceWarningHelper.checkAndRenderBlockingWarning(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                numRows = numRows,
                appLogDao = appLogDao,
                displaySource = displaySource,
                hasSelectedSourceData = hourlyForecasts.any { it.source == displaySource.id },
                callerTag = "CLOUD",
            )
        ) {
            appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=CLOUD_COVER state=warning thread=${Thread.currentThread().name}")
            appWidgetManager.updateAppWidget(appWidgetId, views)
            return
        }

        val sourceIndicator = HeaderFormatter.formatSourceIndicator(
            centerTime = centerTime,
            now = LocalDateTime.now(),
            sourceName = effectiveDisplaySource.shortDisplayName,
            widthDp = dimensions.widthDp
        )

        val now = LocalDateTime.now()
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val sunInfo = SunPositionUtils.getSunInfo(now, lat, lon)
        val currentHourForecast = WeatherTimeUtils.getCurrentHourForecast(hourlyForecasts, effectiveDisplaySource)
        val iconRes = WeatherIconMapper.getIconResource(
            condition = currentHourForecast?.condition,
            isNight = sunInfo.isNight,
            cloudCover = currentHourForecast?.cloudCover,
            precipProbability = currentHourForecast?.precipProbability,
            isTwilight = sunInfo.phase == SunPhase.TWILIGHT,
            isSunBoundary = sunInfo.isSunBoundary,
        )

        // Weather icon + bottom zone → back to temperature view
        val goTempIconIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WidgetActions.ACTION_SET_VIEW
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(WidgetActions.EXTRA_TARGET_VIEW, com.weatherwidget.widget.ViewMode.TEMPERATURE.name)
        }
        val goTempIconPending = PendingIntent.getBroadcast(
            context, WidgetRequestCodes.iconViewToggle(appWidgetId), goTempIconIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.weather_icon, goTempIconPending)

        views.setViewVisibility(R.id.current_temp_delta, View.GONE)

        val (currentTempResolution, resolveMs) =
            CurrentTempResolutionHelper.resolveAndPersistDelta(
                now = now,
                displaySource = effectiveDisplaySource,
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

        val headerPrecipProbability = HeaderPrecipCalculator.getNext8HourPrecipProbability(
            hourlyForecasts = hourlyForecasts,
            displaySource = effectiveDisplaySource,
            fallbackDailyProbability = precipProbability,
            referenceTime = centerTime,
        )
        val isPrecipVisible = HeaderTapTargetHelper.shouldShowPrecipTouchZone(headerPrecipProbability)
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
            currentViewMode = com.weatherwidget.widget.ViewMode.CLOUD_COVER,
            widthDp = dimensions.widthDp,
            isPrecipVisible = isPrecipVisible && disclosure.showsPrecip(),
            scale = headerScale,
        )

        // Setup API toggle (skipped at 1 icon wide — target is hidden)
        if (!isIconWidth) {
            setupApiToggle(context, views, appWidgetId, numRows, scale = headerScale)
        }

        positionCenterIcons(views, dimensions.widthDp, context.resources.displayMetrics.density, isPrecipVisible && disclosure.showsPrecip(), isToday)

val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
        val useGraph = rawRows >= 1.4f
        var buildHoursMs = 0L
        var renderMs = 0L

        if (useGraph) {
            views.setViewVisibility(R.id.text_container, View.GONE)
            views.setViewVisibility(R.id.graph_view, View.VISIBLE)
            views.setViewVisibility(R.id.graph_day_zones, View.GONE)
            views.setViewVisibility(R.id.graph_interaction_container, View.VISIBLE)

            val buildHoursStartMs = SystemClock.elapsedRealtime()
            val hours = buildCloudHourDataList(hourlyForecasts, centerTime, numColumns, effectiveDisplaySource, zoom)
            buildHoursMs = SystemClock.elapsedRealtime() - buildHoursStartMs

            val totalWindowHours = windowHourKeys.size
            val missingHours = (totalWindowHours - hours.size).coerceAtLeast(0)

            val zoneId = ZoneId.systemDefault()
            val presentHourMs = hours.asSequence()
                .map { it.dateTime.atZone(zoneId).toInstant().toEpochMilli() }
                .toSet()
            val missingHourTimes = (windowHourKeys - presentHourMs).asSequence()
                .map { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDateTime() }
                .sorted()
                .toList()
            val missingDescription = formatMissingHourRanges(missingHourTimes).takeIf { it.isNotEmpty() }
            val missingReason = if (missingHours > 0) resolveMissingDataReason(appLogDao) else null

            if (hours.isEmpty() && hourlyForecasts.isNotEmpty()) {
                Log.w(TAG, "buildCloudHourDataList returned empty despite ${hourlyForecasts.size} hourly rows — " +
                    "centerTime=$centerTime zoom=$zoom source=$effectiveDisplaySource, rendering diagnostic")
            }
            if (missingHours > 0) {
                appLogDao.log(
                    "CLOUD_COVER_GAPS",
                    "widget=$appWidgetId source=${effectiveDisplaySource.id} " +
                        "missing=$missingHours total=$totalWindowHours " +
                        "ranges=${missingDescription ?: "-"} reason=${missingReason ?: "-"}",
                )
                val cooldownMs = 15 * 60 * 1000L
                if (stateManager.shouldRefreshMissingData(appWidgetId, effectiveDisplaySource.id, "hourly_gaps", cooldownMs)) {
                    stateManager.markMissingDataRefreshRequested(appWidgetId, effectiveDisplaySource.id, "hourly_gaps")
                    appLogDao.log(
                        "CLOUD_COVER_GAPS_REFRESH",
                        "widget=$appWidgetId source=${effectiveDisplaySource.id} missing=$missingHours, requesting immediate API update",
                        "INFO"
                    )
                    WeatherWidgetProvider.triggerImmediateUpdate(
                        context = context,
                        forceRefresh = true,
                        reason = "hourly_gaps"
                    )
                }
            }

            val bitmapDims = WidgetSizeCalculator.computeBitmapDimensions(context, dimensions.widthDp, dimensions.heightDp)

            val hourLabelSpacingDp = if (zoom == com.weatherwidget.widget.ZoomLevel.NARROW) 18f else 28f
            val renderStartMs = SystemClock.elapsedRealtime()
            val bitmap = CloudCoverGraphRenderer.renderGraph(
                context = context,
                hours = hours,
                widthPx = bitmapDims.widthPx,
                heightPx = bitmapDims.heightPx,
                currentTime = now,
                bitmapScale = bitmapDims.bitmapScale,
                smoothIterations = zoom.smoothIterations,
                hourLabelSpacingDp = hourLabelSpacingDp,
                missingHours = missingHours,
                totalHours = totalWindowHours,
                numColumns = numColumns,
                missingDescription = missingDescription,
                missingReason = missingReason,
                job = coroutineContext[Job],
                showErrorWatermark = stateManager.isSourceErrored(effectiveDisplaySource),
                errorSourceLabel = effectiveDisplaySource.displayName,
                errorCode = stateManager.getSourceLastErrorCode(effectiveDisplaySource),
                errorFailureTimeMs = stateManager.getSourceLastFailureTime(effectiveDisplaySource),
            )
            renderMs = SystemClock.elapsedRealtime() - renderStartMs

            views.setImageViewBitmap(R.id.graph_view, bitmap)

            // Cloud-cover body taps always zoom; bottom zones still route by icon.
            val hourIcons = hours.map { it.iconRes }
            setupZoomTapZones(context, views, appWidgetId, zoom, hourlyOffset)
            HourlyBottomZoneHelper.setup(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                hourIconResources = hourIcons,
                currentViewMode = com.weatherwidget.widget.ViewMode.CLOUD_COVER,
                zoom = zoom,
                hourlyOffset = hourlyOffset,
            )
        } else {
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)
            views.setViewVisibility(R.id.graph_hour_zones, View.GONE)
            views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_hour_zones, View.GONE)
            views.setViewVisibility(R.id.graph_bottom_reserved_space, View.VISIBLE)
            updateCloudTextMode(views, hourlyForecasts, centerTime, numColumns, effectiveDisplaySource)
        }

        if (isIconWidth) {
            HeaderRemoteViewsBinder.hideIconWidthControls(views)
        }

        appLogDao.log(WidgetPerfLogger.TAG_WIDGET_PAINT, "widget=$appWidgetId caller=CLOUD_COVER state=data thread=${Thread.currentThread().name}")
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
                "view" to "CLOUD_COVER",
                "useGraph" to useGraph,
                "resolveMs" to resolveMs,
                "buildHoursMs" to buildHoursMs,
                "renderMs" to renderMs,
                "hourlyCount" to hourlyForecasts.size,
                "source" to effectiveDisplaySource.id,
                "totalMs" to totalMs,
            ),
            debugTag = TAG,
        )
    }

    @androidx.annotation.VisibleForTesting
    internal fun buildCloudHourDataList(
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
        zoom: com.weatherwidget.widget.ZoomLevel = com.weatherwidget.widget.ZoomLevel.WIDE,
    ): List<CloudCoverGraphRenderer.CloudHourData> {
        val hours = mutableListOf<CloudCoverGraphRenderer.CloudHourData>()
        val now = LocalDateTime.now()
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val forecastsByTime = hourlyForecasts.groupBy { it.dateTime }
            .mapValues { entry ->
                entry.value.find { it.source == displaySource.id }
            }

        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        val startHour = alignedCenter.minusHours(zoom.backHours)
        val endHour = alignedCenter.plusHours(zoom.forwardHours)
        Log.d(
            TAG,
            "buildCloudHourDataList: centerTime=$centerTime alignedCenter=$alignedCenter " +
                "startHour=$startHour endHour=$endHour zoom=$zoom source=$displaySource",
        )

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

        // At THREE_DAY zoom switch the footer to one date label per day ("Tue 23"), matching the
        // temperature graph (shared rule in HourlyGraphViewCommon.resolveHourLabel).
        val dateMode = zoom == com.weatherwidget.widget.ZoomLevel.THREE_DAY
        val dateLabelMillis = if (dateMode) dateLabelMillis(startHour, endHour, zoneId) else emptySet()

        while (currentHour.isBefore(endHour)) {
            val hourMs = currentHour.atZone(zoneId).toInstant().toEpochMilli()
            val forecast = forecastsByTime[hourMs]

            if (forecast?.cloudCover != null) {
                val p = HourlyGraphViewCommon.resolveHourPresentation(
                    currentHour, forecast, now, lat, lon, labelInterval, hourIndex,
                    hourMs = hourMs, dateMode = dateMode, dateLabelMillis = dateLabelMillis,
                )
                hours.add(
                    CloudCoverGraphRenderer.CloudHourData(
                        dateTime = currentHour,
                        cloudCover = forecast.cloudCover,
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
                    ),
                )
                hourIndex++
            }

            currentHour = currentHour.plusHours(1)
        }

        return hours
    }



    private fun updateCloudTextMode(
        views: RemoteViews,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
    ) {
        HourlyGraphViewCommon.bindHourlyTextMode(
            views, hourlyForecasts, centerTime, numColumns, displaySource,
        ) { forecast -> forecast?.cloudCover?.let { "$it%" } ?: "--%" }
    }
}
