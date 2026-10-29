package com.weatherwidget.widget

import android.graphics.*
import com.weatherwidget.shared.graph.DailyRainLabelLayout
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
    internal fun rainLabelHost(paints: DailyGraphPaintSet) =
        DailyRainLabelLayout.Host { text, _, fontScale ->
            val paint = createRainPaint(paints, fontScale)
            val metrics = textMetrics(paint)
            DailyRainLabelLayout.TextSize(
                width = paint.measureText(text),
                height = metrics.descent - metrics.ascent,
                baseline = -metrics.ascent,
            )
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
        // The triple bar's left slot (today's, or a past day's thinner one) can rise above the
        // high label; the rain % must clear its cap. Drawn only as a pair.
        val snapshotBarTop = if ((day.isToday || day.isPast) && day.snapshotHigh != null && day.snapshotLow != null) {
            val capRadius =
                (if (day.isToday) paints.todaySnapshotYellowPaint else paints.pastTripleBarPaint).strokeWidth / 2f
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
        days.forEach { (column, day) ->
            val text = day.rainData.nightRainLabelText ?: return@forEach
            val left = boundsByColumn[column] ?: return@forEach
            val leftLow = left.lowLabel ?: return@forEach
            val right = boundsByColumn[column + 1]
            val centerX = layout.columnCenter(column)
            val gapCenterX =
                if (column + 1 < layout.columnLefts.size) {
                    (centerX + layout.columnCenter(column + 1)) / 2f
                } else {
                    centerX + layout.columnWidth(column) / 2f
                }
            val label = DailyRainLabelLayout.layoutNight(
                rainLabelHost(paints),
                DailyRainLabelLayout.NightInput(
                    date = day.date.toString(),
                    text = text,
                    probability = day.rainData.nighttimePrecipProbability,
                    isPast = day.isPast,
                    daysFromToday = day.daysFromToday,
                    columnCenterX = centerX,
                    gapCenterX = gapCenterX,
                    columnWidth = layout.columnWidth(column),
                    leftLowLabel = leftLow.toBox(),
                    rightLowLabel = right?.lowLabel?.toBox(),
                    obstacles = left.nightObstacles + right?.nightObstacles.orEmpty(),
                    dayLabelTop = layout.bottomStack.dayLabelTop,
                    canvasWidth = layout.widthPx.toFloat(),
                    pxPerDp = layout.density,
                ),
                PLATFORM,
            )
            canvas.drawText(label.text, label.centerX, label.baseline, createRainPaint(paints, label.fontScale))
            onRainLabelDrawn?.invoke(label.toPlacement(day, Float.NaN))
        }
    }

    private fun RectF.toBox() =
        com.weatherwidget.shared.graph.DailyRainLabelPlanner.Box(left, top, right, bottom)

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
