package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.SettingsActivity
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.PrecipitationGraphRenderer
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

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
    private const val ACTION_SET_VIEW = "com.weatherwidget.ACTION_SET_VIEW"
    private const val ACTION_CYCLE_ZOOM = "com.weatherwidget.ACTION_CYCLE_ZOOM"
    private const val ACTION_SHOW_OBSERVATIONS = "com.weatherwidget.ACTION_SHOW_OBSERVATIONS"
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
        displaySource: WeatherSource,
        precipProbability: Int? = null,
        observedCurrentTemp: Float? = null,
        observedCurrentTempFetchedAt: Long? = null,
        repository: com.weatherwidget.data.repository.WeatherRepository? = null,
    ) {

        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val numRows = dimensions.rows

        val stateManager = WidgetStateManager(context)

        Log.d(TAG, "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, hourlyCount=${hourlyForecasts.size}")

        // Hide graph day zones (not used in precipitation mode)
        views.setViewVisibility(R.id.graph_day_zones, View.GONE)

        // Set up zoom tap zones
        val zoom = stateManager.getZoomLevel(appWidgetId)
        val hourlyOffset = stateManager.getHourlyOffset(appWidgetId)
        setupZoomTapZones(context, views, appWidgetId, zoom, hourlyOffset)

        // Setup navigation buttons
        setupNavigationButtons(context, views, appWidgetId, stateManager)
        setupSettingsShortcut(context, views, appWidgetId)

        // In precipitation mode: current temp → hourly graph, precip % → daily forecast
        val goTempIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_SET_VIEW
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_TARGET_VIEW, com.weatherwidget.widget.ViewMode.TEMPERATURE.name)
            }
        val goTempPending =
            PendingIntent.getBroadcast(
                context,
                appWidgetId * 2 + 200,
                goTempIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.current_temp, goTempPending)
        views.setOnClickPendingIntent(R.id.current_temp_zone, goTempPending)

        val goDailyIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_SET_VIEW
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_TARGET_VIEW, com.weatherwidget.widget.ViewMode.DAILY.name)
            }
        val goDailyPending =
            PendingIntent.getBroadcast(
                context,
                appWidgetId * 2 + 300,
                goDailyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.precip_probability, goDailyPending)
        views.setOnClickPendingIntent(R.id.precip_touch_zone, goDailyPending)

        // Get current display source
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val dayName = centerTime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val sourceIndicator = if (centerTime.toLocalDate() == LocalDateTime.now().toLocalDate()) {
            displaySource.shortDisplayName
        } else {
            "$dayName • ${displaySource.shortDisplayName}"
        }
        views.setTextViewText(R.id.api_source, sourceIndicator)

        // Set weather icon
        val now = LocalDateTime.now()
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val isNight = SunPositionUtils.isNight(now, lat, lon)
        val currentHourCondition = getCurrentHourCondition(hourlyForecasts, displaySource)
        val iconRes = WeatherIconMapper.getIconResource(currentHourCondition, isNight)
        views.setImageViewResource(R.id.weather_icon, iconRes)
        views.setViewVisibility(R.id.weather_icon, View.VISIBLE)

        // Setup API toggle
        setupApiToggle(context, views, appWidgetId, numRows)

        // Setup History shortcut
        setupHistoryShortcut(context, views, appWidgetId, centerTime, hourlyForecasts, displaySource)

        // Hide observations and temp delta in precip mode
        views.setViewVisibility(R.id.current_stations_icon, View.GONE)
        views.setViewVisibility(R.id.current_stations_touch_zone, View.GONE)
        views.setViewVisibility(R.id.current_temp_delta, View.GONE)

        val currentTempResolution =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = displaySource,
                hourlyForecasts = hourlyForecasts,
                observedCurrentTemp = observedCurrentTemp,
                observedCurrentTempFetchedAt = observedCurrentTempFetchedAt,
                storedDeltaState = stateManager.getCurrentTempDeltaState(appWidgetId),
                currentLat = lat,
                currentLon = lon,
            )
        if (currentTempResolution.shouldClearStoredDelta) {
            stateManager.clearCurrentTempDeltaState(appWidgetId)
        }
        currentTempResolution.updatedDeltaState?.let { stateManager.setCurrentTempDeltaState(appWidgetId, it) }
        val currentTemp = currentTempResolution.displayTemp
        if (currentTemp != null) {
            val formattedTemp =
                CurrentTemperatureResolver.formatDisplayTemperature(
                    temp = currentTemp,
                    numColumns = numColumns,
                    isStaleEstimate = currentTempResolution.isStaleEstimate,
                )
            views.setTextViewText(R.id.current_temp, formattedTemp)
            views.setViewVisibility(R.id.current_temp, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.current_temp, View.GONE)
        }

        val headerPrecipProbability =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
                fallbackDailyProbability = precipProbability,
                referenceTime = centerTime,
            )

        // Show precipitation probability next to current temp.
        // In precipitation mode, show even if 0% so the user gets confirmation.
        if (headerPrecipProbability != null) {
            views.setTextViewText(R.id.precip_probability, "$headerPrecipProbability%")
            val textSizeSp = HeaderPrecipCalculator.getPrecipTextSize(headerPrecipProbability)
            views.setTextViewTextSize(R.id.precip_probability, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            views.setViewVisibility(R.id.precip_probability, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.precip_probability, View.GONE)
        }

        // Use graph mode for 2+ rows, text mode for 1 row
        val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
        val useGraph = rawRows >= 1.4f

        if (useGraph) {
            views.setViewVisibility(R.id.text_container, View.GONE)
            views.setViewVisibility(R.id.graph_view, View.VISIBLE)

            // Build precipitation hour data list
            val hours = buildPrecipHourDataList(hourlyForecasts, centerTime, numColumns, displaySource, zoom)

            // Use actual widget dimensions for bitmap
            // Account for 8dp root padding + 4dp graph margins on each side = 24dp total
            val widthDp = dimensions.widthDp - 24
            val heightDp = dimensions.heightDp - 16
            val (widthPx, heightPx) = WidgetSizeCalculator.getOptimalBitmapSize(context, widthDp, heightDp)
            val rawWidthPx = WidgetSizeCalculator.dpToPx(context, widthDp).coerceAtLeast(1)
            val rawHeightPx = WidgetSizeCalculator.dpToPx(context, heightDp).coerceAtLeast(1)
            val bitmapScale =
                min(
                    widthPx.toFloat() / rawWidthPx.toFloat(),
                    heightPx.toFloat() / rawHeightPx.toFloat(),
                )

            // Render precipitation graph
            val hourLabelSpacingDp = if (zoom == com.weatherwidget.widget.ZoomLevel.NARROW) 18f else 28f
            val bitmap = PrecipitationGraphRenderer.renderGraph(
                context = context,
                hours = hours,
                widthPx = widthPx,
                heightPx = heightPx,
                currentTime = now,
                bitmapScale = bitmapScale,
                smoothIterations = zoom.precipSmoothIterations,
                hourLabelSpacingDp = hourLabelSpacingDp,
                observedTempFetchedAt = observedCurrentTempFetchedAt
            )
            views.setImageViewBitmap(R.id.graph_view, bitmap)
        } else {
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)

            // Text mode: show precip percentages
            updatePrecipTextMode(views, hourlyForecasts, centerTime, numColumns, displaySource)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    /**
     * Get the weather condition for the current hour from hourly forecasts.
     */
    private fun getCurrentHourCondition(
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
    ): String? {
        val currentHourKey = WeatherTimeUtils.toHourlyForecastKey(LocalDateTime.now())

        return hourlyForecasts
            .filter { it.dateTime == currentHourKey }
            .let { forecasts ->
                forecasts.find { it.source == displaySource.id }
                    ?: forecasts.find { it.source == WeatherSource.GENERIC_GAP.id }
                    ?: forecasts.firstOrNull()
            }?.condition
    }

    private val HOUR_ZONE_IDS = listOf(
        R.id.graph_hour_zone_0, R.id.graph_hour_zone_1, R.id.graph_hour_zone_2,
        R.id.graph_hour_zone_3, R.id.graph_hour_zone_4, R.id.graph_hour_zone_5,
        R.id.graph_hour_zone_6, R.id.graph_hour_zone_7, R.id.graph_hour_zone_8,
        R.id.graph_hour_zone_9, R.id.graph_hour_zone_10, R.id.graph_hour_zone_11,
    )

    private fun setupZoomTapZones(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        zoom: com.weatherwidget.widget.ZoomLevel,
        hourlyOffset: Int,
    ) {
        if (zoom == com.weatherwidget.widget.ZoomLevel.WIDE) {
            views.setViewVisibility(R.id.graph_hour_zones, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.graph_view, null)

            HOUR_ZONE_IDS.forEachIndexed { i, zoneId ->
                val zoneCenterOffset = WeatherWidgetProvider.zoneIndexToOffset(i, hourlyOffset)
                val zoomIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                    action = ACTION_CYCLE_ZOOM
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    putExtra(WeatherWidgetProvider.EXTRA_ZOOM_CENTER_OFFSET, zoneCenterOffset)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    appWidgetId * 100 + 500 + i,
                    zoomIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(zoneId, pendingIntent)
            }
        } else {
            views.setViewVisibility(R.id.graph_hour_zones, View.GONE)
            val zoomIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_CYCLE_ZOOM
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val zoomPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId * 2 + 400,
                zoomIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.graph_view, zoomPendingIntent)
        }
    }

    private fun setupNavigationButtons(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        stateManager: WidgetStateManager,
    ) {
        val canLeft = stateManager.canNavigateHourlyLeft(appWidgetId)
        val canRight = stateManager.canNavigateHourlyRight(appWidgetId)

        // Always show the left arrow
        views.setViewVisibility(R.id.nav_left, View.VISIBLE)
        views.setViewVisibility(R.id.nav_left_zone, View.VISIBLE)

        if (canLeft) {
            val leftIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_NAV_LEFT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val leftPendingIntent = PendingIntent.getBroadcast(
                context, WidgetRequestCodes.navLeft(appWidgetId), leftIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.nav_left, toastPendingIntent)
            views.setOnClickPendingIntent(R.id.nav_left_zone, toastPendingIntent)
        }

        // Always show the right arrow
        views.setViewVisibility(R.id.nav_right, View.VISIBLE)
        views.setViewVisibility(R.id.nav_right_zone, View.VISIBLE)

        if (canRight) {
            val rightIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_NAV_RIGHT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val rightPendingIntent = PendingIntent.getBroadcast(
                context, WidgetRequestCodes.navRight(appWidgetId), rightIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.nav_right, toastPendingIntent)
            views.setOnClickPendingIntent(R.id.nav_right_zone, toastPendingIntent)
        }
    }

    private fun setupApiToggle(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        numRows: Int,
    ) {
        val toggleIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_API
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
        val togglePendingIntent =
            PendingIntent.getBroadcast(
                context,
                WidgetRequestCodes.apiToggle(appWidgetId),
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.api_source_container, togglePendingIntent)

        val textSizeSp =
            when {
                numRows >= 3 -> 18f
                numRows >= 2 -> 16f
                else -> 14f
            }
        views.setTextViewTextSize(R.id.api_source, android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
    }

    private fun setupHistoryShortcut(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        centerTime: LocalDateTime,
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
    ) {
        val dateStr = centerTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

        val historyIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WeatherWidgetProvider.ACTION_DAY_CLICK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra("date", dateStr)
            putExtra("showHistory", true)
            putExtra("isHistory", centerTime.toLocalDate().isBefore(LocalDate.now()))
            putExtra(com.weatherwidget.ui.ForecastHistoryActivity.EXTRA_LAT, lat)
            putExtra(com.weatherwidget.ui.ForecastHistoryActivity.EXTRA_LON, lon)
            putExtra(com.weatherwidget.ui.ForecastHistoryActivity.EXTRA_SOURCE, displaySource.displayName)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId * 100 + 700,
            historyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.history_icon, pendingIntent)
        views.setOnClickPendingIntent(R.id.history_touch_zone, pendingIntent)
        views.setViewVisibility(R.id.history_icon, View.VISIBLE)
        views.setViewVisibility(R.id.history_touch_zone, View.VISIBLE)
    }

    private fun setupCurrentStationsShortcut(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
    ) {
        val obsIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = ACTION_SHOW_OBSERVATIONS
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId * 100 + 800,
            obsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.current_stations_icon, pendingIntent)
        views.setOnClickPendingIntent(R.id.current_stations_touch_zone, pendingIntent)
        views.setViewVisibility(R.id.current_stations_icon, View.VISIBLE)
        views.setViewVisibility(R.id.current_stations_touch_zone, View.VISIBLE)
    }

    private fun setupSettingsShortcut(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
    ) {
        val settingsIntent = Intent(context, SettingsActivity::class.java)
        val settingsPendingIntent =
            PendingIntent.getActivity(
                context,
                WidgetRequestCodes.settings(appWidgetId),
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.settings_icon, settingsPendingIntent)
        views.setOnClickPendingIntent(R.id.settings_touch_zone, settingsPendingIntent)
    }

    private fun buildPrecipHourDataList(
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

        while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
            val hourKey = currentHour.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val forecast = forecastsByTime[hourKey]

            if (forecast != null) {
                val diffMinutes = java.time.Duration.between(currentHour, now).toMinutes()
                val absDiff = kotlin.math.abs(diffMinutes)
                val isClosest = absDiff <= 30
                val showLabel = isClosest || (hourIndex % labelInterval == 0)
                val isNight = SunPositionUtils.isNight(currentHour, lat, lon)
                val iconRes = WeatherIconMapper.getIconResource(forecast.condition, isNight)
                val isSunny =
                    iconRes == R.drawable.ic_weather_clear ||
                        iconRes == R.drawable.ic_weather_mostly_clear ||
                        iconRes == R.drawable.ic_weather_night
                val isRainy =
                    iconRes == R.drawable.ic_weather_rain ||
                        iconRes == R.drawable.ic_weather_storm ||
                        iconRes == R.drawable.ic_weather_snow
                val isMixed =
                    iconRes == R.drawable.ic_weather_mostly_cloudy ||
                        iconRes == R.drawable.ic_weather_mostly_cloudy_night ||
                        iconRes == R.drawable.ic_weather_partly_cloudy ||
                        iconRes == R.drawable.ic_weather_partly_cloudy_night ||
                        iconRes == R.drawable.ic_weather_fog_cloudy

                hours.add(
                    PrecipitationGraphRenderer.PrecipHourData(
                        dateTime = currentHour,
                        precipProbability = forecast.precipProbability ?: 0,
                        label = formatHourLabel(currentHour),
                        iconRes = iconRes,
                        isNight = isNight,
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

    private fun formatHourLabel(time: LocalDateTime): String {
        val hour = time.hour
        return when {
            hour == 0 -> "12a"
            hour < 12 -> "${hour}a"
            hour == 12 -> "12p"
            else -> "${hour - 12}p"
        }
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

        containerIds.forEachIndexed { index, (containerId, ids) ->
            if (index < timeOffsets.size) {
                val offset = timeOffsets[index]
                val targetTime = centerTime.plusHours(offset.toLong())
                val hourKey = targetTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                val forecast = forecastsByTime[hourKey]

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
