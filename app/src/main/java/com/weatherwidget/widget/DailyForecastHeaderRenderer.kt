package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.Log
import com.weatherwidget.widget.handlers.HeaderConstants
import com.weatherwidget.widget.DailyForecastGraphRenderer.LayoutInfo
import com.weatherwidget.widget.DailyForecastGraphRenderer.HEADER_TEXT_COLOR

internal object DailyForecastHeaderRenderer {
    private const val TAG = "DailyHeaderRenderer"

    internal data class HeaderPaintSet(
        val tempPaint: Paint,
        val deltaPaint: Paint,
        val precipPaint: Paint,
        val apiPaint: Paint,
        val datePaint: Paint,
        val dateMeasurePaint: Paint,
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
        val labelScale = layout.bitmapScale.coerceAtMost(1f)
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
                    val gearRight = widthPx
                    val gearTop = 0
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

        if (!header.apiSourceText.isNullOrBlank()) {
            val apiX = widthPx - (HeaderConstants.API_SOURCE_MARGIN_END_DP * labelScale).dp(layout.density)
            canvas.drawText(header.apiSourceText, apiX, -headerPaints.apiPaint.ascent(), headerPaints.apiPaint)
        }

        if (!header.dateText.isNullOrBlank()) {
            val dateWidth = headerPaints.datePaint.measureText(header.dateText)
            val leftClusterRight = cursorX
            val apiContainerWidth = (HeaderConstants.API_SOURCE_CONTAINER_PADDING_DP * labelScale).dp(layout.density) +
                headerPaints.dateMeasurePaint.measureText(header.apiSourceText ?: "")
            val apiLeft = widthPx - (HeaderConstants.API_SOURCE_MARGIN_END_DP * labelScale).dp(layout.density) - apiContainerWidth
            val gapPx = (HeaderConstants.DATE_HORIZONTAL_GAP_DP * labelScale).dp(layout.density)

            val centerX = widthPx / 2f
            val centerLeft = centerX - dateWidth / 2f
            val centerRight = centerX + dateWidth / 2f
            val dateBaseline = -headerPaints.datePaint.ascent()
            if (centerLeft >= leftClusterRight + gapPx && centerRight <= apiLeft - gapPx) {
                canvas.drawText(header.dateText, centerX, dateBaseline, headerPaints.datePaint)
            } else {
                val rightMarginPx = (HeaderConstants.DATE_RIGHT_MARGIN_DP * labelScale).dp(layout.density)
                val rightX = widthPx - rightMarginPx
                val rightLeft = rightX - dateWidth / 2f
                val rightRight = rightX + dateWidth / 2f
                if (rightLeft >= leftClusterRight + gapPx && rightRight <= apiLeft - gapPx) {
                    canvas.drawText(header.dateText, rightX, dateBaseline, headerPaints.datePaint)
                }
            }
        }
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
