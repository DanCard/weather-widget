package com.weatherwidget.shared.graph

/**
 * Platform-free temperature→color model shared by the Android widget (`TemperatureGraphStyle`) and
 * the desktop Compose graph (`TemperatureGraph`). Both previously inlined identical thresholds and
 * colors but blended differently — Android in integer RGB space, desktop via Compose `lerp()` — which
 * could yield subtly different pixels for the same temperature. Centralizing the blend here makes the
 * two platforms produce identical colors.
 *
 * Colors are packed ARGB `Int`s (0xAARRGGBB). Android uses them directly; desktop wraps with
 * `Color(argb)`. No android.graphics or Compose types appear here, so this stays in pure `:shared`.
 */
object TemperatureColorModel {
    const val COLD_THRESHOLD = 50f
    const val MILD_TEMP = 70f
    const val HOT_THRESHOLD = 90f

    const val COLOR_COLD = 0xFF5AC8FA.toInt() // Blue
    const val COLOR_MILD = 0xFFE8A24E.toInt() // Golden amber
    const val COLOR_HOT = 0xFFFF6B35.toInt() // Warm orange

    /** Thresholds (high → low) used to seed gradient stops, kept here so callers share the order. */
    val THRESHOLDS = listOf(HOT_THRESHOLD, MILD_TEMP, COLD_THRESHOLD)

    /**
     * Maps a temperature (°F) to a packed ARGB color, blending between cold/mild/hot in integer RGB
     * space. Always fully opaque; callers apply their own alpha for gradients.
     */
    fun tempToColorArgb(temp: Float): Int = when {
        temp <= COLD_THRESHOLD -> COLOR_COLD
        temp >= HOT_THRESHOLD -> COLOR_HOT
        temp <= MILD_TEMP -> blend(COLOR_COLD, COLOR_MILD, (temp - COLD_THRESHOLD) / (MILD_TEMP - COLD_THRESHOLD))
        else -> blend(COLOR_MILD, COLOR_HOT, (temp - MILD_TEMP) / (HOT_THRESHOLD - MILD_TEMP))
    }

    /** Linear interpolation between two opaque ARGB colors in integer RGB space. */
    fun blend(c1: Int, c2: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val r = (red(c1) * (1 - f) + red(c2) * f).toInt()
        val g = (green(c1) * (1 - f) + green(c2) * f).toInt()
        val b = (blue(c1) * (1 - f) + blue(c2) * f).toInt()
        return argb(255, r, g, b)
    }

    /** Replaces the alpha channel of [color] (0..255), leaving RGB intact. */
    fun withAlpha(color: Int, alpha: Int): Int = argb(alpha, red(color), green(color), blue(color))

    fun red(color: Int): Int = (color shr 16) and 0xFF
    fun green(color: Int): Int = (color shr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}
