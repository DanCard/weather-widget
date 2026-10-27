package com.weatherwidget.widget.handlers

import android.content.Context
import android.widget.RemoteViews
import com.weatherwidget.R
import com.weatherwidget.shared.util.HeaderButton
import com.weatherwidget.shared.util.HeaderButtons
import com.weatherwidget.shared.util.HeaderFit

internal object HourlyHeaderBinder {

    data class HeaderResult(
        /** What the header shows ([HeaderFit]); [measured] is what it was resolved from. */
        val fit: HeaderFit.Plan,
        val measured: HeaderFit.Measured,
        /** [HeaderButtons.shown] for [fit]: what [positionCenterIcons] draws. */
        val shown: List<HeaderButton>,
        val isPrecipVisible: Boolean,
        val headerScale: Float,
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
        /** Which buttons the row wants ([HeaderButtons.hourly]). */
        buttons: HeaderButtons.Want,
        /** What every visible source would show now, for the shared line ([HeaderWidthChecker.lineInputs]). */
        line: com.weatherwidget.shared.util.HeaderLine.Inputs? = null,
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
            line = line,
        )

        val precipText = if (isPrecipVisible && headerPrecipProbability != null) "$headerPrecipProbability%" else null
        // The shared drop order (HeaderFit): inline nav zones squeeze, then the icon and
        // temperature shrink, before anything drops.
        val measured = HeaderWidthChecker.measureHeader(
            context = context,
            widthDp = widthDp,
            headerScale = headerScale,
            apiSourceText = safeSourceIndicator,
            apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows),
            currentTempText = formattedTemp,
            currentTempSizeDp = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
            deltaText = null,
            deltaLabelText = null,
            precipText = precipText,
            precipTextSizeDp = precipTextSizeDp,
            dateText = null,
            includeIcon = true,
            baseButtons = buttons.baseButtons,
            wantHome = buttons.wantHome,
            wantMonths = buttons.wantMonths,
            hourly = true,
            line = line,
            nominalDate = HeaderWidthChecker.nominalDate(java.time.LocalDate.now()),
        )
        val fit = HeaderFit.resolve(measured)
        android.util.Log.d(HeaderFit.LOG_TAG, HeaderFit.logLine("hourly", widthDp.toFloat(), headerScale, fit, measured))

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

        HeaderRemoteViewsBinder.applyFit(views, fit, isDeltaVisible = false, isPrecipVisible = isPrecipVisible)

        return HeaderResult(
            fit = fit,
            measured = measured,
            shown = HeaderButtons.shown(buttons, fit),
            isPrecipVisible = isPrecipVisible,
            headerScale = headerScale,
        )
    }
}
