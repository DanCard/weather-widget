package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.widget.ViewMode

import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.data.local.HourlyForecastEntity
import java.time.LocalDateTime

internal object TemperatureViewBinder {
    fun bind(
        context: Context,
        views: RemoteViews,
        state: TemperatureWidgetState,
        stateManager: WidgetStateManager,
        centerTime: LocalDateTime,
        hourlyForecasts: List<HourlyForecastEntity>
    ) {
        val appWidgetId = state.appWidgetId
        val header = state.header
        val routedHourIcons = state.graph.hourData
            .filter { it.showLabel }
            .map { it.iconRes }
            .ifEmpty { state.graph.hourData.map { it.iconRes } }

        views.setViewVisibility(R.id.header_date_center, View.GONE)
        views.setViewVisibility(R.id.header_date_right, View.GONE)

        // 1. Warning
        if (state.warning != null) {
            ApiSourceWarningHelper.renderSourceWarningState(context, views, appWidgetId, state.warning.warning)
            setupApiToggle(context, views, appWidgetId, state.numRows)
            return
        }
        ApiSourceWarningHelper.hideSourceWarning(views)

        // 2. Header
        views.setTextViewText(R.id.api_source, header.sourceIndicator)
        views.setImageViewResource(R.id.weather_icon, header.iconRes)
        views.setViewVisibility(R.id.weather_icon, View.VISIBLE)
        
        if (header.currentTemp != null) {
            views.setTextViewText(R.id.current_temp, header.currentTemp)
            views.setTextViewTextSize(R.id.current_temp, TypedValue.COMPLEX_UNIT_DIP, header.currentTempSizeSp)
            views.setViewVisibility(R.id.current_temp, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.current_temp, View.GONE)
        }

        if (header.isDeltaVisible && header.deltaText != null) {
            views.setTextViewText(R.id.current_temp_delta, header.deltaText)
            views.setTextColor(R.id.current_temp_delta, header.deltaColor)
            views.setViewVisibility(R.id.current_temp_delta, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.current_temp_delta, View.GONE)
        }

        if (header.isPrecipVisible && header.precipProbability != null) {
            views.setTextViewText(R.id.precip_probability, header.precipProbability)
            views.setTextViewTextSize(R.id.precip_probability, TypedValue.COMPLEX_UNIT_SP, header.precipTextSizeSp)
            views.setViewVisibility(R.id.precip_probability, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.precip_probability, View.GONE)
        }
        HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, header.isPrecipVisible)

        // 3. Center Icons & Navigation
        positionCenterIcons(views, state.widthDp, header.isPrecipVisible)

        // 4. Setup Intent Listeners
        setupZoomTapZones(
            context, views, appWidgetId, state.zoom, state.hourlyOffset,
        )
        setupNavigationButtons(context, views, appWidgetId, stateManager)
        setupApiToggle(context, views, appWidgetId, state.numRows)
        setupHomeShortcut(context, views, appWidgetId)
        setupSettingsShortcut(context, views, appWidgetId)
        setupHistoryShortcut(context, views, appWidgetId, centerTime, hourlyForecasts, state.displaySource)
        setupCurrentStationsShortcut(context, views, appWidgetId)
        
        HeaderTapTargetHelper.bindToggleTemperatureHeader(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            interactionSource = "current_temp_header",
        )
        HeaderTapTargetHelper.bindPrecipitationHeader(context, views, appWidgetId)

        // 5. Graph
        if (state.graph.useGraph && state.graph.bitmap != null) {
            views.setViewVisibility(R.id.text_container, View.GONE)
            views.setViewVisibility(R.id.graph_view, View.VISIBLE)
            views.setViewVisibility(R.id.graph_bottom_zone, View.VISIBLE)
            views.setImageViewBitmap(R.id.graph_view, state.graph.bitmap)

            HourlyBottomZoneHelper.setup(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                hourIconResources = routedHourIcons,
                currentViewMode = ViewMode.TEMPERATURE,
                zoom = state.zoom,
                hourlyOffset = state.hourlyOffset,
                showBodyOverlayZones = false,
            )
        } else if (state.graph.showTextMode) {
            showTextMode(views)
        }
    }

    private fun showTextMode(views: RemoteViews) {
        views.setViewVisibility(R.id.text_container, View.VISIBLE)
        views.setViewVisibility(R.id.graph_view, View.GONE)
        views.setViewVisibility(R.id.graph_hour_zones, View.GONE)
        views.setViewVisibility(R.id.graph_body_tap_zone, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_zone, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_hour_zones, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_hour_footer_zones, View.GONE)
        views.setViewVisibility(R.id.graph_bottom_reserved_space, View.VISIBLE)
    }
}
