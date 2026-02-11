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
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.AccuracyDisplayMode
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import java.time.LocalDateTime
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

        Log.d(
            TAG,
            "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, offset=$dateOffset, weatherCount=${weatherList.size}",
        )

        // Setup current temp click to toggle view mode
        setupCurrentTempToggle(context, views, appWidgetId)

        val today = LocalDate.now()
        val centerDate = today.plusDays(dateOffset.toLong())

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
        setupNavigationButtons(context, views, appWidgetId, stateManager, availableDates, numColumns)

        // Use graph mode for 2+ rows
        val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
        val useGraph = rawRows >= 1.4f

        if (useGraph) {
            views.setViewVisibility(R.id.text_container, View.GONE)
            views.setViewVisibility(R.id.graph_view, View.VISIBLE)
            views.setViewVisibility(R.id.graph_day_zones, View.VISIBLE)

            // Build day data for graph with offset
            val days =
                buildDayDataList(
                    centerDate,
                    today,
                    weatherByDate,
                    forecastSnapshots,
                    numColumns,
                    accuracyMode,
                    displaySource,
                )

            // Use actual widget dimensions for bitmap to match ImageView size
            val widthDp = dimensions.widthDp - 24
            val heightDp = dimensions.heightDp - 16

            val (widthPx, heightPx) = WidgetSizeCalculator.getOptimalBitmapSize(context, widthDp, heightDp)

            // Render graph
            val bitmap = DailyForecastGraphRenderer.renderGraph(context, days, widthPx, heightPx)
            views.setImageViewBitmap(R.id.graph_view, bitmap)

            // Setup per-day click handlers for graph mode
            setupGraphDayClickHandlers(context, views, appWidgetId, days, lat, lon, displaySource)
        } else {
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)
            views.setViewVisibility(R.id.graph_day_zones, View.GONE)

            // Text mode - set visibility and populate
            val visibleDates = updateTextMode(views, centerDate, today, weatherByDate, numColumns)

            // Setup per-day click handlers for text mode
            setupTextDayClickHandlers(context, views, appWidgetId, visibleDates, lat, lon, displaySource)
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
        val currentCenterDate = today.plusDays(currentOffset.toLong())

        val sortedDates = availableDates.map { LocalDate.parse(it) }.sorted()
        val minDate = sortedDates.firstOrNull()
        val maxDate = sortedDates.lastOrNull()

        val minOffset = NavigationUtils.getMinOffset(numColumns)
        val maxOffset = NavigationUtils.getMaxOffset(numColumns)

        val newLeftmost = currentCenterDate.minusDays(1).plusDays(minOffset.toLong())
        val canLeft = minDate != null && !minDate.isAfter(newLeftmost)

        val newRightmost = currentCenterDate.plusDays(1).plusDays(maxOffset.toLong())
        val canRight = maxDate != null && !maxDate.isBefore(newRightmost)

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
    ): List<DailyForecastGraphRenderer.DayData> {
        val days = mutableListOf<DailyForecastGraphRenderer.DayData>()

        val dayOffsets = NavigationUtils.getDayOffsets(numColumns)

        dayOffsets.forEach { offset ->
            val date = centerDate.plusDays(offset)
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val weather = weatherByDate[dateStr]

            if (weather == null || (weather.highTemp == null && weather.lowTemp == null)) {
                return@forEach
            }

            val forecasts = forecastSnapshots[dateStr] ?: emptyList()

            val forecast =
                forecasts.find { it.source == displaySource.id }
                    ?: forecasts.find { it.source == WeatherSource.GENERIC_GAP.id }

            val label =
                when {
                    date == today -> "Today"
                    else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }

            val isPastDate = date.isBefore(today)
            val showComparison = isPastDate && forecast != null && accuracyMode != AccuracyDisplayMode.NONE

            val iconRes = WeatherIconMapper.getIconResource(weather.condition)
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

            days.add(
                DailyForecastGraphRenderer.DayData(
                    date = dateStr,
                    label = label,
                    high = weather.highTemp,
                    low = weather.lowTemp,
                    iconRes = iconRes,
                    isSunny = isSunny,
                    isRainy = isRainy,
                    isMixed = isMixed,
                    isToday = date == today,
                    isPast = isPastDate,
                    isClimateNormal = weather.isClimateNormal,
                    forecastHigh = if (showComparison) forecast?.highTemp else null,
                    forecastLow = if (showComparison) forecast?.lowTemp else null,
                    forecastSource = if (showComparison) displaySource else null,
                    accuracyMode = if (showComparison) accuracyMode else AccuracyDisplayMode.NONE,
                ),
            )
        }

        return days
    }

    private fun updateTextMode(
        views: RemoteViews,
        centerDate: LocalDate,
        today: LocalDate,
        weatherByDate: Map<String, WeatherEntity>,
        numColumns: Int,
    ): List<Pair<Int, String>> {
        val day1Date = centerDate.minusDays(1)
        val day2Date = centerDate
        val day3Date = centerDate.plusDays(1)
        val day4Date = centerDate.plusDays(2)
        val day5Date = centerDate.plusDays(3)
        val day6Date = centerDate.plusDays(4)

        val day1Str = day1Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day2Str = day2Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day3Str = day3Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day4Str = day4Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day5Str = day5Date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day6Str = day6Date.format(DateTimeFormatter.ISO_LOCAL_DATE)

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

        when {
            numColumns >= 6 -> {
                views.setViewVisibility(R.id.day1_container, if (hasDay1) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day4_container, if (hasDay4) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day5_container, if (hasDay5) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day6_container, if (hasDay6) View.VISIBLE else View.GONE)
            }
            numColumns == 5 -> {
                views.setViewVisibility(R.id.day1_container, if (hasDay1) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day4_container, if (hasDay4) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day5_container, if (hasDay5) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day6_container, View.GONE)
            }
            numColumns == 4 -> {
                views.setViewVisibility(R.id.day1_container, if (hasDay1) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day4_container, if (hasDay4) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day5_container, View.GONE)
                views.setViewVisibility(R.id.day6_container, View.GONE)
            }
            numColumns == 3 -> {
                views.setViewVisibility(R.id.day1_container, if (hasDay1) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day4_container, View.GONE)
                views.setViewVisibility(R.id.day5_container, View.GONE)
                views.setViewVisibility(R.id.day6_container, View.GONE)
            }
            numColumns == 2 -> {
                views.setViewVisibility(R.id.day1_container, View.GONE)
                views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day3_container, if (hasDay3) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day4_container, View.GONE)
                views.setViewVisibility(R.id.day5_container, View.GONE)
                views.setViewVisibility(R.id.day6_container, View.GONE)
            }
            else -> {
                views.setViewVisibility(R.id.day1_container, View.GONE)
                views.setViewVisibility(R.id.day2_container, if (hasDay2) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.day3_container, View.GONE)
                views.setViewVisibility(R.id.day4_container, View.GONE)
                views.setViewVisibility(R.id.day5_container, View.GONE)
                views.setViewVisibility(R.id.day6_container, View.GONE)
            }
        }

        fun getLabelForDate(date: LocalDate): String {
            return if (date == today) {
                "Today"
            } else {
                date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            }
        }

        if (hasDay1) {
            populateDay(
                views,
                R.id.day1_label,
                R.id.day1_icon,
                R.id.day1_high,
                R.id.day1_low,
                getLabelForDate(day1Date),
                weatherByDate[day1Str],
            )
        }
        if (hasDay2) {
            populateDay(
                views,
                R.id.day2_label,
                R.id.day2_icon,
                R.id.day2_high,
                R.id.day2_low,
                getLabelForDate(day2Date),
                weatherByDate[day2Str],
            )
        }
        if (hasDay3) {
            populateDay(
                views,
                R.id.day3_label,
                R.id.day3_icon,
                R.id.day3_high,
                R.id.day3_low,
                getLabelForDate(day3Date),
                weatherByDate[day3Str],
            )
        }
        if (hasDay4) {
            populateDay(
                views,
                R.id.day4_label,
                R.id.day4_icon,
                R.id.day4_high,
                R.id.day4_low,
                getLabelForDate(day4Date),
                weatherByDate[day4Str],
            )
        }
        if (hasDay5) {
            populateDay(
                views,
                R.id.day5_label,
                R.id.day5_icon,
                R.id.day5_high,
                R.id.day5_low,
                getLabelForDate(day5Date),
                weatherByDate[day5Str],
            )
        }
        if (hasDay6) {
            populateDay(
                views,
                R.id.day6_label,
                R.id.day6_icon,
                R.id.day6_high,
                R.id.day6_low,
                getLabelForDate(day6Date),
                weatherByDate[day6Str],
            )
        }

        val visibleDays = mutableListOf<Pair<Int, String>>()
        if (hasDay1) visibleDays.add(1 to day1Str)
        if (hasDay2) visibleDays.add(2 to day2Str)
        if (hasDay3) visibleDays.add(3 to day3Str)
        if (hasDay4) visibleDays.add(4 to day4Str)
        if (hasDay5) visibleDays.add(5 to day5Str)
        if (hasDay6) visibleDays.add(6 to day6Str)
        return visibleDays
    }

    private fun populateDay(
        views: RemoteViews,
        labelId: Int,
        iconId: Int,
        highId: Int,
        lowId: Int,
        label: String,
        weather: WeatherEntity?,
    ) {
        views.setTextViewText(labelId, label)

        val iconRes = WeatherIconMapper.getIconResource(weather?.condition)
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
    }

    private fun setupTextDayClickHandlers(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        visibleDays: List<Pair<Int, String>>,
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
    ) {
        val containerIds =
            listOf(
                R.id.day1_container,
                R.id.day2_container,
                R.id.day3_container,
                R.id.day4_container,
                R.id.day5_container,
                R.id.day6_container,
            )

        val midpoint = visibleDays.size / 2

        visibleDays.forEachIndexed { index, (dayIndex, dateStr) ->
            val containerId = containerIds[dayIndex - 1]

            val intent =
                if (index < midpoint) {
                    Intent(context, ForecastHistoryActivity::class.java).apply {
                        putExtra(ForecastHistoryActivity.EXTRA_TARGET_DATE, dateStr)
                        putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
                        putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
                        putExtra(ForecastHistoryActivity.EXTRA_SOURCE, displaySource.displayName)
                    }
                } else {
                    Intent(context, com.weatherwidget.ui.SettingsActivity::class.java)
                }

            val pendingIntent =
                PendingIntent.getActivity(
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
    ) {
        val zoneIds =
            listOf(
                R.id.graph_day1_zone,
                R.id.graph_day2_zone,
                R.id.graph_day3_zone,
                R.id.graph_day4_zone,
                R.id.graph_day5_zone,
                R.id.graph_day6_zone,
            )

        val midpoint = days.size / 2

        days.forEachIndexed { index, dayData ->
            val zoneId = zoneIds.getOrNull(index) ?: return@forEachIndexed
            views.setViewVisibility(zoneId, View.VISIBLE)

            val dateStr = dayData.date

            val intent =
                if (index < midpoint) {
                    Intent(context, ForecastHistoryActivity::class.java).apply {
                        putExtra(ForecastHistoryActivity.EXTRA_TARGET_DATE, dateStr)
                        putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
                        putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
                        putExtra(ForecastHistoryActivity.EXTRA_SOURCE, displaySource.displayName)
                    }
                } else {
                    Intent(context, com.weatherwidget.ui.SettingsActivity::class.java)
                }

            val pendingIntent =
                PendingIntent.getActivity(
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
