package com.weatherwidget.widget

import android.graphics.Path
import com.weatherwidget.shared.graph.CurveMath

/** Android [Path] construction for finite runs of graph points. */
object AndroidCurvePathBuilder {
    data class IndexedCurvePath(
        val startPointIndex: Int,
        val endPointIndex: Int,
        val startsContour: Boolean,
        val path: Path,
    )

    private data class IndexedPoint(
        val sourceIndex: Int,
        val coordinates: Pair<Float, Float>,
    )

    fun buildSmoothCurveAndFillPaths(
        points: List<Pair<Float, Float>>,
        graphBottom: Float,
    ): Pair<Path, Path> {
        val curvePath = Path()
        val fillPath = Path()

        finiteRuns(points).forEach { run ->
            val coordinates = run.map { it.coordinates }
            curvePath.moveTo(coordinates.first().first, coordinates.first().second)
            fillPath.moveTo(coordinates.first().first, coordinates.first().second)
            appendCubics(coordinates) { cp1x, cp1y, cp2x, cp2y, endX, endY ->
                curvePath.cubicTo(cp1x, cp1y, cp2x, cp2y, endX, endY)
                fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, endX, endY)
            }
            fillPath.lineTo(coordinates.last().first, graphBottom)
            fillPath.lineTo(coordinates.first().first, graphBottom)
            fillPath.close()
        }

        return curvePath to fillPath
    }

    /**
     * Builds one path for each adjacent finite point pair while retaining the source indices that
     * callers need for per-hour color and dash-phase decisions.
     */
    fun buildPerSegmentPaths(points: List<Pair<Float, Float>>): List<IndexedCurvePath> =
        buildList {
            finiteRuns(points).forEach { run ->
                if (run.size < 2) return@forEach
                val coordinates = run.map { it.coordinates }
                val tangents = CurveMath.computeTangents(coordinates)
                for (index in 0 until run.lastIndex) {
                    val start = coordinates[index]
                    val end = coordinates[index + 1]
                    val startTangent = tangents[index]
                    val endTangent = tangents[index + 1]
                    add(
                        IndexedCurvePath(
                            startPointIndex = run[index].sourceIndex,
                            endPointIndex = run[index + 1].sourceIndex,
                            startsContour = index == 0,
                            path = Path().apply {
                                moveTo(start.first, start.second)
                                cubicTo(
                                    start.first + startTangent.first / 3f,
                                    start.second + startTangent.second / 3f,
                                    end.first - endTangent.first / 3f,
                                    end.second - endTangent.second / 3f,
                                    end.first,
                                    end.second,
                                )
                            },
                        ),
                    )
                }
            }
        }

    private inline fun appendCubics(
        points: List<Pair<Float, Float>>,
        append: (
            cp1x: Float,
            cp1y: Float,
            cp2x: Float,
            cp2y: Float,
            endX: Float,
            endY: Float,
        ) -> Unit,
    ) {
        if (points.size < 2) return
        val tangents = CurveMath.computeTangents(points)
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            append(
                start.first + tangents[index].first / 3f,
                start.second + tangents[index].second / 3f,
                end.first - tangents[index + 1].first / 3f,
                end.second - tangents[index + 1].second / 3f,
                end.first,
                end.second,
            )
        }
    }

    private fun finiteRuns(points: List<Pair<Float, Float>>): List<List<IndexedPoint>> {
        if (points.isEmpty()) return emptyList()
        val runs = mutableListOf<List<IndexedPoint>>()
        var current = mutableListOf<IndexedPoint>()
        points.forEachIndexed { index, point ->
            if (!point.first.isFinite() || !point.second.isFinite()) {
                if (current.isNotEmpty()) {
                    runs.add(current)
                    current = mutableListOf()
                }
            } else {
                current.add(IndexedPoint(index, point))
            }
        }
        if (current.isNotEmpty()) runs.add(current)
        return runs
    }
}
