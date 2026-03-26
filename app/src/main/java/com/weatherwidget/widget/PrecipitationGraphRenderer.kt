package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.Log
import android.util.TypedValue
import com.weatherwidget.R
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object PrecipitationGraphRenderer {

    private const val MIN_ICON_GRAPH_WIDTH_PX = 420

    data class PrecipHourData(
        val dateTime: LocalDateTime,
        val precipProbability: Int, // 0-100
        val label: String, // "12a", "1p", "2p"
        val iconRes: Int? = null,
        val isNight: Boolean = false,
        val isSunny: Boolean = false,
        val isRainy: Boolean = false,
        val isMixed: Boolean = false,
        val isCurrentHour: Boolean = false,
        val showLabel: Boolean = true,
    )

    private data class LabelCandidate(
        val index: Int,
        val priority: Int,
    )

    data class LabelPlacementDebug(
        val index: Int,
        val hourLabel: String,
        val probability: Int,
        val placedAbove: Boolean,
        val reason: String,
        val isPeak: Boolean,
        val isValley: Boolean,
        val isSoftDip: Boolean,
        val elevatedPeakRuleApplied: Boolean,
        val dipBelowRuleApplied: Boolean,
        val firstLabelBelowRuleApplied: Boolean,
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

    data class DayLabelPlacementDebug(
        val side: String,       // "LEFT" or "RIGHT"
        val dayText: String,
        val date: LocalDate,
        val x: Float,
        val y: Float,
        val placement: String,  // "TOP", "MIDDLE", "BOTTOM"
        val isToday: Boolean,
    )

    data class FetchDotDebug(
        val observedAt: Long,
        val fetchDotX: Float?,
        val fetchY: Float? = null,
        val withinWindow: Boolean,
        val ageText: String? = null,
    )

    fun renderGraph(
        context: Context,
        hours: List<PrecipHourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        smoothIterations: Int = 2,
        hourLabelSpacingDp: Float = 28f,
        observedAt: Long? = null,
        onDebugLog: ((String) -> Unit)? = null,
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
        onHourIconDrawn: ((index: Int) -> Unit)? = null,
        onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)? = null,
        onFetchDotResolved: ((FetchDotDebug) -> Unit)? = null,
    ): Bitmap {
        val startNano = System.nanoTime()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (hours.isEmpty()) return bitmap

        val density = context.resources.displayMetrics.density
        val heightDp = heightPx / density

        // Layout zones (mirrors TemperatureGraphRenderer style)
        val topPadding = dpToPx(context, 12f)
        val hasHourlyIcons = hours.any { it.iconRes != null }
        val showHourlyIcons = hasHourlyIcons && shouldShowHourlyIcons(widthPx)
        val iconSize = dpToPx(context, 16f).toInt()
        val iconTopPad = dpToPx(context, 2f)
        val iconBottomPad = dpToPx(context, 1f)
        val labelHeight = dpToPx(context, 10f)
        val bottomPadding = dpToPx(context, 3f)

        val graphTop = topPadding
        val graphBottom =
            if (showHourlyIcons) {
                heightPx - labelHeight - bottomPadding - iconBottomPad - iconSize - iconTopPad
            } else {
                heightPx - labelHeight - bottomPadding
            }
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        val hourWidth = widthPx.toFloat() / (hours.size - 1).coerceAtLeast(1)

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
                textSize = dpToPx(context, 8.5f)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setShadowLayer(dpToPx(context, 1f), 0f, 0f, Color.parseColor("#44000000"))
            }

        val dayLabelTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#88FFFFFF")
                textSize = dpToPx(context, 13.0f)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }

        val todayDayLabelPaint =
            Paint(dayLabelTextPaint).apply {
                color = Color.parseColor("#BBFF9F0A")
            }

        val setupNano = System.nanoTime()

        // --- Build smooth curve + fill ---
        val points = mutableListOf<Pair<Float, Float>>()
        val rawProbs = hours.map { it.precipProbability.coerceIn(0, 100).toFloat() }
        val probs = GraphRenderUtils.smoothValuesPreservingGlobalExtrema(rawProbs, iterations = smoothIterations)

        val rawMax = probs.maxOrNull() ?: 0f
        val yScaleMax = (rawMax * 1.15f).coerceAtLeast(10f).coerceAtMost(100f)

        hours.forEachIndexed { index, _ ->
            val x = hourWidth * index
            val prob = probs[index]
            val y = graphBottom - graphHeight * (prob / yScaleMax)
            points.add(x to y)
        }

        val (curvePath, fillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(points, graphBottom)

        canvas.drawPath(fillPath, gradientPaint)
        canvas.drawPath(curvePath, curvePaint)

        val pathNano = System.nanoTime()

        // --- Draw labels and current-time indicator ---
        val minHourLabelSpacing = dpToPx(context, hourLabelSpacingDp)
        val drawnIconBounds = mutableListOf<RectF>()

        val nowX =
            GraphRenderUtils.computeNowX(
                items = hours,
                points = points,
                currentTime = currentTime,
                hourWidth = hourWidth,
                isCurrentHour = { it.isCurrentHour },
                dateTimeOf = { it.dateTime },
            )

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
        ) { index, clampedX ->
            if (!showHourlyIcons) return@drawHourLabels
            val hour = hours[index]
            val iconRes = hour.iconRes ?: return@drawHourLabels
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, iconRes) ?: return@drawHourLabels

            val iconY = graphBottom + iconTopPad
            val iconX = clampedX - iconSize / 2f
            val iconRect = RectF(iconX, iconY, iconX + iconSize, iconY + iconSize)
            drawnIconBounds.add(iconRect)

            drawable.setBounds(
                iconRect.left.toInt(),
                iconRect.top.toInt(),
                iconRect.right.toInt(),
                iconRect.bottom.toInt(),
            )

            if (!hour.isRainy && !hour.isMixed) {
                val iconTint =
                    when {
                        hour.isNight -> Color.parseColor("#BBBBBB")
                        hour.isSunny -> Color.parseColor("#FFD60A")
                        else -> Color.parseColor("#BBBBBB")
                    }
                drawable.setTint(iconTint)
            }

            drawable.draw(canvas)
            onHourIconDrawn?.invoke(index)
        }

        val drawNano = System.nanoTime()

        val labelSignal = probs.map { it.roundToInt().coerceIn(0, 100) }
        Log.d("PrecipGraph", "signal=${labelSignal.mapIndexed { i, p -> "${hours[i].label}=$p" }}")
        val localMaxima = findLocalExtremaIndices(labelSignal, isMax = true)
        val localMinima = findLocalExtremaIndices(labelSignal, isMax = false)
        Log.d("PrecipGraph", "localMaxima=$localMaxima, localMinima=$localMinima")
        val globalMaxIndex = labelSignal.indices.maxByOrNull { labelSignal[it] } ?: -1
        val globalMinIndex = labelSignal.indices.minByOrNull { labelSignal[it] } ?: -1
        val firstPositive = labelSignal.indexOfFirst { it > 0 }
        val firstLabeledPositive = hours.indices.firstOrNull { hours[it].showLabel && labelSignal[it] > 0 } ?: -1

        // Soft dip candidates catch broad troughs/plateaus that don't always register as
        // strict local minima but are still visually important (for example a midday dip).
        val softDipCandidates =
            labelSignal.indices.filter { idx ->
                val prob = labelSignal[idx]
                if (prob <= 0 || prob > 65) return@filter false
                val left = (idx - 5).coerceAtLeast(0)
                val right = (idx + 5).coerceAtMost(labelSignal.lastIndex)
                if (left == idx || right == idx) return@filter false
                val leftMax = (left until idx).maxOfOrNull { labelSignal[it] } ?: prob
                val rightMax = ((idx + 1)..right).maxOfOrNull { labelSignal[it] } ?: prob
                leftMax >= prob + 8 && rightMax >= prob + 8
            }
        Log.d(
            "PrecipGraph",
            "globalMax=$globalMaxIndex(${labelSignal.getOrNull(
                globalMaxIndex,
            )}%), globalMin=$globalMinIndex(${labelSignal.getOrNull(
                globalMinIndex,
            )}%), firstPos=$firstPositive, firstLabeledPos=$firstLabeledPositive, softDips=$softDipCandidates",
        )

                val candidateMap = mutableMapOf<Int, Int>()
        
                fun addCandidate(
        
            index: Int,
            priority: Int,
        ) {
            if (index !in labelSignal.indices || labelSignal[index] <= 0) return
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

        // Priority 1: Local extrema.
        // Mirror hourly temperature behavior: every local peak/valley is considered
        // a candidate, then overlap and de-clutter logic decides final visibility.
        localMaxima.forEach { index ->
            addCandidate(index, 1)
        }
        localMinima.forEach { index ->
            addCandidate(index, 1)
        }
        softDipCandidates.forEach { index ->
            addCandidate(index, 1)
        }

        // Priority 2: Edge anchors so early/late context is preserved.
        addCandidate(0, 2)
        addCandidate(hours.lastIndex, 2)

        // Priority 0: "Dry window" labels — 0% runs flanked by rain on both sides.
        // These are arguably the most important feature ("there's a break in the rain!")
        // but addCandidate() filters <= 0, so we insert them directly.
        run {
            var i = 0
            while (i < labelSignal.size) {
                if (labelSignal[i] == 0) {
                    val runStart = i
                    while (i < labelSignal.size && labelSignal[i] == 0) i++
                    val runEnd = i - 1 // inclusive
                    // Only label interior zero runs (rain→0→rain), not leading/trailing
                    val hasRainBefore = runStart > 0 && labelSignal[runStart - 1] > 0
                    val hasRainAfter = runEnd < labelSignal.lastIndex && labelSignal[runEnd + 1] > 0
                    if (hasRainBefore && hasRainAfter) {
                        val midIdx = (runStart + runEnd) / 2
                        candidateMap[midIdx] = 0 // same priority as global extrema
                        Log.d("PrecipGraph", "Dry-window 0% label at idx=$midIdx (run $runStart..$runEnd)")
                    }
                } else {
                    i++
                }
            }
        }

        // Priority 5: Interval labels from the underlying timeline (lowest priority).
        hours.forEachIndexed { index, hour ->
            if (hour.showLabel) addCandidate(index, 5)
        }

        val sortedCandidates =
            candidateMap.entries
                .map { LabelCandidate(it.key, it.value) }
                .sortedWith(compareBy<LabelCandidate> { it.priority }.thenBy { -labelSignal[it.index] })

        val candidateNano = System.nanoTime()

        // Treat extrema as mandatory labels so important peaks/valleys survive de-cluttering.
        val mandatoryIndices = mutableSetOf<Int>()
        if (firstLabeledPositive in labelSignal.indices && labelSignal[firstLabeledPositive] > 0) {
            mandatoryIndices.add(firstLabeledPositive)
        }
        if (globalMaxIndex in labelSignal.indices && labelSignal[globalMaxIndex] > 0) mandatoryIndices.add(globalMaxIndex)
        if (globalMinIndex in labelSignal.indices && labelSignal[globalMinIndex] > 0) mandatoryIndices.add(globalMinIndex)

        // Dry-window 0% labels are mandatory (rain→0→rain is a key feature)
        val dryWindowIndices = mutableSetOf<Int>()
        candidateMap.forEach { (idx, _) ->
            if (idx in labelSignal.indices && labelSignal[idx] == 0) {
                mandatoryIndices.add(idx)
                dryWindowIndices.add(idx)
            }
        }

        // Peaks: must have reasonable prominence on BOTH sides to be mandatory
        localMaxima.forEach { idx ->
            val broadPeakProminence = peakBilateralProminence(labelSignal, idx, radius = 4)
            if (broadPeakProminence >= 10) {
                mandatoryIndices.add(idx)
            }
        }

        // Valleys: only mandatory if they represent a dip to "low" probability (< 60%)
        // AND have reasonable prominence on BOTH sides
        localMinima.forEach { idx ->
            val broadValleyProminence = valleyBilateralProminence(labelSignal, idx, radius = 4)
            if (labelSignal[idx] < 60 && broadValleyProminence >= 8) {
                mandatoryIndices.add(idx)
            }
        }
        softDipCandidates.forEach { idx ->
            val prob = labelSignal[idx]
            val left = (idx - 5).coerceAtLeast(0)
            val right = (idx + 5).coerceAtMost(labelSignal.lastIndex)
            val leftMax = (left until idx).maxOfOrNull { labelSignal[it] } ?: prob
            val rightMax = ((idx + 1)..right).maxOfOrNull { labelSignal[it] } ?: prob
            val hasNearbyLowDipMandatory =
                mandatoryIndices.any { existingIdx ->
                    abs(existingIdx - idx) <= 3 && abs(labelSignal[existingIdx] - prob) <= 8
                }
            if (prob < 60 && leftMax >= prob + 10 && rightMax >= prob + 10 && !hasNearbyLowDipMandatory) {
                mandatoryIndices.add(idx)
            }
        }

        val isPeakMandatory: (Int) -> Boolean = { idx ->
            idx == globalMaxIndex || idx in localMaxima
        }
        val isValleyMandatory: (Int) -> Boolean = { idx -> idx == globalMinIndex || idx in localMinima }

        // Thin out clustered mandatory labels: when multiple mandatory labels are within
        // 4 hours of each other, prefer the most important label of the SAME type.
        // Keep meaningful peak+valley pairs (for example a midday dip between highs)
        // so local dips are not lost just because they are near a global extremum.
        val thinnedMandatory = mutableSetOf<Int>()
        val sortedIndices = mandatoryIndices.sorted()

        for (idx in sortedIndices) {
            val nearbyPlaced = thinnedMandatory.filter { abs(it - idx) <= 5 }
            
            if (idx == firstLabeledPositive || nearbyPlaced.isEmpty()) {
                thinnedMandatory.add(idx)
                continue
            }

            val isGlobal = idx == globalMaxIndex || idx == globalMinIndex
            val sameTypeNearby = nearbyPlaced.filter {
                (isPeakMandatory(idx) && isPeakMandatory(it)) ||
                (isValleyMandatory(idx) && isValleyMandatory(it))
            }
            
            val sameTypeHasGlobal = sameTypeNearby.any { it == globalMaxIndex || it == globalMinIndex }

            if (sameTypeNearby.isEmpty()) {
                thinnedMandatory.add(idx)
            } else if (isGlobal && !sameTypeHasGlobal) {
                // Global extrema can replace nearby same-type non-global labels.
                // PROTECT firstLabeledPositive: never remove it.
                sameTypeNearby.filter { it != firstLabeledPositive }.forEach { thinnedMandatory.remove(it) }
                thinnedMandatory.add(idx)
            } else if (!sameTypeHasGlobal) {
                // Same-type non-global already exists nearby; keep the stronger extremum.
                val strongestExisting = sameTypeNearby.maxByOrNull { existingIdx ->
                    if (isPeakMandatory(existingIdx)) labelSignal[existingIdx] else 100 - labelSignal[existingIdx]
                }
                if (strongestExisting != null) {
                    val existingScore = if (isPeakMandatory(strongestExisting)) labelSignal[strongestExisting] else 100 - labelSignal[strongestExisting]
                    val newScore = if (isPeakMandatory(idx)) labelSignal[idx] else 100 - labelSignal[idx]
                    if (newScore > existingScore) {
                        // PROTECT firstLabeledPositive: never remove it.
                        if (strongestExisting != firstLabeledPositive) {
                            thinnedMandatory.remove(strongestExisting)
                            thinnedMandatory.add(idx)
                        }
                    }
                }
            }
        }
        
        mandatoryIndices.clear()
        mandatoryIndices.addAll(thinnedMandatory)

        // Remove shoulder peaks that sit immediately next to a deeper mandatory valley.
        // Example: 56% followed by 40% one hour later should keep the valley label, not both.
        val shoulderPeaksToDrop = mutableSetOf<Int>()
        mandatoryIndices.forEach { idx ->
            val isGlobalPeak = idx == globalMaxIndex
            val isPeak = idx in localMaxima || isGlobalPeak
            if (!isPeak || isGlobalPeak) return@forEach

            val nearbyMandatoryValley =
                mandatoryIndices.firstOrNull { other ->
                    other != idx &&
                        (other == globalMinIndex || other in localMinima) &&
                        abs(other - idx) <= 1
                }
            if (nearbyMandatoryValley != null) {
                val peakProb = labelSignal[idx]
                val valleyProb = labelSignal[nearbyMandatoryValley]
                if (peakProb <= 65 && peakProb - valleyProb <= 20) {
                    shoulderPeaksToDrop.add(idx)
                }
            }
        }
        mandatoryIndices.removeAll(shoulderPeaksToDrop)

        val mandatoryNano = System.nanoTime()

        val orderedCandidates =
            sortedCandidates.sortedWith(
                compareBy<LabelCandidate> { if (it.index in mandatoryIndices) 0 else 1 }
                    .thenBy { it.priority }
                    .thenBy { -labelSignal[it.index] },
            )

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
            val prob = labelSignal[index]
            val isDryWindow = index in dryWindowIndices
            if (prob <= 0 && !isDryWindow) continue
            val isPeak = index in localMaxima || index == globalMaxIndex
            val isValley = index in localMinima || index == globalMinIndex
            val isEarlyAnchor = index == firstPositive || index == firstLabeledPositive

            // Interval labels are now true fallback-only labels.
            // Once we already have a few stronger labels (extrema/anchors/edges),
            // skip interval candidates to avoid clutter like arbitrary mid-curve points.
            val isIntervalCandidate = candidate.priority >= 5
            val intervalCap = if (hours.size <= 8) 5 else 3
            if (!isMandatory && isIntervalCandidate && labelsPlaced >= intervalCap) continue

            // Detect "dip regions": the point sits in a trough where notably higher
            // values exist within ±5 hours on BOTH sides.  Even if this exact index
            // isn't the strict local minimum, the visual curve dips here.
            val dipWindow = 5
            val dipLeft = (index - dipWindow).coerceAtLeast(0)
            val dipRight = (index + dipWindow).coerceAtMost(labelSignal.lastIndex)
            val signalAtIndex = labelSignal[index]
            val leftMax = (dipLeft until index).maxOfOrNull { labelSignal[it] } ?: signalAtIndex
            val rightMax = ((index + 1)..dipRight).maxOfOrNull { labelSignal[it] } ?: signalAtIndex
            val isSoftDip = !isValley && !isPeak &&
                leftMax > signalAtIndex + 5 && rightMax > signalAtIndex + 5

            // For peaks/valleys, use parabolic interpolation to shift the label
            // toward the visual apex of the smoothed curve.  The Bezier spline's
            // actual extremum sits between data points when slopes are asymmetric.
            // For soft dips, center on the middle of the minimum run within the dip window.
            // This keeps labels visually centered on broad/flat troughs (for example 45% plateaus).
            val softDipMinIdx = if (isSoftDip) {
                val minVal = (dipLeft..dipRight).minOf { labelSignal[it] }
                val minIndices = (dipLeft..dipRight).filter { labelSignal[it] == minVal }
                minIndices[minIndices.size / 2]
            } else {
                -1
            }

            // Plateau centering: if the label sits on a flat run of identical values,
            // shift to the center of that plateau so it aligns with the visual peak/trough.
            val plateauStart = (index downTo 0).takeWhile { labelSignal[it] == prob }.lastOrNull() ?: index
            val plateauEnd = (index..labelSignal.lastIndex).takeWhile { labelSignal[it] == prob }.lastOrNull() ?: index
            val onPlateau = plateauEnd - plateauStart >= 2
            val plateauCenterIdx = if (onPlateau) (plateauStart + plateauEnd) / 2 else index

            val anchorIdx =
                when {
                    isSoftDip -> softDipMinIdx
                    onPlateau -> plateauCenterIdx
                    else -> index
                }
            val centerX =
                if ((isSoftDip || isPeak || isValley) && anchorIdx > 0 && anchorIdx < probs.lastIndex) {
                    val fLeft = probs[anchorIdx - 1]
                    val fCenter = probs[anchorIdx]
                    val fRight = probs[anchorIdx + 1]
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
            // De-clutter rule: if a peak has lower candidate labels on BOTH sides nearby,
            // prefer placing the peak on top of the curve so the cluster is easier to scan.
            val crowdWindow = 6
            val nearbyLowerCandidates =
                candidateMap.keys.filter { candidateIdx ->
                    candidateIdx != index &&
                        abs(candidateIdx - index) <= crowdWindow &&
                        labelSignal[candidateIdx] <= prob - 10
                }
            val hasLowerLeftNeighbor = nearbyLowerCandidates.any { it < index }
            val hasLowerRightNeighbor = nearbyLowerCandidates.any { it > index }
            val shouldElevatePeakLabel =
                isPeak &&
                    prob in 55..85 &&
                    hasLowerLeftNeighbor &&
                    hasLowerRightNeighbor
            // Centerline dip rule: if a dip sits near the graph's vertical midpoint,
            // prefer placing its label below the line so it reads as "under the dip".
            val graphMidY = (graphTop + graphBottom) / 2f
            val isNearGraphCenter = abs(y - graphMidY) <= graphHeight * 0.2f
            val shouldPlaceDipBelow =
                (isValley || isSoftDip) &&
                    isNearGraphCenter
            val isNearRightEdge = index >= labelSignal.lastIndex - 1
            val isTrendingDownAtRightEdge =
                index > 0 &&
                    points[index].second > points[index - 1].second + 0.5f
            val isTrendingUpAtRightEdge =
                index > 0 &&
                    points[index].second < points[index - 1].second - 0.5f
            val isNearGraphTop = y <= graphTop + graphHeight * 0.2f
            val isFirstLabel = (index == firstPositive || index == firstLabeledPositive) && index != -1
            val isRising = index < labelSignal.lastIndex && labelSignal[index + 1] > labelSignal[index]
            val fitsBelow = y + belowGap + dpToPx(context, 2f) <= graphBottom
            val shouldPlaceFirstLabelBelow = isFirstLabel && isRising && fitsBelow

            val shouldPlaceRightEdgeBelow =
                isNearRightEdge &&
                    isTrendingDownAtRightEdge
            val shouldPlaceRightEdgeAbove =
                isNearRightEdge &&
                    isTrendingUpAtRightEdge

            val shouldPlacePeakAbove =
                isPeak
            val shouldPlaceValleyBelow =
                isValley || isSoftDip

            val preferBelow =
                when {
                    shouldPlaceFirstLabelBelow -> true
                    shouldPlacePeakAbove -> false
                    shouldElevatePeakLabel -> false
                    shouldPlaceValleyBelow -> true
                    shouldPlaceRightEdgeBelow -> true
                    shouldPlaceRightEdgeAbove -> false
                    else -> prob > 50
                }

            // --- DE-CLUTTER & PLATEAU DE-DUPLICATION ---
            
            // 1. ABSOLUTE SKIP: Never place two labels at the exact same anchor point (e.g. same plateau center)
            if (labeledIndices.contains(anchorIdx)) {
                Log.d("PrecipGraph", "SKIPPED: Anchor point $anchorIdx already labeled")
                continue
            }

            // 2. PROXIMITY SKIP: Skip non-mandatory labels too close to an already-placed label (time spacing)
            // Exception: peak+valley pairs are complementary features that should coexist
            val proximityThreshold = if (hours.size <= 8) 1 else 2
            if (!isMandatory && labeledIndices.any { nearIdx ->
                    abs(nearIdx - anchorIdx) <= proximityThreshold &&
                        // Allow peak next to valley (or vice versa) — they're complementary
                        !((isPeak || isDryWindow) && (nearIdx in localMinima || nearIdx == globalMinIndex || nearIdx in dryWindowIndices)) &&
                        !(isValley && (nearIdx in localMaxima || nearIdx == globalMaxIndex))
                }) {
                Log.d("PrecipGraph", "SKIPPED: Proximity to existing labels for idx=$index (anchor=$anchorIdx)")
                continue
            }

            // 3. VALUE DE-DUPLICATION: Skip if we already labeled this exact % within 5 hours.
            // However, always allow mandatory labels (important peaks/valleys) to bypass this.
            val isFeatureLabel = isPeak || isValley || isEarlyAnchor
            if (!isMandatory && !isFeatureLabel && labeledIndices.any { labelSignal[it] == prob && abs(it - anchorIdx) <= 5 }) {
                Log.d("PrecipGraph", "SKIPPED redundant value label: $prob% at idx=$index")
                continue
            }

            // 4. VALUE SEPARATION: skip non-mandatory labels too close in value to nearby placed labels
            val valueSepWindow = if (hours.size <= 8) 1 else 6
            if (!isMandatory && !isFeatureLabel && labeledIndices.any { abs(it - anchorIdx) <= valueSepWindow && abs(labelSignal[it] - prob) < 15 }) {
                Log.d("PrecipGraph", "SKIPPED low-separation label: $prob% at idx=$index")
                continue
            }

            val attempts = if (preferBelow) {
                listOf(Pair(0f, false), Pair(0f, true)) // Try BELOW, then ABOVE
            } else {
                listOf(Pair(0f, true), Pair(0f, false)) // Try ABOVE, then BELOW
            }

            Log.d(
                "PrecipGraph",
                "Attempting label for idx=$index (${hours[index].label}) prob=$prob% isPeak=$isPeak elevatePeak=$shouldElevatePeakLabel dipBelow=$shouldPlaceDipBelow firstBelow=$shouldPlaceFirstLabelBelow",
            )
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
                    Log.d("PrecipGraph", "  REJECTED ($dx, $placeAbove): Out of vertical bounds $bounds (graphBottom=$graphBottom)")
                    continue
                }

                val overlapsExisting = drawnLabelBounds.any { RectF.intersects(it, bounds) }
                val overlapsIcon = drawnIconBounds.any { RectF.intersects(it, bounds) }
                
                // For mandatory labels (like the only peak of the day), allow a TINY overlap 
                // if it helps the label appear. 15% of height is usually safe.
                val overlapAllowed = isMandatory
                val overlapThreshold = if (overlapAllowed) textHeight * 0.15f else 0f
                
                if (overlapsExisting || overlapsIcon) {
                    if (overlapsIcon) {
                        Log.d("PrecipGraph", "  REJECTED ($dx, $placeAbove): Overlap with icon bounds")
                        continue
                    }
                    // Check if overlap is within allowed threshold
                    val actualExistingOverlap = drawnLabelBounds.maxOfOrNull { existing ->
                        val intersect = RectF()
                        if (intersect.setIntersect(existing, bounds)) intersect.height() else 0f
                    } ?: 0f
                    
                    if (actualExistingOverlap > overlapThreshold) {
                        Log.d("PrecipGraph", "  REJECTED ($dx, $placeAbove): Overlap existing=$overlapsExisting (overlap=$actualExistingOverlap > threshold=$overlapThreshold)")
                        continue
                    } else {
                        Log.d("PrecipGraph", "  ACCEPTED with minor overlap ($dx, $placeAbove): overlap=$actualExistingOverlap <= threshold=$overlapThreshold")
                    }
                }

                val reason =
                    when {
                        isMandatory -> "mandatory"
                        isPeak -> "peak"
                        isValley -> "valley"
                        isEarlyAnchor -> "earlyAnchor"
                        candidate.priority == 2 -> "currentHour/edge"
                        candidate.priority == 4 -> "highProb"
                        candidate.priority == 5 -> "interval"
                        else -> "pri=${candidate.priority}"
                    }
                val logMsg = "PLACED label: $labelText at hour=${hours[index].label}(idx=$index) reason=$reason above=$placeAbove dx=$dx"
                Log.d("PrecipGraph", logMsg)
                onLabelPlaced?.invoke(
                    LabelPlacementDebug(
                        index = index,
                        hourLabel = hours[index].label,
                        probability = prob,
                        placedAbove = placeAbove,
                        reason = reason,
                        isPeak = isPeak,
                        isValley = isValley,
                        isSoftDip = isSoftDip,
                        elevatedPeakRuleApplied = shouldElevatePeakLabel,
                        dipBelowRuleApplied = shouldPlaceDipBelow,
                        firstLabelBelowRuleApplied = shouldPlaceFirstLabelBelow,
                    ),
                )
                canvas.drawText(labelText, x, baselineY, percentLabelPaint)
                drawnLabelBounds.add(bounds)
                labeledIndices.add(anchorIdx)
                labelsPlaced++
                break
            }
        }

        val loopNano = System.nanoTime()

        // Day of week indicators — left and right edges, cascade: TOP → MIDDLE → BOTTOM
        val today = currentTime.toLocalDate()
        val leftDate  = hours.first().dateTime.toLocalDate()
        val rightDate = hours.last().dateTime.toLocalDate()
        val leftText  = hours.first().dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val rightText = hours.last().dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val leftTextWidth  = (if (leftDate  == today) todayDayLabelPaint else dayLabelTextPaint).measureText(leftText)
        val rightTextWidth = (if (rightDate == today) todayDayLabelPaint else dayLabelTextPaint).measureText(rightText)

        GraphRenderUtils.drawDayLabels(
            context = context,
            canvas = canvas,
            leftDate = leftDate,
            rightDate = rightDate,
            leftText = leftText,
            rightText = rightText,
            leftX = leftTextWidth / 2f,
            rightX = widthPx - rightTextWidth / 2f,
            today = today,
            graphTop = graphTop,
            graphBottom = graphBottom,
            heightPx = heightPx,
            dayLabelTextPaint = dayLabelTextPaint,
            todayDayLabelPaint = todayDayLabelPaint,
            drawnLabelBounds = drawnLabelBounds,
            drawnIconBounds = drawnIconBounds,
            dpToPx = { dpToPx(context, it) },
            onDayLabelPlaced = if (onDayLabelPlaced != null) { side, text, date, x, y, placement, isToday ->
                Log.d("DayLabel", "Day=$text side=$side x=$x placement=$placement")
                onDayLabelPlaced.invoke(DayLabelPlacementDebug(side, text, date, x, y, placement, isToday))
            } else null,
        )

        GraphRenderUtils.drawNowIndicator(
            canvas = canvas,
            nowX = nowX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            currentTimePaint = currentTimePaint,
            nowLabelTextPaint = nowLabelTextPaint,
            dpToPx = { dpToPx(context, it) },
        )

        // Draw "Last Fetch Dot" on the curve
        if (observedAt != null) {
            val fetchTime = java.time.Instant.ofEpochMilli(observedAt)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()

            val fetchX = GraphRenderUtils.computeXForTime(
                targetTime = fetchTime,
                items = hours,
                points = points,
                hourWidth = hourWidth,
                dateTimeOf = { it.dateTime }
            )

            Log.d("PrecipGraphRenderer", "drawFetchDot: fetchTime=$fetchTime, fetchX=$fetchX, range=${hours.first().dateTime} to ${hours.last().dateTime}")

            if (fetchX != null) {
                val fetchIdx = hours.indexOfLast { !it.dateTime.isAfter(fetchTime) }
                if (fetchIdx != -1 && fetchIdx < hours.lastIndex) {
                    val baseProb = hours[fetchIdx].precipProbability.toFloat()
                    val nextProb = hours[fetchIdx + 1].precipProbability.toFloat()
                    val fraction = java.time.Duration.between(hours[fetchIdx].dateTime, fetchTime).toMinutes() / 60f
                    val interpolatedProb = baseProb + (nextProb - baseProb) * fraction
                    val fetchY = graphBottom - graphHeight * (interpolatedProb / yScaleMax)
                    val valueLabel = "${interpolatedProb.roundToInt()}%"

                    val windowHours = java.time.Duration.between(hours.first().dateTime, hours.last().dateTime).toHours()
                    val ageMinutes = if (windowHours <= 12) {
                        val age = java.time.Duration.between(fetchTime, currentTime).toMinutes()
                        Log.d("PrecipGraphRenderer", "staleness: fetchTime=$fetchTime currentTime=$currentTime ageMinutes=$age observedAt=$observedAt")
                        age
                    } else null

                    val dotRadius = dpToPx(context, 2.5f * bitmapScale.coerceIn(0.5f, 1f))
                    val clampedFetchX = fetchX.coerceIn(dotRadius, widthPx.toFloat() - dotRadius)

                    GraphRenderUtils.drawFetchDot(
                        context = context,
                        canvas = canvas,
                        fetchX = fetchX,
                        fetchY = fetchY,
                        valueLabel = valueLabel,
                        ageMinutes = ageMinutes,
                        bitmapScale = bitmapScale,
                        widthPx = widthPx,
                        heightPx = heightPx,
                        dpToPx = { dpToPx(context, it) },
                    )

                    // Prevent double-labeling this index in the main probability label loop
                    if (ageMinutes != null) {
                        labeledIndices.add(fetchIdx)
                    }

                    val dotLabelForDebug = if (ageMinutes != null && ageMinutes >= 0) {
                        val ageLabel = if (ageMinutes >= 60) {
                            val h = ageMinutes / 60
                            val m = ageMinutes % 60
                            if (m > 0) "${h}h ${m}m" else "${h}h"
                        } else "${ageMinutes}m"
                        "$valueLabel ($ageLabel)"
                    } else valueLabel

                    onFetchDotResolved?.invoke(
                        FetchDotDebug(
                            observedAt = observedAt,
                            fetchDotX = clampedFetchX,
                            fetchY = fetchY,
                            withinWindow = true,
                            ageText = if (ageMinutes != null) dotLabelForDebug else null,
                        ),
                    )
                }
            }
        }

        // Draw end-of-graph label (right edge) for the final precipitation probability.
        // Keep it inside bounds and avoid overlaps with existing labels/NOW marker.
        if (hours.isNotEmpty()) {
            val lastIndex = hours.lastIndex
            val endProb = labelSignal[lastIndex]

            // Proximity check: skip if any label already placed within 3 hours of the end
            if (labeledIndices.any { abs(it - lastIndex) <= 3 }) {
                Log.d("PrecipGraph", "SKIPPED end label: $endProb% (existing label within 3 hours)")
            // Value De-duplication: Skip if we already labeled this exact % within 5 hours
            } else if (labeledIndices.any { labelSignal[it] == endProb && abs(it - lastIndex) <= 5 }) {
                Log.d("PrecipGraph", "SKIPPED redundant end label: $endProb% (already labeled nearby)")
            } else {
                val endLabelText = "$endProb%"
                val textWidth = percentLabelPaint.measureText(endLabelText)
                val textHeight = percentLabelPaint.textSize
                val endPointY = points[lastIndex].second
                val graphMidY = (graphTop + graphBottom) / 2f
                val isTrendingDownAtEnd =
                    lastIndex > 0 &&
                        points[lastIndex].second > points[lastIndex - 1].second + 0.5f
                val preferEndLabelBelow = isTrendingDownAtEnd || endProb > 50
                val placement =
                    computeEndLabelPlacement(
                        textWidth = textWidth,
                        textHeight = textHeight,
                        widthPx = widthPx,
                        graphBottom = graphBottom,
                        pointY = endPointY,
                        aboveGap = aboveGap,
                        belowGap = belowGap,
                        rightPadding = dpToPx(context, 8f),
                        verticalInset = dpToPx(context, 2f),
                        existingBounds = drawnLabelBounds.map { it.toPlacementRect() },
                        preferBelow = preferEndLabelBelow,
                    )

                if (placement != null) {
                    canvas.drawText(endLabelText, placement.x, placement.baselineY, percentLabelPaint)
                    drawnLabelBounds.add(placement.bounds.toRectF())
                    val mode = if (placement.usedFallback) "fallback" else "preferred"
                    val side = if (placement.baselineY < endPointY) "above" else "below"
                    // Below used in test.  Do not delete!
                    val logMsg = "PLACED end label: $endLabelText at right edge ($mode, $side)"
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
                val avg = (start until start + windowSize).map { probs[it] }.average().toFloat()
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
                    val avg = (start until start + windowSize).map { probs[it] }.average().toFloat()
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

        val endNano = System.nanoTime()
        val totalUs = (endNano - startNano) / 1000
        val setupUs = (setupNano - startNano) / 1000
        val pathUs = (pathNano - setupNano) / 1000
        val drawUs = (drawNano - pathNano) / 1000
        val candidateUs = (candidateNano - drawNano) / 1000
        val mandatoryUs = (mandatoryNano - candidateNano) / 1000
        val loopUs = (loopNano - mandatoryNano) / 1000
        val restUs = (endNano - loopNano) / 1000

        if (com.weatherwidget.BuildConfig.DEBUG) {
            Log.d("PrecipGraphPerf", "renderGraph: total=${totalUs}us, setup=${setupUs}us, path=${pathUs}us, draw=${drawUs}us, candidate=${candidateUs}us, mandatory=${mandatoryUs}us, loop=${loopUs}us, rest=${restUs}us")
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

    private fun peakBilateralProminence(
        values: List<Int>,
        index: Int,
        radius: Int,
    ): Int {
        if (index <= 0 || index >= values.lastIndex) return 0
        val leftStart = (index - radius).coerceAtLeast(0)
        val rightEnd = (index + radius).coerceAtMost(values.lastIndex)
        if (leftStart == index || rightEnd == index) return 0
        val leftMin = (leftStart until index).minOfOrNull { values[it] } ?: values[index]
        val rightMin = ((index + 1)..rightEnd).minOfOrNull { values[it] } ?: values[index]
        return minOf(values[index] - leftMin, values[index] - rightMin)
    }

    private fun valleyBilateralProminence(
        values: List<Int>,
        index: Int,
        radius: Int,
    ): Int {
        if (index <= 0 || index >= values.lastIndex) return 0
        val leftStart = (index - radius).coerceAtLeast(0)
        val rightEnd = (index + radius).coerceAtMost(values.lastIndex)
        if (leftStart == index || rightEnd == index) return 0
        val leftMax = (leftStart until index).maxOfOrNull { values[it] } ?: values[index]
        val rightMax = ((index + 1)..rightEnd).maxOfOrNull { values[it] } ?: values[index]
        return minOf(leftMax - values[index], rightMax - values[index])
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
        preferBelow: Boolean = false,
    ): EndLabelPlacement? {
        val minX = textWidth / 2f
        val maxX = widthPx - rightPadding - textWidth / 2f
        val labelX = maxX.coerceAtLeast(minX)

        val safeTop = textHeight + verticalInset
        val safeBottom = graphBottom - verticalInset
        val aboveBaselineY = (pointY - aboveGap).coerceAtMost(safeBottom).coerceAtLeast(safeTop)
        val belowBaselineY = (pointY + belowGap).coerceAtMost(safeBottom).coerceAtLeast(safeTop)
        val preferredBaselineY = if (preferBelow) belowBaselineY else aboveBaselineY
        val fallbackBaselineY = if (preferBelow) aboveBaselineY else belowBaselineY

        fun makeBounds(
            x: Float,
            baselineY: Float,
        ): PlacementRect {
            return PlacementRect(
                left = x - textWidth / 2f,
                top = baselineY - textHeight,
                right = x + textWidth / 2f,
                bottom = baselineY,
            )
        }

        fun isClear(bounds: PlacementRect): Boolean {
            return existingBounds.none { it.intersects(bounds) }
        }

        // When below is preferred, allow a few small left nudges before giving up and moving above.
        // This keeps right-edge labels under a descending centerline tail even when the exact edge spot is crowded.
        val preferredXOffsets =
            if (preferBelow) {
                listOf(0f, -textWidth * 0.6f, -textWidth * 1.2f)
            } else {
                listOf(0f)
            }

        preferredXOffsets.forEachIndexed { offsetIndex, offset ->
            val candidateX = (labelX + offset).coerceIn(minX, maxX)
            val preferredBounds = makeBounds(candidateX, preferredBaselineY)
            if (isClear(preferredBounds)) {
                val usedFallback = offsetIndex > 0
                return EndLabelPlacement(candidateX, preferredBaselineY, usedFallback = usedFallback, bounds = preferredBounds)
            }
        }

        preferredXOffsets.forEach { offset ->
            val candidateX = (labelX + offset).coerceIn(minX, maxX)
            val fallbackBounds = makeBounds(candidateX, fallbackBaselineY)
            if (isClear(fallbackBounds)) {
                return EndLabelPlacement(candidateX, fallbackBaselineY, usedFallback = true, bounds = fallbackBounds)
            }
        }

        return null
    }

    internal fun shouldShowHourlyIcons(widthPx: Int): Boolean {
        return widthPx >= MIN_ICON_GRAPH_WIDTH_PX
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
