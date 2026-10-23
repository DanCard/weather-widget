package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.WeatherColors
import com.weatherwidget.shared.util.WeatherConditionResolver

/**
 * The daily bar's sky, decided once for Android (`DailyBarRenderer`) and desktop
 * (`DailyForecastGraph`); a platform only draws each [Stroke] — the full round-capped bar line from
 * highY to lowY in that colour, clipped above [Stroke.clipBottomY] — in list order, later over
 * earlier (user, 2026-10-23; plans/261023-daily-bar-noon-cloud-rule-and-soft-edge.md).
 *
 * Top to bottom: clear (gold) · cloud (grey) · rain (blue).
 *  - Rain first: the daytime rain chance is the blue share, any chance (user, 2026-10-23: a 20 % day
 *    is 20 % blue); the cap above it is the sky, gold over grey by the cloud share (all grey without one).
 *  - Otherwise the noon cloud % alone: gold 100 − cloud, grey cloud.
 *  - No noon cloud % → the icon's sky ([WeatherConditionResolver.skyCloudRatio]; the icon is then
 *    the provider's condition word); none either → all grey, "unknown".
 * The icon never decides anything else. Each colour edge is a short blend of [BLEND_STEPS] solid
 * strokes, not a shader — Samsung launchers flattened the gradient shader (6deaeece).
 */
object AdaptiveBarStrokes {

    /** One pass of the bar line in [colorArgb]; [clipBottomY] null = the whole bar. */
    data class Stroke(val colorArgb: Int, val clipBottomY: Float?)

    /** Shares of the bar, top to bottom; they sum to 1. */
    data class Sky(val clear: Float, val cloud: Float, val rain: Float)

    data class Plan(val strokes: List<Stroke>, val sky: Sky)

    /** Solid steps across each colour edge. */
    const val BLEND_STEPS = 6

    fun sky(noonCloudRatio: Float?, fallbackIconName: String?, dayRainPercent: Int?): Sky {
        val cloud = (noonCloudRatio ?: WeatherConditionResolver.skyCloudRatio(fallbackIconName))?.coerceIn(0f, 1f)
        val rain = dayRainPercent?.let { (it / 100f).coerceIn(0f, 1f) } ?: 0f
        val cap = 1f - rain
        val grey = cloud ?: 1f
        return Sky(clear = cap * (1f - grey), cloud = cap * grey, rain = rain)
    }

    /**
     * The rain chance of the "yesterday's forecast" bar (left of a triple bar). Today: the picked
     * prior row's own daytime chance (else its whole-day one), as its icon reads it — without it a
     * forecast of rain under heavy cloud drew mostly grey. A past day's prior row keeps no chance,
     * so the bar takes the day's, as it takes the day's icon.
     */
    fun priorBarRainPercent(isPast: Boolean, priorRowDaytime: Int?, priorRowWholeDay: Int?, dayRainPercent: Int?): Int? =
        if (isPast) dayRainPercent else priorRowDaytime ?: priorRowWholeDay

    /**
     * @param noonCloudRatio the day's noon cloud share (0..1), null when unknown.
     * @param fallbackIconName the day's icon, read only when [noonCloudRatio] is null.
     * @param dayRainPercent the daytime rain chance (a snapshot bar: [priorBarRainPercent]); null = none.
     * @param clearArgb the clear share's colour: gold, or a snapshot bar's own yellow / blue.
     * @param inkRadius half the stroke width (the round cap reaches this far past each end).
     */
    fun plan(
        noonCloudRatio: Float?,
        fallbackIconName: String?,
        dayRainPercent: Int?,
        highY: Float,
        lowY: Float,
        inkRadius: Float,
        clearArgb: Int = WeatherColors.FORECAST_SUNNY,
    ): Plan {
        val sky = sky(noonCloudRatio, fallbackIconName, dayRainPercent)
        val segments = listOf(
            clearArgb to sky.clear,
            WeatherColors.FORECAST_CLOUDY to sky.cloud,
            WeatherColors.FORECAST_RAINY to sky.rain,
        ).filter { it.second > 0f }
        if (segments.size == 1 || lowY - highY <= 1f) {
            return Plan(listOf(Stroke(segments.maxBy { it.second }.first, null)), sky)
        }

        // Edges over the painted ink: the round caps reach inkRadius past both ends.
        val inkTop = highY - inkRadius
        val ink = lowY + inkRadius - inkTop
        val tops = segments.runningFold(inkTop) { y, (_, share) -> y + ink * share }
        val strokes = mutableListOf(Stroke(segments.last().first, null))
        for (i in segments.lastIndex downTo 1) {
            val upper = segments[i - 1].first
            val lower = segments[i].first
            val edge = tops[i]
            val half = minOf(inkRadius, (edge - tops[i - 1]) / 2f, (tops[i + 1] - edge) / 2f)
            if (half >= 0.5f) {
                for (step in 0 until BLEND_STEPS) {
                    val t = (step + 0.5f) / BLEND_STEPS
                    strokes += Stroke(lerpArgb(lower, upper, t), edge + half - 2f * half * step / BLEND_STEPS)
                }
                strokes += Stroke(upper, edge - half)
            } else {
                strokes += Stroke(upper, edge)
            }
        }
        return Plan(strokes, sky)
    }

    internal fun lerpArgb(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * t + 0.5f).toInt().coerceIn(0, 255) shl shift
        }
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}
