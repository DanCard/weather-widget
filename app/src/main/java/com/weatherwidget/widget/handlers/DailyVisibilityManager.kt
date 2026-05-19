package com.weatherwidget.widget.handlers

import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R

internal object DailyVisibilityManager {

    fun setGraphModeViews(views: RemoteViews) {
        views.setViewVisibility(R.id.text_container, View.GONE)
        views.setViewVisibility(R.id.graph_view, View.VISIBLE)
        views.setViewVisibility(R.id.graph_day_zones, View.VISIBLE)
        views.setViewVisibility(R.id.graph_hour_zones, View.GONE)
        views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_hour_zones, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_reserved_space, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_day_zones, View.VISIBLE)
        views.setViewVisibility(R.id.graph_night_rain_zones, View.VISIBLE)
        Log.d("DailyViewHandler", "setGraphModeViews: graph_night_rain_zones set VISIBLE")
        setSingleRowControlsVisible(views, false)
        views.setViewVisibility(R.id.api_source_container, View.VISIBLE)
        views.setViewVisibility(R.id.api_touch_zone, View.VISIBLE)
        views.setViewVisibility(R.id.settings_icon, View.VISIBLE)
        views.setViewVisibility(R.id.settings_touch_zone, View.VISIBLE)
    }

    fun setTextModeViews(views: RemoteViews) {
        views.setViewVisibility(R.id.text_container, View.VISIBLE)
        views.setViewVisibility(R.id.graph_view, View.GONE)
        views.setViewVisibility(R.id.header_date_center, View.GONE)
        views.setViewVisibility(R.id.header_date_right, View.GONE)
        views.setViewVisibility(R.id.graph_day_zones, View.GONE)
        views.setViewVisibility(R.id.graph_hour_zones, View.GONE)
        views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_hour_zones, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_reserved_space, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_day_zones, View.GONE)
        views.setViewVisibility(R.id.graph_night_rain_zones, View.GONE)

        views.setViewVisibility(R.id.nav_left, View.GONE)
        views.setViewVisibility(R.id.nav_left_zone, View.GONE)
        views.setViewVisibility(R.id.nav_right, View.GONE)
        views.setViewVisibility(R.id.nav_right_zone, View.GONE)
        Log.d("HomeShortcut", "DailyVisibilityManager.setGraphModeViews: setting home_touch_zone=GONE (daily/graph mode)")
        views.setViewVisibility(R.id.home_icon, View.GONE)
        views.setViewVisibility(R.id.home_touch_zone, View.GONE)
        views.setViewVisibility(R.id.home_touch_zone_inline, View.GONE)
        views.setViewVisibility(R.id.history_icon, View.GONE)
        views.setViewVisibility(R.id.forecast_history_activity_touch_zone, View.GONE)
        views.setViewVisibility(R.id.forecast_history_activity_touch_zone_inline, View.GONE)
        views.setViewVisibility(R.id.weather_stations_icon, View.GONE)
        views.setViewVisibility(R.id.weather_stations_touch_zone, View.GONE)
        views.setViewVisibility(R.id.weather_stations_touch_zone_inline, View.GONE)
        views.setViewVisibility(R.id.graph_selector_icon, View.GONE)
        views.setViewVisibility(R.id.graph_selector_touch_zone, View.GONE)
        views.setViewVisibility(R.id.graph_selector_touch_zone_inline, View.GONE)

        views.setViewVisibility(R.id.current_temp_zone, View.GONE)
        views.setViewVisibility(R.id.precip_touch_zone, View.GONE)
        views.setViewVisibility(R.id.api_source_container, View.GONE)
        views.setViewVisibility(R.id.api_touch_zone, View.GONE)
        views.setViewVisibility(R.id.settings_icon, View.GONE)
        views.setViewVisibility(R.id.settings_touch_zone, View.GONE)
        views.setViewVisibility(R.id.dual_touch_zone, View.GONE)
        setSingleRowControlsVisible(views, true)
    }

    fun setSingleRowControlsVisible(views: RemoteViews, visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.text_mode_api_source_container, visibility)
        views.setViewVisibility(R.id.text_mode_api_touch_zone, visibility)
        views.setViewVisibility(R.id.text_mode_settings_icon, visibility)
        views.setViewVisibility(R.id.text_mode_settings_touch_zone, visibility)
    }
}