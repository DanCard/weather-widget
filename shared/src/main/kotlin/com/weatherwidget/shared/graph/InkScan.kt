package com.weatherwidget.shared.graph

/**
 * Bounds of the ink in a rasterized glyph run or icon, for the night rain label placer's measured
 * obstacles (plans/261116-night-rain-label-scored-placement-redesign.md). Each platform rasterizes;
 * the scan is shared.
 */
object InkScan {
    /**
     * Band height for night-label obstacles: single pixel rows, the glyph's exact shape. 3 px bands
     * put the bottom of Fri's 4 crossbar 2 px low and kept Fold "33%" off centre (2026-11-16).
     */
    const val NIGHT_OBSTACLE_BAND_PX = 1

    /**
     * Smallest box holding every pixel in columns [fromX] until [toX] and rows 0 until [height] for
     * which [isInk] is true, as (left, top, right, bottom) with exclusive right/bottom. Null when none.
     */
    fun opaqueBounds(
        height: Int,
        fromX: Int,
        toX: Int,
        isInk: (x: Int, y: Int) -> Boolean,
    ): DailyRainLabelPlanner.Box? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            for (x in fromX until toX) {
                if (isInk(x, y)) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < 0) null else DailyRainLabelPlanner.Box(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
    }

    /**
     * The ink as horizontal bands [bandPx] rows tall: per band, the box from its leftmost to its
     * rightmost ink pixel and from its first to its last ink row. A glyph's open spaces (under a 4's
     * crossbar, under a °, between a sun's rays) fall outside every band, so a night label can use
     * them; one bounding box would block them. Empty bands are skipped.
     */
    fun rowBands(
        height: Int,
        fromX: Int,
        toX: Int,
        bandPx: Int,
        isInk: (x: Int, y: Int) -> Boolean,
    ): List<DailyRainLabelPlanner.Box> {
        val band = bandPx.coerceAtLeast(1)
        val out = ArrayList<DailyRainLabelPlanner.Box>()
        var y0 = 0
        while (y0 < height) {
            val y1 = minOf(y0 + band, height)
            opaqueBounds(y1 - y0, fromX, toX) { x, y -> isInk(x, y0 + y) }?.let {
                out += DailyRainLabelPlanner.Box(it.left, it.top + y0, it.right, it.bottom + y0)
            }
            y0 = y1
        }
        return out
    }
}
