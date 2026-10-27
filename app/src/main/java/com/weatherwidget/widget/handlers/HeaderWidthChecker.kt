package com.weatherwidget.widget.handlers

import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
import com.weatherwidget.shared.util.HeaderButtonZone
import com.weatherwidget.shared.util.HeaderContent
import com.weatherwidget.shared.util.HeaderLine
import com.weatherwidget.shared.util.SourceCoverage
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.shared.util.HeaderFit

object HeaderWidthChecker {
    internal val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Scale factor applied to header icons and fonts when the header row has plenty
     * of empty space (e.g. Samsung full-width widgets).
     *
     * Returns 1.35 when occupied header content fills less than 50% of the widget width
     * AND the widget is at least 450dp wide; 1.0 otherwise.
     */
    private const val WIDE_HEADER_SCALE = 1.35f
    private const val WIDE_HEADER_OCCUPANCY_THRESHOLD = 0.50f
    private const val WIDE_HEADER_MIN_WIDTH_DP = 450

    /** Narrowest the hourly header's inline zones squeeze to: the 20 dp icon plus 4 dp either side. */
    const val HOURLY_MIN_INLINE_ZONE_DP = 28f

    /** Smallest scale for the hourly header's weather icon and temperature beside inline buttons. */
    const val HOURLY_MIN_TEXT_SCALE = 0.8f

    private const val INLINE_NAV_ZONE_XML_WIDTH_DP = 36
    private const val INLINE_NAV_FIRST_ZONE_MARGIN_DP = 1

    /** The hourly header's inline nav zone width before any squeezing ([HeaderFit]). */
    fun nominalInlineZoneWidthDp(widthDp: Int): Float =
        if (canResizeInlineZones()) {
            when {
                widthDp < 350 -> 32f
                widthDp < 400 -> 40f
                else -> 48f
            }
        } else {
            INLINE_NAV_ZONE_XML_WIDTH_DP.toFloat()
        }

    /** RemoteViews.setViewLayoutWidth exists from API 31; below it the XML width stands. */
    private fun canResizeInlineZones(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    /**
     * A centred header button's preferred zone, daily and hourly alike: the icon plus the shared
     * [HeaderButtonZone] air for this width. Unscaled, like the zones [positionDailyIcons] /
     * [positionCenterIcons] lay out. Below API 31 the XML width stands (zones cannot be resized),
     * so measurement uses that.
     */
    fun centerIconZoneWidthDp(widthDp: Int): Float =
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            HeaderConstants.CENTER_ICON_ZONE_XML_WIDTH_DP
        } else {
            HeaderButtonZone.zoneDp(HeaderConstants.CENTER_ICON_SIZE_DP, widthDp.toFloat())
        }

    /** Narrowest [HeaderFit] may squeeze a centred zone to; the XML width below API 31 (no squeeze). */
    fun minCenterIconZoneWidthDp(): Float =
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            HeaderConstants.CENTER_ICON_ZONE_XML_WIDTH_DP
        } else {
            HeaderButtonZone.minZoneDp(HeaderConstants.CENTER_ICON_SIZE_DP)
        }

    /** The centred zone [fit] chose, in dp; the preferred one when it chose none. */
    fun centerIconZoneWidthDp(fit: HeaderFit.Plan, widthDp: Int, density: Float): Float =
        fit.centreZone.takeIf { it > 0f }?.let { it / density } ?: centerIconZoneWidthDp(widthDp)

    /** Width of the daily header button pair when inline in the left cluster, in dp. */
    fun dailyInlineIconsWidthDp(iconCount: Int): Float =
        if (iconCount <= 0) 0f
        else HeaderConstants.DAILY_INLINE_ICON_ZONE_WIDTH_DP * iconCount +
            HeaderConstants.DAILY_INLINE_FIRST_ZONE_MARGIN_DP

    /**
     * A header's pieces measured for the shared drop order ([HeaderFit],
     * `plans/261022-shared-header-fit-priority.md`); the caller resolves and logs both.
     *
     * Text, the weather icon and the API label are measured at [headerScale], the size they are
     * drawn; the button zones are not scaled ([positionDailyIcons] / [positionCenterIcons] size them
     * in plain dp). A null text or a zero [baseButtons] means that piece is absent, not dropped.
     *
     * [hourly]: the hourly header's button zones, and its compression — inline zones narrow to
     * [HOURLY_MIN_INLINE_ZONE_DP], then the icon and temperature shrink to [HOURLY_MIN_TEXT_SCALE]
     * before anything drops (user, 2026-10-09; the daily header never shrinks).
     */
    fun measureHeader(
        context: Context,
        widthDp: Int,
        headerScale: Float,
        apiSourceText: String,
        apiTextSizeDp: Float,
        currentTempText: String?,
        currentTempSizeDp: Float,
        deltaText: String?,
        deltaLabelText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        dateText: String?,
        includeIcon: Boolean,
        baseButtons: Int,
        wantHome: Boolean,
        wantMonths: Boolean,
        hourly: Boolean = false,
        /**
         * What every visible source would show now ([HeaderContent.lineInputs]): the shared line
         * ([HeaderLine]) is placed against it, so a source tap moves neither buttons nor date.
         * Null keeps the fixed [HeaderFit.SEAM_OFFSET_DP] line.
         */
        line: HeaderLine.Inputs? = null,
        /** The daily date, passed by hourly headers too so the line matches across views. */
        nominalDate: String? = null,
    ): HeaderFit.Measured {
        val widthPx = dpToPx(context, widthDp.toFloat())
        fun scaledDp(dp: Float) = dpToPx(context, dp) * headerScale
        fun scaledText(text: String?, sizeDp: Float, marginDp: Float = 0f) =
            if (text.isNullOrBlank()) 0f else scaledDp(marginDp) + textWidthPx(context, text, sizeDp) * headerScale
        fun rightCluster(sourceText: String) =
            (widthPx - resolveApiLeftPx(context, widthPx, sourceText, apiTextSizeDp)) * headerScale
        val iconSlot = scaledDp(HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP)
        val measured = HeaderFit.Measured(
            width = widthPx,
            rightCluster = rightCluster(apiSourceText),
            gap = dpToPx(context, HeaderConstants.DATE_HORIZONTAL_GAP_DP),
            // The line the buttons end on; positionDailyIcons can only move them off the middle on
            // API 31+ (setViewLayoutMargin).
            seamOffset = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                scaledDp(HeaderFit.SEAM_OFFSET_DP)
            } else null,
            temp = if (currentTempText.isNullOrBlank()) 0f
                else currentTempTextWidthPx(context, currentTempText, currentTempSizeDp) * headerScale,
            rain = if (precipTextSizeDp == null) 0f
                else scaledText(precipText, precipTextSizeDp, HeaderConstants.PRECIP_MARGIN_START_DP),
            delta = scaledText(deltaText, HeaderConstants.DELTA_TEXT_SIZE_DP, HeaderConstants.DELTA_MARGIN_START_DP),
            icon = if (includeIcon) iconSlot else 0f,
            date = scaledText(dateText, HeaderConstants.DATE_TEXT_SIZE_DP),
            caption = scaledText(deltaLabelText, HeaderConstants.DELTA_LABEL_TEXT_SIZE_DP, HeaderConstants.DELTA_LABEL_MARGIN_START_DP),
            baseButtons = baseButtons,
            wantHome = wantHome,
            wantMonths = wantMonths,
            centreButton = dpToPx(context, centerIconZoneWidthDp(widthDp)),
            minCentreButton = dpToPx(context, minCenterIconZoneWidthDp()),
            inlineButton = dpToPx(context, if (hourly) nominalInlineZoneWidthDp(widthDp) else HeaderConstants.DAILY_INLINE_ICON_ZONE_WIDTH_DP),
            inlineLead = dpToPx(context, if (hourly) INLINE_NAV_FIRST_ZONE_MARGIN_DP.toFloat() else HeaderConstants.DAILY_INLINE_FIRST_ZONE_MARGIN_DP),
            minInlineButton = if (hourly && canResizeInlineZones()) dpToPx(context, HOURLY_MIN_INLINE_ZONE_DP) else 0f,
            minTextScale = if (hourly) HOURLY_MIN_TEXT_SCALE else 1f,
        )
        line ?: return measured
        val measure = lineMeasure(context, widthPx, headerScale, apiTextSizeDp, currentTempSizeDp)
        return HeaderLine.place(measured, measure, line, iconSlot = if (includeIcon) iconSlot else 0f, nominalDate = nominalDate)
    }

    /** Header pieces at [headerScale], as [measureHeader] draws them, for [HeaderLine]. */
    private fun lineMeasure(
        context: Context,
        widthPx: Float,
        headerScale: Float,
        apiTextSizeDp: Float,
        currentTempSizeDp: Float,
    ) = HeaderLine.Measure { piece, text, fontScale ->
        fun scaledText(sizeDp: Float, marginDp: Float = 0f) =
            if (text.isBlank()) 0f
            else (dpToPx(context, marginDp) + textWidthPx(context, text, sizeDp)) * headerScale
        when (piece) {
            HeaderLine.Piece.TEMP -> currentTempTextWidthPx(context, text, currentTempSizeDp) * headerScale
            HeaderLine.Piece.DELTA -> scaledText(HeaderConstants.DELTA_TEXT_SIZE_DP, HeaderConstants.DELTA_MARGIN_START_DP)
            HeaderLine.Piece.RAIN ->
                scaledText(HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP * fontScale, HeaderConstants.PRECIP_MARGIN_START_DP)
            HeaderLine.Piece.DATE -> scaledText(HeaderConstants.DATE_TEXT_SIZE_DP)
            HeaderLine.Piece.SOURCE -> (widthPx - resolveApiLeftPx(context, widthPx, text, apiTextSizeDp)) * headerScale
        }
    }

    /**
     * The header buttons' icon scale: [WIDE_HEADER_SCALE] on any header at least
     * [WIDE_HEADER_MIN_WIDTH_DP] wide, whatever the row shows, so a source tap never resizes them
     * (Fold 4, 2026-10-27: they shrank on Google, the one source with a rain %). Capped so the icon
     * keeps [HeaderButtonZone.MIN_AIR_DP] inside a zone the fit narrowed.
     */
    fun buttonScale(widthDp: Int, fit: HeaderFit.Plan, density: Float): Float {
        if (widthDp < WIDE_HEADER_MIN_WIDTH_DP) return 1f
        val zoneDp = when (fit.placement) {
            HeaderFit.ButtonPlacement.CENTER -> centerIconZoneWidthDp(fit, widthDp, density)
            HeaderFit.ButtonPlacement.INLINE -> fit.inlineZone.takeIf { it > 0f }?.let { it / density }
            HeaderFit.ButtonPlacement.HIDDEN -> null
        } ?: return WIDE_HEADER_SCALE
        val roomy = (zoneDp - HeaderButtonZone.MIN_AIR_DP) / HeaderConstants.CENTER_ICON_SIZE_DP
        return roomy.coerceIn(1f, WIDE_HEADER_SCALE)
    }

    /**
     * [HeaderContent.lineInputs] for the widget's visible sources at this site: what
     * [measureHeader]'s shared line reserves. Reads its own rows — every visible source over the
     * header's 6 h rain window — because the render paths load only the displayed sources'.
     */
    @Suppress("LongParameterList")
    suspend fun lineInputs(
        context: Context,
        stateManager: com.weatherwidget.widget.WidgetStateManager,
        view: HeaderContent.View,
        now: java.time.LocalDateTime,
        centerTime: java.time.LocalDateTime,
        lat: Double,
        lon: Double,
        widthDp: Int,
    ): HeaderLine.Inputs {
        val sourceIds = SourceCoverage.effectiveSources(stateManager.getVisibleSourcesOrder().map { it.id }, lat, lon)
        val zone = java.time.ZoneId.systemDefault()
        val fromMs = minOf(now, centerTime).minusHours(1).atZone(zone).toInstant().toEpochMilli()
        val toMs = maxOf(now, centerTime).plusHours(LINE_RAIN_WINDOW_HOURS).atZone(zone).toInstant().toEpochMilli()
        val rows = GraphDataLoader.loadHeaderLineHourlyForecasts(
            com.weatherwidget.data.local.WeatherDatabase.getDatabase(context).hourlyForecastDao(),
            lat,
            lon,
            fromMs,
            toMs,
            sourceIds + com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id,
        )
        return HeaderContent.lineInputs(
            view = view,
            hourly = rows.map { it.toHourlyForecast() },
            sourceIds = sourceIds,
            now = now,
            centerTime = centerTime,
            lat = lat,
            lon = lon,
            widthDp = widthDp,
        )
    }

    /** The header's rain % looks 6 h ahead; one more covers the hour it starts in. */
    private const val LINE_RAIN_WINDOW_HOURS = 7L

    /** The daily header's date, reserved by every header's line so it matches across views. */
    fun nominalDate(today: java.time.LocalDate): String = today.format(DailyViewHandler.headerDateFormatter())

    fun computeHeaderScale(
        context: Context,
        widthDp: Int,
        apiSourceText: String,
        apiTextSizeDp: Float,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        currentTempSizeDp: Float = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
        /**
         * What every visible source would show ([HeaderLine.reserve]): occupancy is judged against
         * it, so every source gets the same scale. Wider live text still counts.
         */
        line: HeaderLine.Inputs? = null,
    ): Float {
        val widthPx = dpToPx(context, widthDp.toFloat())
        if (widthPx <= 0f) return 1f

        if (widthDp < WIDE_HEADER_MIN_WIDTH_DP) return 1f

        val liveLeft = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = deltaText,
            precipText = precipText,
            precipTextSizeDp = precipTextSizeDp,
            includeIcon = true,
            currentTempSizeDp = currentTempSizeDp,
        )
        val apiLeft = resolveApiLeftPx(context, widthPx, apiSourceText, apiTextSizeDp)

        // Occupied = left cluster + (widthPx - apiLeft), i.e. the right cluster width
        val liveRight = (widthPx - apiLeft).coerceAtLeast(0f)
        val reserve = line?.let {
            val iconSlot = dpToPx(context, HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP)
            HeaderLine.reserve(lineMeasure(context, widthPx, 1f, apiTextSizeDp, currentTempSizeDp), it, iconSlot)
        }
        val occupiedWidth = maxOf(liveLeft, reserve?.left ?: 0f) + maxOf(liveRight, reserve?.right ?: 0f)
        val occupancy = occupiedWidth / widthPx

        return if (occupancy < WIDE_HEADER_OCCUPANCY_THRESHOLD) WIDE_HEADER_SCALE else 1f
    }

    internal fun resolveLeftClusterRightPx(
        context: Context,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        includeIcon: Boolean,
        currentTempSizeDp: Float = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
        deltaLabelText: String? = null,
    ): Float {
        var width = 0f
        if (includeIcon) {
            width += dpToPx(context, HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP)
        }
        if (!currentTempText.isNullOrBlank()) {
            width += currentTempTextWidthPx(context, currentTempText, currentTempSizeDp)
        }
        if (!deltaText.isNullOrBlank()) {
            width += dpToPx(context, HeaderConstants.DELTA_MARGIN_START_DP)
            width += textWidthPx(context, deltaText, HeaderConstants.DELTA_TEXT_SIZE_DP)
            if (!deltaLabelText.isNullOrBlank()) {
                width += dpToPx(context, HeaderConstants.DELTA_LABEL_MARGIN_START_DP)
                width += textWidthPx(context, deltaLabelText, HeaderConstants.DELTA_LABEL_TEXT_SIZE_DP)
            }
        }
        if (!precipText.isNullOrBlank() && precipTextSizeDp != null) {
            width += dpToPx(context, HeaderConstants.PRECIP_MARGIN_START_DP)
            width += textWidthPx(context, precipText, precipTextSizeDp)
        }
        return width
    }

    internal fun resolveApiLeftPx(
        context: Context,
        widthPx: Float,
        apiSourceText: String,
        apiTextSizeDp: Float,
    ): Float {
        val apiContainerWidth = dpToPx(context, HeaderConstants.API_SOURCE_CONTAINER_PADDING_DP) +
            textWidthPx(context, apiSourceText, apiTextSizeDp)
        val isDualApiText = apiSourceText.contains(" - ")
        val marginEndDp = HeaderConstants.API_SOURCE_MARGIN_END_DP +
            (if (isDualApiText) 0f else HeaderConstants.API_SINGLE_SOURCE_EXTRA_MARGIN_DP)
        return widthPx - dpToPx(context, marginEndDp) - apiContainerWidth
    }

    internal fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        )
    }

    internal fun textWidthPx(context: Context, text: String, textSizeDp: Float): Float {
        measurePaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            textSizeDp,
            context.resources.displayMetrics,
        )
        return measurePaint.measureText(text)
    }

    internal fun currentTempTextWidthPx(
        context: Context,
        text: String,
        currentTempSizeDp: Float = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
    ): Float {
        measurePaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            currentTempSizeDp,
            context.resources.displayMetrics,
        )
        return measurePaint.measureText(text)
    }
}
