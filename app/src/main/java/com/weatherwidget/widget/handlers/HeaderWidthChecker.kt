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

    fun resolveHeaderDisclosure(
        context: Context,
        widthDp: Int,
        apiSourceText: String,
        apiTextSizeDp: Float,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeDp: Float?,
): HeaderDisclosureLevel {
    val widthPx = dpToPx(context, widthDp.toFloat())

        val leftClusterRightFull = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = deltaText,
            precipText = precipText,
            precipTextSizeDp = precipTextSizeDp,
            includeIcon = true,
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
    ): Float {
        var width = 0f
        if (includeIcon) {
            width += dpToPx(context, HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP)
        }
        if (!currentTempText.isNullOrBlank()) {
            width += currentTempTextWidthPx(context, currentTempText)
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

    internal fun currentTempTextWidthPx(context: Context, text: String): Float {
        measurePaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
            context.resources.displayMetrics,
        )
        return measurePaint.measureText(text)
    }
}
