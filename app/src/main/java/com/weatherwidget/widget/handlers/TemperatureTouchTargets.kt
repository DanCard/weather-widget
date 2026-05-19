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
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.ui.SettingsActivity
import com.weatherwidget.ui.WeatherObservationsActivity
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter



internal val HOUR_ZONE_IDS = listOf(
    R.id.graph_hour_zone_0, R.id.graph_hour_zone_1, R.id.graph_hour_zone_2,
    R.id.graph_hour_zone_3, R.id.graph_hour_zone_4, R.id.graph_hour_zone_5,
    R.id.graph_hour_zone_6, R.id.graph_hour_zone_7, R.id.graph_hour_zone_8,
    R.id.graph_hour_zone_9, R.id.graph_hour_zone_10, R.id.graph_hour_zone_11,
    R.id.graph_hour_zone_12,
)

/**
 * Sets up per-zone tap handling for the temperature hourly graph body.
 *
 * Body taps in the temperature graph always toggle zoom. Icon-dependent routing is
 * reserved for the bottom icon/label row so graph-body taps do not unexpectedly
 * switch views when they align with cloudy or rainy hours.
 */
internal fun setupZoomTapZones(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    zoom: ZoomLevel,
    hourlyOffset: Int,
) {
    views.setViewVisibility(R.id.graph_hour_zones, View.VISIBLE)
    views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
    views.setOnClickPendingIntent(R.id.graph_view, null)
    views.setOnClickPendingIntent(R.id.graph_body_tap_zone, null)

    HOUR_ZONE_IDS.forEachIndexed { i, zoneId ->
        val zoneCenterOffset = WeatherWidgetProvider.zoneIndexToOffset(i, hourlyOffset, zoom)
        val zoomIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WidgetActions.ACTION_CYCLE_ZOOM
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET, zoneCenterOffset)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            WidgetRequestCodes.cycleZoomZone(appWidgetId, i),
            zoomIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(zoneId, pendingIntent)
    }
}

internal fun setupNavigationButtons(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    stateManager: WidgetStateManager,
) {
    val canLeft = stateManager.canNavigateHourlyLeft(appWidgetId)
    val canRight = stateManager.canNavigateHourlyRight(appWidgetId)

    views.setViewVisibility(R.id.nav_left, View.VISIBLE)
    views.setViewVisibility(R.id.nav_left_zone, View.VISIBLE)

    if (canLeft) {
        val leftIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WidgetActions.ACTION_NAV_LEFT
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
            action = WidgetActions.ACTION_SHOW_TOAST
            putExtra(WidgetActions.EXTRA_TOAST_MESSAGE, "No additional history available")
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val toastPendingIntent = PendingIntent.getBroadcast(
            context, WidgetRequestCodes.navLeft(appWidgetId), toastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.nav_left, toastPendingIntent)
        views.setOnClickPendingIntent(R.id.nav_left_zone, toastPendingIntent)
    }

    views.setViewVisibility(R.id.nav_right, View.VISIBLE)
    views.setViewVisibility(R.id.nav_right_zone, View.VISIBLE)

    if (canRight) {
        val rightIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WidgetActions.ACTION_NAV_RIGHT
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
            action = WidgetActions.ACTION_SHOW_TOAST
            putExtra(WidgetActions.EXTRA_TOAST_MESSAGE, "No more forecast available")
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

internal fun setupApiToggle(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    numRows: Int,
    includeTextMode: Boolean = false,
) {
    val toggleIntent =
        Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WidgetActions.ACTION_TOGGLE_API
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
    views.setOnClickPendingIntent(R.id.api_touch_zone, togglePendingIntent)

    if (includeTextMode) {
        views.setOnClickPendingIntent(R.id.text_mode_api_source_container, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.text_mode_api_touch_zone, togglePendingIntent)
    }

    val textSizeDp = HeaderConstants.apiTextSizeDp(numRows)
    val apiPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, textSizeDp, context.resources.displayMetrics)
    views.setTextViewTextSize(R.id.api_source, android.util.TypedValue.COMPLEX_UNIT_PX, apiPx)
    if (includeTextMode) {
        views.setTextViewTextSize(R.id.text_mode_api_source, android.util.TypedValue.COMPLEX_UNIT_PX, apiPx)
    }
}

internal fun setupDualToggle(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
) {
    val toggleIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
        action = WidgetActions.ACTION_TOGGLE_DUAL_BARS
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        WidgetRequestCodes.dualToggle(appWidgetId),
        toggleIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    views.setOnClickPendingIntent(R.id.dual_touch_zone, pendingIntent)
}

internal fun setupHistoryShortcut(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    centerTime: LocalDateTime,
    hourlyForecasts: List<HourlyForecastEntity>,
    displaySource: WeatherSource,
    setVisibility: Boolean = false,
) {
    val dateStr = centerTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val lat = hourlyForecasts.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
    val lon = hourlyForecasts.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

    val historyIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
        action = WidgetActions.ACTION_DAY_CLICK
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        putExtra("date", dateStr)
        putExtra("showHistory", true)
        putExtra("isHistory", centerTime.toLocalDate().isBefore(LocalDate.now()))
        putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
        putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
        putExtra(ForecastHistoryActivity.EXTRA_SOURCE, displaySource.displayName)
    }

    val pendingIntent = PendingIntent.getBroadcast(
        context,
        WidgetRequestCodes.history(appWidgetId),
        historyIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    views.setOnClickPendingIntent(R.id.history_icon, pendingIntent)
    views.setOnClickPendingIntent(R.id.forecast_history_activity_touch_zone, pendingIntent)
    views.setOnClickPendingIntent(R.id.forecast_history_activity_touch_zone_inline, pendingIntent)
    if (setVisibility) {
        views.setViewVisibility(R.id.history_icon, View.VISIBLE)
        views.setViewVisibility(R.id.forecast_history_activity_touch_zone, View.VISIBLE)
    }
}

internal fun setupHomeShortcut(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    setVisibility: Boolean = false,
) {
    val homeIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
        action = WidgetActions.ACTION_SET_VIEW
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        putExtra(WidgetActions.EXTRA_TARGET_VIEW, ViewMode.DAILY.name)
    }
    val requestCode = WidgetRequestCodes.home(appWidgetId)
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        homeIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    Log.d("HomeShortcut", "setupHomeShortcut: widget=$appWidgetId requestCode=$requestCode setVisibility=$setVisibility -> ACTION_SET_VIEW target=DAILY (bound to home_icon, home_touch_zone, home_touch_zone_inline)")
    views.setOnClickPendingIntent(R.id.home_icon, pendingIntent)
    views.setOnClickPendingIntent(R.id.home_touch_zone, pendingIntent)
    views.setOnClickPendingIntent(R.id.home_touch_zone_inline, pendingIntent)
    if (setVisibility) {
        views.setViewVisibility(R.id.home_icon, View.VISIBLE)
        views.setViewVisibility(R.id.home_touch_zone, View.VISIBLE)
    }
}

internal fun setupWeatherStationsShortcut(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    setVisibility: Boolean = false,
) {
    val obsIntent = Intent(context, WeatherObservationsActivity::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val pendingIntent = PendingIntent.getActivity(
        context,
        WidgetRequestCodes.weatherStations(appWidgetId),
        obsIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    views.setOnClickPendingIntent(R.id.weather_stations_icon, pendingIntent)
    views.setOnClickPendingIntent(R.id.weather_stations_touch_zone, pendingIntent)
    views.setOnClickPendingIntent(R.id.weather_stations_touch_zone_inline, pendingIntent)
    if (setVisibility) {
        views.setViewVisibility(R.id.weather_stations_icon, View.VISIBLE)
        views.setViewVisibility(R.id.weather_stations_touch_zone, View.VISIBLE)
    }
}

internal fun setupSettingsShortcut(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    includeTextMode: Boolean = false,
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
    if (includeTextMode) {
        views.setOnClickPendingIntent(R.id.text_mode_settings_icon, settingsPendingIntent)
        views.setOnClickPendingIntent(R.id.text_mode_settings_touch_zone, settingsPendingIntent)
    }
}

internal fun positionCenterIcons(
    views: RemoteViews,
    widthDp: Int,
    isPrecipVisible: Boolean,
    isToday: Boolean = true,
) {
    val useInline = widthDp < 420
    val floatingVis = if (useInline) View.GONE else View.VISIBLE
    val inlineVis = if (useInline) View.VISIBLE else View.GONE
    Log.d("HomeShortcut", "positionCenterIcons: widthDp=$widthDp isPrecipVisible=$isPrecipVisible useInline=$useInline isToday=$isToday -> home_touch_zone=${if (floatingVis == View.VISIBLE) "VISIBLE" else "GONE"} home_touch_zone_inline=${if (inlineVis == View.VISIBLE) "VISIBLE" else "GONE"}")
    for (id in listOf(R.id.home_icon, R.id.home_touch_zone, R.id.history_icon, R.id.forecast_history_activity_touch_zone, R.id.weather_stations_icon, R.id.weather_stations_touch_zone, R.id.graph_selector_icon, R.id.graph_selector_touch_zone)) {
        views.setViewVisibility(id, floatingVis)
    }
    for (id in listOf(R.id.home_touch_zone_inline, R.id.forecast_history_activity_touch_zone_inline, R.id.weather_stations_touch_zone_inline, R.id.graph_selector_touch_zone_inline)) {
        views.setViewVisibility(id, inlineVis)
    }

    if (!isToday) {
        for (id in listOf(R.id.weather_stations_icon, R.id.weather_stations_touch_zone, R.id.weather_stations_touch_zone_inline)) {
            views.setViewVisibility(id, View.GONE)
        }
    }
}

internal fun setupGraphSelectorShortcut(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    currentViewMode: ViewMode,
    widthDp: Int,
    isPrecipVisible: Boolean,
) {
    val nextView = when (currentViewMode) {
        ViewMode.TEMPERATURE -> ViewMode.CLOUD_COVER
        ViewMode.CLOUD_COVER -> ViewMode.PRECIPITATION
        ViewMode.PRECIPITATION -> ViewMode.TEMPERATURE
        else -> ViewMode.TEMPERATURE
    }
    val emoji = when (currentViewMode) {
        ViewMode.TEMPERATURE -> "\u2601\uFE0F" // Cloud Cover next ☁️
        ViewMode.CLOUD_COVER -> "\uD83C\uDF27\uFE0F" // Precipitation next 🌧️
        ViewMode.PRECIPITATION -> "\uD83C\uDF21\uFE0F" // Temperature next 🌡️
        else -> ""
    }
    HeaderTapTargetHelper.bindGraphSelector(context, views, appWidgetId, nextView)
    views.setTextViewText(R.id.graph_selector_icon, emoji)
    views.setTextViewText(R.id.graph_selector_icon_inline, emoji)

    val useInline = widthDp < 420
    if (useInline) {
        views.setViewVisibility(R.id.graph_selector_icon, View.GONE)
        views.setViewVisibility(R.id.graph_selector_touch_zone, View.GONE)
        views.setViewVisibility(R.id.graph_selector_touch_zone_inline, View.VISIBLE)
    } else {
        views.setViewVisibility(R.id.graph_selector_icon, View.VISIBLE)
        views.setViewVisibility(R.id.graph_selector_touch_zone, View.VISIBLE)
        views.setViewVisibility(R.id.graph_selector_touch_zone_inline, View.GONE)
    }
}
