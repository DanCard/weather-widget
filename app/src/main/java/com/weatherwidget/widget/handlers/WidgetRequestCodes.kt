package com.weatherwidget.widget.handlers

/**
 * Centrally manages request codes for PendingIntents to ensure they are unique
 * across different widgets and actions.
 */
object WidgetRequestCodes {
    private const val BASE_NAV_LEFT = 0
    private const val BASE_NAV_RIGHT = 1
    private const val BASE_API_TOGGLE = 100
    private const val BASE_VIEW_TOGGLE = 200
    private const val BASE_PRECIP_TOGGLE = 300
    private const val BASE_CYCLE_ZOOM = 400
    private const val BASE_HOME = 850
    private const val BASE_SETTINGS = 900
    private const val BASE_DAY_CLICK = 1000
    private const val BASE_GRAPH_CLICK = 2000

    fun navLeft(id: Int) = id * 10000 + BASE_NAV_LEFT
    fun navRight(id: Int) = id * 10000 + BASE_NAV_RIGHT
    fun apiToggle(id: Int) = id * 10000 + BASE_API_TOGGLE
    fun viewToggle(id: Int) = id * 10000 + BASE_VIEW_TOGGLE
    fun precipToggle(id: Int) = id * 10000 + BASE_PRECIP_TOGGLE
    fun cycleZoom(id: Int) = id * 10000 + BASE_CYCLE_ZOOM
    fun home(id: Int) = id * 10000 + BASE_HOME
    fun settings(id: Int) = id * 10000 + BASE_SETTINGS
    fun dayClick(id: Int, dayIndex: Int) = id * 10000 + BASE_DAY_CLICK + dayIndex
    fun graphClick(id: Int, index: Int) = id * 10000 + BASE_GRAPH_CLICK + index
}
