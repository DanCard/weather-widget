package com.weatherwidget.widget

import androidx.annotation.VisibleForTesting
import kotlin.math.abs
import kotlin.math.min

/** Pure vertical-placement search for the large daily Today-column annotations. */
internal object TodayColumnOverlayPlanner {
    enum class Zone {
        ABOVE,
        BELOW,
        ON_COLUMN,
    }

    data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        fun intersects(other: Bounds): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }

    data class Line(
        val key: String,
        val text: String,
        val width: Float,
        val height: Float,
    )

    data class Placement(
        val key: String,
        val text: String,
        val zone: Zone,
        val bounds: Bounds,
        val score: Float,
    )

    data class Input(
        val columnLeft: Float,
        val columnRight: Float,
        val graphTop: Float,
        val graphBottom: Float,
        val barTop: Float,
        val barBottom: Float,
        val hardObstacles: List<Bounds>,
        val horizontalPadding: Float,
        val padding: Float,
        val verticalStep: Float = 2f,
    )

    fun place(lines: List<Line>, input: Input): List<Placement> {
        if (input.columnRight <= input.columnLeft || input.graphBottom <= input.graphTop) return emptyList()
        val occupied = input.hardObstacles.toMutableList()
        val placements = mutableListOf<Placement>()
        lines.forEach { line ->
            findBest(line, input, occupied)?.let { placement ->
                placements += placement
                occupied += placement.bounds
            }
        }
        return placements
    }

    private fun findBest(
        line: Line,
        input: Input,
        obstacles: List<Bounds>,
    ): Placement? {
        if (line.width <= 0f || line.height <= 0f) return null
        val availableWidth = input.columnRight - input.columnLeft - 2f * input.horizontalPadding
        if (line.width > availableWidth) return null
        val left = (input.columnLeft + input.columnRight - line.width) / 2f
        val right = left + line.width
        val bands =
            listOf(
                Zone.ABOVE to (input.graphTop + input.padding to input.barTop - input.padding),
                Zone.BELOW to (input.barBottom + input.padding to input.graphBottom - input.padding),
                Zone.ON_COLUMN to (input.barTop + input.padding to input.barBottom - input.padding),
            )

        return bands.flatMap { (zone, band) ->
            candidateTops(band.first, band.second, line.height, input.verticalStep).mapNotNull { top ->
                val bounds = Bounds(left, top, right, top + line.height)
                if (obstacles.any(bounds::intersects)) return@mapNotNull null
                val clearance = clearance(bounds, obstacles, band.first, band.second)
                val barPenalty =
                    if (zone == Zone.ON_COLUMN &&
                        bounds.bottom > input.barTop && bounds.top < input.barBottom
                    ) {
                        1_000f
                    } else {
                        0f
                    }
                Placement(
                    key = line.key,
                    text = line.text,
                    zone = zone,
                    bounds = bounds,
                    score = clearance - barPenalty,
                )
            }
        }.maxWithOrNull(
            compareBy<Placement> { it.score }
                .thenBy { zonePreference(it.zone) }
                .thenBy { -abs((it.bounds.top + it.bounds.bottom) / 2f - (input.graphTop + input.graphBottom) / 2f) },
        )
    }

    private fun zonePreference(zone: Zone): Int =
        when (zone) {
            Zone.ABOVE -> 3
            Zone.BELOW -> 2
            Zone.ON_COLUMN -> 1
        }

    private fun clearance(
        bounds: Bounds,
        obstacles: List<Bounds>,
        bandTop: Float,
        bandBottom: Float,
    ): Float {
        var result = min(bounds.top - bandTop, bandBottom - bounds.bottom).coerceAtLeast(0f)
        obstacles.forEach { obstacle ->
            if (bounds.left < obstacle.right && obstacle.left < bounds.right) {
                val gap =
                    when {
                        obstacle.bottom <= bounds.top -> bounds.top - obstacle.bottom
                        obstacle.top >= bounds.bottom -> obstacle.top - bounds.bottom
                        else -> 0f
                    }
                result = min(result, gap)
            }
        }
        return result
    }

    @VisibleForTesting
    internal fun candidateTops(
        bandTop: Float,
        bandBottom: Float,
        height: Float,
        step: Float,
    ): List<Float> {
        val maxTop = bandBottom - height
        if (maxTop < bandTop) return emptyList()
        val safeStep = step.coerceAtLeast(1f)
        return buildList {
            var top = bandTop
            while (top <= maxTop) {
                add(top)
                top += safeStep
            }
            if (isEmpty() || last() < maxTop) add(maxTop)
        }
    }
}
