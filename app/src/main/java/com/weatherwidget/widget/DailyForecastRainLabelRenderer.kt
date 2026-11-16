package com.weatherwidget.widget

import android.graphics.*
import com.weatherwidget.shared.graph.DailyRainLabelLayout
import com.weatherwidget.shared.graph.DailyRainLabelPlanner
import com.weatherwidget.widget.DailyForecastGraphRenderer.DayData
import com.weatherwidget.widget.DailyForecastGraphRenderer.DailyRainLabelPlacement
import com.weatherwidget.widget.DailyForecastGraphRenderer.RainLabelKind

/**
 * Android host for the shared [DailyRainLabelLayout]: measures and draws the daily graph's rain
 * labels with the rain paint. Where each label goes is decided in :shared.
 */
internal object DailyForecastRainLabelRenderer {
    private const val PLATFORM = "android"

    internal data class TextMetrics(
        val ascent: Float,
        val descent: Float,
    )

    /** Every kind shares the rain paint; NIGHT_SCALE and the peak scale arrive in [fontScale]. */
    internal fun createRainPaint(paints: DailyGraphPaintSet, fontScale: Float): Paint =
        Paint(paints.rainTextPaint).apply { textSize = paints.rainTextPaint.textSize * fontScale }

    /** Measures with [createRainPaint]; checked against desktop by DailyRainLabelHostContract. */
    internal fun rainLabelHost(paints: DailyGraphPaintSet): DailyRainLabelLayout.Host =
        object : DailyRainLabelLayout.Host {
            override fun measure(text: String, kind: DailyRainLabelLayout.Kind, fontScale: Float): DailyRainLabelLayout.TextSize {
                val paint = createRainPaint(paints, fontScale)
                val metrics = textMetrics(paint)
                return DailyRainLabelLayout.TextSize(
                    width = paint.measureText(text),
                    height = metrics.descent - metrics.ascent,
                    baseline = -metrics.ascent,
                )
            }

            /** Glyph bounds ([Paint.getTextBounds]) moved from the baseline origin to the box's top-left. */
            override fun ink(text: String, kind: DailyRainLabelLayout.Kind, fontScale: Float): DailyRainLabelPlanner.Box? {
                val paint = createRainPaint(paints, fontScale)
                val bounds = TextInk.bounds(paint, text) ?: return null
                val baseline = -textMetrics(paint).ascent
                return DailyRainLabelPlanner.Box(bounds.left, baseline + bounds.top, bounds.right, baseline + bounds.bottom)
            }

            /** Row bands ([TextInk.textBands]) moved from the baseline origin to the box's top-left. */
            override fun inkBands(text: String, kind: DailyRainLabelLayout.Kind, fontScale: Float): List<DailyRainLabelPlanner.Box>? {
                val paint = createRainPaint(paints, fontScale)
                val baseline = -textMetrics(paint).ascent
                return TextInk.textBands(paint, text, 0f, com.weatherwidget.shared.graph.InkScan.NIGHT_OBSTACLE_BAND_PX)
                    ?.map { DailyRainLabelPlanner.Box(it.left, baseline + it.top, it.right, baseline + it.bottom) }
            }
        }

    fun drawDailyRainLabel(
        day: DayData,
        centerX: Float,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
        onRainLabelDrawn: ((DailyRainLabelPlacement) -> Unit)?,
        canvas: Canvas,
    ) {
        val labels = layoutDayLabels(day, centerX, layout, paints) ?: return
        listOfNotNull(labels.rain, labels.peak).forEach { label ->
            canvas.drawText(label.text, label.centerX, label.baseline, createRainPaint(paints, label.fontScale))
            onRainLabelDrawn?.invoke(label.toPlacement(day, labels.rawTop))
        }
    }

    /** The day chance label as [drawDailyRainLabel] places it, without drawing. */
    internal fun resolveDailyRainLabelPlacement(
        day: DayData,
        centerX: Float,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
    ): DailyRainLabelPlacement? =
        layoutDayLabels(day, centerX, layout, paints)?.let { it.rain.toPlacement(day, it.rawTop) }

    private fun layoutDayLabels(
        day: DayData,
        centerX: Float,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
    ): DailyRainLabelLayout.DayLabels? {
        val rainText = day.rainData.dailyRainLabelText ?: return null
        val tempPaint = when {
            day.isToday -> paints.todayTempTextPaint
            day.isPast -> paints.pastTempTextPaint
            else -> paints.tempTextPaint
        }
        // The high label is shrink-to-fit when its temp is wide for the column (e.g. dual-label
        // "75.6°"), so anchor to its top *as drawn*: full-size ascent times that same draw scale.
        val highLabelTop = DailyHighLabelPlanner.resolveHighLabelBaseline(day, layout, paints)?.let { baseline ->
            baseline + textMetrics(tempPaint).ascent * DailyHighLabelPlanner.resolveHighLabelDrawScale(day, layout, paints)
        }
        // The column's left slot (today's, a past day's thinner one, or the opt-in future one — shared
        // PriorBarSlot) can rise above the high label; the rain % must clear its cap. Drawn only as a pair.
        val snapshotBarTop = if (
            com.weatherwidget.shared.graph.PriorBarSlot.drawn(day.isToday, day.isPast, day.futurePriorBarDrawn) &&
            day.snapshotHigh != null && day.snapshotLow != null
        ) {
            val capRadius =
                com.weatherwidget.shared.graph.PriorBarSlot.widthPx(day.isToday, layout.barWidths) / 2f
            layout.tempToY(day.snapshotHigh) - capRadius
        } else null
        return DailyRainLabelLayout.layoutDay(
            rainLabelHost(paints),
            DailyRainLabelLayout.DayInput(
                date = day.date.toString(),
                rainText = rainText,
                peakText = day.rainData.dailyRainPeakLabelText,
                probability = day.rainData.dailyPrecipProbability,
                isPast = day.isPast,
                daysFromToday = day.daysFromToday,
                centerX = centerX,
                highLabelTop = highLabelTop,
                snapshotBarTop = snapshotBarTop,
                // Nothing above the bitmap is visible.
                ceiling = layout.labelCeiling.coerceAtLeast(0f),
                // The peak hour may overlap the header, up to the bitmap top.
                peakCeiling = 0f,
                pxPerDp = layout.density * layout.bitmapScale.coerceAtMost(1f),
            ),
            PLATFORM,
        )
    }

    /**
     * Second pass of the daily graph: draws every night rain label once all columns are drawn, so
     * both neighbours' low labels and icons are known obstacles.
     */
    fun drawNightRainLabels(
        days: List<Pair<Int, DayData>>,
        boundsByColumn: Map<Int, DailyColumnRenderer.DrawnBounds>,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
        onRainLabelDrawn: ((DailyRainLabelPlacement) -> Unit)?,
        canvas: Canvas,
    ) {
        // Lay out every night label first, in column order, so each one avoids those already placed.
        val inputs = days.mapNotNull { (column, day) ->
            val text = day.rainData.nightRainLabelText ?: return@mapNotNull null
            val left = boundsByColumn[column] ?: return@mapNotNull null
            val leftLowInk = left.lowInk ?: return@mapNotNull null
            val right = boundsByColumn[column + 1]
            val centerX = layout.columnCenter(column)
            val gapCenterX =
                if (column + 1 < layout.columnLefts.size) {
                    (centerX + layout.columnCenter(column + 1)) / 2f
                } else {
                    centerX + layout.columnWidth(column) / 2f
                }
            day to DailyRainLabelLayout.NightInput(
                date = day.date.toString(),
                text = text,
                probability = day.rainData.nighttimePrecipProbability,
                isPast = day.isPast,
                daysFromToday = day.daysFromToday,
                columnCenterX = centerX,
                gapCenterX = gapCenterX,
                columnWidth = layout.columnWidth(column),
                leftLowInk = leftLowInk,
                rightLowInk = right?.lowInk,
                obstacles = left.nightObstacles + right?.nightObstacles.orEmpty(),
                floorInkTop = minOf(left.dayNameInkTop, right?.dayNameInkTop ?: left.dayNameInkTop),
                canvasWidth = layout.widthPx.toFloat(),
                pxPerDp = layout.density,
            )
        }
        val placed = DailyRainLabelLayout.layoutNights(rainLabelHost(paints), inputs.map { it.second }, PLATFORM)
        inputs.zip(placed).forEach { (entry, label) ->
            canvas.drawText(label.text, label.centerX, label.baseline, createRainPaint(paints, label.fontScale))
            onRainLabelDrawn?.invoke(label.toPlacement(entry.first, Float.NaN))
        }
    }

    private fun DailyRainLabelLayout.Placed.toPlacement(day: DayData, rawTop: Float): DailyRainLabelPlacement {
        val isNight = kind == DailyRainLabelLayout.Kind.NIGHT
        return DailyRainLabelPlacement(
            date = day.date,
            text = text,
            placement = placement,
            kind = when (kind) {
                DailyRainLabelLayout.Kind.DAY -> RainLabelKind.DAY
                DailyRainLabelLayout.Kind.PEAK -> RainLabelKind.PEAK
                DailyRainLabelLayout.Kind.NIGHT -> RainLabelKind.NIGHT
            },
            centerX = centerX,
            leftX = left,
            rightX = right,
            baselineY = baseline,
            topY = top,
            bottomY = bottom,
            anchorTopY = if (isNight) Float.NaN else anchorY,
            anchorBaselineY = if (isNight) anchorY else Float.NaN,
            rawTopY = rawTop,
        )
    }

    internal fun textMetrics(paint: Paint): TextMetrics {
        val metrics = paint.fontMetrics
        if (metrics.ascent != 0f || metrics.descent != 0f) {
            return TextMetrics(metrics.ascent, metrics.descent)
        }
        if (paint.textSize <= 0f) {
            return TextMetrics(0f, 0f)
        }
        return TextMetrics(
            ascent = -paint.textSize * 0.8f,
            descent = paint.textSize * 0.2f,
        )
    }
}
