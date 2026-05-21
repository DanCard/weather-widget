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

        var cursorX = -(3f * labelScale).dp(layout.density)

        if (header.showIcon && header.iconRes != null && header.iconRes != 0) {
            val iconSizePx = (HeaderConstants.WEATHER_ICON_SIZE_DP * labelScale).dp(layout.density).toInt()
            try {
                val drawable = androidx.core.content.ContextCompat.getDrawable(context, header.iconRes)?.mutate()
                if (drawable != null) {
                    val iconTop = -(2f * labelScale).dp(layout.density).toInt()
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

        if (!header.currentTempText.isNullOrBlank()) {
            canvas.drawText(header.currentTempText, cursorX, -headerPaints.tempPaint.ascent(), headerPaints.tempPaint)
            cursorX += headerPaints.tempPaint.measureText(header.currentTempText)
        }

        if (header.showDelta && !header.deltaText.isNullOrBlank()) {
            cursorX += (HeaderConstants.DELTA_MARGIN_START_DP * labelScale).dp(layout.density)
            canvas.drawText(header.deltaText, cursorX, -headerPaints.deltaPaint.ascent(), headerPaints.deltaPaint)
            cursorX += headerPaints.deltaPaint.measureText(header.deltaText)
        }

        if (header.showPrecip && !header.precipText.isNullOrBlank()) {
            cursorX += (HeaderConstants.PRECIP_MARGIN_START_DP * labelScale).dp(layout.density)
            canvas.drawText(header.precipText, cursorX, -headerPaints.precipPaint.ascent(), headerPaints.precipPaint)
            cursorX += headerPaints.precipPaint.measureText(header.precipText)
        }

        if (header.settingsIconRes != 0) {
            val gearSizePx = (HeaderConstants.SETTINGS_ICON_SIZE_DP * labelScale).dp(layout.density).toInt()
            try {
                val drawable = androidx.core.content.ContextCompat.getDrawable(context, header.settingsIconRes)?.mutate()
                if (drawable != null) {
                    drawable.setTint(HEADER_TEXT_COLOR)
                    // Push gear further up and right (clipping is O.K.)
                    val gearRight = widthPx + (10f * labelScale).dp(layout.density).toInt()
                    val gearTop = -(8f * labelScale).dp(layout.density).toInt()
                    drawable.setBounds(
                        gearRight - gearSizePx, gearTop,
                        gearRight, gearTop + gearSizePx,
                    )
                    drawable.draw(canvas)
                }
            } catch (e: Exception) {
                Log.w(TAG, "drawHeader: failed to draw settings icon", e)
            }
        }

        val isDualApiText = header.apiSourceText?.contains(" - ") == true
        val apiMarginEndDp = HeaderConstants.API_SOURCE_MARGIN_END_DP +
            (if (isDualApiText) 0f else HeaderConstants.API_SINGLE_SOURCE_EXTRA_MARGIN_DP)
        val apiMarginEndPx = (apiMarginEndDp * labelScale).dp(layout.density)

        val apiShiftPx = (10f * labelScale).dp(layout.density)
        if (!header.apiSourceText.isNullOrBlank()) {
            // Push API text further up and right
            val apiX = widthPx - apiMarginEndPx + apiShiftPx
            val apiY = -headerPaints.apiPaint.ascent() - (10f * labelScale).dp(layout.density)
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
            )
        } else {
            apiLeft
        }

        if (!header.dateText.isNullOrBlank()) {
            val dateWidth = headerPaints.datePaint.measureText(header.dateText)
            val leftClusterRight = cursorX
            val gapPx = (HeaderConstants.DATE_HORIZONTAL_GAP_DP * labelScale).dp(layout.density)
            val dateRightBoundary = dualLeftEdge

            val centerX = widthPx / 2f
            val centerLeft = centerX - dateWidth / 2f
            val centerRight = centerX + dateWidth / 2f
            val dateBaseline = -headerPaints.datePaint.ascent()

            // Two-tier placement: try centered first; if that overlaps the dual pill (or
            // the API column on small widgets), fall back to a right-anchored placement
            // that ends just left of the pill (or the regular DATE_RIGHT_MARGIN_DP band
            // when the pill isn't shown).
            val canCenter = centerLeft >= leftClusterRight + gapPx &&
                centerRight <= dateRightBoundary - gapPx
            if (canCenter) {
                canvas.drawText(header.dateText, centerX, dateBaseline, headerPaints.datePaint)
            } else {
                val rightX = if (header.showDualButton) {
                    // Anchor the date's right edge to just left of the pill — the only
                    // position that keeps the visual order (left cluster | date | pill | api).
                    dateRightBoundary - gapPx - dateWidth / 2f
                } else {
                    widthPx - (HeaderConstants.DATE_RIGHT_MARGIN_DP * labelScale).dp(layout.density)
                }
                val rightLeft = rightX - dateWidth / 2f
                val rightRight = rightX + dateWidth / 2f
                if (rightLeft >= leftClusterRight + gapPx && rightRight <= dateRightBoundary - gapPx) {
                    canvas.drawText(header.dateText, rightX, dateBaseline, headerPaints.datePaint)
                }
            }
        }
    }

    private fun drawDualButton(
        canvas: Canvas,
        paints: HeaderPaintSet,
        widthPx: Float,
        labelScale: Float,
        bitmapScale: Float,
        density: Float,
        active: Boolean,
    ): Float {
        val padX = (DUAL_PILL_PADDING_X_DP * labelScale).dp(density)
        val padY = (DUAL_PILL_PADDING_Y_DP * labelScale).dp(density)
        val corner = (DUAL_PILL_CORNER_DP * labelScale).dp(density)
        val glyphPaint = if (active) paints.dualActivePaint else paints.dualPaint
        val glyphWidth = glyphPaint.measureText(DUAL_GLYPH)
        val glyphAscent = glyphPaint.ascent()
        val glyphDescent = glyphPaint.descent()
        val glyphHeight = glyphDescent - glyphAscent

        // Use bitmapScale (not labelScale) for the right-edge offset so the pill lands at
        // DUAL_BUTTON_MARGIN_END_DP screen-dp from the right regardless of bitmap downscaling.
        // labelScale is clamped to <= 1f and would mis-place the pill when bitmapScale > 1.
        val pillRight = widthPx - (DUAL_BUTTON_MARGIN_END_DP * bitmapScale).dp(density)
        val pillLeft = pillRight - (glyphWidth + 2 * padX)
        val pillTop = -padY
        val pillBottom = glyphHeight + padY

        if (active) {
            canvas.drawRoundRect(pillLeft, pillTop, pillRight, pillBottom, corner, corner, paints.dualPillPaint)
        }
        val glyphX = pillLeft + padX
        val glyphY = -glyphAscent
        canvas.drawText(DUAL_GLYPH, glyphX, glyphY, glyphPaint)

        return pillLeft
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
