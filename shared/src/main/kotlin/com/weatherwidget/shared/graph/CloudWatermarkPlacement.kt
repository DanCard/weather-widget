package com.weatherwidget.shared.graph

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
