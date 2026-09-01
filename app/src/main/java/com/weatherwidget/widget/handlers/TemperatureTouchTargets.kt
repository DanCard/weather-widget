package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.BuildConfig
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.ui.SettingsActivity
import com.weatherwidget.ui.WeatherObservationsActivity
import com.weatherwidget.widget.HourlyTouchZoneMapper
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetActionReceiver
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomWindow
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
    zoom: ZoomWindow,
    hourlyOffset: Int,
) {
    views.setViewVisibility(R.id.graph_hour_zones, View.VISIBLE)
    views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
    views.setOnClickPendingIntent(R.id.graph_view, null)
    views.setOnClickPendingIntent(R.id.graph_body_tap_zone, null)

    HOUR_ZONE_IDS.forEachIndexed { i, zoneId ->
        val zoneCenterOffset = HourlyTouchZoneMapper.zoneIndexToOffset(i, hourlyOffset, zoom)
        val zoomIntent = Intent(context, WidgetActionReceiver::class.java).apply {
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
        val leftIntent = Intent(context, WidgetActionReceiver::class.java).apply {
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
        val toastIntent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActions.ACTION_SHOW_TOAST
            putExtra(WidgetActions.EXTRA_TOAST_MESSAGE, context.getString(R.string.widget_nav_no_history))
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
        val rightIntent = Intent(context, WidgetActionReceiver::class.java).apply {
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
        val toastIntent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActions.ACTION_SHOW_TOAST
            putExtra(WidgetActions.EXTRA_TOAST_MESSAGE, context.getString(R.string.widget_nav_no_forecast))
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
    scale: Float = 1.0f,
) {
    val toggleIntent =
        Intent(context, WidgetActionReceiver::class.java).apply {
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
    views.setOnClickPendingIntent(R.id.api_touch_zone, togglePendingIntent)

    if (includeTextMode) {
        views.setOnClickPendingIntent(R.id.text_mode_api_source_container, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.text_mode_api_touch_zone, togglePendingIntent)
    }

    val textSizeDp = HeaderConstants.apiTextSizeDp(numRows)
    val apiPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, textSizeDp * scale, context.resources.displayMetrics)
    views.setTextViewTextSize(R.id.api_source, android.util.TypedValue.COMPLEX_UNIT_PX, apiPx)
    if (includeTextMode) {
        views.setTextViewTextSize(R.id.text_mode_api_source, android.util.TypedValue.COMPLEX_UNIT_PX, apiPx)
    }
}

internal fun setupHistoryShortcut(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    centerTime: LocalDateTime,
    hourlyForecasts: List<HourlyForecastEntity>,
    displaySource: WeatherSource,
    setVisibility: Boolean = false,
    scale: Float = 1.0f,
) {
    // Unlike the sun-position sites, these coordinates leave the widget: they become Intent extras
    // that open ForecastHistoryActivity. A placeholder here would open the history screen at Google
    // HQ, so with no location the shortcut is simply not bound — there is no history to show.
    val lat = hourlyForecasts.firstOrNull()?.locationLat
    val lon = hourlyForecasts.firstOrNull()?.locationLon
    if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return
    setupHistoryShortcutAt(
        context = context,
        views = views,
        appWidgetId = appWidgetId,
        date = centerTime.toLocalDate(),
        lat = lat,
        lon = lon,
        displaySource = displaySource,
        setVisibility = setVisibility,
        scale = scale,
    )
}

/**
 * Binds the forecast-history button for an explicit date and location.
 *
 * The daily view has real coordinates and a target date to hand and no hourly rows to mine them
 * from, so the lat/lon derivation stays in the hourly wrapper above rather than being duplicated.
 */
internal fun setupHistoryShortcutAt(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    date: LocalDate,
    lat: Double,
    lon: Double,
    displaySource: WeatherSource,
    setVisibility: Boolean = false,
    scale: Float = 1.0f,
) {
    val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
    val centerTime = date.atStartOfDay()

    val historyIntent = Intent(context, WidgetActionReceiver::class.java).apply {
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
    HeaderRemoteViewsBinder.bindScaledIcon(
        context = context,
        views = views,
        viewId = R.id.history_icon,
        iconRes = R.drawable.ic_forecast_history_line,
        sizeDp = HeaderConstants.CENTER_ICON_SIZE_DP,
        scale = scale,
        tintColor = 0xAAFFFFFF.toInt()
    )
    HeaderRemoteViewsBinder.bindScaledIcon(
        context = context,
        views = views,
        viewId = R.id.history_icon_inline,
        iconRes = R.drawable.ic_forecast_history_line,
        sizeDp = HeaderConstants.CENTER_ICON_SIZE_DP,
        scale = scale,
        tintColor = 0xAAFFFFFF.toInt()
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
    scale: Float = 1.0f,
) {
    val homeIntent = Intent(context, WidgetActionReceiver::class.java).apply {
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
    HeaderRemoteViewsBinder.bindScaledIcon(
        context = context,
        views = views,
        viewId = R.id.home_icon,
        iconRes = R.drawable.ic_home,
        sizeDp = HeaderConstants.CENTER_ICON_SIZE_DP,
        scale = scale,
        tintColor = 0xAAFFFFFF.toInt()
    )
    HeaderRemoteViewsBinder.bindScaledIcon(
        context = context,
        views = views,
        viewId = R.id.home_icon_inline,
        iconRes = R.drawable.ic_home,
        sizeDp = HeaderConstants.CENTER_ICON_SIZE_DP,
        scale = scale,
        tintColor = 0xAAFFFFFF.toInt()
    )
    views.setOnClickPendingIntent(R.id.home_icon, pendingIntent)
    views.setOnClickPendingIntent(R.id.home_touch_zone, pendingIntent)
    views.setOnClickPendingIntent(R.id.home_touch_zone_inline, pendingIntent)
    if (setVisibility) {
        views.setViewVisibility(R.id.home_icon, View.VISIBLE)
        views.setViewVisibility(R.id.home_touch_zone, View.VISIBLE)
    }
}

/**
 * The DAILY view's home button: same `ic_home` glyph and the same two zones, but the tap resets the
 * display SOURCE instead of the view mode.
 *
 * The daily view is already home in the view sense, so [setupHomeShortcut]'s ACTION_SET_VIEW would
 * be a no-op here. What the button exits is the other axis a widget can be off home on — the API
 * indicator having been tapped away from the preferred source. It is bound only while that is true
 * (see [com.weatherwidget.shared.util.PreferredSourceHome]), so the icon appearing is itself the
 * message that the shown source is not the one the user put first.
 */
internal fun setupSourceHomeShortcut(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    scale: Float = 1.0f,
) {
    val resetIntent = Intent(context, WidgetActionReceiver::class.java).apply {
        action = WidgetActions.ACTION_RESET_SOURCE
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        WidgetRequestCodes.sourceHome(appWidgetId),
        resetIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    for (viewId in listOf(R.id.home_icon, R.id.home_icon_inline)) {
        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = viewId,
            iconRes = R.drawable.ic_home,
            sizeDp = HeaderConstants.CENTER_ICON_SIZE_DP,
            scale = scale,
            tintColor = 0xAAFFFFFF.toInt(),
        )
    }
    views.setOnClickPendingIntent(R.id.home_icon, pendingIntent)
    views.setOnClickPendingIntent(R.id.home_touch_zone, pendingIntent)
    views.setOnClickPendingIntent(R.id.home_touch_zone_inline, pendingIntent)
    Log.d(
        "HomeShortcut",
        "setupSourceHomeShortcut: widget=$appWidgetId " +
            "requestCode=${WidgetRequestCodes.sourceHome(appWidgetId)} -> ACTION_RESET_SOURCE",
    )
}

internal fun setupWeatherStationsShortcut(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    setVisibility: Boolean = false,
    scale: Float = 1.0f,
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
    HeaderRemoteViewsBinder.bindScaledIcon(
        context = context,
        views = views,
        viewId = R.id.weather_stations_icon,
        iconRes = R.drawable.ic_thermometer,
        sizeDp = HeaderConstants.CENTER_ICON_SIZE_DP,
        scale = scale,
        tintColor = 0xAAFFFFFF.toInt()
    )
    HeaderRemoteViewsBinder.bindScaledIcon(
        context = context,
        views = views,
        viewId = R.id.weather_stations_icon_inline,
        iconRes = R.drawable.ic_thermometer,
        sizeDp = HeaderConstants.CENTER_ICON_SIZE_DP,
        scale = scale,
        tintColor = 0xAAFFFFFF.toInt()
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
    density: Float,
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

    if (inlineVis == View.VISIBLE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val touchWidthDp = when {
            widthDp < 350 -> 32
            widthDp < 400 -> 40
            else -> 48
        }
        val touchWidthPx = (touchWidthDp * density).toInt()
        for (id in listOf(R.id.graph_selector_touch_zone_inline, R.id.weather_stations_touch_zone_inline, R.id.home_touch_zone_inline, R.id.forecast_history_activity_touch_zone_inline)) {
            views.setViewLayoutWidth(id, touchWidthPx.toFloat(), android.util.TypedValue.COMPLEX_UNIT_PX)
        }
        Log.d("HomeShortcut", "positionCenterIcons: inline touch zones resized to ${touchWidthDp}dp for widthDp=$widthDp")
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Restore the hourly line explicitly; the daily view moves this same container.
        views.setViewLayoutMargin(
            R.id.hourly_center_header_container,
            RemoteViews.MARGIN_TOP,
            HeaderConstants.HOURLY_ICON_CONTAINER_MARGIN_TOP_DP,
            android.util.TypedValue.COMPLEX_UNIT_DIP,
        )
    }

    if (floatingVis == View.VISIBLE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val touchWidthDp = if (widthDp >= 450) 56 else 24
        val touchWidthPx = (touchWidthDp * density).toInt()
        for (id in listOf(R.id.graph_selector_touch_zone, R.id.weather_stations_touch_zone, R.id.home_touch_zone, R.id.forecast_history_activity_touch_zone)) {
            views.setViewLayoutWidth(id, touchWidthPx.toFloat(), android.util.TypedValue.COMPLEX_UNIT_PX)
        }
        Log.d("HomeShortcut", "positionCenterIcons: floating touch zones resized to ${touchWidthDp}dp for widthDp=$widthDp")
    }

    if (!isToday) {
        for (id in listOf(R.id.weather_stations_icon, R.id.weather_stations_touch_zone, R.id.weather_stations_touch_zone_inline)) {
            views.setViewVisibility(id, View.GONE)
        }
    }
}

/**
 * Places the daily view's header buttons (current observations / home / forecast history).
 *
 * Deliberately narrower than [positionCenterIcons]: it never touches the graph-selector zones,
 * which only cycle hourly graphs.
 *
 * [showObservations] is false when neither today nor yesterday is on screen, so a navigated header
 * shows the history button alone; the reserved width in [HeaderWidthChecker.resolveDailyIconPlacement]
 * follows the same count.
 *
 * [showHome] is the source-reset button, not a view switch: the daily view is always home in the
 * view sense, but it can still be showing a source the user did not put first. See
 * [setupSourceHomeShortcut] and [com.weatherwidget.shared.util.PreferredSourceHome]. It is the one
 * daily button that gets dropped when the header cannot hold three
 * ([HeaderWidthChecker.resolveDailyIconLayout]) — the pair is never sacrificed for it.
 *
 * Inline zone widths are set from the same fixed [HeaderConstants.DAILY_INLINE_ICON_ZONE_WIDTH_DP]
 * the fit math used, rather than the hourly ladder, so layout and measurement cannot drift.
 */
internal fun positionDailyIcons(
    views: RemoteViews,
    placement: DailyIconPlacement,
    showObservations: Boolean,
    widthDp: Int,
    density: Float,
    scale: Float = 1.0f,
    showHome: Boolean = false,
) {
    val floating = placement == DailyIconPlacement.CENTER
    val inline = placement == DailyIconPlacement.INLINE

    fun vis(shown: Boolean) = if (shown) View.VISIBLE else View.GONE

    views.setViewVisibility(R.id.history_icon, vis(floating))
    views.setViewVisibility(R.id.forecast_history_activity_touch_zone, vis(floating))
    views.setViewVisibility(R.id.forecast_history_activity_touch_zone_inline, vis(inline))

    val obs = showObservations
    views.setViewVisibility(R.id.weather_stations_icon, vis(floating && obs))
    views.setViewVisibility(R.id.weather_stations_touch_zone, vis(floating && obs))
    views.setViewVisibility(R.id.weather_stations_touch_zone_inline, vis(inline && obs))

    views.setViewVisibility(R.id.home_icon, vis(floating && showHome))
    views.setViewVisibility(R.id.home_touch_zone, vis(floating && showHome))
    views.setViewVisibility(R.id.home_icon_inline, vis(inline && showHome))
    views.setViewVisibility(R.id.home_touch_zone_inline, vis(inline && showHome))

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Drop the shared container onto the daily view's PAINTED text line. See
        // HeaderConstants.DAILY_ICON_CONTAINER_MARGIN_TOP_DP.
        views.setViewLayoutMargin(
            R.id.hourly_center_header_container,
            RemoteViews.MARGIN_TOP,
            HeaderConstants.DAILY_ICON_CONTAINER_MARGIN_TOP_DP,
            android.util.TypedValue.COMPLEX_UNIT_DIP,
        )
        // Widen the zones so the pair reads as two controls, not one glued-together blob. The
        // hourly header gets away with 24dp zones because it packs four icons; two at that pitch
        // sit ~4dp apart. Widths must match what dailyCenterIconsWidthDp/dailyInlineIconsWidthDp
        // measured, or the reserved slot and the drawn buttons disagree.
        val zoneDp =
            if (inline) HeaderConstants.DAILY_INLINE_ICON_ZONE_WIDTH_DP
            else HeaderWidthChecker.dailyCenterIconZoneWidthDp(widthDp)
        val widthPx = (zoneDp * density).toInt().toFloat()
        val ids =
            if (inline) listOf(
                R.id.weather_stations_touch_zone_inline,
                R.id.home_touch_zone_inline,
                R.id.forecast_history_activity_touch_zone_inline,
            ) else listOf(
                R.id.weather_stations_touch_zone,
                R.id.home_touch_zone,
                R.id.forecast_history_activity_touch_zone,
            )
        for (id in ids) {
            views.setViewLayoutWidth(id, widthPx, android.util.TypedValue.COMPLEX_UNIT_PX)
        }
    }

    Log.d(
        "DailyHeaderIcons",
        "positionDailyIcons: placement=$placement showObservations=$showObservations " +
            "showHome=$showHome widthDp=$widthDp " +
            "zoneDp=${HeaderWidthChecker.dailyCenterIconZoneWidthDp(widthDp)} scale=$scale",
    )
}

internal fun setupGraphSelectorShortcut(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    currentViewMode: ViewMode,
    widthDp: Int,
    isPrecipVisible: Boolean,
    scale: Float = 1.0f,
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

    val apiPx = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP,
        HeaderConstants.GRAPH_SELECTOR_TEXT_SIZE_DP * scale,
        context.resources.displayMetrics
    )
    views.setTextViewTextSize(R.id.graph_selector_icon, android.util.TypedValue.COMPLEX_UNIT_PX, apiPx)
    views.setTextViewTextSize(R.id.graph_selector_icon_inline, android.util.TypedValue.COMPLEX_UNIT_PX, apiPx)

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

/**
 * Backstop click handler on the widget root. On Samsung (One UI Home), a tap that lands on a region
 * with no PendingIntent falls through to launching the app's LAUNCHER activity (MainActivity), which
 * we don't want. Binding a toast broadcast to [R.id.widget_root] absorbs those "dead zone" taps so
 * the launcher never takes over. RemoteViews dispatches a click to the deepest view that has a
 * PendingIntent, so every real touch zone still wins — only unclaimed taps reach the root.
 * Must be set on every render (RemoteViews are rebuilt each update).
 *
 * Dead-zone taps on the widget root toggle the API source.
 */
internal fun setupDeadZoneCatchAll(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
) {
    val toggleIntent = Intent(context, WidgetActionReceiver::class.java).apply {
        action = WidgetActions.ACTION_TOGGLE_API
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }
    val pendingIntent = PendingIntent.getBroadcast(
        context, WidgetRequestCodes.deadZone(appWidgetId), toggleIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
}
