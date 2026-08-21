package com.weatherwidget.shared.graph

/**
 * The cloud graph's palette and label rules, shared so the Android renderer
 * (`CloudCoverGraphStyle` / `CloudCoverGraphRenderer`) and the desktop composable
 * (`CloudCoverGraph`) cannot drift. ARGB ints; each platform wraps them in its own Color type.
 *
 * Complementary hues 180 deg apart (343 pink / 163 mint), but deliberately ASYMMETRIC in how far
 * each is pushed. The forecast is the background quantity and stays essentially grey; the actual
 * is the thing worth looking at and carries real pink.
 *
 * Saturation has to fight lightness here: at 94% lightness even 35% saturation is invisible,
 * which is why an earlier attempt at a "slight pink tint" read as plain white. Showing the hue at
 * all means coming down in lightness, so the actual sits at 85% lightness — which is what lets
 * 32% saturation register as pink rather than disappear.
 */
object CloudCoverGraphPalette {
    /** Forecast curve: light neutral grey (hsl 163, 4%, 72%). */
    const val CURVE_FORECAST = 0xFFB5BAB9.toInt()

    /** Actual curve: pale pink (hsl 343, 55%, 91%). */
    const val CURVE_ACTUAL = 0xFFF5DBE3.toInt()

    /** Forecast value labels: pale neutral white — lighter than the curve to read on dark plots. */
    const val LABEL_FORECAST = 0xFFE9ECEB.toInt()

    /** Fill under the forecast curve: the curve grey at 22 -> 0 alpha. */
    const val FILL_START = 0x44B5BAB9
    const val FILL_END = 0x00B5BAB9

    /**
     * Minimum forecast-vs-actual gap, in percentage points, worth a second label. Below this the
     * curves overlap on screen and the extra number is pure clutter.
     */
    const val ACTUAL_LABEL_MIN_DIVERGENCE = 8
}

/**
 * The emptiest-region search for the cloud graph's background watermark, shared by the Android
 * renderer and the desktop composable. Pure: given the curve's (smoothed) values it returns
 * candidate center indices, emptiest first, preferring window centers away from the plot edges.
 * Each platform keeps its own bounds/overlap placement loop and drawing.
 */
object CloudWatermarkPlacement {
    const val WINDOW_DIVISOR = 5
    const val WINDOW_MIN = 3
    const val WINDOW_MAX = 6

    fun candidateCenters(values: List<Float>): List<Int> {
        val windowSize = (values.size / WINDOW_DIVISOR).coerceIn(WINDOW_MIN, WINDOW_MAX)
        if (values.size < windowSize) return emptyList()
        return (0..values.size - windowSize)
            .map { start ->
                val avg = (start until start + windowSize).map { values[it] }.average().toFloat()
                val center = start + windowSize / 2
                val edgeDistance = minOf(center, values.lastIndex - center)
                Triple(center, avg, edgeDistance)
            }
            .sortedWith(compareBy<Triple<Int, Float, Int>> { it.second }.thenByDescending { it.third })
            .map { it.first }
            .distinct()
    }
}
