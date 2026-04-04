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
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val ACTION_NAV_LEFT = "com.weatherwidget.ACTION_NAV_LEFT"
private const val ACTION_NAV_RIGHT = "com.weatherwidget.ACTION_NAV_RIGHT"
private const val ACTION_TOGGLE_API = "com.weatherwidget.ACTION_TOGGLE_API"
private const val ACTION_CYCLE_ZOOM = "com.weatherwidget.ACTION_CYCLE_ZOOM"

private val HOUR_ZONE_IDS = listOf(
    R.id.graph_hour_zone_0, R.id.graph_hour_zone_1, R.id.graph_hour_zone_2,
    R.id.graph_hour_zone_3, R.id.graph_hour_zone_4, R.id.graph_hour_zone_5,
    R.id.graph_hour_zone_6, R.id.graph_hour_zone_7, R.id.graph_hour_zone_8,
    R.id.graph_hour_zone_9, R.id.graph_hour_zone_10, R.id.graph_hour_zone_11,
    R.id.graph_hour_zone_12,
)

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
            action = ACTION_CYCLE_ZOOM
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(WeatherWidgetProvider.EXTRA_ZOOM_CENTER_OFFSET, zoneCenterOffset)
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

internal fun setupApiToggle(
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
    views.setOnClickPendingIntent(R.id.api_touch_zone, togglePendingIntent)

    val textSizeSp =
        when {
            numRows >= 3 -> 18f
            numRows >= 2 -> 16f
            else -> 14f
        }
    views.setTextViewTextSize(R.id.api_source, android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
}

internal fun setupHistoryShortcut(
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
    views.setOnClickPendingIntent(R.id.history_touch_zone, pendingIntent)
    views.setOnClickPendingIntent(R.id.history_touch_zone_inline, pendingIntent)
}

internal fun setupHomeShortcut(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
) {
    val homeIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
        action = WidgetIntentRouter.ACTION_SET_VIEW
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        putExtra(WidgetIntentRouter.EXTRA_TARGET_VIEW, ViewMode.DAILY.name)
    }
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        WidgetRequestCodes.home(appWidgetId),
        homeIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    views.setOnClickPendingIntent(R.id.home_icon, pendingIntent)
    views.setOnClickPendingIntent(R.id.home_touch_zone, pendingIntent)
    views.setOnClickPendingIntent(R.id.home_touch_zone_inline, pendingIntent)
}

internal fun setupCurrentStationsShortcut(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
) {
    val obsIntent = Intent(context, WeatherObservationsActivity::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val pendingIntent = PendingIntent.getActivity(
        context,
        WidgetRequestCodes.currentStations(appWidgetId),
        obsIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    views.setOnClickPendingIntent(R.id.current_stations_icon, pendingIntent)
    views.setOnClickPendingIntent(R.id.current_stations_touch_zone, pendingIntent)
    views.setOnClickPendingIntent(R.id.current_stations_touch_zone_inline, pendingIntent)
}

internal fun setupSettingsShortcut(
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

internal fun positionCenterIcons(
    views: RemoteViews,
    widthDp: Int,
    isPrecipVisible: Boolean,
) {
    val useInline = widthDp < 420 && isPrecipVisible
    Log.d("TemperatureTouchTargets", "positionCenterIcons: widthDp=$widthDp isPrecipVisible=$isPrecipVisible useInline=$useInline")
    val floatingVis = if (useInline) View.GONE else View.VISIBLE
    val inlineVis = if (useInline) View.VISIBLE else View.GONE
    for (id in listOf(R.id.home_icon, R.id.home_touch_zone, R.id.history_icon, R.id.history_touch_zone, R.id.current_stations_icon, R.id.current_stations_touch_zone)) {
        views.setViewVisibility(id, floatingVis)
    }
    for (id in listOf(R.id.home_touch_zone_inline, R.id.history_touch_zone_inline, R.id.current_stations_touch_zone_inline)) {
        views.setViewVisibility(id, inlineVis)
    }
}
