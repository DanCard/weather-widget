package com.weatherwidget.widget.handlers

import android.content.Context
import com.weatherwidget.R
import com.weatherwidget.shared.util.HeaderButtons
import com.weatherwidget.shared.util.HeaderFit
import com.weatherwidget.widget.ZoomWindow
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The hourly header's scale and shared fit ([HeaderFit]), resolved once from the header state for
 * [TemperatureViewBinder].
 */
internal object TemperatureHeaderFit {
    data class Resolved(
        val headerScale: Float,
        val headerButtons: HeaderButtons.Want,
        val deltaLabelText: String,
        val measured: HeaderFit.Measured,
        val fit: HeaderFit.Plan,
    )

    fun resolve(
        context: Context,
        header: TemperatureWidgetState.HeaderState,
        widthDp: Int,
        numRows: Int,
        zoom: ZoomWindow,
        centerTime: LocalDateTime,
        today: LocalDate,
    ): Resolved {
        val headerScale = HeaderWidthChecker.computeHeaderScale(
            context = context,
            widthDp = widthDp,
            apiSourceText = header.sourceIndicator,
            apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows),
            currentTempText = header.currentTemp,
            deltaText = header.deltaText,
            precipText = header.precipProbability,
            precipTextSizeDp = header.precipTextSizeDp,
            allApiSourceLabels = header.allApiSourceLabels,
        )
        val headerButtons = HeaderButtons.hourly(
            observationsInView = HeaderButtons.hourlyObservationsInView(
                centre = centerTime,
                backHours = zoom.backHours,
                forwardHours = zoom.forwardHours,
                today = today,
            ),
        )

        val deltaLabelText = context.getString(R.string.header_delta_from_yesterday)
        val measured = HeaderWidthChecker.measureHeader(
            context = context,
            widthDp = widthDp,
            headerScale = headerScale,
            apiSourceText = header.sourceIndicator,
            apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows),
            currentTempText = header.currentTemp,
            currentTempSizeDp = header.currentTempSizeDp,
            deltaText = if (header.isDeltaVisible) header.deltaText else null,
            deltaLabelText = deltaLabelText,
            precipText = if (header.isPrecipVisible) header.precipProbability else null,
            precipTextSizeDp = header.precipTextSizeDp,
            dateText = null,
            includeIcon = header.iconRes != 0,
            baseButtons = headerButtons.baseButtons,
            wantHome = headerButtons.wantHome,
            wantMonths = headerButtons.wantMonths,
            allApiSourceLabels = header.allApiSourceLabels,
        )
        return Resolved(headerScale, headerButtons, deltaLabelText, measured, HeaderFit.resolve(measured))
    }

    /**
     * Where the RemoteViews header row stops covering the graph bitmap, in bitmap px from its top.
     */
    fun headerBandBottomPx(
        context: Context,
        resolved: Resolved,
        header: TemperatureWidgetState.HeaderState,
        bitmapScale: Float,
    ): Float {
        val density = context.resources.displayMetrics.density
        val textScale = resolved.headerScale
        val tallestDp = maxOf(
            header.currentTempSizeDp * textScale,
            HeaderConstants.WEATHER_ICON_SIZE_DP * textScale,
            if (resolved.fit.showRain) header.precipTextSizeDp * resolved.headerScale else 0f,
            HeaderConstants.CENTER_ICON_SIZE_DP,
        )
        return (HEADER_ROW_CENTER_DP + tallestDp / 2f) * density * bitmapScale
    }

    /**
     * The header row's vertical centre, in dp below the bitmap top. Each header zone is 50dp tall,
     * starts 8dp (left cluster) to 10dp (right) above the root's content top, and centres its child
     * with a -10dp top margin — (50 - h) / 2 - 10, a centre 15dp down the zone — so 5–7dp into the
     * bitmap. 5 matches the Fold 4's weather-icon ink (2026-10-27, outlined on device).
     */
    private const val HEADER_ROW_CENTER_DP = 5f
}
