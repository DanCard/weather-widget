package com.weatherwidget.widget.handlers

import android.content.Context
import android.graphics.Paint
import android.util.TypedValue

enum class HeaderDisclosureLevel {
    FULL,
    NO_ICON,
    NO_ICON_NO_DELTA,
    MINIMAL,
    NONE,
}

fun HeaderDisclosureLevel.showsIcon(): Boolean = this == HeaderDisclosureLevel.FULL

fun HeaderDisclosureLevel.showsDelta(): Boolean = this == HeaderDisclosureLevel.FULL || this == HeaderDisclosureLevel.NO_ICON

fun HeaderDisclosureLevel.showsPrecip(): Boolean = this == HeaderDisclosureLevel.FULL || this == HeaderDisclosureLevel.NO_ICON || this == HeaderDisclosureLevel.NO_ICON_NO_DELTA

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

    fun resolveHeaderDisclosure(
        context: Context,
        widthDp: Int,
        apiSourceText: String,
        apiTextSizeDp: Float,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        currentTempSizeDp: Float = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
): HeaderDisclosureLevel {
    val widthPx = dpToPx(context, widthDp.toFloat())

        val leftClusterRightFull = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = deltaText,
            precipText = precipText,
            precipTextSizeDp = precipTextSizeDp,
            includeIcon = true,
            currentTempSizeDp = currentTempSizeDp,
        )
        val apiLeft = resolveApiLeftPx(context, widthPx, apiSourceText, apiTextSizeDp)
        val gapPx = dpToPx(context, HeaderConstants.DATE_HORIZONTAL_GAP_DP)

        if (leftClusterRightFull + gapPx <= apiLeft) {
            return HeaderDisclosureLevel.FULL
        }

        val leftClusterRightNoIcon = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = deltaText,
            precipText = precipText,
            precipTextSizeDp = precipTextSizeDp,
            includeIcon = false,
            currentTempSizeDp = currentTempSizeDp,
        )
        if (leftClusterRightNoIcon + gapPx <= apiLeft) {
            return HeaderDisclosureLevel.NO_ICON
        }

        val leftClusterRightNoIconNoDelta = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = null,
            precipText = precipText,
            precipTextSizeDp = precipTextSizeDp,
            includeIcon = false,
            currentTempSizeDp = currentTempSizeDp,
        )
        if (leftClusterRightNoIconNoDelta + gapPx <= apiLeft) {
            return HeaderDisclosureLevel.NO_ICON_NO_DELTA
        }

        val leftClusterRightMinimal = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = null,
            precipText = null,
            precipTextSizeDp = null,
            includeIcon = false,
            currentTempSizeDp = currentTempSizeDp,
        )
        if (leftClusterRightMinimal + gapPx <= apiLeft) {
            return HeaderDisclosureLevel.MINIMAL
        }

        return HeaderDisclosureLevel.NONE
    }

    internal fun resolveLeftClusterRightPx(
        context: Context,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
        includeIcon: Boolean,
        currentTempSizeDp: Float = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
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
