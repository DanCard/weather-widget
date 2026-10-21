package com.weatherwidget.shared.rainmap

import kotlin.math.floor

/**
 * Turns one moment of a [RainGrid] into ARGB pixels: **bicubic (Catmull-Rom) across the grid**,
 * linear between hours **on the values** — not a cross-fade of two pictures, which shows a storm at
 * half strength in both places. North is up; the image spans exactly the grid's lat/lon box.
 *
 * Bicubic, not bilinear: at ~60 km cells bilinear drew an isolated rain cell as a square / plus sign
 * (2026-10-20, Pixel and desktop). Catmull-Rom passes through every grid value and rounds the
 * shapes between them. It can overshoot, so each pixel is clamped to its cell's four corner values —
 * the map never shows rain heavier (or drier) than the forecast points around it.
 *
 * Rendered small (see [DEFAULT_SIZE_PX]) and scaled up by the view with filtering: a 15×15 grid has
 * no detail a bigger raster would show, and this runs on every animation tick.
 */
object RainGridRaster {
    const val DEFAULT_SIZE_PX = 240

    fun render(
        grid: RainGrid,
        layer: RainLayer,
        hourPos: Double,
        widthPx: Int = DEFAULT_SIZE_PX,
        heightPx: Int = DEFAULT_SIZE_PX,
        out: IntArray = IntArray(widthPx * heightPx),
    ): IntArray {
        val v = field(grid, layer, hourPos, widthPx, heightPx, scratch(widthPx * heightPx))
        for (i in 0 until widthPx * heightPx) out[i] = RainColorScale.color(layer, v[i])
        return out
    }

    // One field buffer per raster size, reused across ticks (render runs on the UI thread only).
    private var scratchBuf = FloatArray(0)
    private fun scratch(n: Int): FloatArray = if (scratchBuf.size == n) scratchBuf else FloatArray(n).also { scratchBuf = it }

    /** The interpolated values behind [render], before colouring (pixel row 0 = north). */
    internal fun field(
        grid: RainGrid,
        layer: RainLayer,
        hourPos: Double,
        widthPx: Int,
        heightPx: Int,
        out: FloatArray = FloatArray(widthPx * heightPx),
    ): FloatArray {
        val values = grid.values(layer)
        if (values.isEmpty()) return out.also { it.fill(0f) }
        val h0 = floor(hourPos).toInt().coerceIn(0, values.size - 1)
        val h1 = (h0 + 1).coerceAtMost(values.size - 1)
        val t = (hourPos - h0).toFloat().coerceIn(0f, 1f)
        val cols = grid.cols
        val rows = grid.rows
        // This moment's field, blended between hours once rather than 16 times per pixel.
        val a = values[h0]
        val b = values[h1]
        val f = FloatArray(a.size) { a[it] * (1 - t) + b[it] * t }

        val colC0 = IntArray(widthPx)
        val colW = FloatArray(widthPx * 4)
        for (x in 0 until widthPx) {
            val fc = x.toFloat() / (widthPx - 1).coerceAtLeast(1) * (cols - 1)
            val c0 = fc.toInt().coerceAtMost(cols - 2)
            colC0[x] = c0
            catmullRom(fc - c0, colW, x * 4)
        }
        val rowW = FloatArray(4)
        val tmp = FloatArray(4)
        for (y in 0 until heightPx) {
            // Row 0 is the south edge; pixel row 0 is the top (north).
            val fr = (heightPx - 1 - y).toFloat() / (heightPx - 1).coerceAtLeast(1) * (rows - 1)
            val r0 = fr.toInt().coerceAtMost(rows - 2)
            catmullRom(fr - r0, rowW, 0)
            for (x in 0 until widthPx) {
                val c0 = colC0[x]
                val wo = x * 4
                for (k in 0 until 4) {
                    val rowBase = (r0 - 1 + k).coerceIn(0, rows - 1) * cols
                    tmp[k] = colW[wo] * f[rowBase + (c0 - 1).coerceAtLeast(0)] +
                        colW[wo + 1] * f[rowBase + c0] +
                        colW[wo + 2] * f[rowBase + c0 + 1] +
                        colW[wo + 3] * f[rowBase + (c0 + 2).coerceAtMost(cols - 1)]
                }
                var v = rowW[0] * tmp[0] + rowW[1] * tmp[1] + rowW[2] * tmp[2] + rowW[3] * tmp[3]
                val i00 = r0 * cols + c0
                val lo = minOf(minOf(f[i00], f[i00 + 1]), minOf(f[i00 + cols], f[i00 + cols + 1]))
                val hi = maxOf(maxOf(f[i00], f[i00 + 1]), maxOf(f[i00 + cols], f[i00 + cols + 1]))
                out[y * widthPx + x] = v.coerceIn(lo, hi)
            }
        }
        return out
    }

    /** Catmull-Rom weights for the four samples around a point [t] (0..1) past the second one. */
    internal fun catmullRom(t: Float, w: FloatArray, at: Int) {
        val t2 = t * t
        val t3 = t2 * t
        w[at] = (-t3 + 2 * t2 - t) / 2
        w[at + 1] = (3 * t3 - 5 * t2 + 2) / 2
        w[at + 2] = (-3 * t3 + 4 * t2 + t) / 2
        w[at + 3] = (t3 - t2) / 2
    }
}

/**
 * Colour ramps. Amount in mm/h (the legend converts to in/h by locale); chance in percent, the same
 * quantity the precipitation graph draws. Below the first stop is fully transparent so the base map
 * shows through where it is dry.
 */
object RainColorScale {
    data class Stop(val value: Float, val argb: Int)

    val AMOUNT_STOPS = listOf(
        Stop(0.1f, 0x8C97F58D.toInt()),
        Stop(0.5f, 0xB451F051.toInt()),
        Stop(2f, 0xC82D7DEE.toInt()),
        Stop(5f, 0xD2FFC03D.toInt()),
        Stop(10f, 0xDCFF6100.toInt()),
        Stop(20f, 0xE6E41800.toInt()),
    )

    val CHANCE_STOPS = listOf(
        Stop(10f, 0x5A9FD8FF.toInt()),
        Stop(30f, 0x8C5AC8FA.toInt()),
        Stop(50f, 0xB42D7DEE.toInt()),
        Stop(70f, 0xC86E4BD8.toInt()),
        Stop(90f, 0xD2C03CC8.toInt()),
    )

    fun stops(layer: RainLayer) = if (layer == RainLayer.AMOUNT) AMOUNT_STOPS else CHANCE_STOPS

    fun color(layer: RainLayer, value: Float): Int {
        val s = stops(layer)
        val first = s.first()
        if (value < first.value) {
            // Fade in over the last half-step below the first stop: a hard cut-off at the threshold
            // drew stair-stepped blob edges once the raster was scaled up.
            val fadeFrom = first.value / 2f
            if (value <= fadeFrom) return 0
            val alpha = ((first.argb ushr 24) * (value - fadeFrom) / (first.value - fadeFrom)).toInt()
            return (alpha shl 24) or (first.argb and 0xFFFFFF)
        }
        if (value >= s.last().value) return s.last().argb
        for (k in 0 until s.size - 1) {
            val lo = s[k]
            val hi = s[k + 1]
            if (value < hi.value) return lerp(lo.argb, hi.argb, (value - lo.value) / (hi.value - lo.value))
        }
        return s.last().argb
    }

    private fun lerp(c0: Int, c1: Int, t: Float): Int {
        fun ch(shift: Int) = ((c0 ushr shift and 0xFF) * (1 - t) + (c1 ushr shift and 0xFF) * t).toInt() and 0xFF
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Legend label for an amount stop: "0.5" mm/h, or inches/h ("0.02") where the app uses inches. */
    fun amountLabel(mmPerHour: Float, useInches: Boolean): String =
        if (useInches) {
            // 0.1 mm/h is .004 in/h: two decimals rounded the first stop to an empty label.
            val inches = mmPerHour / 25.4f
            String.format(java.util.Locale.US, if (inches < 0.01f) "%.3f" else "%.2f", inches).trimEnd('0').trimEnd('.').removePrefix("0")
        } else {
            String.format(java.util.Locale.US, "%.1f", mmPerHour).trimEnd('0').trimEnd('.')
        }
}
