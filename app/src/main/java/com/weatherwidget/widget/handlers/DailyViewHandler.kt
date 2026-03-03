/**
 * Handler for the daily forecast view mode.
 */
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
import com.weatherwidget.data.local.CurrentTempEntity
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.ui.SettingsActivity
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.handlers.WidgetRequestCodes
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min

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

    // Intent actions from WeatherWidgetProvider
    private const val ACTION_NAV_LEFT = "com.weatherwidget.ACTION_NAV_LEFT"
    private const val ACTION_NAV_RIGHT = "com.weatherwidget.ACTION_NAV_RIGHT"
    private const val ACTION_TOGGLE_API = "com.weatherwidget.ACTION_TOGGLE_API"
    private const val ACTION_TOGGLE_VIEW = "com.weatherwidget.ACTION_TOGGLE_VIEW"
    private const val ACTION_TOGGLE_PRECIP = "com.weatherwidget.ACTION_TOGGLE_PRECIP"
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
        weatherList: List<ForecastEntity>,
        forecastSnapshots: Map<String, List<ForecastEntity>>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTemps: List<CurrentTempEntity>,
        dailyActuals: Map<String, ObservationResolver.DailyActual>,
        repository: com.weatherwidget.data.repository.WeatherRepository?,
    ) {
        updateWidget(context, appWidgetManager, appWidgetId, weatherList, forecastSnapshots, hourlyForecasts, currentTemps, dailyActuals, repository, LocalDateTime.now())
    }

    @VisibleForTesting
    suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        weatherList: List<ForecastEntity>,
        forecastSnapshots: Map<String, List<ForecastEntity>>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTemps: List<CurrentTempEntity>,
        dailyActuals: Map<String, ObservationResolver.DailyActual>,
        repository: com.weatherwidget.data.repository.WeatherRepository?,
        now: LocalDateTime
    ) {
        Log.d(TAG, "updateWidget: [START] widgetId=$appWidgetId at time=$now")
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val numColumns = dimensions.cols
        val numRows = dimensions.rows

        val stateManager = WidgetStateManager(context)
        val dateOffset = stateManager.getDateOffset(appWidgetId)

        val isEveningMode = NavigationUtils.isEveningMode(now.toLocalTime())
        
        // Single source of truth for time in this update cycle
        val today = now.toLocalDate()
        val skipHistory = NavigationUtils.shouldSkipHistory(isEveningMode, dateOffset)
        val centerDate = NavigationUtils.getDisplayCenterDate(today, dateOffset, isEveningMode)

        Log.d(
            TAG,
            "updateWidget: widgetId=$appWidgetId, cols=$numColumns, rows=$numRows, offset=$dateOffset, " +
                "isEveningMode=$isEveningMode, weatherCount=${weatherList.size}, actualsCount=${dailyActuals.size}",
        )

        // Setup common click actions
        setupCurrentTempToggle(context, views, appWidgetId)
        setupSettingsShortcut(context, views, appWidgetId)

        // Get the current display source for this widget
        val displaySource = stateManager.getCurrentDisplaySource(appWidgetId)

        // Build weather map: prefer the selected display source, fallback to generic gap
        val weatherByDate =
            weatherList
                .filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
                .groupBy { it.targetDate }
                .mapValues { (_, items) -> items.find { it.source == displaySource.id } ?: items.first() }

        // Set API source indicator
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        views.setTextViewText(R.id.api_source, displaySource.shortDisplayName)

        // Set weather icon
        val lat = weatherList.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
        val lon = weatherList.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
        val isNight = SunPositionUtils.isNight(now, lat, lon)

        val currentHourCondition = DailyViewLogic.getEffectiveCondition(now, todayStr, displaySource, hourlyForecasts, weatherByDate[todayStr])

        val iconRes = WeatherIconMapper.getIconResource(currentHourCondition, isNight)
        views.setImageViewResource(R.id.weather_icon, iconRes)
        views.setViewVisibility(R.id.weather_icon, View.VISIBLE)

        val observedCurrentTemp = ObservationResolver.resolveObservedCurrentTemp(currentTemps, displaySource)

        val currentTempResolution =
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = displaySource,
                hourlyForecasts = hourlyForecasts,
                observedCurrentTemp = observedCurrentTemp?.temperature,
                observedCurrentTempFetchedAt = observedCurrentTemp?.observedAt,
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

        // Show precipitation probability next to current temp when rain is expected
        val todayWeather = weatherByDate[todayStr]
        val precipProb =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = hourlyForecasts,
                displaySource = displaySource,
                fallbackDailyProbability = todayWeather?.precipProbability,
                referenceTime = now,
            )
        if (precipProb != null && precipProb > 0) {
            views.setTextViewText(R.id.precip_probability, "$precipProb%")
            val textSizeSp = HeaderPrecipCalculator.getPrecipTextSize(precipProb)
            views.setTextViewTextSize(R.id.precip_probability, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            views.setViewVisibility(R.id.precip_probability, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.precip_probability, View.GONE)
        }

        // Setup API source toggle click handler
        setupApiToggle(context, views, appWidgetId, numRows)
        
        // Hide history icon and delta badge in daily mode
        views.setViewVisibility(R.id.history_icon, View.GONE)
        views.setViewVisibility(R.id.history_touch_zone, View.GONE)
        views.setViewVisibility(R.id.current_stations_icon, View.GONE)
        views.setViewVisibility(R.id.current_stations_touch_zone, View.GONE)
        views.setViewVisibility(R.id.current_temp_delta, View.GONE)

        // Set up navigation click handlers
        val availableDates = weatherList.map { it.targetDate }.toSet() + dailyActuals.keys
        val sortedDates = availableDates.mapNotNull { try { LocalDate.parse(it) } catch (e: Exception) { null } }.sorted()
        Log.d(TAG, "updateWidget: widgetId=$appWidgetId, widthDp=${dimensions.widthDp}, heightDp=${dimensions.heightDp}, cols=$numColumns, rows=$numRows, offset=$dateOffset, minDate=${sortedDates.firstOrNull()}, maxDate=${sortedDates.lastOrNull()}")
        setupNavigationButtons(context, views, appWidgetId, stateManager, availableDates, numColumns, isEveningMode)

        // Use graph mode for 2+ rows
        val rawRows = (dimensions.heightDp + 25).toFloat() / CELL_HEIGHT_DP
        val useGraph = rawRows >= 1.4f

        if (useGraph) {
            views.setViewVisibility(R.id.text_container, View.GONE)
            views.setViewVisibility(R.id.graph_view, View.VISIBLE)
            views.setViewVisibility(R.id.graph_day_zones, View.VISIBLE)
            views.setViewVisibility(R.id.graph_hour_zones, View.GONE)

            val lat = weatherList.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
            val lon = weatherList.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
            val climateNormals = repository?.getHistoricalNormalsByMonthDay(lat, lon) ?: emptyMap()

            val days = DailyViewLogic.prepareGraphDays(
                now, centerDate, today, weatherByDate, forecastSnapshots,
                numColumns, displaySource, isEveningMode, skipHistory,
                hourlyForecasts, stateManager, appWidgetId, precipProb,
                dailyActuals, climateNormals
            )
            Log.d(TAG, "updateWidget: Graph mode - prepared ${days.size} days for $numColumns columns. Day dates: ${days.map { it.date }}")
            days.forEach { day ->
                Log.d(TAG, "  Day: ${day.date} [${day.label}] High=${day.high}, Low=${day.low}, fcstHigh=${day.forecastHigh}, fcstLow=${day.forecastLow}")
            }

            // Mark rain as shown if today's rain is in the list
            if (days.any { it.isToday && it.rainSummary != null }) {
                stateManager.markRainShown(appWidgetId, todayStr)
            }

            // Render graph
            val widthDp = dimensions.widthDp - 24
            val heightDp = dimensions.heightDp - 16
            val (widthPx, heightPx) = WidgetSizeCalculator.getOptimalBitmapSize(context, widthDp, heightDp)
            val rawWidthPx = WidgetSizeCalculator.dpToPx(context, widthDp).coerceAtLeast(1)
            val rawHeightPx = WidgetSizeCalculator.dpToPx(context, heightDp).coerceAtLeast(1)
            val bitmapScale = min(widthPx.toFloat() / rawWidthPx.toFloat(), heightPx.toFloat() / rawHeightPx.toFloat())

            val bitmap = DailyForecastGraphRenderer.renderGraph(context, days, widthPx, heightPx, bitmapScale, days.size)
            views.setImageViewBitmap(R.id.graph_view, bitmap)

            setupGraphDayClickHandlers(context, views, appWidgetId, now, days, lat, lon, displaySource)
        } else {
            views.setViewVisibility(R.id.text_container, View.VISIBLE)
            views.setViewVisibility(R.id.graph_view, View.GONE)
            views.setViewVisibility(R.id.graph_day_zones, View.GONE)
            views.setViewVisibility(R.id.graph_hour_zones, View.GONE)

            val visibleDaysInfo = updateTextMode(
                context, views, now, centerDate, today, weatherByDate,
                hourlyForecasts, numColumns, displaySource, skipHistory,
                stateManager, appWidgetId, precipProb, dailyActuals
            )

            setupTextDayClickHandlers(context, views, appWidgetId, now, visibleDaysInfo, lat, lon, displaySource)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun setupCurrentTempToggle(context: Context, views: RemoteViews, appWidgetId: Int) {
        val toggleIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_VIEW
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context, WidgetRequestCodes.viewToggle(appWidgetId), toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.current_temp, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.current_temp_zone, togglePendingIntent)

        val precipIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_PRECIP
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val precipPendingIntent = PendingIntent.getBroadcast(
            context, WidgetRequestCodes.precipToggle(appWidgetId), precipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.precip_probability, precipPendingIntent)
        views.setOnClickPendingIntent(R.id.precip_touch_zone, precipPendingIntent)
    }

    private fun setupSettingsShortcut(context: Context, views: RemoteViews, appWidgetId: Int) {
        val settingsIntent = Intent(context, SettingsActivity::class.java)
        val settingsPendingIntent = PendingIntent.getActivity(
            context, WidgetRequestCodes.settings(appWidgetId), settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.settings_icon, settingsPendingIntent)
        views.setOnClickPendingIntent(R.id.settings_touch_zone, settingsPendingIntent)
    }

    private fun setupApiToggle(context: Context, views: RemoteViews, appWidgetId: Int, numRows: Int) {
        val toggleIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_API
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context, WidgetRequestCodes.apiToggle(appWidgetId), toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.api_source_container, togglePendingIntent)

        val textSizeSp = when {
            numRows >= 3 -> 18f
            numRows >= 2 -> 16f
            else -> 14f
        }
        views.setTextViewTextSize(R.id.api_source, TypedValue.COMPLEX_UNIT_SP, textSizeSp)
    }

    private fun setupNavigationButtons(
        context: Context, views: RemoteViews, appWidgetId: Int,
        stateManager: WidgetStateManager, availableDates: Set<String>,
        numColumns: Int, isEveningMode: Boolean
    ) {
        val today = LocalDate.now()
        val sortedDates = availableDates.map { LocalDate.parse(it) }.sorted()
        val minDate = sortedDates.firstOrNull()
        val maxDate = sortedDates.lastOrNull()

        val (leftmost, _) = NavigationUtils.getVisibleDateRange(today, stateManager.getDateOffset(appWidgetId) - 1, numColumns, isEveningMode)
        val (_, rightmost) = NavigationUtils.getVisibleDateRange(today, stateManager.getDateOffset(appWidgetId) + 1, numColumns, isEveningMode)

        val canLeft = minDate != null && !minDate.isAfter(leftmost)
        val canRight = maxDate != null && !maxDate.isBefore(rightmost)
        
        Log.d(TAG, "setupNavigationButtons: id=$appWidgetId, leftmostVisibleIfNavLeft=$leftmost, minAvailableDate=$minDate, canLeft=$canLeft")
        Log.d(TAG, "setupNavigationButtons: id=$appWidgetId, rightmostVisibleIfNavRight=$rightmost, maxAvailableDate=$maxDate, canRight=$canRight")

        // Always show the left arrow
        views.setViewVisibility(R.id.nav_left, View.VISIBLE)
        views.setViewVisibility(R.id.nav_left_zone, View.VISIBLE)
        
        if (canLeft) {
            val leftIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_NAV_LEFT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val leftPendingIntent = PendingIntent.getBroadcast(
                context, WidgetRequestCodes.navLeft(appWidgetId), leftIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.nav_left, toastPendingIntent)
            views.setOnClickPendingIntent(R.id.nav_left_zone, toastPendingIntent)
        }

        // Always show the right arrow
        views.setViewVisibility(R.id.nav_right, View.VISIBLE)
        views.setViewVisibility(R.id.nav_right_zone, View.VISIBLE)

        if (canRight) {
            val rightIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_NAV_RIGHT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val rightPendingIntent = PendingIntent.getBroadcast(
                context, WidgetRequestCodes.navRight(appWidgetId), rightIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.nav_right, toastPendingIntent)
            views.setOnClickPendingIntent(R.id.nav_right_zone, toastPendingIntent)
        }
    }

    private fun updateTextMode(
        context: Context, views: RemoteViews, now: LocalDateTime, centerDate: LocalDate,
        today: LocalDate, weatherByDate: Map<String, ForecastEntity>,
        hourlyForecasts: List<HourlyForecastEntity>, numColumns: Int,
        displaySource: WeatherSource, skipHistory: Boolean,
        stateManager: WidgetStateManager?, appWidgetId: Int,
        todayNext8HourPrecipProbability: Int?,
        dailyActuals: Map<String, ObservationResolver.DailyActual> = emptyMap()
    ): List<Triple<Int, String, Boolean>> {
        val dayDataList = DailyViewLogic.prepareTextDays(
            now, centerDate, today, weatherByDate, hourlyForecasts, numColumns,
            displaySource, skipHistory, stateManager, appWidgetId, todayNext8HourPrecipProbability, dailyActuals
        )

        val dayIds = listOf(
            DayIds(R.id.day1_container, R.id.day1_label, R.id.day1_icon, R.id.day1_high, R.id.day1_low, R.id.day1_rain),
            DayIds(R.id.day2_container, R.id.day2_label, R.id.day2_icon, R.id.day2_high, R.id.day2_low, R.id.day2_rain),
            DayIds(R.id.day3_container, R.id.day3_label, R.id.day3_icon, R.id.day3_high, R.id.day3_low, R.id.day3_rain),
            DayIds(R.id.day4_container, R.id.day4_label, R.id.day4_icon, R.id.day4_high, R.id.day4_low, R.id.day4_rain),
            DayIds(R.id.day5_container, R.id.day5_label, R.id.day5_icon, R.id.day5_high, R.id.day5_low, R.id.day5_rain),
            DayIds(R.id.day6_container, R.id.day6_label, R.id.day6_icon, R.id.day6_high, R.id.day6_low, R.id.day6_rain),
            DayIds(R.id.day7_container, R.id.day7_label, R.id.day7_icon, R.id.day7_high, R.id.day7_low, R.id.day7_rain),
        )

        dayDataList.forEachIndexed { index, data ->
            val ids = dayIds[index]
            if (data.isVisible) {
                views.setViewVisibility(ids.container, View.VISIBLE)
                populateDay(context, views, now, ids, data, hourlyForecasts, displaySource)
            } else {
                views.setViewVisibility(ids.container, View.GONE)
            }
        }

        if (dayDataList.any { it.isToday && it.rainSummary != null }) {
            stateManager?.markRainShown(appWidgetId, today.format(DateTimeFormatter.ISO_LOCAL_DATE))
        }

        return dayDataList.filter { it.isVisible }.map { Triple(it.dayIndex, it.dateStr, it.hasRainForecast) }
    }

    private fun populateDay(
        context: Context, views: RemoteViews, now: LocalDateTime,
        ids: DayIds, data: DailyViewLogic.TextDayData,
        hourlyForecasts: List<HourlyForecastEntity>, displaySource: WeatherSource
    ) {
        views.setTextViewText(ids.label, data.label)
        
        val todayStr = now.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val currentHourCondition = if (data.isToday) {
            DailyViewLogic.getEffectiveCondition(now, todayStr, displaySource, hourlyForecasts, data.weather)
        } else data.weather?.condition

        val iconRes = WeatherIconMapper.getIconResource(currentHourCondition)
        views.setImageViewResource(ids.icon, iconRes)

        if (!WeatherIconMapper.isRainy(iconRes) && !WeatherIconMapper.isMixed(iconRes)) {
            val tintColor = if (WeatherIconMapper.isSunny(iconRes)) {
                context.getColor(R.color.sunny_yellow)
            } else {
                context.getColor(R.color.weather_icon_tint_default)
            }
            views.setInt(ids.icon, "setColorFilter", tintColor)
        }

        views.setViewVisibility(ids.icon, View.VISIBLE)
        views.setTextViewText(ids.high, data.highLabel ?: "--°")
        views.setTextViewText(ids.low, data.lowLabel ?: "--°")

        if (data.showRain && !data.rainSummary.isNullOrEmpty()) {
            views.setTextViewText(ids.rain, "💧 ${data.rainSummary}")
            views.setViewVisibility(ids.rain, View.VISIBLE)
        } else {
            views.setViewVisibility(ids.rain, View.GONE)
        }
    }

    @VisibleForTesting
    internal fun buildDayClickIntent(
        context: Context, appWidgetId: Int, dayIndex: Int, dateStr: String,
        hasRainForecast: Boolean, lat: Double, lon: Double,
        displaySource: WeatherSource, now: LocalDateTime = LocalDateTime.now(),
    ): Intent {
        val targetDay = LocalDate.parse(dateStr)
        val isHistory = targetDay.isBefore(now.toLocalDate())
        val showHistory = DayClickHelper.shouldShowHistory(isHistory)

        return Intent(context, WeatherWidgetProvider::class.java).apply {
            action = ACTION_DAY_CLICK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra("date", dateStr)
            putExtra("isHistory", isHistory)
            putExtra("showHistory", showHistory)
            putExtra("index", dayIndex)
            putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
            putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
            putExtra(ForecastHistoryActivity.EXTRA_SOURCE, displaySource.displayName)

            if (!showHistory) {
                val targetMode = DayClickHelper.resolveTargetViewMode(hasRainForecast)
                val offset = DayClickHelper.calculatePrecipitationOffset(now, targetDay)
                putExtra(EXTRA_TARGET_VIEW, targetMode.name)
                putExtra(EXTRA_HOURLY_OFFSET, offset)
            }
        }
    }

    private fun setupTextDayClickHandlers(
        context: Context, views: RemoteViews, appWidgetId: Int, now: LocalDateTime,
        visibleDays: List<Triple<Int, String, Boolean>>, lat: Double, lon: Double, displaySource: WeatherSource
    ) {
        val containerIds = listOf(R.id.day1_container, R.id.day2_container, R.id.day3_container, R.id.day4_container, R.id.day5_container, R.id.day6_container, R.id.day7_container)
        visibleDays.forEach { (dayIndex, dateStr, hasRainForecast) ->
            val intent = buildDayClickIntent(context, appWidgetId, dayIndex, dateStr, hasRainForecast, lat, lon, displaySource, now)
            val pendingIntent = PendingIntent.getBroadcast(context, WidgetRequestCodes.dayClick(appWidgetId, dayIndex), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(containerIds[dayIndex - 1], pendingIntent)
        }
    }

    private fun setupGraphDayClickHandlers(
        context: Context, views: RemoteViews, appWidgetId: Int, now: LocalDateTime,
        days: List<DailyForecastGraphRenderer.DayData>, lat: Double, lon: Double, displaySource: WeatherSource
    ) {
        val zoneIds = listOf(
            R.id.graph_day1_zone, R.id.graph_day2_zone, R.id.graph_day3_zone, R.id.graph_day4_zone,
            R.id.graph_day5_zone, R.id.graph_day6_zone, R.id.graph_day7_zone, R.id.graph_day8_zone,
            R.id.graph_day9_zone, R.id.graph_day10_zone
        )
        days.forEachIndexed { index, dayData ->
            val zoneId = zoneIds.getOrNull(index) ?: return@forEachIndexed
            views.setViewVisibility(zoneId, View.VISIBLE)
            val intent = buildDayClickIntent(context, appWidgetId, index + 1, dayData.date, dayData.hasRainForecast, lat, lon, displaySource, now)
            val pendingIntent = PendingIntent.getBroadcast(context, WidgetRequestCodes.graphClick(appWidgetId, index), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(zoneId, pendingIntent)
        }
        for (i in days.size until zoneIds.size) views.setViewVisibility(zoneIds[i], View.GONE)
    }
}
