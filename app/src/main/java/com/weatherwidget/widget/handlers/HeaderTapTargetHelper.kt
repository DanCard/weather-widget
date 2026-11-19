package com.weatherwidget.widget.handlers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetInteractionReceiver

internal object HeaderTapTargetHelper {
    fun shouldShowPrecipTouchZone(headerPrecipProbability: Int?): Boolean =
        headerPrecipProbability != null && headerPrecipProbability > 0

    private fun viewTogglePendingIntent(
        context: Context,
        appWidgetId: Int,
        interactionSource: String? = null,
    ): PendingIntent {
        val toggleIntent =
            Intent(context, WidgetInteractionReceiver::class.java).apply {
                action = WidgetActions.ACTION_TOGGLE_VIEW
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                interactionSource?.let {
                    putExtra(WidgetActions.EXTRA_INTERACTION_SOURCE, it)
                }
            }
        return PendingIntent.getBroadcast(
            context,
            WidgetRequestCodes.viewToggle(appWidgetId),
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun precipTogglePendingIntent(
        context: Context,
        appWidgetId: Int,
    ): PendingIntent {
        val precipIntent =
            Intent(context, WidgetInteractionReceiver::class.java).apply {
                action = WidgetActions.ACTION_TOGGLE_PRECIP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
        return PendingIntent.getBroadcast(
            context,
            WidgetRequestCodes.precipToggle(appWidgetId),
            precipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun bindToggleTemperatureHeader(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        interactionSource: String? = null,
    ) {
        val togglePendingIntent = viewTogglePendingIntent(context, appWidgetId, interactionSource)
        views.setOnClickPendingIntent(R.id.current_temp, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.current_temp_delta, togglePendingIntent)
        views.setOnClickPendingIntent(R.id.current_temp_zone, togglePendingIntent)
    }

    /**
     * Binds the header weather icon and its touch zone. While rain is actively falling
     * ([isRainingNow] = true, blue rain-now dots), tapping opens the hourly rain chance graph
     * (`ACTION_TOGGLE_PRECIP`); otherwise it uses [defaultPendingIntent] or the standard view toggle.
     */
    fun bindWeatherIconHeader(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        isRainingNow: Boolean,
        defaultPendingIntent: PendingIntent? = null,
    ) {
        val pendingIntent = if (isRainingNow) {
            precipTogglePendingIntent(context, appWidgetId)
        } else {
            defaultPendingIntent ?: viewTogglePendingIntent(context, appWidgetId)
        }
        views.setOnClickPendingIntent(R.id.weather_icon, pendingIntent)
        views.setOnClickPendingIntent(R.id.weather_icon_touch_zone, pendingIntent)
    }

    fun bindGraphSelector(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        targetViewMode: ViewMode,
    ) {
        val intent =
            Intent(context, WidgetInteractionReceiver::class.java).apply {
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
    }

    fun bindPrecipitationHeader(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
    ) {
        val precipPendingIntent = precipTogglePendingIntent(context, appWidgetId)
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
