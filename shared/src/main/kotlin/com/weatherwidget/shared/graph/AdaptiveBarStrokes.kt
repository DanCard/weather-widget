package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.WeatherColors
import com.weatherwidget.shared.util.WeatherConditionResolver

/**
 * The whole weather-adaptive daily bar, decided once for Android (`DailyBarRenderer`) and desktop
 * (`DailyForecastGraph`): whether it splits, which colours, and where each segment ends. A platform
 * only draws each [Stroke] — the full round-capped bar line from highY to lowY in that colour,
 * clipped above [Stroke.clipBottomY] — in list order, so later strokes paint over earlier ones.
 *
 * Split bars (see [WeatherColors.resolveMixedBarSplit]): the bottom (cloud grey / rain blue) full
 * height, then a rainy bar's grey middle, then the clear top. The top's colour is
 * [WeatherColors.splitTopArgb] — the bar's own colour, except gold over a rainy or cloud-grey bar.
 */
object AdaptiveBarStrokes {

    /** One pass of the bar line in [colorArgb]; [clipBottomY] null = the whole bar. */
    data class Stroke(val colorArgb: Int, val clipBottomY: Float?)

    /** [split] is null for a solid bar; kept for the platforms' debug logs. */
    data class Plan(val strokes: List<Stroke>, val split: WeatherColors.MixedBarSplit?)

    /** Segments thinner than this many px are not drawn. */
    private const val MIN_SEGMENT_PX = 0.5f

    /**
     * @param iconName the bar's resolved icon; none → a solid bar.
     * @param cloudCoverRatio the day's cloud share (0..1); a split needs a mixed icon or a share > 0.
     * @param dayPrecipProbability the column's daytime rain % (gates the rainy split); null for
     *   snapshot bars.
     * @param barArgb the bar's own colour.
     * @param inkRadius half the stroke width (the round cap reaches this far past each end).
     */
    fun plan(
        iconName: String?,
        cloudCoverRatio: Float?,
        dayPrecipProbability: Int?,
        barArgb: Int,
        highY: Float,
        lowY: Float,
        inkRadius: Float,
    ): Plan {
        val solid = Plan(listOf(Stroke(barArgb, null)), null)
        if (iconName == null) return solid
        if (!WeatherColors.shouldDrawAdaptiveBar(WeatherConditionResolver.isMixed(iconName), cloudCoverRatio)) return solid
        if (lowY - highY <= 1f) return solid
        val split = WeatherColors.resolveMixedBarSplit(iconName, cloudCoverRatio, dayPrecipProbability) ?: return solid

        val edges = WeatherColors.segmentEdges(split.topFraction, split.midFraction, highY, lowY, inkRadius)
        val strokes = buildList {
            add(Stroke(split.bottomColorArgb, null))
            if (edges.midEndY - edges.topEndY > MIN_SEGMENT_PX) add(Stroke(split.midColorArgb, edges.midEndY))
            if (edges.topEndY - (highY - inkRadius) > MIN_SEGMENT_PX) {
                add(Stroke(WeatherColors.splitTopArgb(split.topOverridesBar, split.topColorArgb, barArgb), edges.topEndY))
            }
        }
        return Plan(strokes, split)
    }
}
