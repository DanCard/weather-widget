package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
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
        // Reset sticky visibility from DailyViewHandler — dual_touch_zone is only meaningful in DAILY view.
        views.setViewVisibility(R.id.dual_touch_zone, View.GONE)

        // 1. Warning
        if (state.warning != null) {
            ApiSourceWarningHelper.renderSourceWarningState(context, views, appWidgetId, state.warning.warning)
            setupApiToggle(context, views, appWidgetId, state.numRows)
            return
        }
        ApiSourceWarningHelper.hideSourceWarning(views)

        // 2. Header.
        val headerScale = HeaderWidthChecker.computeHeaderScale(
            context = context,
            widthDp = state.widthDp,
            apiSourceText = header.sourceIndicator,
            apiTextSizeDp = HeaderConstants.apiTextSizeDp(state.numRows),
            currentTempText = header.currentTemp,
            deltaText = header.deltaText,
            precipText = header.precipProbability,
            precipTextSizeDp = header.precipTextSizeDp,
        )

        // Explicitly set VISIBLE because DailyViewHandler sets these to INVISIBLE
        // when rendering the source label into the bitmap — RemoteViews updates are
        // deltas, so the prior INVISIBLE state persists until restored here.
        HeaderRemoteViewsBinder.bindApiSource(
            context = context,
            views = views,
            sourceText = header.sourceIndicator,
            textSizeDp = HeaderConstants.apiTextSizeDp(state.numRows),
            scale = headerScale,
        )
        views.setViewVisibility(R.id.api_touch_zone, View.VISIBLE)
        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = R.id.settings_icon,
            iconRes = R.drawable.ic_settings_gear,
            sizeDp = HeaderConstants.SETTINGS_ICON_SIZE_DP,
            scale = headerScale,
            tintColor = 0xAAFFFFFF.toInt()
        )
        views.setViewVisibility(R.id.top_right_header_container, View.VISIBLE)
        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = R.id.weather_icon,
            iconRes = header.iconRes,
            sizeDp = HeaderConstants.WEATHER_ICON_SIZE_DP,
            scale = headerScale,
        )
        
        HeaderRemoteViewsBinder.bindCurrentTemp(
            context = context,
            views = views,
            formattedTemp = header.currentTemp,
            textSizeDp = header.currentTempSizeDp,
            scale = headerScale,
        )

        HeaderRemoteViewsBinder.bindDelta(
            context = context,
            views = views,
            deltaText = header.deltaText,
            deltaVisible = header.isDeltaVisible,
            scale = headerScale,
        )

        HeaderRemoteViewsBinder.bindPrecipProbability(
            context = context,
            views = views,
            precipText = if (header.isPrecipVisible) header.precipProbability else null,
            textSizeDp = header.precipTextSizeDp,
            scale = headerScale,
        )
HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, header.isPrecipVisible)

// Apply progressive disclosure for narrow widgets
val disclosure = HeaderWidthChecker.resolveHeaderDisclosure(
    context = context,
    widthDp = state.widthDp,
    apiSourceText = header.sourceIndicator,
    apiTextSizeDp = HeaderConstants.apiTextSizeDp(state.numRows),
    currentTempText = header.currentTemp,
    deltaText = header.deltaText,
    precipText = header.precipProbability,
    precipTextSizeDp = header.precipTextSizeDp,
)
HeaderRemoteViewsBinder.applyDisclosure(views, disclosure, isDeltaVisible = header.isDeltaVisible, isPrecipVisible = header.isPrecipVisible)

// 3. Center Icons & Navigation
val today = LocalDateTime.now().toLocalDate()
val firstHour = state.graph.hourData.firstOrNull()?.dateTime
val lastHour = state.graph.hourData.lastOrNull()?.dateTime
val isToday = firstHour?.toLocalDate() == today ||
        lastHour?.minusHours(1)?.toLocalDate() == today ||
        (state.graph.hourData.isEmpty() && centerTime.toLocalDate() == today)

Log.d("TemperatureViewBinder", "isToday check: today=$today firstHour=$firstHour lastHour=$lastHour centerTime=$centerTime -> isToday=$isToday")

        // 4. Setup Intent Listeners
        setupZoomTapZones(
            context, views, appWidgetId, state.zoom, state.hourlyOffset,
        )
        setupNavigationButtons(context, views, appWidgetId, stateManager)
        setupApiToggle(context, views, appWidgetId, state.numRows, scale = headerScale)
        setupHomeShortcut(context, views, appWidgetId, scale = headerScale)
        setupSettingsShortcut(context, views, appWidgetId)
        setupHistoryShortcut(context, views, appWidgetId, centerTime, hourlyForecasts, state.displaySource, scale = headerScale)
        setupWeatherStationsShortcut(context, views, appWidgetId, scale = headerScale)

        setupGraphSelectorShortcut(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            currentViewMode = com.weatherwidget.widget.ViewMode.TEMPERATURE,
            widthDp = state.widthDp,
            isPrecipVisible = header.isPrecipVisible && disclosure.showsPrecip(),
            scale = headerScale,
        )

        positionCenterIcons(views, state.widthDp, context.resources.displayMetrics.density, header.isPrecipVisible && disclosure.showsPrecip(), isToday)

        
        HeaderTapTargetHelper.bindToggleTemperatureHeader(context, views, appWidgetId)
        HeaderTapTargetHelper.bindPrecipitationHeader(context, views, appWidgetId)

        // 5. Graph
        if (state.graph.useGraph && state.graph.bitmap != null) {
            views.setViewVisibility(R.id.text_container, View.GONE)
            views.setViewVisibility(R.id.graph_view, View.VISIBLE)
            views.setImageViewBitmap(R.id.graph_view, state.graph.bitmap)

            HourlyBottomZoneHelper.setup(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                hourIconResources = routedHourIcons,
                currentViewMode = ViewMode.TEMPERATURE,
                zoom = state.zoom,
                hourlyOffset = state.hourlyOffset,
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
 views.setViewVisibility(R.id.graph_bottom_reserved_space, View.VISIBLE)
    }
}
