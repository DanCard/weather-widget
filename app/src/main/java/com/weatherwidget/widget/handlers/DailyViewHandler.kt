package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.ui.SettingsActivity
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.util.RainAnalyzer
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.WeatherTimeUtils
import java.time.LocalTime
import com.weatherwidget.widget.AccuracyDisplayMode
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

/**
 * Handler for the daily forecast view mode.
 */
object DailyViewHandler : WidgetViewHandler {
    private const val TAG = "DailyViewHandler"
    private const val CELL_HEIGHT_DP = 90
    private data class DayIds(
        val container: Int,
        val label: Int,
        val icon: Int,
        val high: Int,
        val low: Int,
        val rain: Int,
    )

    private data class DaySlot(
        val dayIndex: Int, // 1..7
        val date: LocalDate,
        val dateStr: String,
        val hasData: Boolean,
        val ids: DayIds,
    )

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
        setupSettingsShortcut(context, views, appWidgetId)

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
        val precipProb =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
                fallbackDailyProbability = todayWeather?.precipProbability,
                now = now,
            )
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
            views.setViewVisibility(R.id.graph_hour_zones, View.GONE)

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
                    stateManager,
                    appWidgetId,
                    precipProb,
                )

            // Use actual widget dimensions for bitmap to match ImageView size
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

            // Render graph
            val bitmap = DailyForecastGraphRenderer.renderGraph(context, days, widthPx, heightPx, bitmapScale)
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
            )
        } else {
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)
            views.setViewVisibility(R.id.graph_day_zones, View.GONE)
            views.setViewVisibility(R.id.graph_hour_zones, View.GONE)

            // Text mode - set visibility and populate
            val visibleDates = updateTextMode(views, centerDate, today, weatherByDate, hourlyForecasts, numColumns, displaySource, skipHistory, stateManager, appWidgetId, precipProb)

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
        val currentHourKey = WeatherTimeUtils.toHourlyForecastKey(LocalDateTime.now())

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

    private fun setupSettingsShortcut(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
    ) {
        val settingsIntent = Intent(context, SettingsActivity::class.java)
        val settingsPendingIntent =
            PendingIntent.getActivity(
                context,
                appWidgetId * 2 + 900,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.settings_icon, settingsPendingIntent)
        views.setOnClickPendingIntent(R.id.settings_touch_zone, settingsPendingIntent)
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
        stateManager: WidgetStateManager? = null,
        appWidgetId: Int = 0,
        todayNext8HourPrecipProbability: Int? = null,
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

            val label = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

            val isPastDate = date.isBefore(today)
            val isToday = date == today

            // Show forecast comparison for:
            // 1. Past dates (normal history mode)
            // 2. Today in evening mode (show what was predicted vs estimated actuals)
            val showComparison = (isPastDate || (isToday && isEveningMode)) &&
                forecast != null && accuracyMode != AccuracyDisplayMode.NONE

            // In evening mode for today, estimate actuals from hourly data.
            // Also extract full-day forecast for Today's triple-line representation.
            var finalHigh: Float? = weather.highTemp?.toFloat()
            var finalLow: Float? = weather.lowTemp?.toFloat()
            var fHigh: Float? = null
            var fLow: Float? = null

            if (isToday && hourlyForecasts.isNotEmpty()) {
                val tripleValues = com.weatherwidget.util.DailyActualsEstimator.calculateTodayTripleLineValues(
                    hourlyForecasts, today, LocalDateTime.now(), displaySource, weather
                )
                // Use observed range for the primary bars (yellow/orange)
                finalHigh = tripleValues.observedHigh
                finalLow = tripleValues.observedLow
                // Use full-day forecast for the comparison bar (blue)
                fHigh = tripleValues.forecastHigh
                fLow = tripleValues.forecastLow
            } else if (showComparison && forecast != null) {
                // Standard history comparison
                fHigh = forecast.highTemp?.toFloat()
                fLow = forecast.lowTemp?.toFloat()
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

            // Get rain summary for all future days (determines click behavior)
            // Suppress today's rain if already shown once this day
            val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val rawRainSummary = if (!isPastDate) {
                RainAnalyzer.getRainSummary(hourlyForecasts, date, displaySource.id, LocalDateTime.now())
            } else {
                null
            }
            val hasRainForecast = DayClickHelper.hasRainForecast(
                rawRainSummary,
                if (isToday) todayNext8HourPrecipProbability else weather.precipProbability,
            )
            val nearTermLimit = today.plusDays(2)
            val rainSummary = if (!date.isBefore(today) && !date.isAfter(nearTermLimit)) {
                if (isToday && rawRainSummary != null && stateManager != null) {
                    if (stateManager.wasRainShownToday(appWidgetId, todayStr)) {
                        null
                    } else {
                        stateManager.markRainShown(appWidgetId, todayStr)
                        rawRainSummary
                    }
                } else {
                    rawRainSummary
                }
            } else {
                null
            }

            days.add(
                DailyForecastGraphRenderer.DayData(
                    date = dateStr,
                    label = label,
                    high = finalHigh,
                    low = finalLow,
                    iconRes = iconRes,
                    isSunny = isSunny,
                    isRainy = isRainy,
                    isMixed = isMixed,
                    isToday = isToday,
                    isPast = isPastDate,
                    isClimateNormal = weather.isClimateNormal,
                    forecastHigh = fHigh,
                    forecastLow = fLow,
                    forecastSource = if (showComparison || isToday) displaySource else null,
                    accuracyMode = if (showComparison || isToday) accuracyMode else AccuracyDisplayMode.NONE,
                    rainSummary = rainSummary,
                    dailyPrecipProbability = if (isToday) todayNext8HourPrecipProbability else weather.precipProbability,
                    hasRainForecast = hasRainForecast,
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
        hourlyForecasts: List<HourlyForecastEntity>,
        numColumns: Int,
        displaySource: WeatherSource,
        skipHistory: Boolean = false,
        stateManager: WidgetStateManager? = null,
        appWidgetId: Int = 0,
        todayNext8HourPrecipProbability: Int? = null,
    ): List<Triple<Int, String, Boolean>> {  // dayIndex, dateStr, hasRainForecast
        val effectiveCenter = if (skipHistory) centerDate.plusDays(1) else centerDate

        val dayIds =
            listOf(
                DayIds(R.id.day1_container, R.id.day1_label, R.id.day1_icon, R.id.day1_high, R.id.day1_low, R.id.day1_rain),
                DayIds(R.id.day2_container, R.id.day2_label, R.id.day2_icon, R.id.day2_high, R.id.day2_low, R.id.day2_rain),
                DayIds(R.id.day3_container, R.id.day3_label, R.id.day3_icon, R.id.day3_high, R.id.day3_low, R.id.day3_rain),
                DayIds(R.id.day4_container, R.id.day4_label, R.id.day4_icon, R.id.day4_high, R.id.day4_low, R.id.day4_rain),
                DayIds(R.id.day5_container, R.id.day5_label, R.id.day5_icon, R.id.day5_high, R.id.day5_low, R.id.day5_rain),
                DayIds(R.id.day6_container, R.id.day6_label, R.id.day6_icon, R.id.day6_high, R.id.day6_low, R.id.day6_rain),
                DayIds(R.id.day7_container, R.id.day7_label, R.id.day7_icon, R.id.day7_high, R.id.day7_low, R.id.day7_rain),
            )

        fun hasCompleteData(dateStr: String): Boolean {
            val weather = weatherByDate[dateStr] ?: return false
            return weather.highTemp != null && weather.lowTemp != null
        }

        val daySlots =
            listOf(
                effectiveCenter.minusDays(1),
                effectiveCenter,
                effectiveCenter.plusDays(1),
                effectiveCenter.plusDays(2),
                effectiveCenter.plusDays(3),
                effectiveCenter.plusDays(4),
                effectiveCenter.plusDays(5),
            ).mapIndexed { index, date ->
                val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                DaySlot(
                    dayIndex = index + 1,
                    date = date,
                    dateStr = dateStr,
                    hasData = hasCompleteData(dateStr),
                    ids = dayIds[index],
                )
            }

        fun slotVisibleByColumns(index: Int): Boolean {
            return when {
                numColumns >= 7 -> true
                numColumns == 6 -> index <= 5
                numColumns == 5 -> index <= 4
                numColumns == 4 -> index <= 3
                numColumns == 3 -> index <= 2
                numColumns == 2 -> index in 1..2
                else -> index == 1
            }
        }

        daySlots.forEachIndexed { index, slot ->
            val shouldShow = slotVisibleByColumns(index) && slot.hasData
            views.setViewVisibility(slot.ids.container, if (shouldShow) View.VISIBLE else View.GONE)
        }

        fun getLabelForDate(date: LocalDate): String {
            return date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }

        // Check rain forecast for all visible days.
        val now = LocalDateTime.now()
        val nearTermLimit = today.plusDays(2)
        fun isNearTerm(date: LocalDate) = !date.isBefore(today) && !date.isAfter(nearTermLimit)
        // Suppress today's rain summary if already shown once this day
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        fun suppressIfAlreadyShown(date: LocalDate, summary: String?): String? {
            if (date != today || stateManager == null) return summary
            if (summary == null) return null
            if (stateManager.wasRainShownToday(appWidgetId, todayStr)) return null
            stateManager.markRainShown(appWidgetId, todayStr)
            return summary
        }

        val rawRainSummaries =
            daySlots.map { slot ->
                if (slot.hasData) {
                    RainAnalyzer.getRainSummary(hourlyForecasts, slot.date, displaySource.id, now)
                } else {
                    null
                }
            }

        val displayedRainSummaries =
            daySlots.mapIndexed { index, slot ->
                if (!isNearTerm(slot.date)) {
                    null
                } else {
                    suppressIfAlreadyShown(slot.date, rawRainSummaries[index])
                }
            }

        daySlots.forEachIndexed { index, slot ->
            if (!slot.hasData) return@forEachIndexed
            populateDay(
                views,
                slot.ids.label,
                slot.ids.icon,
                slot.ids.high,
                slot.ids.low,
                slot.ids.rain,
                getLabelForDate(slot.date),
                weatherByDate[slot.dateStr],
                displayedRainSummaries[index],
                isToday = slot.date == today,
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
            )
        }

        // Determine first day with rain (only show rain indicator for first occurrence)
        val firstRainDay = displayedRainSummaries.indexOfFirst { it != null }.let { if (it >= 0) it + 1 else -1 }

        val visibleDays = mutableListOf<Triple<Int, String, Boolean>>()
        fun clickPrecip(slot: DaySlot): Int? {
            return if (slot.date == today) todayNext8HourPrecipProbability else weatherByDate[slot.dateStr]?.precipProbability
        }

        daySlots.forEachIndexed { index, slot ->
            if (!slot.hasData) return@forEachIndexed
            visibleDays.add(
                Triple(
                    slot.dayIndex,
                    slot.dateStr,
                    DayClickHelper.hasRainForecast(rawRainSummaries[index], clickPrecip(slot)),
                ),
            )
        }

        // Update rain visibility - only show for first rainy day
        daySlots.forEachIndexed { index, slot ->
            if (!slot.hasData) return@forEachIndexed
            populateDayRain(views, slot.ids.rain, displayedRainSummaries[index], firstRainDay == slot.dayIndex)
        }

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

    @VisibleForTesting
    internal fun buildDayClickIntent(
        context: Context,
        appWidgetId: Int,
        dayIndex: Int,
        dateStr: String,
        hasRainForecast: Boolean,
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
        now: LocalDateTime = LocalDateTime.now(),
    ): Intent {
        val targetDay = LocalDate.parse(dateStr)
        val isHistory = targetDay.isBefore(now.toLocalDate())
        val navigateToPrecip = DayClickHelper.shouldNavigateToPrecipitation(isHistory, hasRainForecast)
        val showHistory = !navigateToPrecip

        return Intent(context, WeatherWidgetProvider::class.java).apply {
            action = ACTION_DAY_CLICK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra("date", dateStr)
            putExtra("isHistory", isHistory)
            putExtra("showHistory", showHistory)
            putExtra("index", dayIndex)

            if (showHistory) {
                putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
                putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
                putExtra(ForecastHistoryActivity.EXTRA_SOURCE, displaySource.displayName)
            } else {
                val offset = DayClickHelper.calculatePrecipitationOffset(now, targetDay)
                putExtra(EXTRA_TARGET_VIEW, "PRECIPITATION")
                putExtra(EXTRA_HOURLY_OFFSET, offset)
                putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
                putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
            }
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

        visibleDays.forEach { (dayIndex, dateStr, hasRainForecast) ->
            val containerId = containerIds[dayIndex - 1]
            val intent =
                buildDayClickIntent(
                    context = context,
                    appWidgetId = appWidgetId,
                    dayIndex = dayIndex,
                    dateStr = dateStr,
                    hasRainForecast = hasRainForecast,
                    lat = lat,
                    lon = lon,
                    displaySource = displaySource,
                )

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

        days.forEachIndexed { index, dayData ->
            val zoneId = zoneIds.getOrNull(index) ?: return@forEachIndexed
            views.setViewVisibility(zoneId, View.VISIBLE)

            val dateStr = dayData.date
            val hasRainForecast = dayData.hasRainForecast
            val intent =
                buildDayClickIntent(
                    context = context,
                    appWidgetId = appWidgetId,
                    dayIndex = index + 1,
                    dateStr = dateStr,
                    hasRainForecast = hasRainForecast,
                    lat = lat,
                    lon = lon,
                    displaySource = displaySource,
                )

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
