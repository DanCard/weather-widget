package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.widget.HourlyTouchZoneMapper
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetActionReceiver
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.ZoomLevel
import kotlin.math.roundToInt

private const val TAG = "HourlyBottomZone"

/**
 * Wires per-hour bottom-row tap zones on hourly graphs.
 *
 * Each zone's intent depends on the icon at that hour position:
 * - If the icon's "home" graph matches the current view → zoom toggle
 * - Otherwise → navigate to the icon's home graph
 */
object HourlyBottomZoneHelper {
    internal data class ZoneAction(
        val targetView: ViewMode?,
        val zoneCenterOffset: Int,
    )

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
        // Search outward across the full list. Sub-hourly observation entries have
        // null iconRes, so the nearest top-of-hour icon can be several entries away,
        // especially in NARROW zoom where sub-hourly points are dense.
        val maxRadius = icons.size
        for (r in 1..maxRadius) {
            val left = centerIndex - r
            val right = centerIndex + r
            if (left >= 0) icons[left]?.let { return it }
            if (right <= icons.lastIndex) icons[right]?.let { return it }
        }
        return null
    }

    /**
     * Resolves the icon represented by a tap zone.
     *
     * Prefer an icon already inside the zone's slice so taps on a visible icon do not
     * inherit an adjacent zone's action. If the slice contains no icon, fall back to the
     * nearest icon across the full list to keep sparse data interactive.
     */
    internal fun resolveZoneIcon(
        icons: List<Int?>,
        zoneIndex: Int,
        zoneCount: Int,
    ): Int? {
        if (icons.isEmpty()) return null
        if (icons.size <= zoneCount) {
            // When the list represents only the visible labeled icons, treat entries as
            // evenly spaced anchors across the graph and snap the tap zone to the nearest
            // rendered icon center instead of an arbitrary 1/N data slice.
            val anchorIndex = (((zoneIndex + 0.5f) / zoneCount) * (icons.size - 1))
                .roundToInt()
                .coerceIn(0, icons.lastIndex)
            return findNearestIcon(icons, anchorIndex)
        }

        val zoneStart = (zoneIndex * icons.size / zoneCount).coerceIn(0, icons.lastIndex)
        val zoneEndExclusive = (((zoneIndex + 1) * icons.size + zoneCount - 1) / zoneCount)
            .coerceIn(zoneStart + 1, icons.size)
        val zoneEnd = zoneEndExclusive - 1
        val centerIndex = ((zoneStart + zoneEnd) / 2).coerceIn(zoneStart, zoneEnd)

        findNearestIconWithinRange(icons, centerIndex, zoneStart, zoneEnd)?.let { return it }
        return findNearestIcon(icons, centerIndex)
    }

    private fun findNearestIconWithinRange(
        icons: List<Int?>,
        centerIndex: Int,
        startIndex: Int,
        endIndex: Int,
    ): Int? {
        icons[centerIndex]?.let { return it }
        val maxRadius = maxOf(centerIndex - startIndex, endIndex - centerIndex)
        for (r in 1..maxRadius) {
            val left = centerIndex - r
            val right = centerIndex + r
            if (left >= startIndex) icons[left]?.let { return it }
            if (right <= endIndex) icons[right]?.let { return it }
        }
        return null
    }

    internal fun resolveZoneAction(
        iconRes: Int?,
        currentViewMode: ViewMode,
        zoneIndex: Int,
        hourlyOffset: Int,
        zoom: ZoomLevel,
    ): ZoneAction {
        return ZoneAction(
            targetView = DayClickHelper.resolveHourlyBottomRowAction(iconRes, currentViewMode),
            zoneCenterOffset = HourlyTouchZoneMapper.zoneIndexToOffset(zoneIndex, hourlyOffset, zoom),
        )
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
        showBodyOverlayZones: Boolean = true,
    ) {
        views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)
        // Consolidate: use the root-level graph_bottom_hour_zones for all hourly views.
        // It covers both icons and labels with its 112dp height.
        views.setViewVisibility(R.id.graph_bottom_hour_zones, View.VISIBLE)
        
        // Preserve the footer row height in the vertical interaction container.
        views.setViewVisibility(R.id.graph_bottom_reserved_space, View.VISIBLE)
        views.setViewVisibility(R.id.graph_bottom_day_zones, View.GONE)
        Log.d(
            TAG,
            "setup: widget=$appWidgetId currentView=$currentViewMode zoom=$zoom hourlyOffset=$hourlyOffset " +
                "showBodyOverlayZones=$showBodyOverlayZones icons=${hourIconResources.size}",
        )

        BOTTOM_HOUR_ZONE_IDS.forEachIndexed { i, zoneId ->
            val iconRes = resolveZoneIcon(hourIconResources, i, BOTTOM_HOUR_ZONE_IDS.size)
            val iconName = iconRes?.let { runCatching { context.resources.getResourceEntryName(it) }.getOrNull() } ?: "null"
            val zoneAction = resolveZoneAction(iconRes, currentViewMode, i, hourlyOffset, zoom)
            Log.d(
                TAG,
                "zone=$i iconRes=$iconRes iconName=$iconName targetView=${zoneAction.targetView} " +
                    "centerOffset=${zoneAction.zoneCenterOffset} currentView=$currentViewMode listSize=${hourIconResources.size}",
            )

            val pendingIntent = if (zoneAction.targetView == null) {
                // Zoom — same offset calculation as the body zoom zones
                val zoomIntent = Intent(context, WidgetActionReceiver::class.java).apply {
                    action = WidgetActions.ACTION_CYCLE_ZOOM
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    putExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET, zoneAction.zoneCenterOffset)
                }
                PendingIntent.getBroadcast(
                    context,
                    WidgetRequestCodes.bottomHourClick(appWidgetId, i),
                    zoomIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                // Navigate to the icon's home graph
                val navIntent = Intent(context, WidgetActionReceiver::class.java).apply {
                    action = WidgetActions.ACTION_SET_VIEW
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    putExtra(WidgetActions.EXTRA_TARGET_VIEW, zoneAction.targetView.name)
                    putExtra(WidgetActions.EXTRA_HOURLY_OFFSET, zoneAction.zoneCenterOffset)
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
