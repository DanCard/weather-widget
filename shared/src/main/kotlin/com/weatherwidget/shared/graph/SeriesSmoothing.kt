package com.weatherwidget.shared.graph

import kotlin.math.roundToInt

/**
 * Platform-free smoothing for graph and interpolation series.
 *
 * Every preserving variant reapplies anchors from the original input after each pass so repeated
 * smoothing cannot erode values that callers promise to keep exact.
 */
object SeriesSmoothing {
    fun smoothValues(
        values: List<Float>,
        iterations: Int = 1,
    ): List<Float> {
        if (values.size < 3 || iterations <= 0) return values
        var current = values
        repeat(iterations) {
            current = smoothValuesOnePass(current)
        }
        return current
    }

    fun smoothValuesPreservingGlobalExtrema(
        values: List<Float>,
        iterations: Int = 1,
    ): List<Float> {
        if (values.size < 3 || iterations <= 0) return values
        val globalMaxIndex = values.indices.maxByOrNull { values[it] } ?: 0
        val globalMinIndex = values.indices.minByOrNull { values[it] } ?: 0
        return smoothValuesWithPreservedAnchors(
            values = values,
            iterations = iterations,
            preservedIndices = setOf(0, values.lastIndex, globalMaxIndex, globalMinIndex),
        )
    }

    fun smoothValuesPreservingExtrema(
        values: List<Float>,
        iterations: Int = 1,
        preserveGlobalMax: Boolean = true,
        preserveGlobalMin: Boolean = true,
        preserveStart: Boolean = true,
        preserveEnd: Boolean = true,
    ): List<Float> {
        if (values.size < 3 || iterations <= 0) return values
        val globalMaxIndex = values.indices.maxByOrNull { values[it] } ?: 0
        val globalMinIndex = values.indices.minByOrNull { values[it] } ?: 0
        val preservedIndices = buildSet {
            if (preserveStart) add(0)
            if (preserveEnd) add(values.lastIndex)
            if (preserveGlobalMax) add(globalMaxIndex)
            if (preserveGlobalMin) add(globalMinIndex)
        }
        return smoothValuesWithPreservedAnchors(values, iterations, preservedIndices)
    }

    /**
     * Preserves extrema after rounding to integer display units. Cloud cover and precipitation use
     * integer source values; temperature interpolation retains this historical policy for parity.
     */
    fun smoothValuesPreservingAllExtrema(
        values: List<Float>,
        iterations: Int = 1,
    ): List<Float> {
        if (values.size < 3 || iterations <= 0) return values
        val rounded = values.map { it.roundToInt() }
        val preservedIndices = buildSet {
            add(0)
            add(values.lastIndex)
            addAll(GraphLabelPlacementUtils.findLocalExtremaIndices(rounded, isMax = true))
            addAll(GraphLabelPlacementUtils.findLocalExtremaIndices(rounded, isMax = false))
        }
        return smoothValuesWithPreservedAnchors(values, iterations, preservedIndices)
    }

    private fun smoothValuesOnePass(values: List<Float>): List<Float> =
        List(values.size) { index ->
            val previous = values.getOrElse(index - 1) { values[index] }
            val current = values[index]
            val next = values.getOrElse(index + 1) { values[index] }
            previous * 0.25f + current * 0.5f + next * 0.25f
        }

    private fun smoothValuesWithPreservedAnchors(
        values: List<Float>,
        iterations: Int,
        preservedIndices: Set<Int>,
    ): List<Float> {
        var current = values
        repeat(iterations) {
            val smoothed = smoothValuesOnePass(current).toMutableList()
            preservedIndices.forEach { index -> smoothed[index] = values[index] }
            current = smoothed
        }
        return current
    }
}
