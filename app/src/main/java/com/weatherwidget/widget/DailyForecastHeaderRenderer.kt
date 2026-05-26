package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.Log
import com.weatherwidget.widget.handlers.HeaderConstants
import com.weatherwidget.widget.DailyForecastGraphRenderer.LayoutInfo
import com.weatherwidget.widget.DailyForecastGraphRenderer.HEADER_TEXT_COLOR

internal object DailyForecastHeaderRenderer {
    private const val TAG = "DailyHeaderRenderer"

    private const val DUAL_GLYPH = "‖"
    private const val DUAL_TEXT_SIZE_DP = 20f
    // Pill's RIGHT edge sits this far from the widget's right edge — chosen to clear the
    // widest API label (e.g. "Tmrw - Meteo" at 18sp). Touch zone XML uses the same value.
    internal const val DUAL_BUTTON_MARGIN_END_DP = 150f
    private const val DUAL_PILL_PADDING_X_DP = 5f
    private const val DUAL_PILL_PADDING_Y_DP = 2f
    private const val DUAL_PILL_CORNER_DP = 6f
    private const val DUAL_PILL_BG_COLOR = -0x1 // 0xFFFFFFFF
    private const val DUAL_PILL_FG_COLOR = -0xCCCCCD // ~#333333

    internal data class HeaderPaintSet(
        val tempPaint: Paint,
        val deltaPaint: Paint,
        val precipPaint: Paint,
        val apiPaint: Paint,
        val datePaint: Paint,
        val dateMeasurePaint: Paint,
        val dualPaint: Paint,
        val dualActivePaint: Paint,
        val dualPillPaint: Paint,
    )

    internal data class HeaderDateLayout(
        val bounds: RectF,
        val centerX: Float,
        val baseline: Float,
    )

    private data class HeaderPaintCache(val key: String, val set: HeaderPaintSet)
    @Volatile
    private var headerPaintCache: HeaderPaintCache? = null

    fun drawHeader(
        canvas: Canvas,
        context: Context,
        header: DailyForecastGraphRenderer.HeaderRenderData,
        widthPx: Int,
        layout: LayoutInfo,
    ) {
        val labelScale = layout.bitmapScale.coerceAtMost(1f) * header.headerScale
        val headerPaints = getHeaderPaintSet(header, labelScale, layout.density)

        val upOffset = -(2f * labelScale).dp(layout.density)
        var cursorX = -(3f * labelScale).dp(layout.density)

        if (header.showIcon && header.iconRes != null && header.iconRes != 0) {
            val iconSizePx = (HeaderConstants.WEATHER_ICON_SIZE_DP * labelScale).dp(layout.density).toInt()
            try {
                val drawable = androidx.core.content.ContextCompat.getDrawable(context, header.iconRes)?.mutate()
                if (drawable != null) {
                    val iconTop = upOffset.toInt()
                    drawable.setBounds(
                        cursorX.toInt(), iconTop,
                        cursorX.toInt() + iconSizePx, iconTop + iconSizePx,
                    )
                    drawable.draw(canvas)
                }
            } catch (e: Exception) {
                Log.w(TAG, "drawHeader: failed to draw weather icon", e)
            }
            cursorX += ((HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP) * labelScale).dp(layout.density)
        }

        val tempBaseline = -headerPaints.tempPaint.ascent() + upOffset
        val tempCenterY = tempBaseline + (headerPaints.tempPaint.ascent() + headerPaints.tempPaint.descent()) / 2f

        if (!header.currentTempText.isNullOrBlank()) {
            canvas.drawText(header.currentTempText, cursorX, tempBaseline, headerPaints.tempPaint)
            cursorX += headerPaints.tempPaint.measureText(header.currentTempText)
        }

        if (header.showDelta && !header.deltaText.isNullOrBlank()) {
            cursorX += (HeaderConstants.DELTA_MARGIN_START_DP * labelScale).dp(layout.density)
            // Align delta's visual center with the temperature's visual center
            val deltaBaseline = tempCenterY - (headerPaints.deltaPaint.ascent() + headerPaints.deltaPaint.descent()) / 2f
            canvas.drawText(header.deltaText, cursorX, deltaBaseline, headerPaints.deltaPaint)
            cursorX += headerPaints.deltaPaint.measureText(header.deltaText)
        }

        if (header.showPrecip && !header.precipText.isNullOrBlank()) {
            cursorX += (HeaderConstants.PRECIP_MARGIN_START_DP * labelScale).dp(layout.density)
            canvas.drawText(header.precipText, cursorX, -headerPaints.precipPaint.ascent() + upOffset, headerPaints.precipPaint)
            cursorX += headerPaints.precipPaint.measureText(header.precipText)
        }

        if (header.settingsIconRes != 0) {
            val gearSizePx = (HeaderConstants.SETTINGS_ICON_SIZE_DP * labelScale).dp(layout.density).toInt()
            try {
                val drawable = androidx.core.content.ContextCompat.getDrawable(context, header.settingsIconRes)?.mutate()
                if (drawable != null) {
                    drawable.setTint(HEADER_TEXT_COLOR)
                    // Anchor gear at upOffset
                    val gearRight = widthPx - (2f * labelScale).dp(layout.density).toInt()
                    val gearTop = upOffset.toInt()
                    drawable.setBounds(
                        gearRight - gearSizePx, gearTop,
                        gearRight, gearTop + gearSizePx,
                    )
                    drawable.draw(canvas)
                }
            } catch (e: Exception) {
                Log.w(TAG, "drawHeader: failed to draw weather icon", e)
            }
        }

        val apiMarginEndPx = resolveApiMarginEndPx(header, labelScale, layout.density)
        val apiShiftPx = resolveApiShiftPx(labelScale, layout.density)
        if (!header.apiSourceText.isNullOrBlank()) {
            val apiX = widthPx - apiMarginEndPx + apiShiftPx
            val apiY = -headerPaints.apiPaint.ascent() + upOffset
            canvas.drawText(header.apiSourceText, apiX, apiY, headerPaints.apiPaint)
        }

        val apiContainerWidth = (HeaderConstants.API_SOURCE_CONTAINER_PADDING_DP * labelScale).dp(layout.density) +
            headerPaints.dateMeasurePaint.measureText(header.apiSourceText ?: "")
        val apiLeft = widthPx - apiMarginEndPx - apiContainerWidth + apiShiftPx

        val dualLeftEdge = if (header.showDualButton) {
            drawDualButton(
                canvas, headerPaints, widthPx.toFloat(),
                labelScale = labelScale, bitmapScale = layout.bitmapScale,
                density = layout.density, active = header.dualActive,
                offsetY = upOffset
            )
        } else {
            apiLeft
        }

        resolveHeaderDateLayout(
            header = header,
            widthPx = widthPx,
            layout = layout,
            leftClusterRight = cursorX,
            dateRightBoundary = dualLeftEdge,
            headerPaints = headerPaints,
            labelScale = labelScale,
            upOffset = upOffset,
        )?.let { dateLayout ->
            canvas.drawText(header.dateText!!, dateLayout.centerX, dateLayout.baseline, headerPaints.datePaint)
        }
    }

    internal fun resolveHeaderDateBounds(
        header: DailyForecastGraphRenderer.HeaderRenderData,
        widthPx: Int,
        layout: LayoutInfo,
        extraPaddingPx: Float = 0f,
    ): RectF? {
        val labelScale = layout.bitmapScale.coerceAtMost(1f) * header.headerScale
        val headerPaints = getHeaderPaintSet(header, labelScale, layout.density)
        val upOffset = -(2f * labelScale).dp(layout.density)
        val leftClusterRight = resolveLeftClusterRight(header, headerPaints, labelScale, layout.density)
        val dateRightBoundary = resolveDateRightBoundary(header, headerPaints, widthPx, layout, labelScale)
        val bounds = resolveHeaderDateLayout(
            header = header,
            widthPx = widthPx,
            layout = layout,
            leftClusterRight = leftClusterRight,
            dateRightBoundary = dateRightBoundary,
            headerPaints = headerPaints,
            labelScale = labelScale,
            upOffset = upOffset,
        )?.bounds ?: return null
        val minVisibleBottom = (HeaderConstants.DATE_TEXT_SIZE_DP * labelScale).dp(layout.density)
        return RectF(
            bounds.left - extraPaddingPx,
            minOf(bounds.top - extraPaddingPx, 0f),
            bounds.right + extraPaddingPx,
            maxOf(bounds.bottom + extraPaddingPx, minVisibleBottom),
        )
    }

    private fun resolveHeaderDateLayout(
        header: DailyForecastGraphRenderer.HeaderRenderData,
        widthPx: Int,
        layout: LayoutInfo,
        leftClusterRight: Float,
        dateRightBoundary: Float,
        headerPaints: HeaderPaintSet,
        labelScale: Float,
        upOffset: Float,
    ): HeaderDateLayout? {
        val dateText = header.dateText?.takeIf { it.isNotBlank() } ?: return null
        val dateWidth = headerPaints.datePaint.measureText(dateText)
        val gapPx = (HeaderConstants.DATE_HORIZONTAL_GAP_DP * labelScale).dp(layout.density)
        val centerX = widthPx / 2f
        val centerLeft = centerX - dateWidth / 2f
        val centerRight = centerX + dateWidth / 2f
        val dateBaseline = -headerPaints.datePaint.ascent() + upOffset

        val drawX = if (centerLeft >= leftClusterRight + gapPx && centerRight <= dateRightBoundary - gapPx) {
            centerX
        } else {
            val rightX = if (header.showDualButton) {
                dateRightBoundary - gapPx - dateWidth / 2f
            } else {
                widthPx - (HeaderConstants.DATE_RIGHT_MARGIN_DP * labelScale).dp(layout.density)
            }
            val rightLeft = rightX - dateWidth / 2f
            val rightRight = rightX + dateWidth / 2f
            if (rightLeft >= leftClusterRight + gapPx && rightRight <= dateRightBoundary - gapPx) {
                rightX
            } else {
                return null
            }
        }

        return HeaderDateLayout(
            bounds = RectF(
                drawX - dateWidth / 2f,
                dateBaseline + headerPaints.datePaint.ascent(),
                drawX + dateWidth / 2f,
                dateBaseline + headerPaints.datePaint.descent(),
            ),
            centerX = drawX,
            baseline = dateBaseline,
        )
    }

    private fun resolveLeftClusterRight(
        header: DailyForecastGraphRenderer.HeaderRenderData,
        paints: HeaderPaintSet,
        labelScale: Float,
        density: Float,
    ): Float {
        var cursorX = -(3f * labelScale).dp(density)
        if (header.showIcon && header.iconRes != null && header.iconRes != 0) {
            cursorX += ((HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP) * labelScale).dp(density)
        }
        if (!header.currentTempText.isNullOrBlank()) {
            cursorX += paints.tempPaint.measureText(header.currentTempText)
        }
        if (header.showDelta && !header.deltaText.isNullOrBlank()) {
            cursorX += (HeaderConstants.DELTA_MARGIN_START_DP * labelScale).dp(density)
            cursorX += paints.deltaPaint.measureText(header.deltaText)
        }
        if (header.showPrecip && !header.precipText.isNullOrBlank()) {
            cursorX += (HeaderConstants.PRECIP_MARGIN_START_DP * labelScale).dp(density)
            cursorX += paints.precipPaint.measureText(header.precipText)
        }
        return cursorX
    }

    private fun resolveDateRightBoundary(
        header: DailyForecastGraphRenderer.HeaderRenderData,
        paints: HeaderPaintSet,
        widthPx: Int,
        layout: LayoutInfo,
        labelScale: Float,
    ): Float {
        if (header.showDualButton) {
            return resolveDualButtonLeft(paints, widthPx.toFloat(), labelScale, layout.bitmapScale, layout.density)
        }
        val apiMarginEndPx = resolveApiMarginEndPx(header, labelScale, layout.density)
        val apiShiftPx = resolveApiShiftPx(labelScale, layout.density)
        val apiContainerWidth = (HeaderConstants.API_SOURCE_CONTAINER_PADDING_DP * labelScale).dp(layout.density) +
            paints.dateMeasurePaint.measureText(header.apiSourceText ?: "")
        return widthPx - apiMarginEndPx - apiContainerWidth + apiShiftPx
    }

    private fun resolveApiMarginEndPx(
        header: DailyForecastGraphRenderer.HeaderRenderData,
        labelScale: Float,
        density: Float,
    ): Float {
        val isDualApiText = header.apiSourceText?.contains(" - ") == true
        val apiMarginEndDp = HeaderConstants.API_SOURCE_MARGIN_END_DP +
            (if (isDualApiText) 0f else HeaderConstants.API_SINGLE_SOURCE_EXTRA_MARGIN_DP)
        return (apiMarginEndDp * labelScale).dp(density)
    }

    private fun resolveApiShiftPx(labelScale: Float, density: Float): Float =
        (10f * labelScale).dp(density)

    private fun drawDualButton(
        canvas: Canvas,
        paints: HeaderPaintSet,
        widthPx: Float,
        labelScale: Float,
        bitmapScale: Float,
        density: Float,
        active: Boolean,
        offsetY: Float = 0f,
    ): Float {
        val pillLeft = resolveDualButtonLeft(paints, widthPx, labelScale, bitmapScale, density)
        val padX = (DUAL_PILL_PADDING_X_DP * labelScale).dp(density)
        val padY = (DUAL_PILL_PADDING_Y_DP * labelScale).dp(density)
        val corner = (DUAL_PILL_CORNER_DP * labelScale).dp(density)
        val glyphPaint = if (active) paints.dualActivePaint else paints.dualPaint
        val glyphAscent = glyphPaint.ascent()
        val glyphDescent = glyphPaint.descent()
        val glyphHeight = glyphDescent - glyphAscent

        val pillRight = pillLeft + glyphPaint.measureText(DUAL_GLYPH) + 2 * padX
        val pillTop = offsetY
        val pillBottom = offsetY + glyphHeight + 2 * padY

        if (active) {
            canvas.drawRoundRect(pillLeft, pillTop, pillRight, pillBottom, corner, corner, paints.dualPillPaint)
        }
        val glyphX = pillLeft + padX
        val glyphY = offsetY + padY - glyphAscent
        canvas.drawText(DUAL_GLYPH, glyphX, glyphY, glyphPaint)

        return pillLeft
    }

    private fun resolveDualButtonLeft(
        paints: HeaderPaintSet,
        widthPx: Float,
        labelScale: Float,
        bitmapScale: Float,
        density: Float,
    ): Float {
        val padX = (DUAL_PILL_PADDING_X_DP * labelScale).dp(density)
        val glyphPaint = paints.dualPaint
        val glyphWidth = glyphPaint.measureText(DUAL_GLYPH)
        // Use bitmapScale (not labelScale) for the right-edge offset so the pill lands at
        // DUAL_BUTTON_MARGIN_END_DP screen-dp from the right regardless of bitmap downscaling.
        // labelScale is clamped to <= 1f and would mis-place the pill when bitmapScale > 1.
        val pillRight = widthPx - (DUAL_BUTTON_MARGIN_END_DP * bitmapScale).dp(density)
        return pillRight - (glyphWidth + 2 * padX)
    }

    private fun getHeaderPaintSet(
        header: DailyForecastGraphRenderer.HeaderRenderData,
        labelScale: Float,
        density: Float,
    ): HeaderPaintSet {
        val key = "$labelScale-${header.deltaColor}-${header.precipColor}-${header.precipTextSizeDp}-${header.apiTextSizeDp}"
        val cache = headerPaintCache
        if (cache?.key == key) return cache.set

        val tempTextSizePx = (HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP * labelScale).dp(density)
        val dualTextSizePx = (DUAL_TEXT_SIZE_DP * labelScale).dp(density)
        val set = HeaderPaintSet(
            tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = HEADER_TEXT_COLOR
                textSize = tempTextSizePx
                textAlign = Paint.Align.LEFT
            },
            deltaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = header.deltaColor
                textSize = (HeaderConstants.DELTA_TEXT_SIZE_DP * labelScale).dp(density)
                textAlign = Paint.Align.LEFT
            },
            precipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = header.precipColor
                textSize = (header.precipTextSizeDp * labelScale).dp(density)
                textAlign = Paint.Align.LEFT
            },
            apiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = HEADER_TEXT_COLOR
                textSize = (header.apiTextSizeDp * labelScale).dp(density)
                textAlign = Paint.Align.RIGHT
            },
            datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = HEADER_TEXT_COLOR
                textSize = (HeaderConstants.DATE_TEXT_SIZE_DP * labelScale).dp(density)
                textAlign = Paint.Align.CENTER
            },
            dateMeasurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = (header.apiTextSizeDp * labelScale).dp(density)
            },
            dualPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = HEADER_TEXT_COLOR
                textSize = dualTextSizePx
                textAlign = Paint.Align.LEFT
                isFakeBoldText = true
            },
            dualActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = DUAL_PILL_FG_COLOR
                textSize = dualTextSizePx
                textAlign = Paint.Align.LEFT
                isFakeBoldText = true
            },
            dualPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = DUAL_PILL_BG_COLOR
                style = Paint.Style.FILL
            },
        )
        headerPaintCache = HeaderPaintCache(key, set)
        return set
    }

    private fun Float.dp(density: Float): Float = this * density
}
