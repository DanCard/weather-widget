package com.weatherwidget.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.shared.graph.TodayColumnOverlayPlanner
import com.weatherwidget.shared.graph.TodayColumnOverlayStyle
import com.weatherwidget.widget.DailyForecastGraphRenderer.BarDrawnDebug
import com.weatherwidget.widget.DailyForecastGraphRenderer.DailyRainLabelPlacement
import com.weatherwidget.widget.DailyForecastGraphRenderer.TodayOverlayPlacementDebug
import com.weatherwidget.widget.DailyForecastGraphRenderer.TodayOverlayRenderData

/** Measures, plans, and draws the two optional large-Today annotations. */
internal object TodayColumnOverlayRenderer {
    private const val TAG = "TodayColumnOverlay"
    @VisibleForTesting
    internal const val TEXT_SIZE_DP = TodayColumnOverlayStyle.TEXT_SIZE_DP
    private const val HORIZONTAL_PADDING_DP = TodayColumnOverlayStyle.HORIZONTAL_PADDING_DP
    private const val VERTICAL_PADDING_DP = TodayColumnOverlayStyle.VERTICAL_PADDING_DP
    private const val ROW_SPACING_DP = TodayColumnOverlayStyle.ROW_SPACING_DP
    @VisibleForTesting
    internal const val INLINE_CAPTION_TEXT_SCALE = TodayColumnOverlayStyle.INLINE_CAPTION_TEXT_SCALE
    private const val INLINE_CAPTION_GAP_EM = TodayColumnOverlayStyle.INLINE_CAPTION_GAP_EM
    @VisibleForTesting
    internal const val MAIN_TEXT_COLOR = TodayColumnOverlayStyle.MAIN_TEXT_ARGB
    @VisibleForTesting
    internal const val INLINE_CAPTION_TEXT_COLOR = MAIN_TEXT_COLOR
    // A heavier outline overwhelms the thin glyphs after the bitmap is scaled into RemoteViews,
    // making the white fill look muddy on the dark Today panel.
    private const val OUTLINE_FRACTION = 0.08f

    fun draw(
        canvas: Canvas,
        data: TodayOverlayRenderData,
        layout: DailyGraphLayoutInfo,
        todayColumnIndex: Int,
        todayBars: List<BarDrawnDebug>,
        highLabelBounds: List<RectF>,
        columnBounds: DailyColumnRenderer.DrawnBounds,
        rainPlacements: List<DailyRainLabelPlacement>,
    ): List<TodayOverlayPlacementDebug> {
        val columnLeft = layout.columnLefts[todayColumnIndex]
        val columnRight = columnLeft + layout.columnWidth(todayColumnIndex)
        val horizontalPadding = HORIZONTAL_PADDING_DP.dp(layout.density)
        val labelScale = layout.bitmapScale.coerceIn(0.5f, 1f)

        val specs =
            listOfNotNull(
                data.deltaValueText?.takeIf { it.isNotBlank() }?.let { value ->
                    TextBlockSpec(
                        key = "delta",
                        rows = listOf(
                            TextRow(
                                text = value,
                                color = MAIN_TEXT_COLOR,
                                inlineCaption = data.deltaCaptionText?.takeIf(String::isNotBlank),
                            ),
                        ),
                    )
                },
                data.dominantTempText?.takeIf { it.isNotBlank() }?.let { temperature ->
                    TextBlockSpec(
                        key = "dominant_temp_age",
                        rows =
                            listOfNotNull(temperature, data.dominantAgeText?.takeIf(String::isNotBlank))
                                .map { TextRow(it, MAIN_TEXT_COLOR) },
                    )
                },
            )
        if (specs.isEmpty()) return emptyList()

        val rowSpacing = ROW_SPACING_DP.dp(layout.density) * labelScale
        fun paintsFor(blocks: List<TextBlockSpec>): Map<String, Paint> {
            // One shared paint keeps every row at the same main font size.
            val commonPaint = fittedPaint(MAIN_TEXT_COLOR, labelScale, layout.density)
            return blocks.associate { spec -> spec.key to Paint(commonPaint).apply { color = spec.rows.first().color } }
        }
        fun linesFor(blocks: List<TextBlockSpec>, blockPaints: Map<String, Paint>) =
            blocks.map { spec ->
                val paint = blockPaints.getValue(spec.key)
                val lineHeight = TemperatureGraphStyle.fontDescent(paint) - TemperatureGraphStyle.fontAscent(paint)
                TodayColumnOverlayPlanner.Line(
                    key = spec.key,
                    text = spec.rows.joinToString("\n", transform = TextRow::displayText),
                    width = spec.rows.maxOf { measureRow(paint, it) },
                    height = lineHeight * spec.rows.size + rowSpacing * (spec.rows.size - 1),
                )
            }

        val barTop = todayBars.minOfOrNull { it.highY } ?: (layout.graphTop + layout.graphHeight * 0.35f)
        val barBottom =
            (todayBars.maxOfOrNull { it.lowY }?.plus(layout.bulbRadius * 1.5f)
                ?: (layout.graphBottom - layout.graphHeight * 0.25f))
                .coerceAtMost(layout.graphBottom)
        val hardObstacles = buildList {
            highLabelBounds.mapTo(this) { it.toPlannerBounds() }
            columnBounds.icon?.let { add(it.toPlannerBounds()) }
            columnBounds.lowLabel?.let { add(it.toPlannerBounds()) }
            add(columnBounds.dayLabel.toPlannerBounds())
            rainPlacements.forEach { placement ->
                if (
                    placement.leftX.isFinite() && placement.rightX.isFinite() &&
                    placement.topY.isFinite() && placement.bottomY.isFinite()
                ) {
                    add(
                        TodayColumnOverlayPlanner.Bounds(
                            placement.leftX,
                            placement.topY,
                            placement.rightX,
                            placement.bottomY,
                        ),
                    )
                }
            }
        }
        fun place(blocks: List<TextBlockSpec>, blockPaints: Map<String, Paint>): List<TodayColumnOverlayPlanner.Placement> {
            val lines = linesFor(blocks, blockPaints)
            val placements = TodayColumnOverlayPlanner.place(
                lines = lines,
                input =
                    TodayColumnOverlayPlanner.Input(
                        columnLeft = columnLeft,
                        columnRight = columnRight,
                        graphTop = layout.graphTop,
                        graphBottom = layout.heightPx - layout.dayLabelHeight,
                        barTop = barTop,
                        barBottom = barBottom,
                        hardObstacles = hardObstacles,
                        horizontalPadding = horizontalPadding,
                        padding = VERTICAL_PADDING_DP.dp(layout.density),
                        verticalStep = 1f.dp(layout.density).coerceAtLeast(1f),
                    ),
            )
            Log.v(
                TAG,
                "attempt blocks=${blocks.map(TextBlockSpec::key)} " +
                    "lines=${lines.map { line -> "${line.key}:${line.width}x${line.height}" }} " +
                    "column=$columnLeft..$columnRight graph=${layout.graphTop}..${layout.heightPx - layout.dayLabelHeight} " +
                    "bars=$barTop..$barBottom obstacles=${hardObstacles.size} " +
                    "placements=${placements.map { it.key }}",
            )
            return placements
        }

        var activeSpecs = specs
        var paints = paintsFor(activeSpecs)
        var placements = place(activeSpecs, paints)
        if (specs.size > 1 && placements.size < specs.size) {
            // At the doubled font size, two independently optimal blocks may leave no valid band
            // for the second. Preserve all requested rows by retrying them as one narrow stack.
            val combinedSpecs = listOf(TextBlockSpec("combined", specs.flatMap(TextBlockSpec::rows)))
            val combinedPaints = paintsFor(combinedSpecs)
            val combinedPlacements = place(combinedSpecs, combinedPaints)
            if (combinedPlacements.isNotEmpty()) {
                activeSpecs = combinedSpecs
                paints = combinedPaints
                placements = combinedPlacements
            }
        }

        placements.forEach { placement ->
            val paint = paints.getValue(placement.key)
            val spec = activeSpecs.first { it.key == placement.key }
            val centerX = (placement.bounds.left + placement.bounds.right) / 2f
            val lineHeight = TemperatureGraphStyle.fontDescent(paint) - TemperatureGraphStyle.fontAscent(paint)
            spec.rows.forEachIndexed { index, row ->
                val rowPaint = Paint(paint).apply { color = row.color }
                val rowTop = placement.bounds.top + index * (lineHeight + rowSpacing)
                val baseline = rowTop - TemperatureGraphStyle.fontAscent(rowPaint)
                drawRow(canvas, row, rowPaint, centerX, baseline)
            }
            Log.v(
                TAG,
                "placement key=${placement.key} zone=${placement.zone} text=${placement.text} " +
                    "bounds=${placement.bounds.left},${placement.bounds.top}," +
                    "${placement.bounds.right},${placement.bounds.bottom} score=${placement.score}",
            )
        }

        return placements.map { placement ->
            TodayOverlayPlacementDebug(
                key = placement.key,
                text = placement.text,
                zone = placement.zone.name,
                left = placement.bounds.left,
                top = placement.bounds.top,
                right = placement.bounds.right,
                bottom = placement.bounds.bottom,
                mainTextSizePx = paints.getValue(placement.key).textSize,
            )
        }
    }

    private data class TextBlockSpec(
        val key: String,
        val rows: List<TextRow>,
    )

    private data class TextRow(
        val text: String,
        val color: Int,
        val inlineCaption: String? = null,
    ) {
        fun displayText(): String = listOfNotNull(text, inlineCaption).joinToString(" ")
    }

    /**
     * Fixed-size overlay paint. No width fitting: rows render at the base size and may overflow
     * narrow Today columns. The placement planner still measures these paints and avoids
     * vertical collisions; only horizontal fit is unenforced.
     */
    @VisibleForTesting
    internal fun fittedPaint(
        color: Int,
        labelScale: Float,
        density: Float,
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            textSize = TEXT_SIZE_DP * labelScale * density
            setShadowLayer(1.5f * labelScale * density, 0f, labelScale * density, 0xCC000000.toInt())
        }

    private fun drawRow(
        canvas: Canvas,
        row: TextRow,
        paint: Paint,
        centerX: Float,
        baseline: Float,
    ) {
        val caption = row.inlineCaption
        if (caption == null) {
            drawOutlinedText(canvas, row.text, centerX, baseline, paint)
            return
        }

        val captionPaint =
            Paint(paint).apply {
                color = INLINE_CAPTION_TEXT_COLOR
                textSize *= INLINE_CAPTION_TEXT_SCALE
            }
        val gap = paint.textSize * INLINE_CAPTION_GAP_EM
        val valueWidth = measureText(paint, row.text)
        val captionWidth = measureText(captionPaint, caption)
        var x = centerX - (valueWidth + gap + captionWidth) / 2f
        val valuePaint = Paint(paint).apply { textAlign = Paint.Align.LEFT }
        captionPaint.textAlign = Paint.Align.LEFT
        drawOutlinedText(canvas, row.text, x, baseline, valuePaint)
        x += valueWidth + gap
        drawOutlinedText(canvas, caption, x, baseline, captionPaint)
    }

    private fun drawOutlinedText(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        paint: Paint,
    ) {
        val outline =
            Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = textSize * OUTLINE_FRACTION
                color = 0xE6000000.toInt()
                clearShadowLayer()
            }
        canvas.drawText(text, x, baseline, outline)
        canvas.drawText(text, x, baseline, paint)
    }

    private fun measureRow(paint: Paint, row: TextRow): Float {
        val caption = row.inlineCaption ?: return measureText(paint, row.text)
        val captionPaint = Paint(paint).apply { textSize *= INLINE_CAPTION_TEXT_SCALE }
        return measureText(paint, row.text) +
            paint.textSize * INLINE_CAPTION_GAP_EM +
            measureText(captionPaint, caption)
    }

    private fun measureText(paint: Paint, text: String): Float =
        paint.measureText(text).takeIf { it > 0f } ?: text.length * paint.textSize * 0.55f

    private fun RectF.toPlannerBounds() =
        TodayColumnOverlayPlanner.Bounds(left, top, right, bottom)

    private fun Float.dp(density: Float): Float = this * density
}
