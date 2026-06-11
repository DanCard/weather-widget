package com.weatherwidget.shared.graph

/**
 * Shared placement geometry for the hourly-graph "NOW" indicator (the dashed vertical
 * current-time line plus its "NOW" text label). Pure geometry — no platform graphics. Each
 * platform supplies the time→x mapping (Android: discrete hour bucket + minute offset; desktop:
 * continuous [xAtTime]) and does its own drawing; this object owns the single source of truth for
 * *where* the line and label go, so Android and desktop can't drift. See [HourlyGraphDefaults].
 */
object NowIndicatorGeometry {

    /** Vertical extent of the dashed NOW line. The x is platform-supplied, so it isn't stored. */
    data class NowLineGeometry(val lineTop: Float, val lineBottom: Float)

    /**
     * Result of placing the "NOW" label. Carries BOTH coordinate conventions so each platform uses
     * the one native to its drawing API:
     *  - Android `Canvas.drawText(text, x, baselineY, paint)` with `Align.CENTER` → [centerX] + [baselineY]
     *  - Compose `drawText(layout, topLeft = Offset)` → [box].left / [box].top
     */
    data class NowLabelPlacement(
        val centerX: Float,
        val baselineY: Float,
        val box: GraphRect,
    )

    fun computeNowLine(graphTop: Float, graphHeight: Float): NowLineGeometry {
        val lineHeight = graphHeight * HourlyGraphDefaults.NOW_LINE_HEIGHT_FRACTION
        val lineTop = graphTop + (graphHeight - lineHeight) / 2f
        return NowLineGeometry(lineTop, lineTop + lineHeight)
    }

    /**
     * Places the "NOW" label, trying below the line first then above, returning the first
     * collision-free spot against [drawnBounds] — or `null` to suppress the label when both
     * candidates collide. [fontAscent] is negative (font-metrics convention); [fontDescent] is
     * positive. Compose callers (which lack an ascent/descent split) treat the measured box's
     * bottom as the baseline by passing `fontAscent = -height, fontDescent = 0f`; then
     * `box.top == baselineY - height` is exactly the top-left y for `drawText`.
     */
    fun computeNowLabel(
        nowX: Float,
        graphTop: Float,
        graphHeight: Float,
        labelWidth: Float,
        fontAscent: Float,
        fontDescent: Float,
        drawnBounds: List<GraphRect> = emptyList(),
        dpToPx: (Float) -> Float,
    ): NowLabelPlacement? {
        val line = computeNowLine(graphTop, graphHeight)
        val gap = dpToPx(HourlyGraphDefaults.NOW_LABEL_LINE_GAP_DP)
        val labelYTop = line.lineTop - gap
        val labelYBottom = line.lineBottom - fontAscent + gap

        for (baselineY in listOf(labelYBottom, labelYTop)) {
            val box = GraphRect(
                nowX - labelWidth / 2f,
                baselineY + fontAscent,
                nowX + labelWidth / 2f,
                baselineY + fontDescent,
            )
            if (drawnBounds.none { it.intersects(box) }) {
                return NowLabelPlacement(nowX, baselineY, box)
            }
        }
        return null
    }
}
