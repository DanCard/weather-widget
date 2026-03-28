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
                action = WidgetIntentRouter.ACTION_TOGGLE_VIEW
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                interactionSource?.let {
                    putExtra(WeatherWidgetProvider.EXTRA_INTERACTION_SOURCE, it)
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

    fun bindSetTemperatureHeader(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
    ) {
        val goTempIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WidgetIntentRouter.ACTION_SET_VIEW
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(WidgetIntentRouter.EXTRA_TARGET_VIEW, ViewMode.TEMPERATURE.name)
            }
        val goTempPending =
            PendingIntent.getBroadcast(
                context,
                appWidgetId * 2 + 200,
                goTempIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.current_temp, goTempPending)
        views.setOnClickPendingIntent(R.id.current_temp_delta, goTempPending)
        views.setOnClickPendingIntent(R.id.current_temp_zone, goTempPending)
    }

    fun bindPrecipitationHeader(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
    ) {
        val precipIntent =
            Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WidgetIntentRouter.ACTION_TOGGLE_PRECIP
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
