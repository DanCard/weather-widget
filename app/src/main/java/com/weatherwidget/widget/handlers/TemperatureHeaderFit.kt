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
            line = header.line,
        )
        // The stations zone in the inline nav row shows only for today, so the header fit needs
        // Current observations only while the graph shows some of today (:shared HeaderButtons).
        val headerButtons = HeaderButtons.hourly(
            observationsInView = HeaderButtons.hourlyObservationsInView(
                centre = centerTime,
                backHours = zoom.backHours,
                forwardHours = zoom.forwardHours,
                today = today,
            ),
        )

        // The shared drop order (HeaderFit): inline nav zones squeeze, then the icon and
        // temperature shrink, before anything drops; "from yest" only where it needs neither.
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
            includeIcon = true,
            baseButtons = headerButtons.baseButtons,
            wantHome = headerButtons.wantHome,
            wantMonths = headerButtons.wantMonths,
            hourly = true,
            line = header.line,
            nominalDate = HeaderWidthChecker.nominalDate(today),
        )
        return Resolved(headerScale, headerButtons, deltaLabelText, measured, HeaderFit.resolve(measured))
    }

    /**
     * Where the RemoteViews header row stops covering the graph bitmap, in bitmap px from its top.
     *
     * The context annotations (dominant station, "Actual temperature data from X") use this as their
     * search ceiling (`plans/261106-actuals-source-label-clears-header.md`); value and ghost labels
     * ignore the header (`da576280`). Vertical only — the annotations are wider than the gaps between
     * header items.
     * The band runs from the bitmap top to [HEADER_ROW_CENTER_DP] plus half the row's tallest item:
     * the temperature, the weather icon, the rain % when shown, or a centre button.
     */
    fun headerBandBottomPx(
        context: Context,
        resolved: Resolved,
        header: TemperatureWidgetState.HeaderState,
        bitmapScale: Float,
    ): Float {
        val density = context.resources.displayMetrics.density
        val textScale = resolved.headerScale * resolved.fit.textScale
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
