package com.weatherwidget.widget.handlers

import com.weatherwidget.widget.ViewMode

/**
 * Centrally manages request codes for PendingIntents to ensure they are unique
 * across different widgets and actions.
 *
 * Each code is `widgetId * 10000 + base` with bases spaced ≥ 50 apart, so codes never collide
 * within a widget and different widgets are separated by at least 10000. This assumes the
 * host-allocated widgetId stays below ~214,748 (Int.MAX_VALUE / 10000); Android hosts allocate
 * small positive ints, so the multiplication never overflows in practice.
 */
object WidgetRequestCodes {
    private const val BASE_NAV_LEFT = 0
    private const val BASE_NAV_RIGHT = 1
    private const val BASE_API_TOGGLE = 100
    private const val BASE_VIEW_TOGGLE = 200
    private const val BASE_PRECIP_TOGGLE = 300
    private const val BASE_CYCLE_ZOOM = 400
    private const val BASE_ZOOM_ZONE = 500
    private const val BASE_SET_TEMP = 600
    private const val BASE_SET_CLOUD_COVER = 610
    private const val BASE_SET_PRECIP = 620
    private const val BASE_HISTORY = 700
    private const val BASE_WEATHER_STATIONS = 800
    private const val BASE_ICON_VIEW_TOGGLE = 900
    private const val BASE_HOME = 850
    private const val BASE_SETTINGS = 950
    private const val BASE_DAY_CLICK = 1000
    private const val BASE_GRAPH_CLICK = 2000
    private const val BASE_BOTTOM_HOUR_CLICK = 3000
    private const val BASE_NIGHT_RAIN_CLICK = 4000
    private const val BASE_DEAD_ZONE = 5000
    private const val BASE_SET_LOCATION = 5100
    private const val BASE_ERROR_REFRESH = 5200
    private const val BASE_SOURCE_HOME = 5300

    fun navLeft(id: Int) = id * 10000 + BASE_NAV_LEFT
    fun navRight(id: Int) = id * 10000 + BASE_NAV_RIGHT
    fun apiToggle(id: Int) = id * 10000 + BASE_API_TOGGLE
    fun viewToggle(id: Int) = id * 10000 + BASE_VIEW_TOGGLE
    fun precipToggle(id: Int) = id * 10000 + BASE_PRECIP_TOGGLE
    fun cycleZoom(id: Int) = id * 10000 + BASE_CYCLE_ZOOM
    fun cycleZoomZone(id: Int, index: Int) = id * 10000 + BASE_ZOOM_ZONE + index
    fun setTemperature(id: Int) = id * 10000 + BASE_SET_TEMP
    fun setCloudCover(id: Int) = id * 10000 + BASE_SET_CLOUD_COVER
    fun setPrecipitation(id: Int) = id * 10000 + BASE_SET_PRECIP
    fun history(id: Int) = id * 10000 + BASE_HISTORY
    fun weatherStations(id: Int) = id * 10000 + BASE_WEATHER_STATIONS
    fun iconViewToggle(id: Int) = id * 10000 + BASE_ICON_VIEW_TOGGLE
    fun home(id: Int) = id * 10000 + BASE_HOME
    fun settings(id: Int) = id * 10000 + BASE_SETTINGS
    fun graphSelector(id: Int, targetViewMode: ViewMode) = when (targetViewMode) {
        ViewMode.TEMPERATURE -> setTemperature(id)
        ViewMode.CLOUD_COVER -> setCloudCover(id)
        ViewMode.PRECIPITATION -> setPrecipitation(id)
        else -> id * 10000 + 630
    }
    fun dayClick(id: Int, dayIndex: Int) = id * 10000 + BASE_DAY_CLICK + dayIndex
    fun graphClick(id: Int, index: Int) = id * 10000 + BASE_GRAPH_CLICK + index
    fun bottomHourClick(id: Int, index: Int) = id * 10000 + BASE_BOTTOM_HOUR_CLICK + index
    fun nightRainClick(id: Int, dayIndex: Int) = id * 10000 + BASE_NIGHT_RAIN_CLICK + dayIndex
    fun deadZone(id: Int) = id * 10000 + BASE_DEAD_ZONE

    /** The "No location — tap to set" root target. */
    fun setLocation(id: Int) = id * 10000 + BASE_SET_LOCATION

    /** The "Tap to refresh" root target on the error placeholder. */
    fun errorRefresh(id: Int) = id * 10000 + BASE_ERROR_REFRESH

    /**
     * The daily view's home button, which resets the display source rather than the view mode.
     * Its own base, not [home]: the two intents ride the same `home_icon` view, and reusing one
     * code would leave whichever was bound first able to satisfy the other's FLAG_UPDATE_CURRENT.
     */
    fun sourceHome(id: Int) = id * 10000 + BASE_SOURCE_HOME
}
