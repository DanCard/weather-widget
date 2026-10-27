package com.weatherwidget.widget.handlers

import android.content.Context
import com.weatherwidget.R
import com.weatherwidget.shared.graph.GraphLabelArea
import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.shared.util.HeaderButtons
import com.weatherwidget.shared.util.HeaderFit
import com.weatherwidget.widget.ZoomWindow
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The hourly header's scale and shared fit ([HeaderFit]), resolved once from the header state.
 *
 * Two callers need the same answer: [TemperatureViewBinder] lays the RemoteViews header out from it,
 * and [TemperatureStateResolver] maps what it draws into the graph bitmap as label obstacles before
 * rendering (`plans/261027-shared-graph-label-top-limit.md`) — the header sits over the bitmap's top
 * strip, which the labels may use wherever the header leaves it free.
 */
internal object TemperatureHeaderFit {
    data class Resolved(
        val headerScale: Float,
        val headerButtons: HeaderButtons.Want,
        val deltaLabelText: String,
        val measured: HeaderFit.Measured,
        val fit: HeaderFit.Plan,
        /**
         * The source label and gear as drawn, in widget px. [measured]'s right cluster is the shared
         * line's reserve (`HeaderLine`: the widest visible source's), wider than what is on screen.
         */
        val drawnRightCluster: Float,
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
        val widthPx = HeaderWidthChecker.dpToPx(context, widthDp.toFloat())
        val drawnRightCluster = (widthPx - HeaderWidthChecker.resolveApiLeftPx(
            context, widthPx, header.sourceIndicator, HeaderConstants.apiTextSizeDp(numRows),
        )) * headerScale
        return Resolved(headerScale, headerButtons, deltaLabelText, measured, HeaderFit.resolve(measured), drawnRightCluster)
    }

    /**
     * Where the header row's items land in the graph bitmap, as label obstacles
     * ([GraphLabelArea.headerObstacles]).
     *
     * Horizontally, the shared fit's spans ([HeaderFit.occupiedSpans], widget px; the right cluster as
     * drawn, [Resolved.drawnRightCluster]) shifted by the bitmap's inset ([BITMAP_INSET_START_DP]: the
     * root's 8dp padding plus graph_view's 4dp margin) and scaled by [bitmapScale]. Vertically, a band
     * from the bitmap top to [HEADER_ROW_CENTER_DP] plus half the row's tallest item: the temperature,
     * the rain % when shown, or the weather icon. Tight to the ink on purpose — the day labels sit
     * just under the weather icon, and a looser band pushed them out of the top corners (Fold 4).
     */
    fun graphObstacles(
        context: Context,
        resolved: Resolved,
        header: TemperatureWidgetState.HeaderState,
        bitmapScale: Float,
        graphWidthPx: Float,
    ): List<GraphRect> {
        val density = context.resources.displayMetrics.density
        val textScale = resolved.headerScale * resolved.fit.textScale
        val tallestDp = maxOf(
            header.currentTempSizeDp * textScale,
            HeaderConstants.WEATHER_ICON_SIZE_DP * textScale,
            if (resolved.fit.showRain) header.precipTextSizeDp * resolved.headerScale else 0f,
            HeaderConstants.CENTER_ICON_SIZE_DP,
        )
        val bandBottomDp = HEADER_ROW_CENTER_DP + tallestDp / 2f
        return GraphLabelArea.headerObstacles(
            spans = HeaderFit.occupiedSpans(resolved.measured.copy(rightCluster = resolved.drawnRightCluster), resolved.fit),
            originX = BITMAP_INSET_START_DP * density,
            scale = bitmapScale,
            bandTop = GraphLabelArea.TOP,
            bandBottom = bandBottomDp * density * bitmapScale,
            graphWidth = graphWidthPx,
        )
    }

    /** graph_view's left edge from the widget's: widget_root's 8dp padding plus its own 4dp margin. */
    private const val BITMAP_INSET_START_DP = 12f

    /**
     * The header row's vertical centre, in dp below the bitmap top. Each header zone is 50dp tall,
     * starts 8dp (left cluster) to 10dp (right) above the root's content top, and centres its child
     * with a -10dp top margin — (50 - h) / 2 - 10, a centre 15dp down the zone — so 5–7dp into the
     * bitmap. 5 matches the Fold 4's weather-icon ink (2026-10-27, outlined on device).
     */
    private const val HEADER_ROW_CENTER_DP = 5f
}
