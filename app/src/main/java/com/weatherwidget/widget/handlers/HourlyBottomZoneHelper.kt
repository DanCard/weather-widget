package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.ZoomLevel

/**
 * Wires per-hour bottom-row tap zones on hourly graphs.
 *
 * Each zone's intent depends on the icon at that hour position:
 * - If the icon's "home" graph matches the current view → zoom toggle
 * - Otherwise → navigate to the icon's home graph
 */
object HourlyBottomZoneHelper {

    private val BOTTOM_HOUR_ZONE_IDS = listOf(
        R.id.graph_bottom_hour_zone_0, R.id.graph_bottom_hour_zone_1, R.id.graph_bottom_hour_zone_2,
        R.id.graph_bottom_hour_zone_3, R.id.graph_bottom_hour_zone_4, R.id.graph_bottom_hour_zone_5,
        R.id.graph_bottom_hour_zone_6, R.id.graph_bottom_hour_zone_7, R.id.graph_bottom_hour_zone_8,
        R.id.graph_bottom_hour_zone_9, R.id.graph_bottom_hour_zone_10, R.id.graph_bottom_hour_zone_11,
        R.id.graph_bottom_hour_zone_12,
    )

    /**
     * Searches outward from [centerIndex] for the nearest non-null icon.
     * Sub-hourly observation points have null iconRes; top-of-hour forecasts have icons.
     * Returns null only if no icon exists within a reasonable search radius.
     */
    internal fun findNearestIcon(icons: List<Int?>, centerIndex: Int): Int? {
        // Check center first
        icons[centerIndex]?.let { return it }
        // Search outward up to half the zone width (icons.size / 13 / 2)
        val maxRadius = (icons.size / BOTTOM_HOUR_ZONE_IDS.size).coerceAtLeast(1)
        for (r in 1..maxRadius) {
            val left = centerIndex - r
            val right = centerIndex + r
            if (left >= 0) icons[left]?.let { return it }
            if (right <= icons.lastIndex) icons[right]?.let { return it }
        }
        return null
    }

    /**
     * Sets up per-hour bottom zones with icon-dependent routing.
     *
     * @param hourIconResources list of iconRes values from the graph's hour data, in display order
     * @param currentViewMode the active hourly view (TEMPERATURE, CLOUD_COVER, or PRECIPITATION)
     * @param zoom current zoom level (needed for zoom center offset calculation)
     * @param hourlyOffset current hourly offset (needed for zoom center offset calculation)
     */
    fun setup(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        hourIconResources: List<Int?>,
        currentViewMode: ViewMode,
        zoom: ZoomLevel,
        hourlyOffset: Int,
    ) {
        views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_hour_zones, View.VISIBLE)
        views.setViewVisibility(R.id.graph_bottom_reserved_space, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_day_zones, View.GONE)

        BOTTOM_HOUR_ZONE_IDS.forEachIndexed { i, zoneId ->
            // Map zone center to the closest hour in the data list.
            // Zone i covers [i/13, (i+1)/13) of the graph width.
            // Hours are evenly distributed, so center of zone i → hour (2i+1)*N/26.
            // Sub-hourly observation points have null iconRes, so search nearby
            // for the nearest top-of-hour entry that has an icon.
            val centerIndex = if (hourIconResources.isEmpty()) {
                null
            } else {
                ((2 * i + 1) * hourIconResources.size / (2 * BOTTOM_HOUR_ZONE_IDS.size))
                    .coerceIn(0, hourIconResources.lastIndex)
            }
            val iconRes = centerIndex?.let { findNearestIcon(hourIconResources, it) }
            val targetView = DayClickHelper.resolveHourlyBottomRowAction(iconRes, currentViewMode)

            val pendingIntent = if (targetView == null) {
                // Zoom — same offset calculation as the body zoom zones
                val zoneCenterOffset = WeatherWidgetProvider.zoneIndexToOffset(i, hourlyOffset, zoom)
                val zoomIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                    action = WeatherWidgetProvider.ACTION_CYCLE_ZOOM
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    putExtra(WeatherWidgetProvider.EXTRA_ZOOM_CENTER_OFFSET, zoneCenterOffset)
                }
                PendingIntent.getBroadcast(
                    context,
                    WidgetRequestCodes.bottomHourClick(appWidgetId, i),
                    zoomIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                // Navigate to the icon's home graph
                val navIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
                    action = WidgetIntentRouter.ACTION_SET_VIEW
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    putExtra(WidgetIntentRouter.EXTRA_TARGET_VIEW, targetView.name)
                }
                PendingIntent.getBroadcast(
                    context,
                    WidgetRequestCodes.bottomHourClick(appWidgetId, i),
                    navIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

            views.setOnClickPendingIntent(zoneId, pendingIntent)
        }
    }
}
