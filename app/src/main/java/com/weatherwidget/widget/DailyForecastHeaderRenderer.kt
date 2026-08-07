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
        val deltaLabelPaint: Paint,
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

        val apiLeft = resolveApiLeftPx(header, widthPx, labelScale, layout.density, headerPaints.dateMeasurePaint)
        // The "from yest" caption is opportunistic: drawn only when the date (higher priority)
        // still fits with it, or when there is no date and the cluster clears the API label.
        val deltaLabelText = header.deltaLabelText?.takeIf { it.isNotBlank() }
        val showDeltaLabel = deltaLabelText != null &&
            resolveDeltaLabelVisible(header, widthPx, layout, headerPaints, labelScale, upOffset, apiLeft)

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

            if (showDeltaLabel && deltaLabelText != null) {
                cursorX += (HeaderConstants.DELTA_LABEL_MARGIN_START_DP * labelScale).dp(layout.density)
                val labelBaseline =
                    tempCenterY - (headerPaints.deltaLabelPaint.ascent() + headerPaints.deltaLabelPaint.descent()) / 2f
                canvas.drawText(deltaLabelText, cursorX, labelBaseline, headerPaints.deltaLabelPaint)
                cursorX += headerPaints.deltaLabelPaint.measureText(deltaLabelText)
            }
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

        if (!header.apiSourceText.isNullOrBlank()) {
            val apiMarginEndDp = HeaderConstants.API_SOURCE_MARGIN_END_DP + HeaderConstants.API_SINGLE_SOURCE_EXTRA_MARGIN_DP
            val apiX = widthPx - (apiMarginEndDp * labelScale).dp(layout.density) + (10f * labelScale).dp(layout.density)
            val apiY = -headerPaints.apiPaint.ascent() + upOffset
            canvas.drawText(header.apiSourceText, apiX, apiY, headerPaints.apiPaint)
        }

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
        val apiLeft = resolveApiLeftPx(header, widthPx, labelScale, layout.density, headerPaints.dateMeasurePaint)
        // Match drawHeader: include the "from yest" caption in the cluster when it would be drawn.
        val includeDeltaLabel = !header.deltaLabelText.isNullOrBlank() &&
            resolveDeltaLabelVisible(header, widthPx, layout, headerPaints, labelScale, upOffset, apiLeft)
        val leftClusterRight = resolveLeftClusterRight(header, headerPaints, labelScale, layout.density, includeDeltaLabel)

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
        includeDeltaLabel: Boolean = false,
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
            if (includeDeltaLabel && !header.deltaLabelText.isNullOrBlank()) {
                cursorX += (HeaderConstants.DELTA_LABEL_MARGIN_START_DP * labelScale).dp(density)
                cursorX += paints.deltaLabelPaint.measureText(header.deltaLabelText)
            }
        }
        if (header.showPrecip && !header.precipText.isNullOrBlank()) {
            cursorX += (HeaderConstants.PRECIP_MARGIN_START_DP * labelScale).dp(density)
            cursorX += paints.precipPaint.measureText(header.precipText)
        }
        return cursorX
    }

    private fun resolveApiLeftPx(
        header: DailyForecastGraphRenderer.HeaderRenderData,
        widthPx: Int,
        labelScale: Float,
        density: Float,
        measurePaint: Paint,
    ): Float {
        val apiMarginEndDp = HeaderConstants.API_SOURCE_MARGIN_END_DP + HeaderConstants.API_SINGLE_SOURCE_EXTRA_MARGIN_DP
        val apiMarginEndPx = (apiMarginEndDp * labelScale).dp(density)
        val apiShiftPx = (10f * labelScale).dp(density)
        val apiContainerWidth = (HeaderConstants.API_SOURCE_CONTAINER_PADDING_DP * labelScale).dp(density) +
            measurePaint.measureText(header.apiSourceText ?: "")
        return widthPx - apiMarginEndPx - apiContainerWidth + apiShiftPx
    }

    /**
     * Whether the "from yest" caption is drawn in the bitmap header. The date has priority:
     * if the caption would crowd the date out, the caption is dropped instead.
     */
    private fun resolveDeltaLabelVisible(
        header: DailyForecastGraphRenderer.HeaderRenderData,
        widthPx: Int,
        layout: DailyGraphLayoutInfo,
        headerPaints: HeaderPaintSet,
        labelScale: Float,
        upOffset: Float,
        apiLeft: Float,
    ): Boolean {
        if (!header.showDelta || header.deltaText.isNullOrBlank() || header.deltaLabelText.isNullOrBlank()) {
            return false
        }
        val gapPx = (HeaderConstants.DATE_HORIZONTAL_GAP_DP * labelScale).dp(layout.density)
        val leftWithLabel = resolveLeftClusterRight(header, headerPaints, labelScale, layout.density, includeDeltaLabel = true)
        val hasDateText = !header.dateText.isNullOrBlank()
        val dateFitsWithLabel = hasDateText &&
            resolveHeaderDateLayout(
                header = header,
                widthPx = widthPx,
                layout = layout,
                leftClusterRight = leftWithLabel,
                dateRightBoundary = apiLeft,
                headerPaints = headerPaints,
                labelScale = labelScale,
                upOffset = upOffset,
            ) != null
        val dateFitsWithoutLabel = hasDateText && !dateFitsWithLabel &&
            resolveHeaderDateLayout(
                header = header,
                widthPx = widthPx,
                layout = layout,
                leftClusterRight = resolveLeftClusterRight(header, headerPaints, labelScale, layout.density, includeDeltaLabel = false),
                dateRightBoundary = apiLeft,
                headerPaints = headerPaints,
                labelScale = labelScale,
                upOffset = upOffset,
            ) != null
        return shouldDrawDeltaLabel(
            hasDateText = hasDateText,
            dateFitsWithLabel = dateFitsWithLabel,
            dateFitsWithoutLabel = dateFitsWithoutLabel,
            leftWithLabelRight = leftWithLabel,
            apiLeft = apiLeft,
            gapPx = gapPx,
        )
    }

    /**
     * Pure decision for the opportunistic delta caption, extracted for framework-free tests.
     * Priority order: date text > caption. When the date still fits with the caption, both are
     * shown; when the caption would displace the date, the caption is hidden; otherwise the
     * caption shows if the cluster clears the API label on the right.
     */
    internal fun shouldDrawDeltaLabel(
        hasDateText: Boolean,
        dateFitsWithLabel: Boolean,
        dateFitsWithoutLabel: Boolean,
        leftWithLabelRight: Float,
        apiLeft: Float,
        gapPx: Float,
    ): Boolean =
        when {
            dateFitsWithLabel -> true
            hasDateText && dateFitsWithoutLabel -> false
            else -> leftWithLabelRight + gapPx <= apiLeft
        }

    /**
     * The lowest y the header's ink reaches **within `xLeft..xRight`**, in bitmap pixels — i.e. how
     * far down the header actually intrudes over one column, not over the widget as a whole.
     *
     * `DailyGraphLayoutResolver.TOP_PADDING_DP` reserves 50dp above `graphTop` for this header, but
     * the header fills well under half of it, and over any given column usually far less than that.
     * The large-Today overlay needs the remainder: it was fenced out by a guessed constant
     * (`TodayColumnOverlayRenderer.HEADER_BAND_RECLAIM_FRACTION`) which cost the Samsung fold's
     * overlay every row it had — all three drew across the forecast bars while ~17 px of empty band
     * sat above them.
     *
     * The x test is not a refinement, it is the point. Measured full-width on that same fold this
     * returns 30.6 — the 24dp weather icon in the far-left corner, x ≈ -6..27, against a Today
     * column at x 122..199 — which is 0.3 px worse than useless: still short of a row, having
     * reserved space for something nowhere near the column.
     *
     * Measured, not assumed: every branch mirrors [drawHeader]'s own geometry — same `labelScale`,
     * same `upOffset`, same paints, same cursor walk, same date placement. Text uses real ink bounds
     * and falls back to the font box when the platform reports none, so Robolectric — which has no
     * font engine — reads the header as DEEPER than it draws and pushes the overlay down rather than
     * silently over the header. Icons have no ink to measure and always use their full drawn box.
     *
     * Resolved from the pre-suppression header data on purpose: the date may still be dropped later,
     * and an item reported that is not drawn only pushes the overlay down.
     */
    internal fun resolveHeaderInkBottom(
        header: DailyForecastGraphRenderer.HeaderRenderData,
        widthPx: Int,
        layout: DailyGraphLayoutInfo,
        xLeft: Float,
        xRight: Float,
    ): Float {
        val labelScale = layout.bitmapScale.coerceAtMost(1f) * header.headerScale
        val paints = getHeaderPaintSet(header, labelScale, layout.density)
        val upOffset = -(2f * labelScale).dp(layout.density)
        val apiLeft = resolveApiLeftPx(header, widthPx, labelScale, layout.density, paints.dateMeasurePaint)
        var bottom = 0f

        // Strict inequality, matching Bounds.intersects: an item that merely touches the column edge
        // is not over it.
        fun consider(left: Float, right: Float, itemBottom: Float) {
            if (left < xRight && xLeft < right) bottom = maxOf(bottom, itemBottom)
        }
        fun considerText(text: String?, paint: Paint, left: Float, baseline: Float): Float {
            if (text.isNullOrBlank()) return 0f
            val width = paint.measureText(text)
            consider(left, left + width, baseline + inkBottomBelowBaseline(paint, text))
            return width
        }

        // --- left cluster: the same cursor walk drawHeader performs ---
        var cursorX = -(3f * labelScale).dp(layout.density)
        if (header.showIcon && header.iconRes != null && header.iconRes != 0) {
            val iconSize = (HeaderConstants.WEATHER_ICON_SIZE_DP * labelScale).dp(layout.density)
            consider(cursorX, cursorX + iconSize, upOffset + iconSize)
            cursorX += ((HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP) * labelScale)
                .dp(layout.density)
        }

        val tempBaseline = -paints.tempPaint.ascent() + upOffset
        cursorX += considerText(header.currentTempText, paints.tempPaint, cursorX, tempBaseline)

        // Delta and its caption are centred on the temperature's visual centre, so they sit lower
        // than their own baseline math alone suggests.
        val tempCenterY = tempBaseline + (paints.tempPaint.ascent() + paints.tempPaint.descent()) / 2f
        if (header.showDelta && !header.deltaText.isNullOrBlank()) {
            cursorX += (HeaderConstants.DELTA_MARGIN_START_DP * labelScale).dp(layout.density)
            cursorX += considerText(
                header.deltaText,
                paints.deltaPaint,
                cursorX,
                tempCenterY - (paints.deltaPaint.ascent() + paints.deltaPaint.descent()) / 2f,
            )
            val deltaLabelText = header.deltaLabelText?.takeIf { it.isNotBlank() }
            if (deltaLabelText != null &&
                resolveDeltaLabelVisible(header, widthPx, layout, paints, labelScale, upOffset, apiLeft)
            ) {
                cursorX += (HeaderConstants.DELTA_LABEL_MARGIN_START_DP * labelScale).dp(layout.density)
                cursorX += considerText(
                    deltaLabelText,
                    paints.deltaLabelPaint,
                    cursorX,
                    tempCenterY - (paints.deltaLabelPaint.ascent() + paints.deltaLabelPaint.descent()) / 2f,
                )
            }
        }
        if (header.showPrecip && !header.precipText.isNullOrBlank()) {
            cursorX += (HeaderConstants.PRECIP_MARGIN_START_DP * labelScale).dp(layout.density)
            cursorX += considerText(
                header.precipText,
                paints.precipPaint,
                cursorX,
                -paints.precipPaint.ascent() + upOffset,
            )
        }
        val leftClusterRight = cursorX

        // --- right cluster: gear anchored to the right edge, API label right-aligned ---
        if (header.settingsIconRes != 0) {
            val gearSize = (HeaderConstants.SETTINGS_ICON_SIZE_DP * labelScale).dp(layout.density)
            val gearRight = widthPx - (2f * labelScale).dp(layout.density)
            consider(gearRight - gearSize, gearRight, upOffset + gearSize)
        }
        if (!header.apiSourceText.isNullOrBlank()) {
            val apiMarginEndDp = HeaderConstants.API_SOURCE_MARGIN_END_DP + HeaderConstants.API_SINGLE_SOURCE_EXTRA_MARGIN_DP
            val apiX = widthPx - (apiMarginEndDp * labelScale).dp(layout.density) +
                (10f * labelScale).dp(layout.density)
            // Drawn with Align.RIGHT, so apiX is the text's right edge.
            val apiWidth = paints.apiPaint.measureText(header.apiSourceText)
            consider(
                apiX - apiWidth,
                apiX,
                -paints.apiPaint.ascent() + upOffset + inkBottomBelowBaseline(paints.apiPaint, header.apiSourceText),
            )
        }

        // --- centre date, placed exactly as drawHeader places it ---
        resolveHeaderDateLayout(
            header = header,
            widthPx = widthPx,
            layout = layout,
            leftClusterRight = leftClusterRight,
            dateRightBoundary = apiLeft,
            headerPaints = paints,
            labelScale = labelScale,
            upOffset = upOffset,
        )?.let { dateLayout ->
            consider(
                dateLayout.bounds.left,
                dateLayout.bounds.right,
                dateLayout.baseline + inkBottomBelowBaseline(paints.datePaint, header.dateText.orEmpty()),
            )
        }

        return bottom
    }

    /**
     * How far [text]'s ink drops below the baseline, falling back to the font's descent when the
     * platform reports no ink bounds. Digits and degree signs have no descenders at all, so the
     * fallback is the conservative direction.
     */
    private fun inkBottomBelowBaseline(paint: Paint, text: String): Float {
        val ink = Rect()
        paint.getTextBounds(text, 0, text.length, ink)
        return if (ink.isEmpty) paint.descent() else ink.bottom.toFloat()
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
            // Same hue as the delta, dimmed and smaller so the caption reads as secondary text.
            deltaLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (header.deltaColor and 0x00FFFFFF) or 0xB3000000.toInt()
                textSize = (HeaderConstants.DELTA_LABEL_TEXT_SIZE_DP * labelScale).dp(density)
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
