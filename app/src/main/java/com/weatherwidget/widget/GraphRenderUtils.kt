package com.weatherwidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

internal object GraphRenderUtils {
    // Allocates two new Path objects per call. Caller invokes ~4x per render cycle (15-60 min interval),
    // producing ~8 short-lived Path objects. Acceptable for widget update frequency; if interval drops
    // below ~1 min, consider reusing Path instances via reset().
    fun buildSmoothCurveAndFillPaths(
        points: List<Pair<Float, Float>>,
        graphBottom: Float,
    ): Pair<Path, Path> {
        val curvePath = Path()
        val fillPath = Path()

        val segments = splitContiguousSegments(points)
        for (segment in segments) {
            if (segment.isEmpty()) continue
            curvePath.moveTo(segment[0].first, segment[0].second)
            fillPath.moveTo(segment[0].first, segment[0].second)

            if (segment.size > 1) {
                val tangents = computeTangents(segment)

                for (i in 0 until segment.size - 1) {
                    val cp1x = segment[i].first + tangents[i].first / 3f
                    val cp1y = segment[i].second + tangents[i].second / 3f
                    val cp2x = segment[i + 1].first - tangents[i + 1].first / 3f
                    val cp2y = segment[i + 1].second - tangents[i + 1].second / 3f
                    curvePath.cubicTo(cp1x, cp1y, cp2x, cp2y, segment[i + 1].first, segment[i + 1].second)
                    fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, segment[i + 1].first, segment[i + 1].second)
                }
            }

            fillPath.lineTo(segment.last().first, graphBottom)
            fillPath.lineTo(segment.first().first, graphBottom)
            fillPath.close()
        }

        return curvePath to fillPath
    }

    /**
     * Builds individual Path objects for each cubic segment between adjacent points.
     * Used for per-segment coloring (e.g., weather-adaptive forecast line colors).
     * Uses the same Catmull-Rom tangent logic as [buildSmoothCurveAndFillPaths].
     */
    fun buildPerSegmentPaths(points: List<Pair<Float, Float>>): List<Path> {
        if (points.size < 2) return emptyList()
        val result = mutableListOf<Path>()
        val segments = splitContiguousSegments(points)
        for (segment in segments) {
            if (segment.size < 2) continue
            val tangents = computeTangents(segment)
            for (i in 0 until segment.size - 1) {
                result.add(Path().apply {
                    moveTo(segment[i].first, segment[i].second)
                    val cp1x = segment[i].first + tangents[i].first / 3f
                    val cp1y = segment[i].second + tangents[i].second / 3f
                    val cp2x = segment[i + 1].first - tangents[i + 1].first / 3f
                    val cp2y = segment[i + 1].second - tangents[i + 1].second / 3f
                    cubicTo(cp1x, cp1y, cp2x, cp2y, segment[i + 1].first, segment[i + 1].second)
                })
            }
        }
        return result
    }

    fun splitContiguousSegments(points: List<Pair<Float, Float>>): List<List<Pair<Float, Float>>> {
        if (points.isEmpty()) return emptyList()
        val segments = mutableListOf<List<Pair<Float, Float>>>()
        var current = mutableListOf<Pair<Float, Float>>()
        for (p in points) {
            if (p.first.isNaN() || p.second.isNaN()) {
                if (current.isNotEmpty()) {
                    segments.add(current)
                    current = mutableListOf()
                }
            } else {
                current.add(p)
            }
        }
        if (current.isNotEmpty()) segments.add(current)
        return segments
    }

    /**
     * Computes monotone-aware tangents for a set of points.
     * These tangents ensure the cubic spline doesn't overshoot at peaks/valleys.
     */
    fun computeTangents(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.size < 2) return points.map { 0f to 0f }
        
        return points.indices.map { i ->
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
                    val dxPrev = points[i].first - points[i - 1].first
                    val dxNext = points[i + 1].first - points[i].first
                    
                    val dx = (dxPrev + dxNext) * 0.5f
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

                    // For non-uniform spacing (like sub-hourly observations), prevent the tangent
                    // from overshooting the segment distance, which causes loopbacks and wild swoops.
                    val maxSafeDx = dxPrev.coerceAtMost(dxNext) * 1.5f
                    if (dx > maxSafeDx && maxSafeDx > 0) {
                        val scale = maxSafeDx / dx
                        Pair(maxSafeDx, dy * scale)
                    } else {
                        Pair(dx, dy)
                    }
                }
            }
        }
    }

    /**
     * Evaluates the Y value of a monotone-aware cubic spline at a given fraction [0..1]
     * between two points p0 and p1 with tangents t0 and t1.
     */
    fun evaluateCubicY(
        p0y: Float,
        t0y: Float,
        p1y: Float,
        t1y: Float,
        t: Float,
    ): Float {
        val cp1y = p0y + t0y / 3f
        val cp2y = p1y - t1y / 3f
        
        val mt = 1f - t
        return mt * mt * mt * p0y +
               3f * mt * mt * t * cp1y +
               3f * mt * t * t * cp2y +
               t * t * t * p1y
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

    data class NowLabelResult(val labelY: Float, val bounds: RectF)

    data class DayLabelPlacement(
        val side: String,
        val text: String,
        val date: LocalDate,
        val x: Float,
        val y: Float,
        val placement: String,
        val isToday: Boolean,
        val bounds: RectF,
    )

    fun computeNowLabelBounds(
        nowX: Float,
        graphTop: Float,
        graphHeight: Float,
        textWidth: Float,
        fontAscent: Float,
        fontDescent: Float,
        drawnBounds: List<RectF> = emptyList(),
        dpToPx: (Float) -> Float,
    ): NowLabelResult? {
        val lineHeight = graphHeight * 0.6f
        val lineTop = graphTop + (graphHeight - lineHeight) / 2f
        val lineBottom = lineTop + lineHeight

        val labelYTop = lineTop - dpToPx(2f)
        val labelYBottom = lineBottom - fontAscent + dpToPx(2f)

        for (labelY in listOf(labelYBottom, labelYTop)) {
            val textBounds = RectF(
                nowX - textWidth / 2f,
                labelY + fontAscent,
                nowX + textWidth / 2f,
                labelY + fontDescent
            )
            val hasCollision = drawnBounds.any { RectF.intersects(it, textBounds) }
            if (!hasCollision) {
                return NowLabelResult(labelY, textBounds)
            }
        }
        return null
    }

    fun drawNowIndicator(
        canvas: Canvas,
        nowX: Float?,
        graphTop: Float,
        graphHeight: Float,
        currentTimePaint: Paint,
        nowLabelTextPaint: Paint,
        drawnBounds: List<RectF> = emptyList(),
        dpToPx: (Float) -> Float,
    ) {
        if (nowX == null) return

        val lineHeight = graphHeight * 0.6f
        val lineTop = graphTop + (graphHeight - lineHeight) / 2f
        val lineBottom = lineTop + lineHeight
        canvas.drawLines(floatArrayOf(nowX, lineTop, nowX, lineBottom), currentTimePaint)

        val text = "NOW"
        val textWidth = nowLabelTextPaint.measureText(text)
        val fm = nowLabelTextPaint.fontMetrics ?: Paint.FontMetrics().apply {
            ascent = -nowLabelTextPaint.textSize
            descent = nowLabelTextPaint.textSize * 0.2f
        }
        val result = computeNowLabelBounds(
            nowX = nowX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            textWidth = textWidth,
            fontAscent = fm.ascent,
            fontDescent = fm.descent,
            drawnBounds = drawnBounds,
            dpToPx = dpToPx
        )
        if (result != null) {
            canvas.drawText(text, nowX, result.labelY, nowLabelTextPaint)
        }
    }

    /**
     * Computes final placements for the left and right day-of-week labels on an hourly graph
     * without drawing them.
     *
     * The function mirrors the placement rules used by [drawDayLabels]: it scans from the top
     * of the graph downward, avoids collisions with existing text/icon bounds and with the other
     * day label, and falls back to a bottom placement if no collision-free graph position exists.
     *
     * Use this when later layout decisions, such as watermark or secondary annotation placement,
     * need to reserve space for day labels before the render pass draws them.
     */
    fun computeDayLabelPlacements(
        leftDate: LocalDate,
        rightDate: LocalDate,
        leftText: String,
        rightText: String,
        leftX: Float,
        rightX: Float,
        today: LocalDate,
        graphTop: Float,
        graphBottom: Float,
        heightPx: Int,
        dayLabelFontMetrics: Pair<Float, Float>,
        todayLabelFontMetrics: Pair<Float, Float>,
        measureDayText: (String, Boolean) -> Float,
        drawnLabelBounds: List<RectF>,
        drawnIconBounds: List<RectF>,
        dpToPx: (Float) -> Float,
    ): List<DayLabelPlacement> {
        val fm = Paint.FontMetrics().apply {
            ascent = dayLabelFontMetrics.first
            descent = dayLabelFontMetrics.second
        }

        fun dayBounds(x: Float, y: Float, textWidth: Float, fontMetrics: Paint.FontMetrics): RectF =
            RectF(x - textWidth / 2f, y + fontMetrics.ascent, x + textWidth / 2f, y + fontMetrics.descent)

        val placedBounds = mutableListOf<RectF>()

        fun collides(bounds: RectF): Boolean =
            drawnLabelBounds.any { RectF.intersects(it, bounds) } ||
                drawnIconBounds.any { RectF.intersects(it, bounds) } ||
                placedBounds.any { RectF.intersects(it, bounds) }

        data class DayCandidate(val side: String, val date: LocalDate, val x: Float, val text: String)

        val candidates = listOf(
            DayCandidate(side = "LEFT", date = leftDate, x = leftX, text = leftText),
            DayCandidate(side = "RIGHT", date = rightDate, x = rightX, text = rightText),
        )

        val yFractions = (5..92 step 2).map { it / 100f }
        val graphHeight = graphBottom - graphTop
        val placements = mutableListOf<DayLabelPlacement>()

        for (candidate in candidates) {
            val isToday = candidate.date == today
            val fontMetrics = if (isToday) {
                Paint.FontMetrics().apply {
                    ascent = todayLabelFontMetrics.first
                    descent = todayLabelFontMetrics.second
                }
            } else fm
            val textWidth = measureDayText(candidate.text, isToday)

            var placement: DayLabelPlacement? = null
            for (yFrac in yFractions) {
                val y = graphTop + graphHeight * yFrac
                val bounds = dayBounds(candidate.x, y, textWidth, fontMetrics)
                if (!collides(bounds)) {
                    placement = DayLabelPlacement(
                        side = candidate.side,
                        text = candidate.text,
                        date = candidate.date,
                        x = candidate.x,
                        y = y,
                        placement = "yFrac=$yFrac",
                        isToday = isToday,
                        bounds = bounds,
                    )
                    break
                }
            }

            if (placement == null) {
                val y = heightPx - dpToPx(14f)
                placement = DayLabelPlacement(
                    side = candidate.side,
                    text = candidate.text,
                    date = candidate.date,
                    x = candidate.x,
                    y = y,
                    placement = "BOTTOM_FALLBACK",
                    isToday = isToday,
                    bounds = dayBounds(candidate.x, y, textWidth, fontMetrics),
                )
            }

            placements.add(placement)
            placedBounds.add(placement.bounds)
        }

        return placements
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
            current = smoothValuesOnePass(current)
        }

        return current
    }

    /**
     * Applies smoothing while preserving the global maximum, global minimum, start point, and end point.
     * This ensures the daily high/low points and graph anchors remain accurate while providing
     * smooth transitions between them.
     */
    fun smoothValuesPreservingGlobalExtrema(
        values: List<Float>,
        iterations: Int = 1,
    ): List<Float> {
        if (values.size < 3 || iterations <= 0) return values

        // Find global max, global min, start, and end indices before smoothing
        val globalMaxIndex = values.indices.maxByOrNull { values[it] } ?: 0
        val globalMinIndex = values.indices.minByOrNull { values[it] } ?: 0
        val preservedIndices = setOf(0, values.lastIndex, globalMaxIndex, globalMinIndex)

        return smoothValuesWithPreservedAnchors(values, iterations, preservedIndices)
    }

    /**
     * Applies smoothing with configurable extrema preservation.
     * Useful when you want to preserve some features (like peaks) but not others (like dips).
     */
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

        val preservedIndices = mutableSetOf<Int>()
        if (preserveStart) preservedIndices.add(0)
        if (preserveEnd) preservedIndices.add(values.lastIndex)
        if (preserveGlobalMax) preservedIndices.add(globalMaxIndex)
        if (preserveGlobalMin) preservedIndices.add(globalMinIndex)

        return smoothValuesWithPreservedAnchors(values, iterations, preservedIndices)
    }

    /**
     * Applies smoothing while preserving ALL local maxima and minima, as well as the
     * start and end points. This prevents "melting" local peaks/valleys into their
     * neighbors, which is critical for highly dynamic data (like cloud cover or precip)
     * where a local 80% peak shouldn't be averaged down just because it's not the
     * global daily maximum.
     */
    fun smoothValuesPreservingAllExtrema(
        values: List<Float>,
        iterations: Int = 1,
    ): List<Float> {
        if (values.size < 3 || iterations <= 0) return values

        val intValues = values.map { it.roundToInt() }
        val maxima = findLocalExtremaIndices(intValues, isMax = true)
        val minima = findLocalExtremaIndices(intValues, isMax = false)
        
        val preservedIndices = mutableSetOf(0, values.lastIndex)
        preservedIndices.addAll(maxima)
        preservedIndices.addAll(minima)

        return smoothValuesWithPreservedAnchors(values, iterations, preservedIndices)
    }

    private fun smoothValuesOnePass(values: List<Float>): List<Float> {
        val smoothed = MutableList(values.size) { 0f }
        for (i in values.indices) {
            val prev = if (i > 0) values[i - 1] else values[i]
            val curr = values[i]
            val next = if (i < values.lastIndex) values[i + 1] else values[i]

            // Weighted average: 25% prev, 50% current, 25% next
            smoothed[i] = prev * 0.25f + curr * 0.5f + next * 0.25f
        }
        return smoothed
    }

    private fun smoothValuesWithPreservedAnchors(
        values: List<Float>,
        iterations: Int,
        preservedIndices: Set<Int>,
    ): List<Float> {
        var current = values

        repeat(iterations) {
            val smoothed = smoothValuesOnePass(current).toMutableList()
            preservedIndices.forEach { smoothed[it] = values[it] }
            current = smoothed
        }

        return current
    }

    /**
     * Draws a "last fetch dot" on the graph curve, with optional staleness age label (below the
     * dot) and value label (right/left/top of the dot).
     *
     * The caller is responsible for computing [fetchX], [fetchY], [valueLabel], and [ageMinutes].
     * Pass [ageMinutes] as null when the zoomed-in label display should be suppressed (i.e. the
     * window is wider than 12 hours).
     */
    fun drawFetchDot(
        context: Context,
        canvas: Canvas,
        fetchX: Float,
        fetchY: Float,
        valueLabel: String,
        ageMinutes: Long?,
        bitmapScale: Float,
        widthPx: Int,
        heightPx: Int,
        dotRadiusDp: Float = 2.5f,
        dpToPx: (Float) -> Float,
    ) {
        val dotRadius = dpToPx(dotRadiusDp * bitmapScale.coerceIn(0.5f, 1f))
        val clampedFetchX = fetchX.coerceIn(dotRadius, widthPx.toFloat() - dotRadius)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(clampedFetchX, fetchY, dotRadius, dotPaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#AAFFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(1f)
        }
        canvas.drawCircle(clampedFetchX, fetchY, dotRadius, ringPaint)

        val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44000000")
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(0.5f)
        }
        canvas.drawCircle(clampedFetchX, fetchY, dotRadius + 0.5f, outerRingPaint)

        if (ageMinutes != null && ageMinutes >= 0) {
            val ageLabel = if (ageMinutes >= 60) {
                val h = ageMinutes / 60
                val m = ageMinutes % 60
                if (m > 0) "${h}h ${m}m" else "${h}h"
            } else "${ageMinutes}m"

            val labelScale = bitmapScale.coerceIn(0.5f, 1f)
            val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#BBFFFFFF")
                textSize = dpToPx(11f * labelScale)
                textAlign = Paint.Align.LEFT
                setShadowLayer(dpToPx(1f), 0f, dpToPx(0.5f), Color.parseColor("#88000000"))
            }
            val stalenessTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#88FFFFFF")
                textSize = dpToPx(8f * labelScale)
                textAlign = Paint.Align.CENTER
                setShadowLayer(dpToPx(1f), 0f, dpToPx(0.5f), Color.parseColor("#88000000"))
            }

            // 1. Draw Staleness (Age) - Underneath the dot
            val ageY = fetchY + dotRadius + dpToPx(4f * labelScale) - stalenessTextPaint.ascent()
            if (ageY + stalenessTextPaint.descent() <= heightPx) {
                canvas.drawText(ageLabel, clampedFetchX, ageY, stalenessTextPaint)
            }

            // 2. Draw Value - Multi-directional placement
            val valueWidth = valueTextPaint.measureText(valueLabel)
            val valueHeight = valueTextPaint.textSize
            val sideGap = dpToPx(4f * labelScale)

            // Attempt 1: Right
            var drawnValue = false
            val rightX = clampedFetchX + dotRadius + sideGap
            if (rightX + valueWidth <= widthPx) {
                valueTextPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(valueLabel, rightX, fetchY + valueHeight / 3f, valueTextPaint)
                drawnValue = true
            }

            // Attempt 2: Left
            if (!drawnValue) {
                val leftX = clampedFetchX - dotRadius - sideGap
                if (leftX - valueWidth >= 0) {
                    valueTextPaint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(valueLabel, leftX, fetchY + valueHeight / 3f, valueTextPaint)
                    drawnValue = true
                }
            }

            // Attempt 3: Top
            if (!drawnValue) {
                val topY = fetchY - dotRadius - dpToPx(2f * labelScale)
                if (topY + valueTextPaint.ascent() >= 0) {
                    valueTextPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(valueLabel, clampedFetchX, topY, valueTextPaint)
                }
            }
        }
    }

    /**
     * Draws day-of-week labels at the left and right edges of an hourly graph.
     * Placement cascades: TOP → MIDDLE → BOTTOM, with collision detection against
     * existing label bounds, icon bounds, and previously placed day labels.
     *
     * Returns the list of [RectF] bounds for all placed day labels (for callers that need it).
     */
    fun drawDayLabels(
        context: Context,
        canvas: Canvas,
        leftDate: LocalDate,
        rightDate: LocalDate,
        leftText: String,
        rightText: String,
        leftX: Float,
        rightX: Float,
        today: LocalDate,
        graphTop: Float,
        graphBottom: Float,
        heightPx: Int,
        dayLabelTextPaint: Paint,
        todayDayLabelPaint: Paint,
        drawnLabelBounds: List<RectF>,
        drawnIconBounds: List<RectF>,
        dpToPx: (Float) -> Float,
        onDayLabelPlaced: ((side: String, text: String, date: LocalDate, x: Float, y: Float, placement: String, isToday: Boolean) -> Unit)? = null,
    ) {
        val dayLabelFontMetrics = dayLabelTextPaint.fontMetrics?.let { it.ascent to it.descent }
            ?: (-dayLabelTextPaint.textSize to dayLabelTextPaint.textSize * 0.2f)
        val todayLabelFontMetrics = todayDayLabelPaint.fontMetrics?.let { it.ascent to it.descent }
            ?: (-todayDayLabelPaint.textSize to todayDayLabelPaint.textSize * 0.2f)
        val measureDayText: (String, Boolean) -> Float = { text, isToday ->
            (if (isToday) todayDayLabelPaint else dayLabelTextPaint).measureText(text)
        }
        val placements = computeDayLabelPlacements(
            leftDate = leftDate,
            rightDate = rightDate,
            leftText = leftText,
            rightText = rightText,
            leftX = leftX,
            rightX = rightX,
            today = today,
            graphTop = graphTop,
            graphBottom = graphBottom,
            heightPx = heightPx,
            dayLabelFontMetrics = dayLabelFontMetrics,
            todayLabelFontMetrics = todayLabelFontMetrics,
            measureDayText = measureDayText,
            drawnLabelBounds = drawnLabelBounds,
            drawnIconBounds = drawnIconBounds,
            dpToPx = dpToPx,
        )

        for (placement in placements) {
            val paint = if (placement.isToday) todayDayLabelPaint else dayLabelTextPaint
            canvas.drawText(placement.text, placement.x, placement.y, paint)
            Log.d("DayLabel", "${placement.side} \"${placement.text}\" at y=${placement.y} ${placement.placement} iconBounds=${drawnIconBounds.size} labelBounds=${drawnLabelBounds.size}")
            onDayLabelPlaced?.invoke(
                placement.side,
                placement.text,
                placement.date,
                placement.x,
                placement.y,
                placement.placement,
                placement.isToday,
            )
        }
    }

    /**
     * Finds local maxima and minima indices in a series, correctly handling plateaus
     * by marking the center of identical runs as the extremum.
     */
    fun findLocalExtremaIndices(
        values: List<Int>,
        isMax: Boolean,
    ): Set<Int> {
        if (values.size < 3) return emptySet()

        val extrema = mutableSetOf<Int>()
        var i = 1
        while (i < values.lastIndex) {
            val current = values[i]
            val prev = values[i - 1]

            val isPotential = if (isMax) current > prev else current < prev

            if (isPotential) {
                // We found the start of a potential peak/valley.
                // Find how long this plateau lasts.
                var j = i
                while (j < values.lastIndex && values[j + 1] == current) {
                    j++
                }

                // Now check the exit condition of the plateau
                if (j < values.lastIndex) {
                    val next = values[j + 1]
                    val isExtremum = if (isMax) next < current else next > current
                    if (isExtremum) {
                        // Mark the middle (or first) of the plateau as the extremum
                        extrema.add(i + (j - i) / 2)
                    }
                }
                i = j // Skip to end of plateau
            }
            i++
        }
        return extrema
    }

    fun drawRateLimitedWatermark(
        canvas: Canvas,
        width: Float,
        height: Float,
        density: Float,
    ) {
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#33FF5252") // semi-transparent premium rose/coral-red
            textSize = 14f * density
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                letterSpacing = 0.15f
            }
        }
        val text = "BEING RATE LIMITED"
        val x = width / 2f
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, x, y, paint)
    }
}

