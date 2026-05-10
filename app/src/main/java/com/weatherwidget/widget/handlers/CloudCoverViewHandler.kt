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
            while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
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
    ) {
        val handlerStartMs = SystemClock.elapsedRealtime()
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val numRows = dimensions.rows
        val isIconWidth = dimensions.isIconWidth

        val stateManager = WidgetStateManager(context)
        val appLogDao = WeatherDatabase.getDatabase(context).appLogDao()

        val sourceRows = hourlyForecasts.count { it.source == displaySource.id }
        val sourceRowsWithCloudCover = hourlyForecasts.count { it.source == displaySource.id && it.cloudCover != null }
        Log.d(
            TAG,
            "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, hourlyCount=${hourlyForecasts.size}, " +
                "source=$displaySource sourceRows=$sourceRows sourceRowsWithCloudCover=$sourceRowsWithCloudCover",
        )

        views.setViewVisibility(R.id.header_date_center, View.GONE)
        views.setViewVisibility(R.id.header_date_right, View.GONE)

        views.setViewVisibility(R.id.graph_day_zones, View.GONE)
        views.setViewVisibility(R.id.graph_night_rain_zones, View.GONE)

        val zoom = stateManager.getZoomLevel(appWidgetId)
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
        val windowHourKeys = buildWindowHourKeys(centerTime, zoom)
        val effectiveDisplaySource = displaySource
        setupZoomTapZones(context, views, appWidgetId, zoom, hourlyOffset)

        setupNavigationButtons(context, views, appWidgetId, stateManager)
        setupHomeShortcut(context, views, appWidgetId, setVisibility = true)
        if (!isIconWidth) {
            setupSettingsShortcut(context, views, appWidgetId)
        }

        // Current temp → hourly temp graph
        HeaderTapTargetHelper.bindSetTemperatureHeader(context, views, appWidgetId)
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
        views.setTextViewText(R.id.api_source, sourceIndicator)

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
        views.setImageViewResource(R.id.weather_icon, iconRes)
        views.setViewVisibility(R.id.weather_icon, View.VISIBLE)

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

        if (!isIconWidth) {
            setupApiToggle(context, views, appWidgetId, numRows)
        }
        setupHistoryShortcut(context, views, appWidgetId, centerTime, hourlyForecasts, displaySource, setVisibility = true)

        views.setViewVisibility(R.id.weather_stations_icon, View.GONE)
        views.setViewVisibility(R.id.weather_stations_touch_zone, View.GONE)
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
        HeaderRemoteViewsBinder.bindCurrentTemp(context, views, formattedTemp)

        val headerPrecipProbability = HeaderPrecipCalculator.getNext8HourPrecipProbability(
            hourlyForecasts = hourlyForecasts,
            displaySource = effectiveDisplaySource,
            fallbackDailyProbability = precipProbability,
            referenceTime = centerTime,
        )
        val isPrecipVisible = HeaderTapTargetHelper.shouldShowPrecipTouchZone(headerPrecipProbability)
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

val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
        val useGraph = rawRows >= 1.4f
        var buildHoursMs = 0L
        var renderMs = 0L

        if (useGraph) {
            views.setViewVisibility(R.id.text_container, View.GONE)
            views.setViewVisibility(R.id.graph_view, View.VISIBLE)

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
                val preferred = entry.value.find { it.source == displaySource.id }
                val gap = entry.value.find { it.source == WeatherSource.GENERIC_GAP.id }
                preferred ?: gap ?: entry.value.firstOrNull()
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

        val labelInterval = zoom.labelInterval
        var currentHour = startHour
        var hourIndex = 0
        val zoneId = ZoneId.systemDefault()

        while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
            val hourMs = currentHour.atZone(zoneId).toInstant().toEpochMilli()
            val forecast = forecastsByTime[hourMs]

            if (forecast?.cloudCover != null) {
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
                val isSunny = WeatherIconMapper.isSunny(iconRes)
                val isRainy = WeatherIconMapper.isPrecipitation(iconRes)
                val isMixed = WeatherIconMapper.isMixed(iconRes)

                hours.add(
                    CloudCoverGraphRenderer.CloudHourData(
                        dateTime = currentHour,
                        cloudCover = forecast.cloudCover,
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
        val forecastsByTime = hourlyForecasts.groupBy { it.dateTime }
            .mapValues { entry ->
                entry.value.find { it.source == displaySource.id }
                    ?: entry.value.find { it.source == WeatherSource.GENERIC_GAP.id }
                    ?: entry.value.firstOrNull()
            }

        val timeOffsets = when {
            numColumns >= 6 -> listOf(0, 3, 6, 9, 12, 15)
            numColumns == 5 -> listOf(0, 3, 6, 9, 12)
            numColumns == 4 -> listOf(0, 3, 6, 9)
            numColumns == 3 -> listOf(0, 3, 6)
            numColumns == 2 -> listOf(0, 6)
            else -> listOf(0)
        }

        val containerIds = listOf(
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

                if (forecast?.cloudCover != null) {
                    val cloud = forecast.cloudCover
                    views.setTextViewText(ids.third, "$cloud%")
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
