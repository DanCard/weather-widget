package com.weatherwidget.widget.handlers

import android.content.Context
import android.widget.RemoteViews
import com.weatherwidget.R

internal object HourlyHeaderBinder {

    data class HeaderResult(
        val disclosure: HeaderDisclosureLevel,
        val isPrecipVisible: Boolean,
        val headerScale: Float,
        /** Fitted inline nav zone width for [positionCenterIcons]; null on wide widgets. */
        val inlineZoneWidthDp: Float?,
    )

    fun bindHourlyHeader(
        context: Context,
        views: RemoteViews,
        iconRes: Int,
        formattedTemp: String?,
        isPrecipVisible: Boolean,
        headerPrecipProbability: Int?,
        precipTextSizeDp: Float?,
        widthDp: Int,
        numRows: Int,
        sourceIndicator: String?,
        /** Whether the inline nav row includes the stations zone (today only). */
        showStations: Boolean,
    ): HeaderResult {
        val safeSourceIndicator = sourceIndicator ?: ""
        val headerScale = HeaderWidthChecker.computeHeaderScale(
            context = context,
            widthDp = widthDp,
            apiSourceText = safeSourceIndicator,
            apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows),
            currentTempText = formattedTemp,
            deltaText = null,
            precipText = if (isPrecipVisible && headerPrecipProbability != null) "$headerPrecipProbability%" else null,
            precipTextSizeDp = precipTextSizeDp,
        )

        val precipText = if (isPrecipVisible && headerPrecipProbability != null) "$headerPrecipProbability%" else null
        // Narrow widgets compress the inline nav row, then the icon and temperature, before
        // anything is dropped (HourlyHeaderFit).
        val fit = HeaderWidthChecker.fitHourlyHeader(
            context = context,
            widthDp = widthDp,
            apiSourceText = safeSourceIndicator,
            apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows),
            currentTempText = formattedTemp,
            deltaText = null,
            deltaLabelText = null,
            precipText = precipText,
            precipTextSizeDp = precipTextSizeDp,
            showStations = showStations,
        )
        val disclosure = fit.disclosure

        HeaderRemoteViewsBinder.bindApiSource(
            context = context,
            views = views,
            sourceText = safeSourceIndicator,
            textSizeDp = HeaderConstants.apiTextSizeDp(numRows),
            scale = headerScale,
        )
        views.setViewVisibility(R.id.api_touch_zone, android.view.View.VISIBLE)
        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = R.id.settings_icon,
            iconRes = R.drawable.ic_settings_gear,
            sizeDp = HeaderConstants.SETTINGS_ICON_SIZE_DP,
            scale = headerScale,
            tintColor = 0xAAFFFFFF.toInt(),
        )
        views.setViewVisibility(R.id.top_right_header_container, android.view.View.VISIBLE)

        HeaderRemoteViewsBinder.bindScaledIcon(
            context = context,
            views = views,
            viewId = R.id.weather_icon,
            iconRes = iconRes,
            sizeDp = HeaderConstants.WEATHER_ICON_SIZE_DP,
            scale = headerScale * fit.textScale,
        )

        HeaderRemoteViewsBinder.bindCurrentTemp(
            context = context,
            views = views,
            formattedTemp = formattedTemp,
            scale = headerScale * fit.textScale,
        )

        HeaderRemoteViewsBinder.bindPrecipProbability(
            context = context,
            views = views,
            precipText = if (isPrecipVisible && headerPrecipProbability != null) "$headerPrecipProbability%" else null,
            textSizeDp = precipTextSizeDp ?: 0f,
            scale = headerScale,
        )
        HeaderTapTargetHelper.setPrecipitationTouchZoneVisible(views, isPrecipVisible)

        HeaderRemoteViewsBinder.applyDisclosure(views, disclosure, isPrecipVisible = isPrecipVisible)

        return HeaderResult(
            disclosure = disclosure,
            isPrecipVisible = isPrecipVisible,
            headerScale = headerScale,
            inlineZoneWidthDp = fit.inlineZoneWidthDp,
        )
    }
}
