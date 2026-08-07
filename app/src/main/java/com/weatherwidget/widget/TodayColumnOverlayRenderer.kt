package com.weatherwidget.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.shared.graph.TodayColumnOverlayBlocks
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

    /**
     * Fallback share of the reserved header band the overlay may rise into, used ONLY when the
     * header's real extent is unavailable (`headerInkBottom == null`, i.e. no header was measured).
     *
     * When the header IS measured, `DailyForecastHeaderRenderer.resolveHeaderInkBottom` supersedes
     * this: `DailyGraphLayoutResolver.TOP_PADDING_DP` reserves 50dp for the header, the header fills
     * under half of it, and a fraction can only ever guess at how much. The guess was expensive —
     * on the Samsung fold it fenced the overlay out of ~17 px of empty band, which was the whole
     * difference between one row above the column and all three rendered across the bars.
     */
    @VisibleForTesting
    internal const val HEADER_BAND_RECLAIM_FRACTION = 0.25f

    fun draw(
        canvas: Canvas,
        data: TodayOverlayRenderData,
        layout: DailyGraphLayoutInfo,
        todayColumnIndex: Int,
        todayBars: List<BarDrawnDebug>,
        highLabelBounds: List<RectF>,
        columnBounds: DailyColumnRenderer.DrawnBounds,
        rainPlacements: List<DailyRainLabelPlacement>,
        /** Lowest y the header's ink reaches, or null when no header is drawn/measurable. */
        headerInkBottom: Float? = null,
    ): List<TodayOverlayPlacementDebug> {
        val columnLeft = layout.columnLefts[todayColumnIndex]
        val columnRight = columnLeft + layout.columnWidth(todayColumnIndex)
        val horizontalPadding = HORIZONTAL_PADDING_DP.dp(layout.density)
        val labelScale = layout.bitmapScale.coerceIn(0.5f, 1f)

        // Block selection (including the independent temp/age toggles) and the ordered content
        // variants for the planner's degradation ladder are pure and shared with the desktop
        // renderer via TodayColumnOverlayBlocks.
        val variants =
            TodayColumnOverlayBlocks.variants(
                deltaValueText = data.deltaValueText,
                deltaCaptionText = data.deltaCaptionText,
                dominantTempText = data.dominantTempText,
                dominantAgeText = data.dominantAgeText,
            ).map { blocks ->
                blocks.map { block ->
                    TextBlockSpec(
                        key = block.key,
                        rows = block.rows.map { TextRow(it.text, MAIN_TEXT_COLOR, it.caption) },
                    )
                }
            }
        if (variants.isEmpty()) return emptyList()

        val rowSpacing = ROW_SPACING_DP.dp(layout.density) * labelScale
        // One shared paint keeps every row at the same main font size.
        fun paintsFor(blocks: List<TextBlockSpec>): Map<String, Paint> {
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
                    // The planner decides fit on ink, so tell it how much of this block's outer box
                    // is blank. "0m" has no descenders at all, so its whole descent is empty.
                    topLeading = inkLeading(paint, spec.rows.first().text).first,
                    bottomLeading = inkLeading(paint, spec.rows.last().text).second,
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
        val plannerInput =
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
                rowSpacing = rowSpacing,
                previousZones = data.previousZones,
                // No second inset at the graph edge: `layout.graphTop` already IS the reserved
                // header band (DailyGraphLayoutResolver's TOP_PADDING_DP), so insetting again only
                // discarded headroom the stack needed. `padding` still applies at the bar cap.
                edgeInset = 0f,
                // The header's measured ink plus the same clearance the bar cap gets. Falls back to
                // the guessed fraction of the band (`layout.graphTop` IS the band's height) only
                // when nothing measured the header.
                aboveCeiling = headerInkBottom?.plus(VERTICAL_PADDING_DP.dp(layout.density))
                    ?: (layout.graphTop * (1f - HEADER_BAND_RECLAIM_FRACTION)),
            )

        // The planner searches variant x zone x grouping in cost order and reports back which
        // content variant it settled on; text is always measured and drawn at the one fixed size
        // (no font-shrink ladder — removed at user request). The old `combined` retry — which
        // merged every block into one spec when a block failed to place — is gone: the planner
        // lays the stack out as a unit, which is what that hack was approximating.
        val result =
            TodayColumnOverlayPlanner.layout(
                variantCount = variants.size,
                measureAt = { variantIndex ->
                    val blocks = variants[variantIndex]
                    linesFor(blocks, paintsFor(blocks))
                },
                input = plannerInput,
            )
        val activeSpecs = variants[result.variantIndex]
        val paints = paintsFor(activeSpecs)
        val placements = result.placements
        Log.v(
            TAG,
            "layout variant=${result.variantIndex}/${variants.size} " +
                "blocks=${activeSpecs.map(TextBlockSpec::key)} " +
                "lines=${linesFor(activeSpecs, paints).map { "${it.key}:${it.width}x${it.height}" }} " +
                "rowSpacing=$rowSpacing " +
                "column=$columnLeft..$columnRight graph=${layout.graphTop}..${layout.heightPx - layout.dayLabelHeight} " +
                "headerInkBottom=$headerInkBottom aboveCeiling=${plannerInput.aboveCeiling} " +
                "bars=$barTop..$barBottom " +
                "obstacles=${hardObstacles.map { "${it.left},${it.top},${it.right},${it.bottom}" }} " +
                "prevZones=${data.previousZones} " +
                "placements=${placements.map { "${it.key}:${it.zone}" }}",
        )

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
                fromLastResort = result.fromLastResort,
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

    /**
     * How much of [text]'s font box (ascent..descent) holds no ink, as `top to bottom`.
     *
     * Font boxes carry leading for the tallest ascender and deepest descender the face can produce;
     * the overlay's rows are digits and short captions, which use neither. Packing those boxes
     * against obstacles that are themselves boxes (`DailyTemperatureLabelRenderer` builds the high
     * label's bounds the same way) counts that blank twice wherever they meet.
     *
     * Returns zero when the platform reports no ink bounds — Robolectric has no font engine, and
     * over-trimming there would silently move text rather than fail a test.
     */
    @VisibleForTesting
    internal fun inkLeading(paint: Paint, text: String): Pair<Float, Float> {
        val ink = Rect()
        paint.getTextBounds(text, 0, text.length, ink)
        if (ink.isEmpty) return 0f to 0f
        val top = (ink.top - TemperatureGraphStyle.fontAscent(paint)).coerceAtLeast(0f)
        val bottom = (TemperatureGraphStyle.fontDescent(paint) - ink.bottom).coerceAtLeast(0f)
        return top to bottom
    }

    private fun measureText(paint: Paint, text: String): Float =
        paint.measureText(text).takeIf { it > 0f } ?: text.length * paint.textSize * 0.55f

    private fun RectF.toPlannerBounds() =
        TodayColumnOverlayPlanner.Bounds(left, top, right, bottom)

    private fun Float.dp(density: Float): Float = this * density
}
