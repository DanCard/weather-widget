package com.weatherwidget.shared.rainmap

import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/**
 * Playback speed of the Open-Meteo rain map, as **seconds of playback per forecast hour**, set by a
 * slider on both platforms. The slider is logarithmic: each halving of the speed is the same travel,
 * so the slow end is not squeezed into a sliver. Left = slow, right = fast.
 *
 * Default 0.8 s/h (user, 2026-10-21: "lower the default speed") — the old "0.5× (Smooth)", half the
 * old default; a 48 h loop from now takes ~38 s.
 * See plans/261021-open-meteo-rain-map-slower-default-speed-slider.md.
 */
object RainLoopSpeed {
    const val SLOWEST_SECONDS = 2.0f
    const val FASTEST_SECONDS = 0.15f
    const val DEFAULT_SECONDS = 0.8f

    /** A stored value from an older build, or garbage, still lands inside the slider. */
    fun clamp(seconds: Float): Float =
        if (seconds.isNaN()) DEFAULT_SECONDS else seconds.coerceIn(FASTEST_SECONDS, SLOWEST_SECONDS)

    /** Slider position 0 (slowest) … 1 (fastest) → seconds per forecast hour. */
    fun secondsAt(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        return exp(ln(SLOWEST_SECONDS) + f * (ln(FASTEST_SECONDS) - ln(SLOWEST_SECONDS)))
    }

    /** Inverse of [secondsAt]. */
    fun fractionOf(seconds: Float): Float =
        ((ln(clamp(seconds)) - ln(SLOWEST_SECONDS)) / (ln(FASTEST_SECONDS) - ln(SLOWEST_SECONDS))).coerceIn(0f, 1f)

    /** Forecast hours to advance per [tickMs] of playback. */
    fun hoursPerTick(tickMs: Long, seconds: Float): Double = tickMs / (clamp(seconds) * 1000.0)

    /** The readout's number: "0.8", "0.15", "2" (the platforms add their "s/h" unit). */
    fun formatSeconds(seconds: Float, locale: Locale = Locale.getDefault()): String {
        val s = clamp(seconds)
        val pattern = if (s < 0.95f) "%.2f" else "%.1f"
        return String.format(locale, pattern, s).trimEnd('0').trimEnd('.', ',')
    }
}
