package com.weatherwidget.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.time.Duration
import java.time.LocalDateTime

internal object GraphRenderUtils {
    fun buildSmoothCurveAndFillPaths(
        points: List<Pair<Float, Float>>,
        graphBottom: Float,
    ): Pair<Path, Path> {
        val curvePath = Path()
        val fillPath = Path()

        if (points.isNotEmpty()) {
            curvePath.moveTo(points[0].first, points[0].second)
            fillPath.moveTo(points[0].first, points[0].second)

            if (points.size > 1) {
                val tangents =
                    points.indices.map { i ->
                        when (i) {
                            0 ->
                                Pair(
                                    (points[1].first - points[0].first) * 0.5f,
                                    (points[1].second - points[0].second) * 0.5f,
                                )

                            points.size - 1 ->
                                Pair(
                                    (points[i].first - points[i - 1].first) * 0.5f,
                                    (points[i].second - points[i - 1].second) * 0.5f,
                                )

                            else -> {
                                val dx = (points[i + 1].first - points[i - 1].first) * 0.5f
                                var dy = (points[i + 1].second - points[i - 1].second) * 0.5f

                                // Monotone-aware tangents: Zero out Y tangent if at a plateau or extremum
                                val yPrev = points[i - 1].second
                                val yCurr = points[i].second
                                val yNext = points[i + 1].second

                                val delta1 = yCurr - yPrev
                                val delta2 = yNext - yCurr

                                // If either side is flat, or if it's a peak/valley (signs differ), force tangent to 0
                                if (delta1 == 0f || delta2 == 0f || (delta1 > 0 && delta2 < 0) || (delta1 < 0 && delta2 > 0)) {
                                    dy = 0f
                                }

                                Pair(dx, dy)
                            }
                        }
                    }

                for (i in 0 until points.size - 1) {
                    val cp1x = points[i].first + tangents[i].first / 3f
                    val cp1y = points[i].second + tangents[i].second / 3f
                    val cp2x = points[i + 1].first - tangents[i + 1].first / 3f
                    val cp2y = points[i + 1].second - tangents[i + 1].second / 3f
                    curvePath.cubicTo(cp1x, cp1y, cp2x, cp2y, points[i + 1].first, points[i + 1].second)
                    fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, points[i + 1].first, points[i + 1].second)
                }
            }

            fillPath.lineTo(points.last().first, graphBottom)
            fillPath.lineTo(points.first().first, graphBottom)
            fillPath.close()
        }

        return curvePath to fillPath
    }

    fun <T> computeXForTime(
        targetTime: LocalDateTime,
        items: List<T>,
        points: List<Pair<Float, Float>>,
        hourWidth: Float,
        dateTimeOf: (T) -> LocalDateTime,
    ): Float? {
        if (items.isEmpty() || points.isEmpty()) return null
        
        // Find the hour bucket
        val firstTime = dateTimeOf(items.first())
        val lastTime = dateTimeOf(items.last())
        
        if (targetTime.isBefore(firstTime) || targetTime.isAfter(lastTime)) return null
        
        // Find the index of the hour starting at or before targetTime
        val index = items.indexOfLast { !dateTimeOf(it).isAfter(targetTime) }
        if (index == -1 || index >= points.size) return null
        
        val basePointX = points[index].first
        val minutesOffset = Duration.between(dateTimeOf(items[index]), targetTime).toMinutes()
        
        return basePointX + (minutesOffset / 60f) * hourWidth
    }

    fun <T> computeNowX(
        items: List<T>,
        points: List<Pair<Float, Float>>,
        currentTime: LocalDateTime,
        hourWidth: Float,
        isCurrentHour: (T) -> Boolean,
        dateTimeOf: (T) -> LocalDateTime,
    ): Float? {
        val currentHourIndex = items.indexOfFirst(isCurrentHour)
        if (currentHourIndex == -1 || currentHourIndex !in points.indices) return null

        val minutesOffset = Duration.between(dateTimeOf(items[currentHourIndex]), currentTime).toMinutes()
        return points[currentHourIndex].first + (minutesOffset / 60f) * hourWidth
    }

    fun <T> drawHourLabels(
        canvas: Canvas,
        items: List<T>,
        points: List<Pair<Float, Float>>,
        widthPx: Int,
        heightPx: Int,
        minHourLabelSpacing: Float,
        hourLabelTextPaint: Paint,
        dpToPx: (Float) -> Float,
        showLabel: (T) -> Boolean,
        labelText: (T) -> String,
        onLabelDrawn: ((index: Int, clampedX: Float) -> Unit)? = null,
    ) {
        var lastHourLabelX = -1000f

        items.forEachIndexed { index, item ->
            val centerX = points[index].first
            if (showLabel(item) && (centerX - lastHourLabelX >= minHourLabelSpacing)) {
                val text = labelText(item)
                val labelY = heightPx - dpToPx(1f)
                val textWidth = hourLabelTextPaint.measureText(text)
                val clampedX = centerX.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)
                canvas.drawText(text, clampedX, labelY, hourLabelTextPaint)
                lastHourLabelX = centerX
                onLabelDrawn?.invoke(index, clampedX)
            }
        }
    }

    fun drawNowIndicator(
        canvas: Canvas,
        nowX: Float?,
        graphTop: Float,
        graphHeight: Float,
        currentTimePaint: Paint,
        nowLabelTextPaint: Paint,
        dpToPx: (Float) -> Float,
    ) {
        if (nowX == null) return

        val lineHeight = graphHeight * 0.6f
        val lineTop = graphTop + (graphHeight - lineHeight) / 2f
        val lineBottom = lineTop + lineHeight
        canvas.drawLine(nowX, lineTop, nowX, lineBottom, currentTimePaint)
        canvas.drawText("NOW", nowX, lineTop - dpToPx(2f), nowLabelTextPaint)
    }

    /**
     * Applies a 3-point weighted moving average [0.25, 0.5, 0.25] to smooth out
     * "stair-step" data plateaus. Multiple iterations create a progressively
     * smoother, more fluid curve.
     */
    fun smoothValues(
        values: List<Float>,
        iterations: Int = 1,
    ): List<Float> {
        if (values.size < 3 || iterations <= 0) return values
        var current = values

        repeat(iterations) {
            val smoothed = mutableListOf<Float>()
            for (i in current.indices) {
                val prev = if (i > 0) current[i - 1] else current[i]
                val curr = current[i]
                val next = if (i < current.lastIndex) current[i + 1] else current[i]
                
                // Weighted average: 25% prev, 50% current, 25% next
                smoothed.add(prev * 0.25f + curr * 0.5f + next * 0.25f)
            }
            current = smoothed
        }

        return current
    }
}
