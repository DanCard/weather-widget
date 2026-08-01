package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.Log
import com.weatherwidget.widget.handlers.HeaderConstants
import com.weatherwidget.widget.DailyGraphLayoutInfo

internal object DailyForecastHeaderRenderer {
    private val HEADER_TEXT_COLOR = 0xAAFFFFFF.toInt()
    private const val TAG = "DailyHeaderRenderer"

    internal data class HeaderPaintSet(
        val tempPaint: Paint,
        val deltaPaint: Paint,
        val precipPaint: Paint,
        val apiPaint: Paint,
        val datePaint: Paint,
        val dateMeasurePaint: Paint,
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
        layout: DailyGraphLayoutInfo,
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

        val apiMarginEndDp = HeaderConstants.API_SOURCE_MARGIN_END_DP + HeaderConstants.API_SINGLE_SOURCE_EXTRA_MARGIN_DP
        val apiMarginEndPx = (apiMarginEndDp * labelScale).dp(layout.density)
        val apiShiftPx = (10f * labelScale).dp(layout.density)

        if (!header.apiSourceText.isNullOrBlank()) {
            val apiX = widthPx - apiMarginEndPx + apiShiftPx
            val apiY = -headerPaints.apiPaint.ascent() + upOffset
            canvas.drawText(header.apiSourceText, apiX, apiY, headerPaints.apiPaint)
        }

        val apiContainerWidth = (HeaderConstants.API_SOURCE_CONTAINER_PADDING_DP * labelScale).dp(layout.density) +
            headerPaints.dateMeasurePaint.measureText(header.apiSourceText ?: "")
        val apiLeft = widthPx - apiMarginEndPx - apiContainerWidth + apiShiftPx

        resolveHeaderDateLayout(
            header = header,
            widthPx = widthPx,
            layout = layout,
            leftClusterRight = cursorX,
            dateRightBoundary = apiLeft,
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
        layout: DailyGraphLayoutInfo,
        extraPaddingPx: Float = 0f,
    ): RectF? {
        val labelScale = layout.bitmapScale.coerceAtMost(1f) * header.headerScale
        val headerPaints = getHeaderPaintSet(header, labelScale, layout.density)
        val upOffset = -(2f * labelScale).dp(layout.density)
        val leftClusterRight = resolveLeftClusterRight(header, headerPaints, labelScale, layout.density)

        val apiMarginEndDp = HeaderConstants.API_SOURCE_MARGIN_END_DP + HeaderConstants.API_SINGLE_SOURCE_EXTRA_MARGIN_DP
        val apiMarginEndPx = (apiMarginEndDp * labelScale).dp(layout.density)
        val apiShiftPx = (10f * labelScale).dp(layout.density)
        val apiContainerWidth = (HeaderConstants.API_SOURCE_CONTAINER_PADDING_DP * labelScale).dp(layout.density) +
            headerPaints.dateMeasurePaint.measureText(header.apiSourceText ?: "")
        val apiLeft = widthPx - apiMarginEndPx - apiContainerWidth + apiShiftPx

        val bounds = resolveHeaderDateLayout(
            header = header,
            widthPx = widthPx,
            layout = layout,
            leftClusterRight = leftClusterRight,
            dateRightBoundary = apiLeft,
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
        layout: DailyGraphLayoutInfo,
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
            val rightX = widthPx - (HeaderConstants.DATE_RIGHT_MARGIN_DP * labelScale).dp(layout.density)
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

    private fun getHeaderPaintSet(
        header: DailyForecastGraphRenderer.HeaderRenderData,
        labelScale: Float,
        density: Float,
    ): HeaderPaintSet {
        val key = "$labelScale-${header.deltaColor}-${header.precipColor}-${header.precipTextSizeDp}-${header.apiTextSizeDp}"
        val cache = headerPaintCache
        if (cache?.key == key) return cache.set

        val tempTextSizePx = (HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP * labelScale).dp(density)
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
        )
        headerPaintCache = HeaderPaintCache(key, set)
        return set
    }

    private fun Float.dp(density: Float): Float = this * density
}
