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
}
