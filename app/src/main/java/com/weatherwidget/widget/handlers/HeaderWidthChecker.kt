package com.weatherwidget.widget.handlers

import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
import com.weatherwidget.shared.util.HeaderButtonZone
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

    /**
     * Exact width of `top_right_header_container` minus the `api_source` text width, in dp:
     * `settings_touch_zone` (29dp) - container `marginEnd` (2dp) + `api_touch_zone` padding (10 + 7dp) = 44dp.
     */
    internal const val RIGHT_CLUSTER_FIXED_DP = 44f

    /**
     * A header action button's preferred zone width, daily and hourly alike: the icon plus the
     * shared [HeaderButtonZone] air for this width. Below API 31 the XML width stands (zones
     * cannot be resized), so measurement uses that.
     */
    fun centerIconZoneWidthDp(widthDp: Int): Float =
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            HeaderConstants.CENTER_ICON_ZONE_XML_WIDTH_DP
        } else {
            HeaderButtonZone.zoneDp(HeaderConstants.CENTER_ICON_SIZE_DP, widthDp.toFloat())
        }

    /** Narrowest [HeaderFit] may squeeze a button zone to; the XML width below API 31 (no squeeze). */
    fun minCenterIconZoneWidthDp(): Float =
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            HeaderConstants.CENTER_ICON_ZONE_XML_WIDTH_DP
        } else {
            HeaderButtonZone.minZoneDp(HeaderConstants.CENTER_ICON_SIZE_DP)
        }

    /** The button zone width [fit] chose, in dp; the preferred one when it chose none. */
    fun centerIconZoneWidthDp(fit: HeaderFit.Plan, widthDp: Int, density: Float): Float =
        fit.buttonZone.takeIf { it > 0f }?.let { it / density } ?: centerIconZoneWidthDp(widthDp)

    /**
     * A header's pieces measured for [HeaderFit]; the caller resolves and logs both.
     *
     * Text, the weather icon and the API label are measured at [headerScale]; button zones are
     * measured in unscaled dp. A null text or zero [baseButtons] means that piece is absent.
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
        /** All enabled sources' labels so rightCluster (and button positions) stay pinned across API toggles. */
        allApiSourceLabels: List<String> = emptyList(),
    ): HeaderFit.Measured {
        val widthPx = dpToPx(context, widthDp.toFloat())
        fun scaledDp(dp: Float) = dpToPx(context, dp) * headerScale
        fun scaledText(text: String?, sizeDp: Float, marginDp: Float = 0f) =
            if (text.isNullOrBlank()) 0f else scaledDp(marginDp) + textWidthPx(context, text, sizeDp) * headerScale
        val maxApiTextPx = (allApiSourceLabels + apiSourceText).maxOf { textWidthPx(context, it, apiTextSizeDp) }
        val rightClusterPx = dpToPx(context, RIGHT_CLUSTER_FIXED_DP) + maxApiTextPx * headerScale
        val iconSlot = scaledDp(HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP)
        return HeaderFit.Measured(
            width = widthPx,
            rightCluster = rightClusterPx,
            gap = dpToPx(context, HeaderConstants.DATE_HORIZONTAL_GAP_DP),
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
            buttonWidth = dpToPx(context, centerIconZoneWidthDp(widthDp)),
            minButtonWidth = dpToPx(context, minCenterIconZoneWidthDp()),
        )
    }

    /**
     * The header buttons' icon scale: [WIDE_HEADER_SCALE] on any header at least
     * [WIDE_HEADER_MIN_WIDTH_DP] wide, capped so the icon keeps [HeaderButtonZone.MIN_AIR_DP]
     * inside a zone the fit narrowed.
     */
    fun buttonScale(widthDp: Int, fit: HeaderFit.Plan, density: Float): Float {
        if (widthDp < WIDE_HEADER_MIN_WIDTH_DP) return 1f
        val zoneDp = when (fit.placement) {
            HeaderFit.ButtonPlacement.SHOWN -> centerIconZoneWidthDp(fit, widthDp, density)
            HeaderFit.ButtonPlacement.HIDDEN -> null
        } ?: return WIDE_HEADER_SCALE
        val roomy = (zoneDp - HeaderButtonZone.MIN_AIR_DP) / HeaderConstants.CENTER_ICON_SIZE_DP
        return roomy.coerceIn(1f, WIDE_HEADER_SCALE)
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
        allApiSourceLabels: List<String> = emptyList(),
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
        val maxApiTextPx = (allApiSourceLabels + apiSourceText).maxOf { textWidthPx(context, it, apiTextSizeDp) }
        val liveRight = dpToPx(context, RIGHT_CLUSTER_FIXED_DP) + maxApiTextPx
        val occupancy = (liveLeft + liveRight) / widthPx
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
