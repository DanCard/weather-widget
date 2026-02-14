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
import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.util.RainAnalyzer
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.util.WeatherIconMapper
import java.time.LocalTime
import com.weatherwidget.widget.AccuracyDisplayMode
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Handler for the daily forecast view mode.
 */
object DailyViewHandler : WidgetViewHandler {
    private const val TAG = "DailyViewHandler"
    private const val CELL_HEIGHT_DP = 90

    // Intent actions from WeatherWidgetProvider
    private const val ACTION_NAV_LEFT = "com.weatherwidget.ACTION_NAV_LEFT"
    private const val ACTION_NAV_RIGHT = "com.weatherwidget.ACTION_NAV_RIGHT"
    private const val ACTION_TOGGLE_API = "com.weatherwidget.ACTION_TOGGLE_API"
    private const val ACTION_TOGGLE_VIEW = "com.weatherwidget.ACTION_TOGGLE_VIEW"
    private const val ACTION_TOGGLE_PRECIP = "com.weatherwidget.ACTION_TOGGLE_PRECIP"
    private const val ACTION_SET_VIEW = "com.weatherwidget.ACTION_SET_VIEW"
    private const val ACTION_DAY_CLICK = "com.weatherwidget.ACTION_DAY_CLICK"
    private const val EXTRA_TARGET_VIEW = "com.weatherwidget.EXTRA_TARGET_VIEW"
    private const val EXTRA_HOURLY_OFFSET = "com.weatherwidget.EXTRA_HOURLY_OFFSET"

    override fun canHandle(
        stateManager: WidgetStateManager,
        appWidgetId: Int,
    ): Boolean {
        return stateManager.getViewMode(appWidgetId) == com.weatherwidget.widget.ViewMode.DAILY
    }

    override suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        weatherList: List<WeatherEntity>,
        forecastSnapshots: Map<String, List<ForecastSnapshotEntity>>,
        hourlyForecasts: List<HourlyForecastEntity>,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val numRows = dimensions.rows

        val stateManager = WidgetStateManager(context)
        val dateOffset = stateManager.getDateOffset(appWidgetId)
        val accuracyMode = stateManager.getAccuracyDisplayMode()

        val isEveningMode = NavigationUtils.isEveningMode()
        Log.d(
            TAG,
            "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, offset=$dateOffset, " +
                "isEveningMode=$isEveningMode, weatherCount=${weatherList.size}",
        )

        // Setup current temp click to toggle view mode
        setupCurrentTempToggle(context, views, appWidgetId)

        val today = LocalDate.now()
        val skipHistory = NavigationUtils.shouldSkipHistory(isEveningMode, dateOffset)
        val centerDate = NavigationUtils.getDisplayCenterDate(today, dateOffset, isEveningMode)

        // Get the current display source for this widget
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)

        // Build weather map: prefer the selected display source, fallback to generic gap
        val weatherByDate =
            weatherList
                .filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
                .groupBy { it.date }
                .mapValues { (_, items) -> items.find { it.source == displaySource.id } ?: items.first() }

        // Set API source indicator
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        views.setTextViewText(R.id.api_source, displaySource.shortDisplayName)

        // Set weather icon - use hourly forecast condition for current hour for consistency
        val now = LocalDateTime.now()
        val lat = weatherList.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = weatherList.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val isNight = SunPositionUtils.isNight(now, lat, lon)

        // Get current hour's condition from hourly forecasts for consistency with hourly graph
        val currentHourCondition =
            getCurrentHourCondition(hourlyForecasts, displaySource)
                ?: weatherByDate[todayStr]?.condition

        val iconRes = WeatherIconMapper.getIconResource(currentHourCondition, isNight)
        views.setImageViewResource(R.id.weather_icon, iconRes)
        views.setViewVisibility(R.id.weather_icon, View.VISIBLE)

        // Set current temperature - always use interpolation from hourly forecasts for accuracy
        var currentTemp: Float? = null
        if (hourlyForecasts.isNotEmpty()) {
            val interpolator = TemperatureInterpolator()
            currentTemp = interpolator.getInterpolatedTemperature(hourlyForecasts, LocalDateTime.now(), displaySource)
            if (currentTemp != null) {
                Log.d(TAG, "updateWidget: Using interpolated temp: $currentTemp from source $displaySource")
            }
        }

        // Fallback to API currentTemp if interpolation unavailable
        if (currentTemp == null) {
            currentTemp =
                weatherList
                    .filter { it.date == todayStr && it.currentTemp != null && it.currentTemp != 0 }
                    .maxByOrNull { it.fetchedAt }
                    ?.currentTemp?.toFloat()
            if (currentTemp != null) {
                Log.d(TAG, "updateWidget: Using API currentTemp fallback: $currentTemp")
            }
        }

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

        // Show precipitation probability next to current temp when rain is expected
        val todayWeather = weatherByDate[todayStr]
        val precipProb = todayWeather?.precipProbability
        if (precipProb != null && precipProb > 0) {
            views.setTextViewText(R.id.precip_probability, "$precipProb%")
            views.setViewVisibility(R.id.precip_probability, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.precip_probability, View.GONE)
        }

        // Setup API source toggle click handler
        setupApiToggle(context, views, appWidgetId, numRows)

        // Get available dates (allow partial data: high OR low)
        val availableDates =
            weatherByDate.filter { (_, weather) ->
                weather.highTemp != null || weather.lowTemp != null
            }.keys

        // Set up navigation click handlers with available dates and widget width
        // In evening mode, skip history in navigation bounds
            setupNavigationButtons(context, views, appWidgetId, stateManager, availableDates, numColumns, isEveningMode)

        // Use graph mode for 2+ rows
        val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
        val useGraph = rawRows >= 1.4f

        if (useGraph) {
            views.setViewVisibility(R.id.text_container, View.GONE)
            views.setViewVisibility(R.id.graph_view, View.VISIBLE)
            views.setViewVisibility(R.id.graph_day_zones, View.VISIBLE)

            // Build day data for graph with offset
            // In evening mode, skip history to show today with forecast comparison
            val days =
                buildDayDataList(
                    centerDate,
                    today,
                    weatherByDate,
                    forecastSnapshots,
                    numColumns,
                    accuracyMode,
                    displaySource,
                    isEveningMode,
                    skipHistory,
                    hourlyForecasts,
                )

            // Use actual widget dimensions for bitmap to match ImageView size
            // Account for 8dp root padding + 4dp graph margins on each side = 24dp total
            val widthDp = dimensions.widthDp - 24
            val heightDp = dimensions.heightDp - 16

            val (widthPx, heightPx) = WidgetSizeCalculator.getOptimalBitmapSize(context, widthDp, heightDp)

            // Render graph
            val bitmap = DailyForecastGraphRenderer.renderGraph(context, days, widthPx, heightPx)
            views.setImageViewBitmap(R.id.graph_view, bitmap)

            // Setup per-day click handlers for graph mode
            // In evening mode at offset 0, today is shown as leftmost but should behave like future day
            setupGraphDayClickHandlers(
                context,
                views,
                appWidgetId,
                days,
                lat,
                lon,
                displaySource,
                skipHistory,
            )
        } else {
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)
            views.setViewVisibility(R.id.graph_day_zones, View.GONE)

            // Text mode - set visibility and populate
            val visibleDates = updateTextMode(views, centerDate, today, weatherByDate, hourlyForecasts, numColumns, displaySource, skipHistory)

            // Setup per-day click handlers for text mode
            // In evening mode at offset 0, today is the leftmost day (day1)
            setupTextDayClickHandlers(
                context,
                views,
                appWidgetId,
                visibleDates,
                lat,
                lon,
                displaySource,
                skipHistory,
            )
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

        val currentHourForecast =
            hourlyForecasts
                .filter { it.dateTime == currentHourKey }
                .let { forecasts ->
                    forecasts.find { it.source == displaySource.id }
                        ?: forecasts.find { it.source == WeatherSource.GENERIC_GAP.id }
                        ?: forecasts.firstOrNull()
                }

        return currentHourForecast?.condition
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

        // Wire precip probability click to toggle precipitation graph
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

        // Scale text size based on widget rows
        val textSizeSp =
            when {
                numRows >= 3 -> 18f
                numRows >= 2 -> 16f
                else -> 14f
            }
        views.setTextViewTextSize(R.id.api_source, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
    }

    private fun setupNavigationButtons(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        stateManager: WidgetStateManager,
        availableDates: Set<String> = emptySet(),
        numColumns: Int = 3,
        isEveningMode: Boolean = false,
    ) {
        // Left arrow
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

        // Right arrow
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

        // Check if navigation would reveal new data
        val today = LocalDate.now()
        val currentOffset = stateManager.getDateOffset(appWidgetId)
        val sortedDates = availableDates.map { LocalDate.parse(it) }.sorted()
        val minDate = sortedDates.firstOrNull()
        val maxDate = sortedDates.lastOrNull()

        val (leftmostAfterLeft, _) =
            NavigationUtils.getVisibleDateRange(
                today = today,
                dateOffset = currentOffset - 1,
                numColumns = numColumns,
                isEveningMode = isEveningMode,
            )
        val (_, rightmostAfterRight) =
            NavigationUtils.getVisibleDateRange(
                today = today,
                dateOffset = currentOffset + 1,
                numColumns = numColumns,
                isEveningMode = isEveningMode,
            )

        val canLeft = minDate != null && !minDate.isAfter(leftmostAfterLeft)
        val canRight = maxDate != null && !maxDate.isBefore(rightmostAfterRight)

        views.setViewVisibility(R.id.nav_left, if (canLeft) View.VISIBLE else View.INVISIBLE)
        views.setViewVisibility(R.id.nav_left_zone, if (canLeft) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.nav_right, if (canRight) View.VISIBLE else View.INVISIBLE)
        views.setViewVisibility(R.id.nav_right_zone, if (canRight) View.VISIBLE else View.GONE)
    }

    private fun buildDayDataList(
        centerDate: LocalDate,
        today: LocalDate,
        weatherByDate: Map<String, WeatherEntity>,
        forecastSnapshots: Map<String, List<ForecastSnapshotEntity>>,
        numColumns: Int,
        accuracyMode: AccuracyDisplayMode,
        displaySource: WeatherSource,
        isEveningMode: Boolean,
        skipHistory: Boolean,
        hourlyForecasts: List<HourlyForecastEntity>,
    ): List<DailyForecastGraphRenderer.DayData> {
        val days = mutableListOf<DailyForecastGraphRenderer.DayData>()

        val dayOffsets = NavigationUtils.getDayOffsets(numColumns, skipHistory)
        Log.d(TAG, "buildDayDataList: centerDate=$centerDate, today=$today, isEveningMode=$isEveningMode, " +
            "skipHistory=$skipHistory, dayOffsets=$dayOffsets")

        dayOffsets.forEach { offset ->
            val date = centerDate.plusDays(offset)
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val weather = weatherByDate[dateStr]

            if (weather == null || (weather.highTemp == null && weather.lowTemp == null)) {
                return@forEach
            }

            val forecasts = forecastSnapshots[dateStr] ?: emptyList()

            // Snapshot groups may contain many rows for the same source/day.
            // Always use the latest by fetchedAt to keep comparison stable.
            val forecast =
                forecasts
                    .filter { it.source == displaySource.id }
                    .maxByOrNull { it.fetchedAt }
                    ?: forecasts
                        .filter { it.source == WeatherSource.GENERIC_GAP.id }
                        .maxByOrNull { it.fetchedAt }

            val label =
                when {
                    date == today -> "Today"
                    else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }

            val isPastDate = date.isBefore(today)
            val isToday = date == today

            // Show forecast comparison for:
            // 1. Past dates (normal history mode)
            // 2. Today in evening mode (show what was predicted vs estimated actuals)
            val showComparison = (isPastDate || (isToday && isEveningMode)) &&
                forecast != null && accuracyMode != AccuracyDisplayMode.NONE

            // In evening mode for today, estimate actuals from hourly data.
            val (actualHigh, actualLow) = if (isToday && isEveningMode && hourlyForecasts.isNotEmpty()) {
                estimateTodayActualsFromHourly(hourlyForecasts, today, displaySource, weather)
            } else {
                weather.highTemp to weather.lowTemp
            }

            // Use current hour condition for Today to match the main widget icon
            val effectiveCondition = if (isToday) {
                getCurrentHourCondition(hourlyForecasts, displaySource) ?: weather.condition
            } else {
                weather.condition
            }
            val iconRes = WeatherIconMapper.getIconResource(effectiveCondition)
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

            // Get rain summary for all future days (determines click behavior)
            val rainSummary = if (!isPastDate) {
                RainAnalyzer.getRainSummary(hourlyForecasts, date, displaySource.id, LocalDateTime.now())
            } else {
                null
            }

            days.add(
                DailyForecastGraphRenderer.DayData(
                    date = dateStr,
                    label = label,
                    high = actualHigh,
                    low = actualLow,
                    iconRes = iconRes,
                    isSunny = isSunny,
                    isRainy = isRainy,
                    isMixed = isMixed,
                    isToday = isToday,
                    isPast = isPastDate,
                    isClimateNormal = weather.isClimateNormal,
                    forecastHigh = if (showComparison) forecast?.highTemp else null,
                    forecastLow = if (showComparison) forecast?.lowTemp else null,
                    forecastSource = if (showComparison) displaySource else null,
                    accuracyMode = if (showComparison) accuracyMode else AccuracyDisplayMode.NONE,
                    rainSummary = rainSummary,
                ),
            )
        }

        return days
    }

    /**
     * Estimates today's actual high/low from hourly forecast data.
     * Uses observed hourly data up to current time for actuals,
     * and remaining forecast for the rest of the day.
     */
    private fun estimateTodayActualsFromHourly(
        hourlyForecasts: List<HourlyForecastEntity>,
        today: LocalDate,
        displaySource: WeatherSource,
        fallbackWeather: WeatherEntity,
    ): Pair<Int?, Int?> {
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val now = LocalDateTime.now()

        // Filter hourly forecasts for today from the display source
        val todayHourly = hourlyForecasts.filter {
            it.dateTime.startsWith(todayStr) &&
                (it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id)
        }

        if (todayHourly.isEmpty()) {
            return fallbackWeather.highTemp to fallbackWeather.lowTemp
        }

        // Get all temperatures for today
        val temps = todayHourly.map { it.temperature }
        if (temps.isEmpty()) {
            return fallbackWeather.highTemp to fallbackWeather.lowTemp
        }

        // For estimation, use the full day's hourly data (both past and future hours)
        // This gives us the best estimate of what the day's actual high/low will be
        val estimatedHigh = temps.maxOrNull()?.toInt()
        val estimatedLow = temps.minOrNull()?.toInt()

        return estimatedHigh to estimatedLow
    }

    private fun updateTextMode(
        views: RemoteViews,
        centerDate: LocalDate,
        today: LocalDate,
        weatherByDate: Map<String, WeatherEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        numColumns: Int,
        displaySource: WeatherSource,
        skipHistory: Boolean = false,
    ): List<Triple<Int, String, Boolean>> {  // dayIndex, dateStr, hasRainForecast
        val effectiveCenter = if (skipHistory) centerDate.plusDays(1) else centerDate

        val day1Date = effectiveCenter.minusDays(1)
        val day2Date = effectiveCenter
        val day3Date = effectiveCenter.plusDays(1)
        val day4Date = effectiveCenter.plusDays(2)
        val day5Date = effectiveCenter.plusDays(3)
        val day6Date = effectiveCenter.plusDays(4)
        val day7Date = effectiveCenter.plusDays(5)

        val day1Str = day1Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day2Str = day2Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day3Str = day3Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day4Str = day4Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day5Str = day5Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day6Str = day6Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day7Str = day7Date.format(DateTimeFormatter.ISO_LOCAL_DATE)

        fun hasCompleteData(dateStr: String): Boolean {
            val weather = weatherByDate[dateStr] ?: return false
            return weather.highTemp != null && weather.lowTemp != null
        }

        val hasDay1 = hasCompleteData(day1Str)
        val hasDay2 = hasCompleteData(day2Str)
        val hasDay3 = hasCompleteData(day3Str)
        val hasDay4 = hasCompleteData(day4Str)
        val hasDay5 = hasCompleteData(day5Str)
        val hasDay6 = hasCompleteData(day6Str)
        val hasDay7 = hasCompleteData(day7Str)

        when {
            numColumns >= 7 -> {
                views.setViewVisibility(R.id.day1_container, if (hasDay1) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day4_container, if (hasDay4) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day5_container, if (hasDay5) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day6_container, if (hasDay6) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day7_container, if (hasDay7) View.VISIBLE else View.GONE)
            }
            numColumns == 6 -> {
                views.setViewVisibility(R.id.day1_container, if (hasDay1) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day4_container, if (hasDay4) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day5_container, if (hasDay5) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day6_container, if (hasDay6) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day7_container, View.GONE)
            }
            numColumns == 5 -> {
                views.setViewVisibility(R.id.day1_container, if (hasDay1) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day4_container, if (hasDay4) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day5_container, if (hasDay5) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day6_container, View.GONE)
                views.setViewVisibility(R.id.day7_container, View.GONE)
            }
            numColumns == 4 -> {
                views.setViewVisibility(R.id.day1_container, if (hasDay1) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day4_container, if (hasDay4) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day5_container, View.GONE)
                views.setViewVisibility(R.id.day6_container, View.GONE)
                views.setViewVisibility(R.id.day7_container, View.GONE)
            }
            numColumns == 3 -> {
                views.setViewVisibility(R.id.day1_container, if (hasDay1) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day4_container, View.GONE)
                views.setViewVisibility(R.id.day5_container, View.GONE)
                views.setViewVisibility(R.id.day6_container, View.GONE)
                views.setViewVisibility(R.id.day7_container, View.GONE)
            }
            numColumns == 2 -> {
                views.setViewVisibility(R.id.day1_container, View.GONE)
                views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day4_container, View.GONE)
                views.setViewVisibility(R.id.day5_container, View.GONE)
                views.setViewVisibility(R.id.day6_container, View.GONE)
                views.setViewVisibility(R.id.day7_container, View.GONE)
            }
            else -> {
                views.setViewVisibility(R.id.day1_container, View.GONE)
                views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day3_container, View.GONE)
                views.setViewVisibility(R.id.day4_container, View.GONE)
                views.setViewVisibility(R.id.day5_container, View.GONE)
                views.setViewVisibility(R.id.day6_container, View.GONE)
                views.setViewVisibility(R.id.day7_container, View.GONE)
            }
        }

        fun getLabelForDate(date: LocalDate): String {
            return if (date == today) {
                "Today"
            } else {
                date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            }
        }

        // Check rain forecast for all days (for click behavior)
        // But only display rain text for near-term days (today + 2 days)
        val now = LocalDateTime.now()
        val nearTermLimit = today.plusDays(2)
        fun isNearTerm(date: LocalDate) = !date.isBefore(today) && !date.isAfter(nearTermLimit)
        val rainSummary1 = if (hasDay1) RainAnalyzer.getRainSummary(hourlyForecasts, day1Date, displaySource.id, now) else null
        val rainSummary2 = if (hasDay2) RainAnalyzer.getRainSummary(hourlyForecasts, day2Date, displaySource.id, now) else null
        val rainSummary3 = if (hasDay3) RainAnalyzer.getRainSummary(hourlyForecasts, day3Date, displaySource.id, now) else null
        val rainSummary4 = if (hasDay4) RainAnalyzer.getRainSummary(hourlyForecasts, day4Date, displaySource.id, now) else null
        val rainSummary5 = if (hasDay5) RainAnalyzer.getRainSummary(hourlyForecasts, day5Date, displaySource.id, now) else null
        val rainSummary6 = if (hasDay6) RainAnalyzer.getRainSummary(hourlyForecasts, day6Date, displaySource.id, now) else null
        val rainSummary7 = if (hasDay7) RainAnalyzer.getRainSummary(hourlyForecasts, day7Date, displaySource.id, now) else null

        if (hasDay1) {
            populateDay(
                views,
                R.id.day1_label,
                R.id.day1_icon,
                R.id.day1_high,
                R.id.day1_low,
                R.id.day1_rain,
                getLabelForDate(day1Date),
                weatherByDate[day1Str],
                rainSummary1,
                isToday = day1Date == today,
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
            )
        }
        if (hasDay2) {
            populateDay(
                views,
                R.id.day2_label,
                R.id.day2_icon,
                R.id.day2_high,
                R.id.day2_low,
                R.id.day2_rain,
                getLabelForDate(day2Date),
                weatherByDate[day2Str],
                rainSummary2,
                isToday = day2Date == today,
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
            )
        }
        if (hasDay3) {
            populateDay(
                views,
                R.id.day3_label,
                R.id.day3_icon,
                R.id.day3_high,
                R.id.day3_low,
                R.id.day3_rain,
                getLabelForDate(day3Date),
                weatherByDate[day3Str],
                rainSummary3,
                isToday = day3Date == today,
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
            )
        }
        if (hasDay4) {
            populateDay(
                views,
                R.id.day4_label,
                R.id.day4_icon,
                R.id.day4_high,
                R.id.day4_low,
                R.id.day4_rain,
                getLabelForDate(day4Date),
                weatherByDate[day4Str],
                rainSummary4,
                isToday = day4Date == today,
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
            )
        }
        if (hasDay5) {
            populateDay(
                views,
                R.id.day5_label,
                R.id.day5_icon,
                R.id.day5_high,
                R.id.day5_low,
                R.id.day5_rain,
                getLabelForDate(day5Date),
                weatherByDate[day5Str],
                rainSummary5,
                isToday = day5Date == today,
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
            )
        }
        if (hasDay6) {
            populateDay(
                views,
                R.id.day6_label,
                R.id.day6_icon,
                R.id.day6_high,
                R.id.day6_low,
                R.id.day6_rain,
                getLabelForDate(day6Date),
                weatherByDate[day6Str],
                rainSummary6,
                isToday = day6Date == today,
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
            )
        }
        if (hasDay7) {
            populateDay(
                views,
                R.id.day7_label,
                R.id.day7_icon,
                R.id.day7_high,
                R.id.day7_low,
                R.id.day7_rain,
                getLabelForDate(day7Date),
                weatherByDate[day7Str],
                rainSummary7,
                isToday = day7Date == today,
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
            )
        }

        // Determine first day with rain (only show rain indicator for first occurrence)
        val firstRainDay = when {
            rainSummary1 != null -> 1
            rainSummary2 != null -> 2
            rainSummary3 != null -> 3
            rainSummary4 != null -> 4
            rainSummary5 != null -> 5
            rainSummary6 != null -> 6
            rainSummary7 != null -> 7
            else -> -1
        }
        
        val visibleDays = mutableListOf<Triple<Int, String, Boolean>>()
        if (hasDay1) visibleDays.add(Triple(1, day1Str, rainSummary1 != null))
        if (hasDay2) visibleDays.add(Triple(2, day2Str, rainSummary2 != null))
        if (hasDay3) visibleDays.add(Triple(3, day3Str, rainSummary3 != null))
        if (hasDay4) visibleDays.add(Triple(4, day4Str, rainSummary4 != null))
        if (hasDay5) visibleDays.add(Triple(5, day5Str, rainSummary5 != null))
        if (hasDay6) visibleDays.add(Triple(6, day6Str, rainSummary6 != null))
        if (hasDay7) visibleDays.add(Triple(7, day7Str, rainSummary7 != null))
        
        // Update rain visibility - only show for first rainy day
        if (hasDay1) populateDayRain(views, R.id.day1_rain, rainSummary1, firstRainDay == 1)
        if (hasDay2) populateDayRain(views, R.id.day2_rain, rainSummary2, firstRainDay == 2)
        if (hasDay3) populateDayRain(views, R.id.day3_rain, rainSummary3, firstRainDay == 3)
        if (hasDay4) populateDayRain(views, R.id.day4_rain, rainSummary4, firstRainDay == 4)
        if (hasDay5) populateDayRain(views, R.id.day5_rain, rainSummary5, firstRainDay == 5)
        if (hasDay6) populateDayRain(views, R.id.day6_rain, rainSummary6, firstRainDay == 6)
        if (hasDay7) populateDayRain(views, R.id.day7_rain, rainSummary7, firstRainDay == 7)
        
        return visibleDays
    }

    private fun populateDay(
        views: RemoteViews,
        labelId: Int,
        iconId: Int,
        highId: Int,
        lowId: Int,
        rainId: Int,
        label: String,
        weather: WeatherEntity?,
        rainSummary: String?,
        isToday: Boolean = false,
        hourlyForecasts: List<HourlyForecastEntity> = emptyList(),
        displaySource: WeatherSource = WeatherSource.NWS,
    ) {
        views.setTextViewText(labelId, label)

        val condition = if (isToday && weather != null) {
            getCurrentHourCondition(hourlyForecasts, displaySource) ?: weather.condition
        } else {
            weather?.condition
        }
        val iconRes = WeatherIconMapper.getIconResource(condition)
        views.setImageViewResource(iconId, iconRes)

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

        if (!isRainy && !isMixed) {
            val tintColor = if (isSunny) android.graphics.Color.parseColor("#FFD60A") else android.graphics.Color.parseColor("#AAAAAA")
            views.setInt(iconId, "setColorFilter", tintColor)
        }

        views.setViewVisibility(iconId, View.VISIBLE)
        views.setTextViewText(highId, weather?.highTemp?.let { "$it°" } ?: "--°")
        views.setTextViewText(lowId, weather?.lowTemp?.let { "$it°" } ?: "--°")

        // Rain display handled separately by populateDayRain to show only first occurrence
    }
    
    private fun populateDayRain(
        views: RemoteViews,
        rainId: Int,
        rainSummary: String?,
        showRain: Boolean,
    ) {
        if (showRain && !rainSummary.isNullOrEmpty()) {
            views.setTextViewText(rainId, "💧 $rainSummary")
            views.setViewVisibility(rainId, View.VISIBLE)
        } else {
            views.setViewVisibility(rainId, View.GONE)
        }
    }

    private fun setupTextDayClickHandlers(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        visibleDays: List<Triple<Int, String, Boolean>>,  // dayIndex, dateStr, hasRainForecast
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
        isEveningModeAtOffset0: Boolean = false,
    ) {
        val containerIds =
            listOf(
                R.id.day1_container,
                R.id.day2_container,
                R.id.day3_container,
                R.id.day4_container,
                R.id.day5_container,
                R.id.day6_container,
                R.id.day7_container,
            )

        val midpoint = visibleDays.size / 2

        visibleDays.forEachIndexed { index, (dayIndex, dateStr, hasRainForecast) ->
            val containerId = containerIds[dayIndex - 1]

            val targetDay = LocalDate.parse(dateStr)
            val today = LocalDate.now()
            val isHistory = targetDay.isBefore(today)

            // Determine action: history view vs precipitation view
            // Any day without rain forecast → show history
            // Any day with rain → show precipitation
            // Past days → always show history
            val showHistory = isHistory || !hasRainForecast
            
            Log.d(TAG, "Text click: date=$dateStr, isHistory=$isHistory, hasRainForecast=$hasRainForecast, showHistory=$showHistory")

            val intent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_DAY_CLICK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra("date", dateStr)
                putExtra("isHistory", isHistory)
                putExtra("showHistory", showHistory)
                putExtra("index", dayIndex)
            }

            if (showHistory) {
                intent.putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
                intent.putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
                intent.putExtra(ForecastHistoryActivity.EXTRA_SOURCE, displaySource.displayName)
            } else {
                val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
                val targetCenter = targetDay.atTime(8, 0)
                val offset = Duration.between(now, targetCenter).toHours().toInt()

                intent.putExtra(EXTRA_TARGET_VIEW, "PRECIPITATION")
                intent.putExtra(EXTRA_HOURLY_OFFSET, offset)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId * 100 + dayIndex,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(containerId, pendingIntent)
        }
    }

    private fun setupGraphDayClickHandlers(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        days: List<DailyForecastGraphRenderer.DayData>,
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
        isEveningModeAtOffset0: Boolean = false,
    ) {
        val zoneIds =
            listOf(
                R.id.graph_day1_zone,
                R.id.graph_day2_zone,
                R.id.graph_day3_zone,
                R.id.graph_day4_zone,
                R.id.graph_day5_zone,
                R.id.graph_day6_zone,
                R.id.graph_day7_zone,
            )

        val midpoint = days.size / 2

        days.forEachIndexed { index, dayData ->
            val zoneId = zoneIds.getOrNull(index) ?: return@forEachIndexed
            views.setViewVisibility(zoneId, View.VISIBLE)

            val dateStr = dayData.date
            val targetDay = LocalDate.parse(dateStr)
            val today = LocalDate.now()
            val isHistory = targetDay.isBefore(today)

            // Determine action: history view vs precipitation view
            // Any day without rain forecast → show history
            // Any day with rain → show precipitation
            // Past days → always show history
            val hasRainForecast = !dayData.rainSummary.isNullOrEmpty()
            val showHistory = isHistory || !hasRainForecast
            
            Log.d(TAG, "Graph click: date=$dateStr, isHistory=$isHistory, hasRainForecast=$hasRainForecast, rainSummary=${dayData.rainSummary}, showHistory=$showHistory")

            val intent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = ACTION_DAY_CLICK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra("date", dateStr)
                putExtra("isHistory", isHistory)
                putExtra("showHistory", showHistory)
                putExtra("index", index + 1)
            }

            if (showHistory) {
                intent.putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
                intent.putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
                intent.putExtra(ForecastHistoryActivity.EXTRA_SOURCE, displaySource.displayName)
            } else {
                val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
                val targetCenter = targetDay.atTime(8, 0)
                val offset = Duration.between(now, targetCenter).toHours().toInt()

                intent.putExtra(EXTRA_TARGET_VIEW, "PRECIPITATION")
                intent.putExtra(EXTRA_HOURLY_OFFSET, offset)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId * 100 + 50 + index,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(zoneId, pendingIntent)
        }

        for (i in days.size until zoneIds.size) {
            views.setViewVisibility(zoneIds[i], View.GONE)
        }
    }
}
