package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.PrecipitationGraphRenderer
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
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
    private const val EXTRA_TARGET_VIEW = "com.weatherwidget.EXTRA_TARGET_VIEW"

    /**
     * Update widget with precipitation data.
     */
    fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        precipProbability: Int? = null,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val numRows = dimensions.rows

        val stateManager = WidgetStateManager(context)

        Log.d(TAG, "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, hourlyCount=${hourlyForecasts.size}")

        // Hide graph day zones (not used in precipitation mode)
        views.setViewVisibility(R.id.graph_day_zones, View.GONE)

        // Set tap to cycle zoom on graph_view
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

        // Setup navigation buttons
        setupNavigationButtons(context, views, appWidgetId, stateManager)

        // In precipitation mode: current temp → hourly graph, precip % → daily forecast
        val goHourlyIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_SET_VIEW
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_TARGET_VIEW, com.weatherwidget.widget.ViewMode.HOURLY.name)
            }
        val goHourlyPending =
            PendingIntent.getBroadcast(
                context,
                appWidgetId * 2 + 200,
                goHourlyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.current_temp, goHourlyPending)
        views.setOnClickPendingIntent(R.id.current_temp_zone, goHourlyPending)

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

        // Set current temperature (interpolated from hourly data)
        if (hourlyForecasts.isNotEmpty()) {
            val interpolator = TemperatureInterpolator()
            val currentTemp = interpolator.getInterpolatedTemperature(hourlyForecasts, now, displaySource)
            if (currentTemp != null) {
                val formattedTemp =
                    when {
                        numColumns >= 2 -> String.format("%.1f°", currentTemp)
                        else -> String.format("%.0f°", currentTemp)
                    }
                views.setTextViewText(R.id.current_temp, formattedTemp)
                views.setViewVisibility(R.id.current_temp, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.current_temp, View.GONE)
            }
        } else {
            views.setViewVisibility(R.id.current_temp, View.GONE)
        }

        // Show precipitation probability next to current temp
        if (precipProbability != null && precipProbability > 0) {
            views.setTextViewText(R.id.precip_probability, "$precipProbability%")
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
            val zoom = stateManager.getZoomLevel(appWidgetId)
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
            val bitmap = PrecipitationGraphRenderer.renderGraph(context, hours, widthPx, heightPx, now, bitmapScale, smoothIterations = zoom.precipSmoothIterations, hourLabelSpacingDp = hourLabelSpacingDp)
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
        val now = LocalDateTime.now()
        val currentHourKey = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))

        return hourlyForecasts
            .filter { it.dateTime == currentHourKey }
            .let { forecasts ->
                forecasts.find { it.source == displaySource.id }
                    ?: forecasts.find { it.source == WeatherSource.GENERIC_GAP.id }
                    ?: forecasts.firstOrNull()
            }?.condition
    }

    private fun setupNavigationButtons(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        stateManager: WidgetStateManager,
    ) {
        val leftIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_NAV_LEFT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
        val leftPendingIntent =
            PendingIntent.getBroadcast(
                context,
                appWidgetId * 2,
                leftIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.nav_left, leftPendingIntent)
        views.setOnClickPendingIntent(R.id.nav_left_zone, leftPendingIntent)

        val rightIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_NAV_RIGHT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
        val rightPendingIntent =
            PendingIntent.getBroadcast(
                context,
                appWidgetId * 2 + 1,
                rightIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.nav_right, rightPendingIntent)
        views.setOnClickPendingIntent(R.id.nav_right_zone, rightPendingIntent)

        val canLeft = stateManager.canNavigateHourlyLeft(appWidgetId)
        val canRight = stateManager.canNavigateHourlyRight(appWidgetId)

        views.setViewVisibility(R.id.nav_left, if (canLeft) View.VISIBLE else View.INVISIBLE)
        views.setViewVisibility(R.id.nav_left_zone, if (canLeft) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.nav_right, if (canRight) View.VISIBLE else View.INVISIBLE)
        views.setViewVisibility(R.id.nav_right_zone, if (canRight) View.VISIBLE else View.GONE)
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
                appWidgetId * 2 + 100,
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

    private fun buildPrecipHourDataList(
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
        zoom: com.weatherwidget.widget.ZoomLevel = com.weatherwidget.widget.ZoomLevel.WIDE,
    ): List<PrecipitationGraphRenderer.PrecipHourData> {
        val hours = mutableListOf<PrecipitationGraphRenderer.PrecipHourData>()
        val now = LocalDateTime.now()

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

                hours.add(
                    PrecipitationGraphRenderer.PrecipHourData(
                        dateTime = currentHour,
                        precipProbability = forecast.precipProbability ?: 0,
                        label = formatHourLabel(currentHour),
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
