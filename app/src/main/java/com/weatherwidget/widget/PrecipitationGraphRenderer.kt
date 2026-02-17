package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.Log
import android.util.TypedValue
import com.weatherwidget.R
import java.time.LocalDateTime
import kotlin.math.abs

object PrecipitationGraphRenderer {
    private const val DAY_LABEL_SIZE_MULTIPLIER = 1.4f

    data class PrecipHourData(
        val dateTime: LocalDateTime,
        val precipProbability: Int, // 0-100
        val label: String, // "12a", "1p", "2p"
        val isCurrentHour: Boolean = false,
        val showLabel: Boolean = true,
    )

    private data class LabelCandidate(
        val index: Int,
        val priority: Int,
    )

    internal data class PlacementRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun intersects(other: PlacementRect): Boolean {
            return left < other.right && other.left < right && top < other.bottom && other.top < bottom
        }
    }

    internal data class EndLabelPlacement(
        val x: Float,
        val baselineY: Float,
        val usedFallback: Boolean,
        val bounds: PlacementRect,
    )

    fun renderGraph(
        context: Context,
        hours: List<PrecipHourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        onDebugLog: ((String) -> Unit)? = null,
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
        val curvePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#5AC8FA")
                strokeWidth = dpToPx(context, curveStrokeDp)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

        val gradientPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader =
                    LinearGradient(
                        0f,
                        graphTop,
                        0f,
                        graphBottom,
                        Color.parseColor("#445AC8FA"),
                        Color.parseColor("#005AC8FA"),
                        Shader.TileMode.CLAMP,
                    )
            }

        // Current-time vertical line
        val currentTimePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF9F0A")
                strokeWidth = dpToPx(context, 0.5f)
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, 4f), dpToPx(context, 3f)), 0f)
            }

        val hourLabelTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#99FFFFFF")
                textSize = dpToPx(context, 13.0f)
                textAlign = Paint.Align.CENTER
                setShadowLayer(dpToPx(context, 1f), 0f, dpToPx(context, 0.5f), Color.parseColor("#44000000"))
            }

        val percentLabelPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFFFFF")
                textSize = dpToPx(context, 11.0f)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setShadowLayer(dpToPx(context, 2f), 0f, dpToPx(context, 0.5f), Color.parseColor("#88000000"))
            }

        val nowLabelTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#BBFF9F0A")
                textSize = dpToPx(context, 11.0f)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setShadowLayer(dpToPx(context, 1f), 0f, 0f, Color.parseColor("#44000000"))
            }

        val dayLabelTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#88FFFFFF")
                val dayLabelScale = bitmapScale.coerceIn(0.5f, 1f)
                textSize = dpToPx(context, 10.0f * dayLabelScale * DAY_LABEL_SIZE_MULTIPLIER)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }

        // --- Build smooth curve + fill ---
        val points = mutableListOf<Pair<Float, Float>>()
        val rawProbs = hours.map { it.precipProbability.coerceIn(0, 100).toFloat() }
        val smoothedProbs = GraphRenderUtils.smoothValues(rawProbs, iterations = 3)

        hours.forEachIndexed { index, _ ->
            val x = hourWidth * index + hourWidth / 2f
            val prob = smoothedProbs[index]
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
        val nowX =
            GraphRenderUtils.computeNowX(
                items = hours,
                points = points,
                currentTime = currentTime,
                hourWidth = hourWidth,
                isCurrentHour = { it.isCurrentHour },
                dateTimeOf = { it.dateTime },
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
        Log.d(
            "PrecipGraph",
            "globalMax=$globalMaxIndex(${probs.getOrNull(
                globalMaxIndex,
            )}%), globalMin=$globalMinIndex(${probs.getOrNull(
                globalMinIndex,
            )}%), firstPos=$firstPositive, firstLabeledPos=$firstLabeledPositive",
        )

        val candidateMap = mutableMapOf<Int, Int>()

        fun addCandidate(
            index: Int,
            priority: Int,
        ) {
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

        // Priority 1: Local extrema (must stand out on BOTH sides)
        localMaxima.forEach { index ->
            if (localProminence(probs, index) >= 2 && bilateralProminence(probs, index) >= 3) {
                addCandidate(index, 1)
            }
        }
        localMinima.forEach { index ->
            if (localProminence(probs, index) >= 2 && bilateralProminence(probs, index) >= 3) {
                addCandidate(index, 1)
            }
        }

        // Priority 2: Edge anchors so early/late context is preserved.
        addCandidate(0, 2)
        addCandidate(hours.lastIndex, 2)

        // Priority 3: Interval labels from the underlying timeline.
        hours.forEachIndexed { index, hour ->
            if (hour.showLabel) addCandidate(index, 3)
        }

        val sortedCandidates =
            candidateMap.entries
                .map { LabelCandidate(it.key, it.value) }
                .sortedWith(compareBy<LabelCandidate> { it.priority }.thenBy { -probs[it.index] })

        // Treat extrema as mandatory labels so important peaks/valleys survive de-cluttering.
        val mandatoryIndices = mutableSetOf<Int>()
        if (globalMaxIndex in probs.indices && probs[globalMaxIndex] > 0) mandatoryIndices.add(globalMaxIndex)
        if (globalMinIndex in probs.indices && probs[globalMinIndex] > 0) mandatoryIndices.add(globalMinIndex)

        // Peaks: must have reasonable prominence on BOTH sides to be mandatory
        localMaxima.forEach { idx ->
            if (localProminence(probs, idx) >= 8 && bilateralProminence(probs, idx) >= 5) {
                mandatoryIndices.add(idx)
            }
        }

        // Valleys: only mandatory if they represent a dip to "low" probability (< 60%)
        // AND have reasonable prominence on BOTH sides
        localMinima.forEach { idx ->
            if (probs[idx] < 60 && localProminence(probs, idx) >= 4 && bilateralProminence(probs, idx) >= 5) {
                mandatoryIndices.add(idx)
            }
        }

        // Thin out clustered mandatory labels: when multiple mandatory labels are within
        // 4 hours of each other, keep only the most important one (global extrema win).
        // This prevents noisy oscillations from flooding the graph with labels.
        val thinnedMandatory = mutableSetOf<Int>()
        for (idx in mandatoryIndices.sorted()) {
            val nearbyPlaced = thinnedMandatory.filter { abs(it - idx) <= 5 }
            if (nearbyPlaced.isEmpty()) {
                thinnedMandatory.add(idx)
            } else {
                val isGlobal = idx == globalMaxIndex || idx == globalMinIndex
                val nearbyHasGlobal = nearbyPlaced.any { it == globalMaxIndex || it == globalMinIndex }
                if (isGlobal && !nearbyHasGlobal) {
                    // This global extremum replaces nearby non-global mandatory labels
                    nearbyPlaced.forEach { thinnedMandatory.remove(it) }
                    thinnedMandatory.add(idx)
                }
                // Otherwise skip: existing nearby mandatory label already covers this area
            }
        }
        mandatoryIndices.clear()
        mandatoryIndices.addAll(thinnedMandatory)

        val orderedCandidates =
            sortedCandidates.sortedWith(
                compareBy<LabelCandidate> { if (it.index in mandatoryIndices) 0 else 1 }
                    .thenBy { it.priority }
                    .thenBy { -probs[it.index] },
            )

        val nowLabelBounds =
            if (nowX != null) {
                val lineHeight = graphHeight * 0.6f
                val lineTop = graphTop + (graphHeight - lineHeight) / 2f
                val nowText = "NOW"
                val nowTextWidth = nowLabelTextPaint.measureText(nowText)
                val nowTextHeight = nowLabelTextPaint.textSize
                RectF(
                    nowX - nowTextWidth / 2f,
                    lineTop - dpToPx(context, 2f) - nowTextHeight,
                    nowX + nowTextWidth / 2f,
                    lineTop - dpToPx(context, 2f),
                )
            } else {
                null
            }

        val drawnLabelBounds = mutableListOf<RectF>()
        val labeledIndices = mutableSetOf<Int>()
        val aboveGap = dpToPx(context, 4f)
        val belowGap = dpToPx(context, 14f)
        val maxLabels =
            when {
                widthPx >= 1100 -> 11
                widthPx >= 800 -> 9
                else -> 7
            }
        var labelsPlaced = 0

        for (candidate in orderedCandidates) {
            val isMandatory = candidate.index in mandatoryIndices
            if (!isMandatory && labelsPlaced >= maxLabels) break

            val index = candidate.index
            val prob = probs[index]
            if (prob <= 0) continue

            // Skip non-mandatory labels too close to an already-placed label (time spacing)
            if (!isMandatory && labeledIndices.any { abs(it - index) <= 2 }) continue

            // VALUE DE-DUPLICATION: Skip if we already labeled this exact % within 5 hours
            if (labeledIndices.any { probs[it] == prob && abs(it - index) <= 5 }) {
                Log.d("PrecipGraph", "SKIPPED redundant value label: $prob% at idx=$index")
                continue
            }

            // VALUE SEPARATION: skip non-mandatory labels too close in value to nearby placed labels
            if (!isMandatory && labeledIndices.any { abs(it - index) <= 6 && abs(probs[it] - prob) < 15 }) {
                Log.d("PrecipGraph", "SKIPPED low-separation label: $prob% at idx=$index")
                continue
            }

            val isPeak = index in localMaxima || index == globalMaxIndex
            val isValley = index in localMinima || index == globalMinIndex
            val isEarlyAnchor = index == firstPositive || index == firstLabeledPositive

            // Detect "dip regions": the point sits in a trough where notably higher
            // values exist within ±5 hours on BOTH sides.  Even if this exact index
            // isn't the strict local minimum, the visual curve dips here.
            val dipWindow = 5
            val dipLeft = (index - dipWindow).coerceAtLeast(0)
            val dipRight = (index + dipWindow).coerceAtMost(probs.lastIndex)
            val leftMax = (dipLeft until index).maxOfOrNull { probs[it] } ?: prob
            val rightMax = ((index + 1)..dipRight).maxOfOrNull { probs[it] } ?: prob
            val isSoftDip = !isValley && !isPeak &&
                leftMax > prob + 5 && rightMax > prob + 5

            // For peaks/valleys, use parabolic interpolation to shift the label
            // toward the visual apex of the smoothed curve.  The Bezier spline's
            // actual extremum sits between data points when slopes are asymmetric.
            // For soft dips, center on the actual minimum within the dip window.
            val softDipMinIdx = if (isSoftDip) {
                (dipLeft..dipRight).minByOrNull { probs[it] } ?: index
            } else {
                -1
            }

            // Plateau centering: if the label sits on a flat run of identical values,
            // shift to the center of that plateau so it aligns with the visual peak/trough.
            val plateauStart = (index downTo 0).takeWhile { probs[it] == prob }.last()
            val plateauEnd = (index..probs.lastIndex).takeWhile { probs[it] == prob }.last()
            val onPlateau = plateauEnd - plateauStart >= 2
            val plateauCenterIdx = if (onPlateau) (plateauStart + plateauEnd) / 2 else index

            val anchorIdx =
                when {
                    isSoftDip -> softDipMinIdx
                    onPlateau -> plateauCenterIdx
                    else -> index
                }
            val centerX =
                if ((isSoftDip || isPeak || isValley) && anchorIdx > 0 && anchorIdx < smoothedProbs.lastIndex) {
                    val fLeft = smoothedProbs[anchorIdx - 1]
                    val fCenter = smoothedProbs[anchorIdx]
                    val fRight = smoothedProbs[anchorIdx + 1]
                    val denom = fLeft - 2f * fCenter + fRight
                    if (abs(denom) > 0.01f) {
                        val shift = (fLeft - fRight) / (2f * denom) * hourWidth
                        (points[anchorIdx].first + shift).coerceIn(
                            points[anchorIdx - 1].first,
                            points[anchorIdx + 1].first,
                        )
                    } else {
                        points[anchorIdx].first
                    }
                } else if (onPlateau) {
                    points[plateauCenterIdx].first
                } else {
                    points[index].first
                }
            // Use the minimum point's y for soft dips so the label clears the curve
            val y =
                when {
                    isSoftDip -> points[softDipMinIdx].second
                    onPlateau -> points[plateauCenterIdx].second
                    else -> points[index].second
                }

            val labelText = "$prob%"
            val textWidth = percentLabelPaint.measureText(labelText)
            val textHeight = percentLabelPaint.textSize

            // Favor the center: high probability (>50%) labels go below the curve,
            // low probability (≤50%) labels go above.  Fallback to the other side.
            val preferBelow = prob > 50
            val attempts =
                listOf(
                    Pair(0f, !preferBelow),
                    Pair(0f, preferBelow),
                )

            Log.d("PrecipGraph", "Attempting label for idx=$index (${hours[index].label}) prob=$prob% isPeak=$isPeak")
            for ((dx, placeAbove) in attempts) {
                val x = (centerX + dx).coerceIn(textWidth / 2f, widthPx - textWidth / 2f)
                val baselineY = if (placeAbove) y - aboveGap else y + belowGap
                val bounds =
                    RectF(
                        x - textWidth / 2f,
                        baselineY - textHeight,
                        x + textWidth / 2f,
                        baselineY,
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

                val reason =
                    when {
                        isMandatory -> "mandatory"
                        isPeak -> "peak"
                        isValley -> "valley"
                        isEarlyAnchor -> "earlyAnchor"
                        candidate.priority == 2 -> "currentHour/edge"
                        candidate.priority == 3 -> "interval"
                        candidate.priority == 4 -> "highProb"
                        else -> "pri=${candidate.priority}"
                    }
                Log.d(
                    "PrecipGraph",
                    "PLACED label: $labelText at hour=${hours[index].label}(idx=$index) reason=$reason above=$placeAbove dx=$dx",
                )
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
            labelText = { it.label },
        )

        // Draw day of week indicators at 8am (start of the "active" day)
        val dayLabelHour = 8
        val dayY = heightPx - dpToPx(context, 14f)
        hours.forEachIndexed { index, hour ->
            if (hour.dateTime.hour == dayLabelHour) {
                val dayText = hour.dateTime.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                val centerX = points[index].first
                val textWidth = dayLabelTextPaint.measureText(dayText)
                val clampedX = centerX.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)
                canvas.drawText(dayText, clampedX, dayY, dayLabelTextPaint)
            }
        }

        // Draw leading day label only if 8am for the same day isn't already in the data
        if (hours.isNotEmpty() && hours.first().dateTime.hour != dayLabelHour) {
            val firstDate = hours.first().dateTime.toLocalDate()
            val sameDayAnchorExists = hours.any {
                it.dateTime.hour == dayLabelHour && it.dateTime.toLocalDate() == firstDate
            }
            if (!sameDayAnchorExists) {
                val dayText = hours.first().dateTime.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                val textWidth = dayLabelTextPaint.measureText(dayText)
                val x = textWidth / 2f
                canvas.drawText(dayText, x, dayY, dayLabelTextPaint)
            }
        }

        GraphRenderUtils.drawNowIndicator(
            canvas = canvas,
            nowX = nowX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            currentTimePaint = currentTimePaint,
            nowLabelTextPaint = nowLabelTextPaint,
            dpToPx = { dpToPx(context, it) },
        )

        // Draw end-of-graph label (right edge) for the final precipitation probability.
        // Keep it inside bounds and avoid overlaps with existing labels/NOW marker.
        if (hours.isNotEmpty()) {
            val lastIndex = hours.lastIndex
            val endProb = probs[lastIndex]

            // Proximity check: skip if any label already placed within 3 hours of the end
            if (labeledIndices.any { abs(it - lastIndex) <= 3 }) {
                Log.d("PrecipGraph", "SKIPPED end label: $endProb% (existing label within 3 hours)")
            // Value De-duplication: Skip if we already labeled this exact % within 5 hours
            } else if (labeledIndices.any { probs[it] == endProb && abs(it - lastIndex) <= 5 }) {
                Log.d("PrecipGraph", "SKIPPED redundant end label: $endProb% (already labeled nearby)")
            } else {
                val endLabelText = "$endProb%"
                val textWidth = percentLabelPaint.measureText(endLabelText)
                val textHeight = percentLabelPaint.textSize
                val placement =
                    computeEndLabelPlacement(
                        textWidth = textWidth,
                        textHeight = textHeight,
                        widthPx = widthPx,
                        graphBottom = graphBottom,
                        pointY = points[lastIndex].second,
                        aboveGap = aboveGap,
                        belowGap = belowGap,
                        rightPadding = dpToPx(context, 8f),
                        verticalInset = dpToPx(context, 2f),
                        existingBounds = drawnLabelBounds.map { it.toPlacementRect() },
                        nowBounds = nowLabelBounds?.toPlacementRect(),
                    )

                if (placement != null) {
                    canvas.drawText(endLabelText, placement.x, placement.baselineY, percentLabelPaint)
                    drawnLabelBounds.add(placement.bounds.toRectF())
                    val mode = if (placement.usedFallback) "fallback" else "preferred"
                    // Below used in test.  Do not delete!
                    val logMsg = "PLACED end label: $endLabelText at right edge ($mode)"
                    Log.d("PrecipGraph", logMsg)
                    onDebugLog?.invoke(logMsg)
                } else {
                    Log.d("PrecipGraph", "SKIPPED end label: $endLabelText no available non-overlapping slot")
                }
            }
        }

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
            val aboveBounds =
                RectF(
                    lowX - iconSizePx / 2f,
                    aboveCenterY - iconSizePx / 2f,
                    lowX + iconSizePx / 2f,
                    aboveCenterY + iconSizePx / 2f,
                )
            if (aboveBounds.top >= 0f &&
                aboveBounds.bottom < lowCurveY - iconGap &&
                !drawnLabelBounds.any { RectF.intersects(it, aboveBounds) }
            ) {
                rainDrawable.alpha = 100
                rainDrawable.setBounds(
                    aboveBounds.left.toInt(),
                    aboveBounds.top.toInt(),
                    aboveBounds.right.toInt(),
                    aboveBounds.bottom.toInt(),
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
                val belowBounds =
                    RectF(
                        highX - iconSizePx / 2f,
                        belowCenterY - iconSizePx / 2f,
                        highX + iconSizePx / 2f,
                        belowCenterY + iconSizePx / 2f,
                    )
                if (belowBounds.top > highCurveY + iconGap &&
                    belowBounds.bottom <= graphBottom &&
                    !drawnLabelBounds.any { RectF.intersects(it, belowBounds) }
                ) {
                    rainDrawable.alpha = 100
                    rainDrawable.setBounds(
                        belowBounds.left.toInt(),
                        belowBounds.top.toInt(),
                        belowBounds.right.toInt(),
                        belowBounds.bottom.toInt(),
                    )
                    rainDrawable.draw(canvas)
                }
            }
        }

        return bitmap
    }

    private fun findLocalExtremaIndices(
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

    private fun localProminence(
        values: List<Int>,
        index: Int,
    ): Int {
        if (index <= 0 || index >= values.lastIndex) return 0
        val current = values[index]
        val prev = values[index - 1]
        val next = values[index + 1]
        return maxOf(abs(current - prev), abs(current - next))
    }

    /**
     * Returns the SMALLER of the two side-differences: minOf(|current - prev|, |current - next|).
     * A true peak/valley must stand out on BOTH sides; one-sided prominences on monotonic
     * stretches will return a low value.
     */
    internal fun bilateralProminence(
        values: List<Int>,
        index: Int,
    ): Int {
        if (index <= 0 || index >= values.lastIndex) return 0
        val current = values[index]
        val prev = values[index - 1]
        val next = values[index + 1]
        return minOf(abs(current - prev), abs(current - next))
    }

    internal fun computeEndLabelPlacement(
        textWidth: Float,
        textHeight: Float,
        widthPx: Int,
        graphBottom: Float,
        pointY: Float,
        aboveGap: Float,
        belowGap: Float,
        rightPadding: Float,
        verticalInset: Float,
        existingBounds: List<PlacementRect>,
        nowBounds: PlacementRect?,
    ): EndLabelPlacement? {
        val minX = textWidth / 2f
        val maxX = widthPx - rightPadding - textWidth / 2f
        val labelX = maxX.coerceAtLeast(minX)

        val safeTop = textHeight + verticalInset
        val safeBottom = graphBottom - verticalInset
        val preferredBaselineY = (pointY - aboveGap).coerceAtMost(safeBottom).coerceAtLeast(safeTop)
        val fallbackBaselineY = (pointY + belowGap).coerceAtMost(safeBottom).coerceAtLeast(safeTop)

        fun makeBounds(baselineY: Float): PlacementRect {
            return PlacementRect(
                left = labelX - textWidth / 2f,
                top = baselineY - textHeight,
                right = labelX + textWidth / 2f,
                bottom = baselineY,
            )
        }

        fun isClear(bounds: PlacementRect): Boolean {
            val overlapsExisting = existingBounds.any { it.intersects(bounds) }
            val overlapsNow = nowBounds?.intersects(bounds) == true
            return !overlapsExisting && !overlapsNow
        }

        val preferredBounds = makeBounds(preferredBaselineY)
        if (isClear(preferredBounds)) {
            return EndLabelPlacement(labelX, preferredBaselineY, usedFallback = false, bounds = preferredBounds)
        }

        val fallbackBounds = makeBounds(fallbackBaselineY)
        if (isClear(fallbackBounds)) {
            return EndLabelPlacement(labelX, fallbackBaselineY, usedFallback = true, bounds = fallbackBounds)
        }

        return null
    }

    private fun RectF.toPlacementRect(): PlacementRect {
        return PlacementRect(left, top, right, bottom)
    }

    private fun PlacementRect.toRectF(): RectF {
        return RectF(left, top, right, bottom)
    }

    private fun dpToPx(
        context: Context,
        dp: Float,
    ): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        )
    }
}
