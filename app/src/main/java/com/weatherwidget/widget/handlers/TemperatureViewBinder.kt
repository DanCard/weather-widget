package com.weatherwidget.widget.handlers

import com.weatherwidget.shared.util.HeaderButtons
import com.weatherwidget.shared.util.HeaderFit
import com.weatherwidget.data.model.ForecastProduct
import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.widget.ViewMode

import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.shared.graph.HourData
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
        views.setViewVisibility(R.id.graph_day_zones, View.GONE)
        views.setViewVisibility(R.id.graph_interaction_container, View.VISIBLE)
        // Reset sticky visibility from DailyViewHandler

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
        // The stations zone in the inline nav row shows only for today, so the header fit needs
        // Current observations only while the graph shows some of today (:shared HeaderButtons).
        val headerButtons = HeaderButtons.hourly(
            observationsInView = HeaderButtons.hourlyObservationsInView(
                centre = centerTime,
                backHours = state.zoom.backHours,
                forwardHours = state.zoom.forwardHours,
                today = LocalDateTime.now().toLocalDate(),
            ),
        )

        // The shared drop order (HeaderFit): inline nav zones squeeze, then the icon and
        // temperature shrink, before anything drops; "from yest" only where it needs neither.
        val deltaLabelText = context.getString(R.string.header_delta_from_yesterday)
        val measured = HeaderWidthChecker.measureHeader(
            context = context,
            widthDp = state.widthDp,
            headerScale = headerScale,
            apiSourceText = header.sourceIndicator,
            apiTextSizeDp = HeaderConstants.apiTextSizeDp(state.numRows),
            currentTempText = header.currentTemp,
            currentTempSizeDp = header.currentTempSizeDp,
            deltaText = if (header.isDeltaVisible) header.deltaText else null,
            deltaLabelText = deltaLabelText,
            precipText = if (header.isPrecipVisible) header.precipProbability else null,
            precipTextSizeDp = header.precipTextSizeDp,
            dateText = null,
            includeIcon = true,
            baseButtons = headerButtons.baseButtons,
            wantHome = headerButtons.wantHome,
            wantMonths = headerButtons.wantMonths,
            hourly = true,
        )
        val fit = HeaderFit.resolve(measured)
        Log.d("TemperatureViewBinder", "HEADER_FIT view=hourly widthDp=${state.widthDp} scale=$headerScale $fit $measured")

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
            scale = headerScale * fit.textScale,
        )
        
        HeaderRemoteViewsBinder.bindCurrentTemp(
            context = context,
            views = views,
            formattedTemp = header.currentTemp,
            textSizeDp = header.currentTempSizeDp,
            scale = headerScale * fit.textScale,
        )

        HeaderRemoteViewsBinder.bindDelta(
            context = context,
            views = views,
            deltaText = header.deltaText,
            deltaVisible = header.isDeltaVisible,
            scale = headerScale,
        )

        val isDeltaLabelVisible = header.isDeltaVisible && fit.showCaption
        HeaderRemoteViewsBinder.bindDeltaLabel(
            context = context,
            views = views,
            labelText = deltaLabelText,
            labelVisible = isDeltaLabelVisible,
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

        // What the fit kept (computed above, before the header binds).
        HeaderRemoteViewsBinder.applyFit(
            views,
            fit,
            isDeltaVisible = header.isDeltaVisible,
            isPrecipVisible = header.isPrecipVisible,
        )

// 3. Center Icons & Navigation (headerButtons computed above, before the header fit)

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
            scale = headerScale,
        )

        positionCenterIcons(
            views, state.widthDp, context.resources.displayMetrics.density,
            header.isPrecipVisible && fit.showRain, measured, fit, HeaderButtons.shown(headerButtons, fit),
        )

        
        HeaderTapTargetHelper.bindToggleTemperatureHeader(context, views, appWidgetId)
        HeaderTapTargetHelper.bindPrecipitationHeader(context, views, appWidgetId)

        views.setViewVisibility(R.id.sim_loop_touch_zone, View.GONE)

        views.setViewVisibility(R.id.radar_touch_zone, View.GONE)

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

            ErrorPillTouchTargetHelper.setupErrorPillTouchTarget(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                showErrorWatermark = stateManager.viewWatermark(state.displaySource, ForecastProduct.HOURLY).show,
                errorCode = stateManager.viewWatermark(state.displaySource, ForecastProduct.HOURLY).errorCode,
                bannerSinceMs = stateManager.viewWatermark(state.displaySource, ForecastProduct.HOURLY).bannerSinceMs,
                sourceId = state.displaySource.id,
            )
        } else if (state.graph.showTextMode) {
            views.setViewVisibility(R.id.error_pill_touch_zone, View.GONE)
            showTextMode(views)
        }
        // useGraph with a null bitmap means renderGraph threw (HOURLY_RENDER_EXCEPTION). Do not
        // fall through to text mode: text_container is the daily layout, unpopulated on this
        // path, so that "fallback" is an empty body. Leaving graph_view untouched keeps the last
        // good bitmap on screen — RemoteViews visibility is sticky, so the previous frame stands.
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
