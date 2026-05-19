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
import com.weatherwidget.widget.WidgetActions

internal object HeaderTapTargetHelper {
    fun shouldShowPrecipTouchZone(headerPrecipProbability: Int?): Boolean =
        headerPrecipProbability != null && headerPrecipProbability > 0

    fun bindToggleTemperatureHeader(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        interactionSource: String? = null,
    ) {
        val toggleIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WidgetActions.ACTION_TOGGLE_VIEW
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                interactionSource?.let {
                    putExtra(WidgetActions.EXTRA_INTERACTION_SOURCE, it)
                }
            }
        val togglePendingIntent =
            PendingIntent.getBroadcast(
                context,
                WidgetRequestCodes.viewToggle(appWidgetId),
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.current_temp, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.current_temp_delta, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.current_temp_zone, togglePendingIntent)
    }

    fun bindGraphSelector(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        targetViewMode: ViewMode,
    ) {
        val intent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WidgetActions.ACTION_SET_VIEW
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(WidgetActions.EXTRA_TARGET_VIEW, targetViewMode.name)
            }
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                WidgetRequestCodes.graphSelector(appWidgetId, targetViewMode),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.graph_selector_icon, pendingIntent)
        views.setOnClickPendingIntent(R.id.graph_selector_touch_zone, pendingIntent)
        views.setOnClickPendingIntent(R.id.graph_selector_touch_zone_inline, pendingIntent)
    }

    fun bindPrecipitationHeader(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
    ) {
        val precipIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WidgetActions.ACTION_TOGGLE_PRECIP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
        val precipPendingIntent =
            PendingIntent.getBroadcast(
                context,
                WidgetRequestCodes.precipToggle(appWidgetId),
                precipIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.precip_probability, precipPendingIntent)
        views.setOnClickPendingIntent(R.id.precip_touch_zone, precipPendingIntent)
    }

    fun setPrecipitationTouchZoneVisible(
        views: RemoteViews,
        isVisible: Boolean,
    ) {
        views.setViewVisibility(R.id.precip_touch_zone, if (isVisible) View.VISIBLE else View.GONE)
    }
}
