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
