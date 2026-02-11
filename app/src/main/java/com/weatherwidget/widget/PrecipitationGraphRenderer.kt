package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.Log
import android.util.TypedValue
import com.weatherwidget.R
import java.time.LocalDateTime
import kotlin.math.abs

object PrecipitationGraphRenderer {

    data class PrecipHourData(
        val dateTime: LocalDateTime,
        val precipProbability: Int,   // 0-100
        val label: String,            // "12a", "1p", "2p"
        val isCurrentHour: Boolean = false,
        val showLabel: Boolean = true
    )

    private data class LabelCandidate(
        val index: Int,
        val priority: Int
    )

    fun renderGraph(
        context: Context,
        hours: List<PrecipHourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (hours.isEmpty()) return bitmap

        val density = context.resources.displayMetrics.density
        val heightDp = heightPx / density

        // Layout zones (mirrors HourlyTemperatureGraphRenderer style)
        val topPadding = dpToPx(context, 12f)
        val labelHeight = dpToPx(context, 10f)
        val bottomPadding = dpToPx(context, 3f)

        val graphTop = topPadding
        val graphBottom = heightPx - labelHeight - bottomPadding
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        val hourWidth = widthPx.toFloat() / hours.size

        // --- Paints ---

        val curveStrokeDp = if (heightDp >= 160) 1.5f else 2f
        val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5AC8FA")
            strokeWidth = dpToPx(context, curveStrokeDp)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, graphTop, 0f, graphBottom,
                Color.parseColor("#445AC8FA"),
                Color.parseColor("#005AC8FA"),
                Shader.TileMode.CLAMP
            )
        }

        // Current-time vertical line
        val currentTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9F0A")
            strokeWidth = dpToPx(context, 0.5f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, 4f), dpToPx(context, 3f)), 0f)
        }

        val hourLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#99FFFFFF")
            textSize = dpToPx(context, 13.0f)
            textAlign = Paint.Align.CENTER
            setShadowLayer(dpToPx(context, 1f), 0f, dpToPx(context, 0.5f), Color.parseColor("#44000000"))
        }

        val percentLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = dpToPx(context, 11.0f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 2f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
        }

        val nowLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BBFF9F0A")
            textSize = dpToPx(context, 11.0f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 1f), 0f, 0f, Color.parseColor("#44000000"))
        }

        // --- Build smooth curve + fill ---
        val points = mutableListOf<Pair<Float, Float>>()
        hours.forEachIndexed { index, hour ->
            val x = hourWidth * index + hourWidth / 2f
            val prob = hour.precipProbability.coerceIn(0, 100)
            val y = graphBottom - graphHeight * (prob / 100f)
            points.add(x to y)
        }

        val (curvePath, fillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(points, graphBottom)

        canvas.drawPath(fillPath, gradientPaint)
        canvas.drawPath(curvePath, curvePaint)

        // --- Draw labels and current-time indicator ---
        val minHourLabelSpacing = dpToPx(context, 28f)

        // Track NOW x-position
        val currentHourIndex = hours.indexOfFirst { it.isCurrentHour }
        val nowX = GraphRenderUtils.computeNowX(
            items = hours,
            points = points,
            currentTime = currentTime,
            hourWidth = hourWidth,
            isCurrentHour = { it.isCurrentHour },
            dateTimeOf = { it.dateTime }
        )

        val probs = hours.map { it.precipProbability.coerceIn(0, 100) }
        Log.d("PrecipGraph", "probs=${probs.mapIndexed { i, p -> "${hours[i].label}=$p" }}")
        val localMaxima = findLocalExtremaIndices(probs, isMax = true)
        val localMinima = findLocalExtremaIndices(probs, isMax = false)
        Log.d("PrecipGraph", "localMaxima=$localMaxima, localMinima=$localMinima")
        val globalMaxIndex = probs.indices.maxByOrNull { probs[it] } ?: -1
        val globalMinIndex = probs.indices.minByOrNull { probs[it] } ?: -1
        val firstPositive = probs.indexOfFirst { it > 0 }
        val firstLabeledPositive = hours.indexOfFirst { it.showLabel && it.precipProbability > 0 }
        Log.d("PrecipGraph", "globalMax=$globalMaxIndex(${probs.getOrNull(globalMaxIndex)}%), globalMin=$globalMinIndex(${probs.getOrNull(globalMinIndex)}%), firstPos=$firstPositive, firstLabeledPos=$firstLabeledPositive")

        val candidateMap = mutableMapOf<Int, Int>()

        fun addCandidate(index: Int, priority: Int) {
            if (index !in probs.indices || probs[index] <= 0) return
            val existing = candidateMap[index]
            if (existing == null || priority < existing) {
                candidateMap[index] = priority
            }
        }

        // Priority -1: Early anchors (must strongly favor first visible rain labels).
        addCandidate(firstPositive, -1)
        addCandidate(firstLabeledPositive, -1)

        // Priority 0: Global extrema
        addCandidate(globalMaxIndex, 0)
        addCandidate(globalMinIndex, 0)

        // Priority 1: Local extrema
        localMaxima.forEach { index ->
            val prominence = localProminence(probs, index)
            if (prominence >= 2) addCandidate(index, 1)
        }
        localMinima.forEach { index ->
            val prominence = localProminence(probs, index)
            if (prominence >= 2) addCandidate(index, 1)
        }

        // Priority 2: Current hour and edge anchors so early/late context is preserved.
        addCandidate(currentHourIndex, 2)
        addCandidate(0, 2)
        addCandidate(hours.lastIndex, 2)

        // Priority 3: Interval labels from the underlying timeline.
        hours.forEachIndexed { index, hour ->
            if (hour.showLabel) addCandidate(index, 3)
        }

        // Priority 4: Any high rain-chance point.
        probs.forEachIndexed { index, prob ->
            if (prob >= 50) addCandidate(index, 4)
        }

        val sortedCandidates = candidateMap.entries
            .map { LabelCandidate(it.key, it.value) }
            .sortedWith(compareBy<LabelCandidate> { it.priority }.thenBy { -probs[it.index] })

        // Treat extrema as mandatory labels so important peaks/valleys survive de-cluttering.
        val mandatoryIndices = mutableSetOf<Int>()
        if (globalMaxIndex in probs.indices && probs[globalMaxIndex] > 0) mandatoryIndices.add(globalMaxIndex)
        if (globalMinIndex in probs.indices && probs[globalMinIndex] > 0) mandatoryIndices.add(globalMinIndex)
        
        // Peaks: must have reasonable prominence (e.g. >= 8%) to be mandatory
        localMaxima.forEach { idx -> 
            if (localProminence(probs, idx) >= 8) mandatoryIndices.add(idx) 
        }
        
        // Valleys: only mandatory if they represent a dip to "low" probability (< 60%) 
        // AND have reasonable prominence (>= 4%)
        localMinima.forEach { idx -> 
            if (probs[idx] < 60 && localProminence(probs, idx) >= 4) mandatoryIndices.add(idx) 
        }

        // Explicitly request the end of graph label
        mandatoryIndices.add(hours.lastIndex)

        val orderedCandidates = sortedCandidates.sortedWith(
            compareBy<LabelCandidate> { if (it.index in mandatoryIndices) 0 else 1 }
                .thenBy { it.priority }
                .thenBy { -probs[it.index] }
        )

        val nowLabelBounds = if (nowX != null) {
            val lineHeight = graphHeight * 0.6f
            val lineTop = graphTop + (graphHeight - lineHeight) / 2f
            val nowText = "NOW"
            val nowTextWidth = nowLabelTextPaint.measureText(nowText)
            val nowTextHeight = nowLabelTextPaint.textSize
            RectF(
                nowX - nowTextWidth / 2f,
                lineTop - dpToPx(context, 2f) - nowTextHeight,
                nowX + nowTextWidth / 2f,
                lineTop - dpToPx(context, 2f)
            )
        } else {
            null
        }

        val drawnLabelBounds = mutableListOf<RectF>()
        val labeledIndices = mutableSetOf<Int>()
        val aboveGap = dpToPx(context, 4f)
        val belowGap = dpToPx(context, 14f)
        val maxLabels = when {
            widthPx >= 1100 -> 11
            widthPx >= 800 -> 9
            else -> 7
        }
        var labelsPlaced = 0

        for (candidate in orderedCandidates) {
            val isMandatory = candidate.index in mandatoryIndices
            if (!isMandatory && labelsPlaced >= maxLabels) break

            // Skip interval labels unless they fill a very large gap (7+ hours) between placed labels
            if (candidate.priority == 3) {
                val nearestLeft = labeledIndices.filter { it < candidate.index }.maxOrNull() ?: -1
                val nearestRight = labeledIndices.filter { it > candidate.index }.minOrNull() ?: (hours.size + 1)
                if (nearestRight - nearestLeft < 7) {
                    Log.d("PrecipGraph", "SKIPPED interval label at idx=${candidate.index} (${hours[candidate.index].label}): gap=${nearestRight - nearestLeft} too small")
                    continue
                }
            }

            val index = candidate.index
            val centerX = points[index].first
            val y = points[index].second
            val prob = probs[index]
            if (prob <= 0) continue

            val labelText = "${prob}%"
            val textWidth = percentLabelPaint.measureText(labelText)
            val textHeight = percentLabelPaint.textSize

            val isPeak = index in localMaxima || index == globalMaxIndex
            val isValley = index in localMinima || index == globalMinIndex
            val isEarlyAnchor = index == firstPositive || index == firstLabeledPositive

            val attempts = when {
                isEarlyAnchor -> listOf(
                    Pair(0f, true),
                    Pair(0f, false)
                )
                isPeak -> listOf(
                    Pair(0f, true)
                )
                isValley -> listOf(
                    Pair(0f, false)
                )
                else -> listOf(
                    Pair(0f, true),
                    Pair(0f, false)
                )
            }

            Log.d("PrecipGraph", "Attempting label for idx=$index (${hours[index].label}) prob=$prob% isPeak=$isPeak")
            for ((dx, placeAbove) in attempts) {
                val x = (centerX + dx).coerceIn(textWidth / 2f, widthPx - textWidth / 2f)
                val baselineY = if (placeAbove) y - aboveGap else y + belowGap
                val bounds = RectF(
                    x - textWidth / 2f,
                    baselineY - textHeight,
                    x + textWidth / 2f,
                    baselineY
                )

                // Relaxed vertical bounds: allow drawing in the top padding area (above graphTop), just stay on screen.
                val inVerticalBounds = bounds.top >= 0f && bounds.bottom <= (graphBottom - dpToPx(context, 2f))
                if (!inVerticalBounds) {
                    Log.d("PrecipGraph", "  REJECTED ($dx, $placeAbove): Out of vertical bounds $bounds")
                    continue
                }

                val overlapsExisting = drawnLabelBounds.any { RectF.intersects(it, bounds) }
                val overlapsNow = nowLabelBounds != null && RectF.intersects(nowLabelBounds, bounds)
                if (overlapsExisting || overlapsNow) {
                    Log.d("PrecipGraph", "  REJECTED ($dx, $placeAbove): Overlap existing=$overlapsExisting now=$overlapsNow")
                    continue
                }

                val reason = when {
                    isMandatory -> "mandatory"
                    isPeak -> "peak"
                    isValley -> "valley"
                    isEarlyAnchor -> "earlyAnchor"
                    candidate.priority == 2 -> "currentHour/edge"
                    candidate.priority == 3 -> "interval"
                    candidate.priority == 4 -> "highProb"
                    else -> "pri=${candidate.priority}"
                }
                Log.d("PrecipGraph", "PLACED label: ${labelText} at hour=${hours[index].label}(idx=$index) reason=$reason above=$placeAbove dx=$dx")
                canvas.drawText(labelText, x, baselineY, percentLabelPaint)
                drawnLabelBounds.add(bounds)
                labeledIndices.add(index)
                labelsPlaced++
                break
            }
        }

        GraphRenderUtils.drawHourLabels(
            canvas = canvas,
            items = hours,
            points = points,
            widthPx = widthPx,
            heightPx = heightPx,
            minHourLabelSpacing = minHourLabelSpacing,
            hourLabelTextPaint = hourLabelTextPaint,
            dpToPx = { dpToPx(context, it) },
            showLabel = { it.showLabel },
            labelText = { it.label }
        )

        GraphRenderUtils.drawNowIndicator(
            canvas = canvas,
            nowX = nowX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            currentTimePaint = currentTimePaint,
            nowLabelTextPaint = nowLabelTextPaint,
            dpToPx = { dpToPx(context, it) }
        )

        // Raindrop icon placed in the emptiest region of the graph
        val rainDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_weather_rain)
        if (rainDrawable != null && points.size >= 3) {
            val iconSizePx = dpToPx(context, 20f).toInt()
            val windowSize = (points.size / 5).coerceIn(3, 6)
            val iconGap = dpToPx(context, 2f)
            var iconPlaced = false

            // Strategy 1: Find lowest-precipitation window → place icon ABOVE curve
            var lowStart = 0
            var lowAvg = Float.MAX_VALUE
            for (start in 0..points.size - windowSize) {
                val avg = (start until start + windowSize).map { probs[it].toFloat() }.average().toFloat()
                if (avg < lowAvg) {
                    lowAvg = avg
                    lowStart = start
                }
            }

            val lowCenter = lowStart + windowSize / 2
            val lowX = points[lowCenter].first
            val lowCurveY = points[lowCenter].second
            val aboveCenterY = graphTop + (lowCurveY - graphTop) / 2f
            val aboveBounds = RectF(
                lowX - iconSizePx / 2f, aboveCenterY - iconSizePx / 2f,
                lowX + iconSizePx / 2f, aboveCenterY + iconSizePx / 2f
            )
            if (aboveBounds.top >= 0f &&
                aboveBounds.bottom < lowCurveY - iconGap &&
                !drawnLabelBounds.any { RectF.intersects(it, aboveBounds) }) {
                rainDrawable.alpha = 100
                rainDrawable.setBounds(
                    aboveBounds.left.toInt(), aboveBounds.top.toInt(),
                    aboveBounds.right.toInt(), aboveBounds.bottom.toInt()
                )
                rainDrawable.draw(canvas)
                iconPlaced = true
            }

            // Strategy 2: Find highest-precipitation window → place icon BELOW curve
            if (!iconPlaced) {
                var highStart = 0
                var highAvg = -1f
                for (start in 0..points.size - windowSize) {
                    val avg = (start until start + windowSize).map { probs[it].toFloat() }.average().toFloat()
                    if (avg > highAvg) {
                        highAvg = avg
                        highStart = start
                    }
                }

                val highCenter = highStart + windowSize / 2
                val highX = points[highCenter].first
                val highCurveY = points[highCenter].second
                val belowCenterY = highCurveY + (graphBottom - highCurveY) / 2f
                val belowBounds = RectF(
                    highX - iconSizePx / 2f, belowCenterY - iconSizePx / 2f,
                    highX + iconSizePx / 2f, belowCenterY + iconSizePx / 2f
                )
                if (belowBounds.top > highCurveY + iconGap &&
                    belowBounds.bottom <= graphBottom &&
                    !drawnLabelBounds.any { RectF.intersects(it, belowBounds) }) {
                    rainDrawable.alpha = 100
                    rainDrawable.setBounds(
                        belowBounds.left.toInt(), belowBounds.top.toInt(),
                        belowBounds.right.toInt(), belowBounds.bottom.toInt()
                    )
                    rainDrawable.draw(canvas)
                }
            }
        }

        return bitmap
    }

    private fun findLocalExtremaIndices(values: List<Int>, isMax: Boolean): Set<Int> {
        if (values.size < 3) return emptySet()

        return (1 until values.lastIndex).filter { i ->
            val current = values[i]
            val prev = values[i - 1]
            val next = values[i + 1]
            if (isMax) {
                (current > prev && current >= next) || (current >= prev && current > next)
            } else {
                (current < prev && current <= next) || (current <= prev && current < next)
            }
        }.toSet()
    }

    private fun localProminence(values: List<Int>, index: Int): Int {
        if (index <= 0 || index >= values.lastIndex) return 0
        val current = values[index]
        val prev = values[index - 1]
        val next = values[index + 1]
        return maxOf(abs(current - prev), abs(current - next))
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }
}
