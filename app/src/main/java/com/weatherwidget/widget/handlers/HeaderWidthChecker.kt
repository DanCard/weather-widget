package com.weatherwidget.widget.handlers

import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
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
     * Width of the daily header button pair when centred, in dp, for [iconCount] buttons.
     * Unscaled, like the zones [positionDailyIcons] lays out; the bitmap renderer applies its own
     * labelScale.
     */
    fun dailyCenterIconsWidthDp(iconCount: Int, widthDp: Int): Float =
        if (iconCount <= 0) 0f else dailyCenterIconZoneWidthDp(widthDp) * iconCount

    /**
     * Zone width [positionDailyIcons] will actually apply, so measurement and layout cannot drift.
     *
     * Three rungs — airy / middle / tight — because a 20dp icon in a 24dp zone leaves 4dp of air
     * and the buttons read as one control. The wider zones are set via `setViewLayoutWidth`
     * (API 31+); below that the 24dp XML width stands, which happens to equal the tight zone.
     */
    fun dailyCenterIconZoneWidthDp(widthDp: Int): Float {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            return HeaderConstants.DAILY_CENTER_ICON_ZONE_NARROW_DP
        }
        return when {
            widthDp >= HeaderConstants.DAILY_EXTRA_WIDE_HEADER_MIN_WIDTH_DP ->
                HeaderConstants.DAILY_CENTER_ICON_ZONE_EXTRA_WIDE_DP
            widthDp >= HeaderConstants.DAILY_WIDE_HEADER_MIN_WIDTH_DP ->
                HeaderConstants.DAILY_CENTER_ICON_ZONE_WIDE_DP
            widthDp >= HeaderConstants.DAILY_MEDIUM_HEADER_MIN_WIDTH_DP ->
                HeaderConstants.DAILY_CENTER_ICON_ZONE_MEDIUM_DP
            else -> HeaderConstants.DAILY_CENTER_ICON_ZONE_NARROW_DP
        }
    }

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
    ): HeaderFit.Measured {
        val widthPx = dpToPx(context, widthDp.toFloat())
        fun scaledDp(dp: Float) = dpToPx(context, dp) * headerScale
        fun scaledText(text: String?, sizeDp: Float, marginDp: Float = 0f) =
            if (text.isNullOrBlank()) 0f else scaledDp(marginDp) + textWidthPx(context, text, sizeDp) * headerScale
        return HeaderFit.Measured(
            width = widthPx,
            rightCluster = (widthPx - resolveApiLeftPx(context, widthPx, apiSourceText, apiTextSizeDp)) * headerScale,
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
            icon = if (includeIcon) scaledDp(HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP) else 0f,
            date = scaledText(dateText, HeaderConstants.DATE_TEXT_SIZE_DP),
            caption = scaledText(deltaLabelText, HeaderConstants.DELTA_LABEL_TEXT_SIZE_DP, HeaderConstants.DELTA_LABEL_MARGIN_START_DP),
            baseButtons = baseButtons,
            wantHome = wantHome,
            wantMonths = wantMonths,
            centreButton = dpToPx(context, if (hourly) hourlyCenterIconZoneWidthDp(widthDp) else dailyCenterIconZoneWidthDp(widthDp)),
            inlineButton = dpToPx(context, if (hourly) nominalInlineZoneWidthDp(widthDp) else HeaderConstants.DAILY_INLINE_ICON_ZONE_WIDTH_DP),
            inlineLead = dpToPx(context, if (hourly) INLINE_NAV_FIRST_ZONE_MARGIN_DP.toFloat() else HeaderConstants.DAILY_INLINE_FIRST_ZONE_MARGIN_DP),
            minInlineButton = if (hourly && canResizeInlineZones()) dpToPx(context, HOURLY_MIN_INLINE_ZONE_DP) else 0f,
            minTextScale = if (hourly) HOURLY_MIN_TEXT_SCALE else 1f,
        )
    }

    /** The hourly header's centred zone width [positionCenterIcons] applies (API 31+). */
    fun hourlyCenterIconZoneWidthDp(widthDp: Int): Float = when {
        widthDp >= 450 -> 56f
        widthDp >= 420 -> 40f
        else -> 24f
    }

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
    ): Float {
        val widthPx = dpToPx(context, widthDp.toFloat())
        if (widthPx <= 0f) return 1f

        if (widthDp < WIDE_HEADER_MIN_WIDTH_DP) return 1f

        val leftClusterRight = resolveLeftClusterRightPx(
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
        val rightClusterWidth = (widthPx - apiLeft).coerceAtLeast(0f)
        val occupiedWidth = leftClusterRight + rightClusterWidth
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
