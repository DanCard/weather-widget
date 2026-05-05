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
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.PrecipitationGraphRenderer
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetPerfLogger
import com.weatherwidget.widget.WidgetStateManager
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

    // Intent actions
    private const val ACTION_NAV_LEFT = "com.weatherwidget.ACTION_NAV_LEFT"
    private const val ACTION_NAV_RIGHT = "com.weatherwidget.ACTION_NAV_RIGHT"
    private const val ACTION_TOGGLE_API = "com.weatherwidget.ACTION_TOGGLE_API"
    private const val ACTION_TOGGLE_PRECIP = "com.weatherwidget.ACTION_TOGGLE_PRECIP"
    private const val ACTION_SET_VIEW = "com.weatherwidget.ACTION_SET_VIEW"
    private const val ACTION_CYCLE_ZOOM = "com.weatherwidget.ACTION_CYCLE_ZOOM"
    private const val EXTRA_TARGET_VIEW = "com.weatherwidget.EXTRA_TARGET_VIEW"

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
    ) {
        val handlerStartMs = SystemClock.elapsedRealtime()
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val numRows = dimensions.rows
        val isIconWidth = dimensions.isIconWidth

        val stateManager = WidgetStateManager(context)
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()

        Log.d(TAG, "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, hourlyCount=${hourlyForecasts.size}")

        views.setViewVisibility(R.id.header_date_center, View.GONE)
        views.setViewVisibility(R.id.header_date_right, View.GONE)

        // Hide graph day zones (not used in precipitation mode)
        views.setViewVisibility(R.id.graph_day_zones, View.GONE)
        views.setViewVisibility(R.id.graph_night_rain_zones, View.GONE)

        // Set up zoom tap zones
        val zoom = stateManager.getZoomLevel(appWidgetId)
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
        setupZoomTapZones(context, views, appWidgetId, zoom, hourlyOffset)

        // Setup navigation buttons
        setupNavigationButtons(context, views, appWidgetId, stateManager)
        setupHomeShortcut(context, views, appWidgetId, setVisibility = true)
        if (!isIconWidth) {
            setupSettingsShortcut(context, views, appWidgetId)
        }

        // In precipitation mode: current temp → hourly graph, precip % → daily forecast
        HeaderTapTargetHelper.bindSetTemperatureHeader(context, views, appWidgetId)
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
        views.setTextViewText(R.id.api_source, sourceIndicator)

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
        views.setImageViewResource(R.id.weather_icon, iconRes)
        views.setViewVisibility(R.id.weather_icon, View.VISIBLE)

        // Weather icon + bottom graph zone → cloud cover view
        val goCloudIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WidgetIntentRouter.ACTION_SET_VIEW
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(WidgetIntentRouter.EXTRA_TARGET_VIEW, com.weatherwidget.widget.ViewMode.CLOUD_COVER.name)
        }
        val goCloudPending = PendingIntent.getBroadcast(
            context, WidgetRequestCodes.iconViewToggle(appWidgetId), goCloudIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.weather_icon, goCloudPending)

        // Setup API toggle (skipped at 1 icon wide — target is hidden)
        if (!isIconWidth) {
            setupApiToggle(context, views, appWidgetId, numRows)
        }

        // Setup History shortcut
        setupHistoryShortcut(context, views, appWidgetId, centerTime, hourlyForecasts, displaySource, setVisibility = true)

        // Hide observations and temp delta in precip mode
        views.setViewVisibility(R.id.weather_stations_icon, View.GONE)
        views.setViewVisibility(R.id.weather_stations_touch_zone, View.GONE)
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
        HeaderRemoteViewsBinder.bindCurrentTemp(context, views, formattedTemp)

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
        HeaderRemoteViewsBinder.bindPrecipProbability(
            context, views,
            if (isPrecipVisible) "$headerPrecipProbability%" else null,
            precipTextSizeDp ?: 0f,
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

// Use graph mode for 2+ rows, text mode for 1 row
        val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
        val useGraph = rawRows >= 1.4f
        var buildHoursMs = 0L
        var renderMs = 0L
        var graphHoursCount = 0

        if (useGraph) {
            views.setViewVisibility(R.id.text_container, View.GONE)
            views.setViewVisibility(R.id.graph_view, View.VISIBLE)

            // Build precipitation hour data list
            val buildHoursStartMs = SystemClock.elapsedRealtime()
            val hours = buildPrecipHourDataList(hourlyForecasts, centerTime, numColumns, displaySource, zoom)
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
            val hourLabelSpacingDp = if (zoom == com.weatherwidget.widget.ZoomLevel.NARROW) 18f else 28f
            val rainAmountWindowHours = hours.size
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
                job = coroutineContext[Job],
                onDebugLog = { renderLogs.add(it) }
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
    ): List<PrecipitationGraphRenderer.PrecipHourData> {
        val hours = mutableListOf<PrecipitationGraphRenderer.PrecipHourData>()
        val now = LocalDateTime.now()
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

        // Time window based on zoom level
        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        val startHour = alignedCenter.minusHours(zoom.backHours)
        val endHour = alignedCenter.plusHours(zoom.forwardHours)

        val labelInterval = zoom.labelInterval
        var currentHour = startHour
        var hourIndex = 0
        val zoneId = ZoneId.systemDefault()

        while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
            val hourMs = currentHour.atZone(zoneId).toInstant().toEpochMilli()
            val forecast = forecastsByTime[hourMs]

            if (forecast != null) {
                val diffMinutes = java.time.Duration.between(currentHour, now).toMinutes()
                val absDiff = kotlin.math.abs(diffMinutes)
                val isClosest = absDiff <= 30
                val showLabel = isClosest || (hourIndex % labelInterval == 0)
                val sunInfo = SunPositionUtils.getSunInfo(currentHour, lat, lon)
                val isNight = sunInfo.isNight
                val isTwilight = sunInfo.phase == SunPhase.TWILIGHT
                val isSunBoundary = sunInfo.isSunBoundary
                val iconRes = WeatherIconMapper.getIconResource(
                    condition = forecast.condition,
                    isNight = isNight,
                    cloudCover = forecast.cloudCover,
                    precipProbability = forecast.precipProbability,
                    isTwilight = isTwilight,
                    isSunBoundary = isSunBoundary,
                )
                val isSunny =
                    WeatherIconMapper.isSunny(iconRes)
                val isRainy =
                    WeatherIconMapper.isPrecipitation(iconRes)
                val isMixed =
                    WeatherIconMapper.isMixed(iconRes)

                hours.add(
                    PrecipitationGraphRenderer.PrecipHourData(
                        dateTime = currentHour,
                        precipProbability = forecast.precipProbability ?: 0,
                        label = formatHourLabel(currentHour),
                        iconRes = iconRes,
                        isNight = isNight,
                        isTwilight = isTwilight,
                        isSunBoundary = isSunBoundary,
                        isSunny = isSunny,
                        isRainy = isRainy,
                        isMixed = isMixed,
                        isCurrentHour = isClosest,
                        showLabel = showLabel,
                        precipAmountMm = forecast.precipAmountMm,
                    ),
                )
                hourIndex++
            }

            currentHour = currentHour.plusHours(1)
        }

        return hours
    }



    private fun updatePrecipTextMode(
        views: RemoteViews,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
    ) {
        val forecastsByTime =
            hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    entry.value.find { it.source == displaySource.id }
                        ?: entry.value.find { it.source == WeatherSource.GENERIC_GAP.id }
                        ?: entry.value.firstOrNull()
                }

        val timeOffsets =
            when {
                numColumns >= 6 -> listOf(0, 3, 6, 9, 12, 15)
                numColumns == 5 -> listOf(0, 3, 6, 9, 12)
                numColumns == 4 -> listOf(0, 3, 6, 9)
                numColumns == 3 -> listOf(0, 3, 6)
                numColumns == 2 -> listOf(0, 6)
                else -> listOf(0)
            }

        val containerIds =
            listOf(
                R.id.day1_container to Quad(R.id.day1_label, R.id.day1_icon, R.id.day1_high, R.id.day1_low),
                R.id.day2_container to Quad(R.id.day2_label, R.id.day2_icon, R.id.day2_high, R.id.day2_low),
                R.id.day3_container to Quad(R.id.day3_label, R.id.day3_icon, R.id.day3_high, R.id.day3_low),
                R.id.day4_container to Quad(R.id.day4_label, R.id.day4_icon, R.id.day4_high, R.id.day4_low),
                R.id.day5_container to Quad(R.id.day5_label, R.id.day5_icon, R.id.day5_high, R.id.day5_low),
                R.id.day6_container to Quad(R.id.day6_label, R.id.day6_icon, R.id.day6_high, R.id.day6_low),
            )

        val zoneId = ZoneId.systemDefault()
        containerIds.forEachIndexed { index, (containerId, ids) ->
            if (index < timeOffsets.size) {
                val offset = timeOffsets[index]
                val targetTime = centerTime.plusHours(offset.toLong())
                val hourMs = targetTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                    .atZone(zoneId).toInstant().toEpochMilli()
                val forecast = forecastsByTime[hourMs]

                views.setViewVisibility(containerId, View.VISIBLE)

                val label = if (offset == 0) "Now" else "+${offset}h"
                views.setTextViewText(ids.first, label)
                views.setViewVisibility(ids.second, View.GONE)

                if (forecast != null) {
                    val precip = forecast.precipProbability ?: 0
                    views.setTextViewText(ids.third, "$precip%")
                    views.setTextViewText(ids.fourth, "")
                } else {
                    views.setTextViewText(ids.third, "--%")
                    views.setTextViewText(ids.fourth, "")
                }
            } else {
                views.setViewVisibility(containerId, View.GONE)
            }
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
