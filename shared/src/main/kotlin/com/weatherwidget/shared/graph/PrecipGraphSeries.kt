package com.weatherwidget.shared.graph

/**
 * The values the hourly rain-chance graph plots and labels, on both platforms.
 *
 * Raw source percentages, clamped to 0..100, never smoothed. A box filter pulled the last hour of
 * a plateau toward its neighbour (NWS 25/25/25/0 drew and labelled 3am as "19%"), showing a chance
 * the source never published. The curve still reads smooth: [CurveMath]'s monotone tangents pass
 * through every hour, flatten at plateaus, and never overshoot.
 * See plans/261104-precip-graph-remove-smoothing.md.
 */
object PrecipGraphSeries {
    fun values(probabilities: List<Int?>): List<Float> =
        probabilities.map { (it ?: 0).coerceIn(0, 100).toFloat() }
}
