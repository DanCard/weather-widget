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
    private const val WEATHER_ICON_WIDTH_DP = 24f
    private const val WEATHER_ICON_END_MARGIN_DP = 2f
    private const val HEADER_DATE_HORIZONTAL_GAP_DP = 6f
    private const val API_SOURCE_MARGIN_END_DP = 32f
    private const val API_SOURCE_CONTAINER_PADDING_DP = 14f
    private const val DELTA_MARGIN_START_DP = 4f
    private const val PRECIP_MARGIN_START_DP = 8f
    private const val CURRENT_TEMP_TEXT_SIZE_DP = 22f
    private const val CURRENT_TEMP_DELTA_TEXT_SIZE_SP = 14f

    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun resolveHeaderDisclosure(
        context: Context,
        widthDp: Int,
        apiSourceText: String,
        apiTextSizeSp: Float,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeSp: Float?,
): HeaderDisclosureLevel {
    val widthPx = dpToPx(context, widthDp.toFloat())

        val leftClusterRightFull = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = deltaText,
            precipText = precipText,
            precipTextSizeSp = precipTextSizeSp,
            includeIcon = true,
        )
        val apiLeft = resolveApiLeftPx(context, widthPx, apiSourceText, apiTextSizeSp)
        val gapPx = dpToPx(context, HEADER_DATE_HORIZONTAL_GAP_DP)

        if (leftClusterRightFull + gapPx <= apiLeft) {
            return HeaderDisclosureLevel.FULL
        }

        val leftClusterRightNoIcon = resolveLeftClusterRightPx(
            context = context,
            currentTempText = currentTempText,
            deltaText = deltaText,
            precipText = precipText,
            precipTextSizeSp = precipTextSizeSp,
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
            precipTextSizeSp = precipTextSizeSp,
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
            precipTextSizeSp = null,
            includeIcon = false,
        )
        if (leftClusterRightMinimal + gapPx <= apiLeft) {
            return HeaderDisclosureLevel.MINIMAL
        }

        return HeaderDisclosureLevel.NONE
    }

    private fun resolveLeftClusterRightPx(
        context: Context,
        currentTempText: String?,
        deltaText: String?,
        precipText: String?,
        precipTextSizeSp: Float?,
        includeIcon: Boolean,
    ): Float {
        var width = 0f
        if (includeIcon) {
            width += dpToPx(context, WEATHER_ICON_WIDTH_DP + WEATHER_ICON_END_MARGIN_DP)
        }
        if (!currentTempText.isNullOrBlank()) {
            width += currentTempTextWidthPx(context, currentTempText)
        }
        if (!deltaText.isNullOrBlank()) {
            width += dpToPx(context, DELTA_MARGIN_START_DP)
            width += textWidthPx(context, deltaText, CURRENT_TEMP_DELTA_TEXT_SIZE_SP)
        }
        if (!precipText.isNullOrBlank() && precipTextSizeSp != null) {
            width += dpToPx(context, PRECIP_MARGIN_START_DP)
            width += textWidthPx(context, precipText, precipTextSizeSp)
        }
        return width
    }

    private fun resolveApiLeftPx(
        context: Context,
        widthPx: Float,
        apiSourceText: String,
        apiTextSizeSp: Float,
    ): Float {
        val apiContainerWidth = dpToPx(context, API_SOURCE_CONTAINER_PADDING_DP) +
            textWidthPx(context, apiSourceText, apiTextSizeSp)
        return widthPx - dpToPx(context, API_SOURCE_MARGIN_END_DP) - apiContainerWidth
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        )
    }

    private fun textWidthPx(context: Context, text: String, textSizeSp: Float): Float {
        measurePaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            textSizeSp,
            context.resources.displayMetrics,
        )
        return measurePaint.measureText(text)
    }

    private fun currentTempTextWidthPx(context: Context, text: String): Float {
        measurePaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            CURRENT_TEMP_TEXT_SIZE_DP,
            context.resources.displayMetrics,
        )
        return measurePaint.measureText(text)
    }
}