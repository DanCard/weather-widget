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
import com.weatherwidget.widget.HourlyTemperatureGraphRenderer
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Handler for the hourly temperature view mode.
 */
object HourlyViewHandler {
    private const val TAG = "HourlyViewHandler"
    private const val CELL_HEIGHT_DP = 90

    // Intent actions
    private const val ACTION_NAV_LEFT = "com.weatherwidget.ACTION_NAV_LEFT"
    private const val ACTION_NAV_RIGHT = "com.weatherwidget.ACTION_NAV_RIGHT"
    private const val ACTION_TOGGLE_API = "com.weatherwidget.ACTION_TOGGLE_API"
    private const val ACTION_TOGGLE_VIEW = "com.weatherwidget.ACTION_TOGGLE_VIEW"
    private const val ACTION_TOGGLE_PRECIP = "com.weatherwidget.ACTION_TOGGLE_PRECIP"

    /**
     * Update widget with hourly temperature data.
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

        // Hourly mode: hide graph day zones, keep settings click on graph_view
        views.setViewVisibility(R.id.graph_day_zones, View.GONE)

        // Set tap to open settings on graph_view
        val settingsIntent = Intent(context, com.weatherwidget.ui.SettingsActivity::class.java)
        val settingsPendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.graph_view, settingsPendingIntent)

        // Setup navigation buttons
        setupNavigationButtons(context, views, appWidgetId, stateManager)

        // Setup current temp click to toggle view
        setupCurrentTempToggle(context, views, appWidgetId)

        // Get current display source
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)
        val dayName = centerTime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val sourceIndicator = if (centerTime.toLocalDate() == LocalDateTime.now().toLocalDate()) {
            displaySource.shortDisplayName
        } else {
            "$dayName • ${displaySource.shortDisplayName}"
        }
        views.setTextViewText(R.id.api_source, sourceIndicator)

        // Set weather icon - use hourly forecast condition for current hour
        val now = LocalDateTime.now()
        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val isNight = SunPositionUtils.isNight(now, lat, lon)

        // Get current hour's condition from hourly forecasts
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

        // Show precipitation probability next to current temp when rain is expected
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

            // Build hour data list for graph
            val hours = buildHourDataList(hourlyForecasts, centerTime, numColumns, displaySource)

            // Use actual widget dimensions for bitmap
            val widthDp = dimensions.widthDp - 24
            val heightDp = dimensions.heightDp - 16

            val (widthPx, heightPx) = WidgetSizeCalculator.getOptimalBitmapSize(context, widthDp, heightDp)

            // Render hourly graph
            val bitmap = HourlyTemperatureGraphRenderer.renderGraph(context, hours, widthPx, heightPx, now)
            views.setImageViewBitmap(R.id.graph_view, bitmap)
        } else {
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)

            // Text mode: show hourly data as text
            updateHourlyTextMode(views, hourlyForecasts, centerTime, numColumns, displaySource)
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

    private fun setupCurrentTempToggle(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
    ) {
        val toggleIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_VIEW
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
        val togglePendingIntent =
            PendingIntent.getBroadcast(
                context,
                appWidgetId * 2 + 200,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.current_temp, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.current_temp_zone, togglePendingIntent)

        val precipIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_PRECIP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
        val precipPendingIntent =
            PendingIntent.getBroadcast(
                context,
                appWidgetId * 2 + 300,
                precipIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.precip_probability, precipPendingIntent)
        views.setOnClickPendingIntent(R.id.precip_touch_zone, precipPendingIntent)
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

    private fun buildHourDataList(
        hourlyForecasts: List<HourlyForecastEntity>,
        centerTime: LocalDateTime,
        numColumns: Int,
        displaySource: WeatherSource,
    ): List<HourlyTemperatureGraphRenderer.HourData> {
        val hours = mutableListOf<HourlyTemperatureGraphRenderer.HourData>()
        val now = LocalDateTime.now()

        // Group by dateTime and prefer the selected source, fallback to generic gap
        val forecastsByTime =
            hourlyForecasts.groupBy { it.dateTime }
                .mapValues { entry ->
                    val preferred = entry.value.find { it.source == displaySource.id }
                    val gap = entry.value.find { it.source == WeatherSource.GENERIC_GAP.id }
                    val fallback = entry.value.firstOrNull()
                    preferred ?: gap ?: fallback
                }

        // Round to nearest hour
        val truncated = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val alignedCenter = if (centerTime.minute >= 30) truncated.plusHours(1) else truncated
        val startHour = alignedCenter.minusHours(8)
        val endHour = alignedCenter.plusHours(16)

        val labelInterval = 4

        var currentHour = startHour
        var hourIndex = 0

        val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

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
                        iconRes == R.drawable.ic_weather_partly_cloudy ||
                        iconRes == R.drawable.ic_weather_mostly_clear
                val isRainy =
                    iconRes == R.drawable.ic_weather_rain ||
                        iconRes == R.drawable.ic_weather_storm
                val isMixed =
                    iconRes == R.drawable.ic_weather_mostly_cloudy ||
                        iconRes == R.drawable.ic_weather_partly_cloudy ||
                        iconRes == R.drawable.ic_weather_mostly_clear

                hours.add(
                    HourlyTemperatureGraphRenderer.HourData(
                        dateTime = currentHour,
                        temperature = forecast.temperature,
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
            hour == 0 -> {
                val day = time.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                "$day 12a"
            }
            hour < 12 -> "${hour}a"
            hour == 12 -> "12p"
            else -> "${hour - 12}p"
        }
    }

    private fun updateHourlyTextMode(
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
                    val temp = String.format("%.0f°", forecast.temperature)
                    views.setTextViewText(ids.third, temp)
                    views.setTextViewText(ids.fourth, "")
                } else {
                    views.setTextViewText(ids.third, "--°")
                    views.setTextViewText(ids.fourth, "")
                }
            } else {
                views.setViewVisibility(containerId, View.GONE)
            }
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
