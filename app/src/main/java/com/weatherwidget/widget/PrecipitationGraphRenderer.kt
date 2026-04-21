package com.weatherwidget.widget

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import android.content.Context
import android.graphics.*
import android.util.Log
import android.util.TypedValue
import com.weatherwidget.R
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import com.weatherwidget.widget.handlers.formatPrecipAmount
import kotlin.math.abs
import kotlin.math.roundToInt

object PrecipitationGraphRenderer {

    private const val TAG = "PrecipGraphRenderer"
    private const val MIN_ICON_GRAPH_WIDTH_PX = 420
    private const val MAX_PRECIP_LABEL_CANDIDATES = 5
    private val DENSE_LABEL_DIFF_THRESHOLDS = listOf(5, 10, 15)

    data class PrecipHourData(
        val dateTime: LocalDateTime,
        val precipProbability: Int, // 0-100
        val label: String, // "12a", "1p", "2p"
        val iconRes: Int? = null,
        val isNight: Boolean = false,
        val isTwilight: Boolean = false,
        val isSunBoundary: Boolean = false,
        val isSunny: Boolean = false,
        val isRainy: Boolean = false,
        val isMixed: Boolean = false,
        val isCurrentHour: Boolean = false,
        val showLabel: Boolean = true,
        val precipAmountMm: Float? = null,
    )

    data class LabelPlacementDebug(
        val index: Int,
        val hourLabel: String,
        val probability: Int,
        val placedAbove: Boolean,
        val isGlobalMax: Boolean,
        val isGlobalMin: Boolean,
        val reason: String = "",
        val isPeak: Boolean = false,
        val isValley: Boolean = false,
        val isSoftDip: Boolean = false,
        val firstLabelBelowRuleApplied: Boolean = false,
        val elevatedPeakRuleApplied: Boolean = false,
        val dipBelowRuleApplied: Boolean = false,
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

    data class WatermarkPlacementDebug(
        val x: Float,
        val y: Float,
        val xFrac: Float,
        val yFrac: Float,
    )

    data class PrecipRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        fun intersects(other: PrecipRect): Boolean {
            return left < other.right && right > other.left && top < other.bottom && bottom > other.top
        }
        
        fun toRectF(): RectF = RectF(left, top, right, bottom)
        
        companion object {
            fun fromRectF(rect: RectF): PrecipRect = PrecipRect(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    data class ProbabilityLabelPlacement(
        val index: Int,
        val text: String,
        val x: Float,
        val baselineY: Float,
        val bounds: PrecipRect,
        val debug: LabelPlacementDebug
    )

    data class RainAmountPlacement(
        val text: String,
        val x: Float,
        val y: Float,
        val bounds: PrecipRect,
        val overlapArea: Float
    )

    data class RainPeriod(
        val startIndex: Int,
        val endIndex: Int,
        val totalAmountMm: Float,
        val startLabel: String,
        val endLabel: String,
    )

    data class PrecipGraphLayout(
        val points: List<Pair<Float, Float>>,
        val probabilityPlacements: List<ProbabilityLabelPlacement>,
        val rainAmountPlacements: List<RainAmountPlacement>,
        val graphTop: Float,
        val graphBottom: Float,
        val graphHeight: Float,
        val nowX: Float?,
        val labelSignal: List<Int>,
        val watermarkPlacement: WatermarkPlacementDebug? = null
    )

    fun calculateLayout(
        hours: List<PrecipHourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        smoothIterations: Int = 2,
        highProbThreshold: Int = 99,
        rainAmountWindowHours: Int = 0,
        showHourlyIcons: Boolean,
        measureProbabilityText: (String) -> Float,
        getProbabilityTextBounds: (String) -> Pair<Float, Float>,
        measureRainAmountText: (String) -> Float,
        getRainAmountTextBounds: (String) -> Pair<Float, Float>,
        dpToPx: (Float) -> Float
    ): PrecipGraphLayout {
        val labelScale = bitmapScale.coerceAtMost(1f)
        val topPadding = dpToPx(44f * labelScale)
        val iconSize = dpToPx(22.4f).toInt()
        val iconTopPad = dpToPx(0f)
        val iconBottomPad = dpToPx(0f)
        val labelHeight = dpToPx(20f * labelScale)
        val bottomPadding = dpToPx(0f)

        val graphTop = topPadding
        val graphBottom =
            if (showHourlyIcons) {
                heightPx - labelHeight - bottomPadding - iconBottomPad - iconSize - iconTopPad
            } else {
                heightPx - labelHeight - bottomPadding
            }
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)
        val hourWidth = widthPx.toFloat() / (hours.size - 1).coerceAtLeast(1)

        val points = mutableListOf<Pair<Float, Float>>()
        val rawProbs = hours.map { it.precipProbability.coerceIn(0, 100).toFloat() }
        val isFarOutData = hours.isNotEmpty() && kotlin.math.abs(
            java.time.Duration.between(
                hours.first().dateTime.plusHours(hours.size.toLong() / 2),
                currentTime,
            ).toHours()
        ) > 72

        val probs = if (isFarOutData) {
            GraphRenderUtils.smoothValuesPreservingExtrema(
                rawProbs,
                iterations = smoothIterations,
                preserveGlobalMax = true,
                preserveGlobalMin = false,
                preserveStart = true,
                preserveEnd = true,
            )
        } else {
            GraphRenderUtils.smoothValuesPreservingGlobalExtrema(rawProbs, iterations = smoothIterations)
        }

        val rawMax = probs.maxOrNull() ?: 0f
        val yScaleMax = (rawMax * 1.15f).coerceAtLeast(10f).coerceAtMost(100f)

        hours.forEachIndexed { index, _ ->
            val x = hourWidth * index
            val prob = probs[index]
            val y = graphBottom - graphHeight * (prob / yScaleMax)
            points.add(x to y)
        }

        val nowX = GraphRenderUtils.computeNowX(
            items = hours,
            points = points,
            currentTime = currentTime,
            hourWidth = hourWidth,
            isCurrentHour = { it.isCurrentHour },
            dateTimeOf = { it.dateTime },
        )

        val labelSignal = probs.map { it.roundToInt().coerceIn(0, 100) }
        val localMaxima = GraphRenderUtils.findLocalExtremaIndices(labelSignal, isMax = true)
        val localMinima = GraphRenderUtils.findLocalExtremaIndices(labelSignal, isMax = false)
        val globalMaxVal = labelSignal.maxOrNull() ?: -1
        val globalMinVal = labelSignal.minOrNull() ?: -1
        val globalMaxIdx = localMaxima.firstOrNull { labelSignal[it] == globalMaxVal }
            ?: labelSignal.indexOfFirst { it == globalMaxVal }
        val globalMinIdx = localMinima.firstOrNull { labelSignal[it] == globalMinVal }
            ?: labelSignal.indexOfFirst { it == globalMinVal }

        val firstPositive = labelSignal.indexOfFirst { it > 0 }
        val firstLabeledPositive = hours.indexOfFirst { it.precipProbability > 0 && it.showLabel }

        val softDipCandidates = mutableListOf<Int>()
        var jIdx = 0
        while (jIdx < labelSignal.size) {
            val prob = labelSignal[jIdx]
            if (prob <= 0 || prob > 65) { jIdx++; continue }
            var runEnd = jIdx
            while (runEnd < labelSignal.lastIndex && labelSignal[runEnd + 1] == prob) runEnd++
            val left = (jIdx - 5).coerceAtLeast(0)
            val right = (runEnd + 5).coerceAtMost(labelSignal.lastIndex)
            if (left < jIdx && right > runEnd) {
                val leftMax = (left until jIdx).maxOfOrNull { labelSignal[it] } ?: prob
                val rightMax = ((runEnd + 1)..right).maxOfOrNull { labelSignal[it] } ?: prob
                if (leftMax >= prob + 8 && rightMax >= prob + 8) softDipCandidates.add(jIdx + (runEnd - jIdx) / 2)
            }
            jIdx = runEnd + 1
        }

        val zeroRunCandidates = mutableListOf<Int>()
        var i = 0
        while (i < labelSignal.size) {
            if (labelSignal[i] == 0) {
                val runStart = i
                while (i < labelSignal.size && labelSignal[i] == 0) i++
                val runEnd = i - 1
                if (runStart > 0 && labelSignal[runStart - 1] > 0 && runEnd < labelSignal.lastIndex && labelSignal[runEnd + 1] > 0) {
                    zeroRunCandidates.add((runStart + runEnd) / 2)
                }
            } else i++
        }

        val candidates = mutableListOf<Int>()
        if (globalMaxIdx >= 0 && labelSignal[globalMaxIdx] > 0) candidates.add(globalMaxIdx)
        if (globalMinIdx >= 0 && globalMinIdx != globalMaxIdx && labelSignal[globalMinIdx] > 0) candidates.add(globalMinIdx)
        candidates.addAll(localMaxima.filter { idx -> labelSignal[idx] > 0 })
        candidates.addAll(localMinima.filter { idx -> labelSignal[idx] > 0 })
        candidates.addAll(softDipCandidates)
        if (0 !in candidates) candidates.add(0)
        if (hours.lastIndex !in candidates && hours.isNotEmpty()) candidates.add(hours.lastIndex)
        if (firstPositive != -1 && firstPositive !in candidates) candidates.add(firstPositive)
        if (firstLabeledPositive != -1 && firstLabeledPositive !in candidates) candidates.add(firstLabeledPositive)

        val protectedIndices = buildSet { addAll(softDipCandidates); addAll(zeroRunCandidates) }
        candidates.sortBy { it }
        val filteredCandidates = GraphLabelPlacementUtils.filterDenseLabelCandidates(
            items = labelSignal,
            candidates = candidates,
            globalMaxIdx = globalMaxIdx,
            globalMinIdx = globalMinIdx,
            maxCandidates = MAX_PRECIP_LABEL_CANDIDATES,
            diffThresholds = DENSE_LABEL_DIFF_THRESHOLDS,
            valueFunction = { it },
            logTag = TAG,
            protectedIndices = protectedIndices,
            nearbyWindow = 5,
        )

        val suppressLeftEdgeLabel = GraphLabelPlacementUtils.shouldSuppressLeftEdgeLabel(
            items = labelSignal,
            candidates = filteredCandidates,
            globalMaxIdx = globalMaxIdx,
            globalMinIdx = globalMinIdx,
            valueFunction = { v -> v },
        )

        val finalCandidates = if (filteredCandidates.size == 2 && filteredCandidates == listOf(0, hours.lastIndex)) {
            val midIndex = hours.lastIndex / 2
            if (midIndex != 0 && midIndex != hours.lastIndex && labelSignal[midIndex] > 0) (filteredCandidates + midIndex).sorted() else filteredCandidates
        } else filteredCandidates

        // Pre-calculate icon bounds for collision detection
        val drawnIconBounds = mutableListOf<PrecipRect>()
        if (showHourlyIcons) {
            hours.forEachIndexed { index, hour ->
                if (hour.iconRes != null) {
                    val x = hourWidth * index
                    val clampedX = x.coerceIn(iconSize / 2f, widthPx - iconSize / 2f)
                    val iconY = graphBottom + iconTopPad
                    val iconX = clampedX - iconSize / 2f
                    drawnIconBounds.add(PrecipRect(iconX, iconY, iconX + iconSize, iconY + iconSize))
                }
            }
        }

        val probabilityPlacements = calculateProbabilityLabelPlacements(
            labelSignal = labelSignal,
            hours = hours,
            points = points,
            widthPx = widthPx,
            heightPx = heightPx,
            graphTop = graphTop,
            graphBottom = graphBottom,
            graphHeight = graphHeight,
            globalMaxIdx = globalMaxIdx,
            globalMinIdx = globalMinIdx,
            firstPositive = firstPositive,
            firstLabeledPositive = firstLabeledPositive,
            softDipCandidates = softDipCandidates,
            filteredCandidates = finalCandidates,
            suppressLeftEdgeLabel = suppressLeftEdgeLabel,
            drawnIconBounds = drawnIconBounds,
            measureText = measureProbabilityText,
            getTextBounds = getProbabilityTextBounds,
            dpToPx = dpToPx
        )

        val rainPeriods = if (rainAmountWindowHours > 0) findFixedWindowRainPeriods(hours, rainAmountWindowHours) else findHighProbRainPeriods(hours, highProbThreshold)
        val rainCollisionBounds = probabilityPlacements.map { it.bounds }.toMutableList()
        
        // Add now label bounds to rain collision
        if (nowX != null) {
            GraphRenderUtils.computeNowLabelBounds(
                nowX = nowX,
                graphTop = graphTop,
                graphHeight = graphHeight,
                textWidth = 15f,   // Estimated for layout
                fontAscent = -12f, // Estimated for layout
                fontDescent = 3f,  // Estimated for layout
                drawnBounds = rainCollisionBounds.map { it.toRectF() },
                dpToPx = dpToPx,
            )?.let { rainCollisionBounds.add(PrecipRect.fromRectF(it.bounds)) }
        }

        val rainPlacements = calculateRainAmountPlacements(
            rainPeriods = rainPeriods,
            widthPx = widthPx,
            heightPx = heightPx,
            graphTop = graphTop,
            graphBottom = graphBottom,
            graphHeight = graphHeight,
            initialCollisionBounds = rainCollisionBounds,
            measureText = measureRainAmountText,
            getTextBounds = getRainAmountTextBounds,
            dpToPx = dpToPx
        )

        val totalDrawnBounds = (probabilityPlacements.map { it.bounds } + rainPlacements.map { it.bounds }).toMutableList()

        var watermarkPlacement: WatermarkPlacementDebug? = null
        if (hours.size >= 3) {
            val iconSizePx = dpToPx(24f).toInt()
            val halfIcon = iconSizePx / 2f
            val xFractions = listOf(0.15f, 0.3f, 0.45f, 0.6f, 0.75f)
            val yFractions = listOf(0.12f, 0.25f, 0.38f, 0.5f, 0.65f, 0.8f)

            var placed = false
            for (yFrac in yFractions) {
                for (xFrac in xFractions) {
                    val cx = widthPx * xFrac
                    val cy = graphTop + graphHeight * yFrac
                    val bounds = PrecipRect(cx - halfIcon, cy - halfIcon, cx + halfIcon, cy + halfIcon)
                    if (bounds.left < 0f || bounds.right > widthPx) continue
                    if (bounds.top < graphTop || bounds.bottom > graphBottom) continue
                    if (totalDrawnBounds.any { it.intersects(bounds) }) continue

                    watermarkPlacement = WatermarkPlacementDebug(x = bounds.left, y = bounds.top, xFrac = xFrac, yFrac = yFrac)
                    placed = true; break
                }
                if (placed) break
            }
        }

        return PrecipGraphLayout(
            points = points,
            probabilityPlacements = probabilityPlacements,
            rainAmountPlacements = rainPlacements,
            graphTop = graphTop,
            graphBottom = graphBottom,
            graphHeight = graphHeight,
            nowX = nowX,
            labelSignal = labelSignal,
            watermarkPlacement = watermarkPlacement
        )
    }

    private class PaintSet(
        val density: Float,
        val labelScale: Float,
        val heightDp: Float,
        val curvePaint: Paint,
        val gradientPaint: Paint,
        val currentTimePaint: Paint,
        val hourLabelTextPaint: Paint,
        val percentLabelPaint: Paint,
        val nowLabelTextPaint: Paint,
        val dayLabelTextPaint: Paint,
        val todayDayLabelPaint: Paint,
        val rainAmountPaint: Paint,
    )

    private var cachedPaints: PaintSet? = null

    private fun ensurePaints(context: Context, heightDp: Float, labelScale: Float): PaintSet {
        val density = context.resources.displayMetrics.density
        val current = cachedPaints
        if (current != null && current.density == density && current.heightDp == heightDp && current.labelScale == labelScale) {
            return current
        }

        val curveStrokeDp = if (heightDp >= 160) 2.5f else 3f
        val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5AC8FA")
            strokeWidth = dpToPx(context, curveStrokeDp * labelScale)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val currentTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9F0A")
            strokeWidth = dpToPx(context, 1.0f * labelScale)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dpToPx(context, 4f * labelScale), dpToPx(context, 3f * labelScale)), 0f)
        }

        val hourLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#99FFFFFF")
            textSize = dpToPx(context, 23.0f * labelScale)
            textAlign = Paint.Align.CENTER
            setShadowLayer(dpToPx(context, 1f * labelScale), 0f, dpToPx(context, 0.5f * labelScale), Color.parseColor("#44000000"))
        }

        val percentLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = dpToPx(context, 23.0f * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 2f * labelScale), 0f, dpToPx(context, 0.5f * labelScale), Color.parseColor("#88000000"))
        }

        val nowLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BBFF9F0A")
            textSize = dpToPx(context, 15.5f * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(dpToPx(context, 1f * labelScale), 0f, 0f, Color.parseColor("#44000000"))
        }

        val dayLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#88FFFFFF")
            textSize = dpToPx(context, 23.0f * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val todayDayLabelPaint = Paint(dayLabelTextPaint).apply {
            color = Color.parseColor("#BBFF9F0A")
        }

        val rainAmountPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = dpToPx(context, 18.0f * labelScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(dpToPx(context, 2f * labelScale), 0f, dpToPx(context, 0.5f * labelScale), Color.parseColor("#88000000"))
        }

        val paints = PaintSet(
            density = density,
            labelScale = labelScale,
            heightDp = heightDp,
            curvePaint = curvePaint,
            gradientPaint = gradientPaint,
            currentTimePaint = currentTimePaint,
            hourLabelTextPaint = hourLabelTextPaint,
            percentLabelPaint = percentLabelPaint,
            nowLabelTextPaint = nowLabelTextPaint,
            dayLabelTextPaint = dayLabelTextPaint,
            todayDayLabelPaint = todayDayLabelPaint,
            rainAmountPaint = rainAmountPaint,
        )
        cachedPaints = paints
        return paints
    }

    fun calculateProbabilityLabelPlacements(
        labelSignal: List<Int>,
        hours: List<PrecipHourData>,
        points: List<Pair<Float, Float>>,
        widthPx: Int,
        heightPx: Int,
        graphTop: Float,
        graphBottom: Float,
        graphHeight: Float,
        globalMaxIdx: Int,
        globalMinIdx: Int,
        firstPositive: Int,
        firstLabeledPositive: Int,
        softDipCandidates: List<Int>,
        filteredCandidates: List<Int>,
        suppressLeftEdgeLabel: Boolean,
        drawnIconBounds: List<PrecipRect>,
        measureText: (String) -> Float,
        getTextBounds: (String) -> Pair<Float, Float>, // Returns Pair(ascent, descent)
        dpToPx: (Float) -> Float
    ): List<ProbabilityLabelPlacement> {
        val placements = mutableListOf<ProbabilityLabelPlacement>()
        val drawnLabelBounds = mutableListOf<PrecipRect>()

        for (index in filteredCandidates) {
            if (index !in labelSignal.indices) continue
            if (index == 0 && suppressLeftEdgeLabel) continue
            
            val prob = labelSignal[index]
            val labelText = "$prob%"
            val (textAscent, textDescent) = getTextBounds(labelText)
            val textWidth = measureText(labelText)
            val centerX = points[index].first
            val y = points[index].second

            val kind = GraphLabelPlacementUtils.candidateKind(index, labelSignal, globalMaxIdx, globalMinIdx) { v -> v }
            val isPeak = kind == GraphLabelPlacementUtils.CandidateKind.PEAK || kind == GraphLabelPlacementUtils.CandidateKind.GLOBAL_MAX
            val isValley = kind == GraphLabelPlacementUtils.CandidateKind.VALLEY || kind == GraphLabelPlacementUtils.CandidateKind.GLOBAL_MIN
            val isSoftDip = index in softDipCandidates
            
            val graphMidY = (graphTop + graphBottom) / 2f
            val isNearGraphCenter = abs(y - graphMidY) <= graphHeight * 0.2f
            val isNearRightEdge = index >= labelSignal.lastIndex - 1
            val isTrendingDownAtRightEdge = index > 0 && points[index].second > points[index - 1].second + 0.5f
            val isTrendingUpAtRightEdge = index > 0 && points[index].second < points[index - 1].second - 0.5f
            val isFirstRising = (index == firstPositive || index == firstLabeledPositive) && prob > 0
            
            val preferBelow = when {
                isFirstRising -> true
                isPeak -> false
                isValley || isSoftDip -> true
                isNearRightEdge && isTrendingDownAtRightEdge -> true
                isNearRightEdge && isTrendingUpAtRightEdge -> false
                else -> prob > 50
            }
            val directions = if (preferBelow) listOf(false, true) else listOf(true, false)

            for ((attemptIndex, placeAbove) in directions.withIndex()) {
                val isFallbackAttempt = attemptIndex > 0
                val gapDp = GraphLabelPlacementUtils.getLabelGapDp(isFallback = isFallbackAttempt)
                val gapPx = if (placeAbove) dpToPx(gapDp.aboveDp) else dpToPx(gapDp.belowDp)
                val x = centerX.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)
                val verticalPlacement = GraphLabelPlacementUtils.computeLabelVerticalPlacement(
                    pointY = y,
                    placeAbove = placeAbove,
                    gapPx = gapPx,
                    textAscent = textAscent,
                    textDescent = textDescent,
                )
                val baselineY = verticalPlacement.baselineY
                val bounds = PrecipRect(
                    x - textWidth / 2f, verticalPlacement.top,
                    x + textWidth / 2f, verticalPlacement.bottom,
                )

                val safeBottom = graphBottom - dpToPx(2f)
                val exceedsTop = bounds.top < 0f
                val exceedsBottom = bounds.bottom > safeBottom
                
                val isLowPreferredBelow = !placeAbove && prob <= 55
                val actualExceedsBottom = if (isLowPreferredBelow) bounds.bottom > heightPx else exceedsBottom

                if (exceedsTop || actualExceedsBottom) continue
                
                val overlapsLabel = drawnLabelBounds.any { it.intersects(bounds) }
                val overlapsIcon = drawnIconBounds.any { it.intersects(bounds) }
                val hasCollision = (overlapsLabel) || (overlapsIcon && !isLowPreferredBelow)

                if (hasCollision) continue

                val dipBelowRuleApplied = (isValley || isSoftDip) && !placeAbove && isNearGraphCenter
                val crowdWindow = 6
                val nearbyLowerCandidates = filteredCandidates.filter { cid ->
                    cid != index && abs(cid - index) <= crowdWindow && labelSignal[cid] <= prob - 10
                }
                val hasLowerNeighbors = nearbyLowerCandidates.any { it < index } && nearbyLowerCandidates.any { it > index }
                val elevatedPeakRuleApplied = isPeak && placeAbove && prob in 55..85 && hasLowerNeighbors

                val reason = when {
                    isPeak -> "peak"
                    isValley -> "valley"
                    isSoftDip -> "softDip"
                    index == 0 -> "start"
                    index == hours.lastIndex -> "end"
                    else -> "other"
                }

                val debug = LabelPlacementDebug(
                    index = index,
                    hourLabel = hours[index].label,
                    probability = prob,
                    placedAbove = placeAbove,
                    isGlobalMax = index == globalMaxIdx,
                    isGlobalMin = index == globalMinIdx,
                    reason = reason,
                    isPeak = isPeak,
                    isValley = isValley,
                    isSoftDip = isSoftDip,
                    firstLabelBelowRuleApplied = (isFirstRising && !placeAbove),
                    elevatedPeakRuleApplied = elevatedPeakRuleApplied,
                    dipBelowRuleApplied = dipBelowRuleApplied
                )
                
                placements.add(ProbabilityLabelPlacement(index, labelText, x, baselineY, bounds, debug))
                drawnLabelBounds.add(bounds)
                break
            }
        }
        return placements
    }

    fun calculateRainAmountPlacements(
        rainPeriods: List<RainPeriod>,
        widthPx: Int,
        heightPx: Int,
        graphTop: Float,
        graphBottom: Float,
        graphHeight: Float,
        initialCollisionBounds: List<PrecipRect>,
        measureText: (String) -> Float,
        getTextBounds: (String) -> Pair<Float, Float>,
        dpToPx: (Float) -> Float
    ): List<RainAmountPlacement> {
        val placements = mutableListOf<RainAmountPlacement>()
        val rainCollisionBounds = initialCollisionBounds.toMutableList()
        val xFractions = listOf(0.15f, 0.3f, 0.45f, 0.6f, 0.75f)
        val yFractions = listOf(0.12f, 0.25f, 0.38f, 0.5f, 0.65f, 0.8f)
        val rainPadPx = dpToPx(4f)

        for (period in rainPeriods) {
            val amountText = formatPrecipAmount(period.totalAmountMm)
            val textWidth = measureText(amountText)
            val (textAscent, textDescent) = getTextBounds(amountText)

            var bestX: Float? = null
            var bestY: Float? = null
            var bestBounds: PrecipRect? = null
            var bestOverlapArea = Float.MAX_VALUE

            for (yFrac in yFractions) {
                for (xFrac in xFractions) {
                    val cx = (widthPx * xFrac).coerceIn(textWidth / 2f, widthPx - textWidth / 2f)
                    val cy = graphTop + graphHeight * yFrac
                    val candidateBounds = PrecipRect(
                        cx - textWidth / 2f,
                        cy + textAscent,
                        cx + textWidth / 2f,
                        cy + textDescent,
                    )
                    if (candidateBounds.top < graphTop || candidateBounds.bottom > graphBottom) continue

                    val paddedBounds = PrecipRect(
                        candidateBounds.left - rainPadPx,
                        candidateBounds.top - rainPadPx,
                        candidateBounds.right + rainPadPx,
                        candidateBounds.bottom + rainPadPx,
                    )
                    val overlapping = rainCollisionBounds.filter { it.intersects(paddedBounds) }
                    if (overlapping.isEmpty()) {
                        bestX = cx
                        bestY = cy
                        bestBounds = candidateBounds
                        bestOverlapArea = 0f
                        break
                    }
                    val overlapArea = overlapping.sumOf { existing ->
                        val intersectLeft = Math.max(existing.left, paddedBounds.left)
                        val intersectTop = Math.max(existing.top, paddedBounds.top)
                        val intersectRight = Math.min(existing.right, paddedBounds.right)
                        val intersectBottom = Math.min(existing.bottom, paddedBounds.bottom)
                        if (intersectLeft < intersectRight && intersectTop < intersectBottom) {
                            ((intersectRight - intersectLeft) * (intersectBottom - intersectTop)).toDouble()
                        } else 0.0
                    }
                    if (overlapArea < bestOverlapArea) {
                        bestOverlapArea = overlapArea.toFloat()
                        bestX = cx
                        bestY = cy
                        bestBounds = candidateBounds
                    }
                }
                if (bestOverlapArea == 0f) break
            }

            if (bestX != null && bestY != null && bestBounds != null) {
                placements.add(RainAmountPlacement(amountText, bestX, bestY, bestBounds, bestOverlapArea))
                val paddedTrackingBounds = PrecipRect(
                    bestBounds.left - rainPadPx,
                    bestBounds.top - rainPadPx,
                    bestBounds.right + rainPadPx,
                    bestBounds.bottom + rainPadPx,
                )
                rainCollisionBounds.add(paddedTrackingBounds)
            }
        }
        return placements
    }

    fun renderGraph(
        context: Context,
        hours: List<PrecipHourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        smoothIterations: Int = 2,
        hourLabelSpacingDp: Float = 28f,
        highProbThreshold: Int = 99,
        rainAmountWindowHours: Int = 0,
        job: Job? = null,
        onDebugLog: ((String) -> Unit)? = null,
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
        onHourIconDrawn: ((index: Int) -> Unit)? = null,
        onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)? = null,
        onWatermarkPlaced: ((WatermarkPlacementDebug) -> Unit)? = null,
    ): Bitmap {
        job?.ensureActive()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (hours.isEmpty()) {
            Log.w(TAG, "renderGraph: empty hours list, returning blank bitmap (${widthPx}x${heightPx})")
            return bitmap
        }

        val density = context.resources.displayMetrics.density
        val heightDp = heightPx / density
        val labelScale = bitmapScale.coerceAtMost(1f)
        val paints = ensurePaints(context, heightDp, labelScale)
        val showHourlyIcons = hours.any { it.iconRes != null } && widthPx >= MIN_ICON_GRAPH_WIDTH_PX

        val layout = calculateLayout(
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            currentTime = currentTime,
            bitmapScale = bitmapScale,
            smoothIterations = smoothIterations,
            highProbThreshold = highProbThreshold,
            rainAmountWindowHours = rainAmountWindowHours,
            showHourlyIcons = showHourlyIcons,
            measureProbabilityText = { paints.percentLabelPaint.measureText(it) },
            getProbabilityTextBounds = {
                val fm = paints.percentLabelPaint.fontMetrics
                (fm?.ascent ?: -paints.percentLabelPaint.textSize) to (fm?.descent ?: 0f)
            },
            measureRainAmountText = { paints.rainAmountPaint.measureText(it) },
            getRainAmountTextBounds = {
                val fm = paints.rainAmountPaint.fontMetrics
                (fm?.ascent ?: -paints.rainAmountPaint.textSize) to (fm?.descent ?: 0f)
            },
            dpToPx = { dpToPx(context, it) }
        )

        val hourWidth = widthPx.toFloat() / (hours.size - 1).coerceAtLeast(1)
        paints.gradientPaint.shader = LinearGradient(
            0f, layout.graphTop, 0f, layout.graphBottom,
            Color.parseColor("#445AC8FA"), Color.parseColor("#005AC8FA"), Shader.TileMode.CLAMP,
        )

        val (curvePath, fillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(layout.points, layout.graphBottom)
        canvas.drawPath(fillPath, paints.gradientPaint)
        canvas.drawPath(curvePath, paints.curvePaint)

        // --- Draw labels and current-time indicator ---
        val minHourLabelSpacing = dpToPx(context, hourLabelSpacingDp)
        val drawnIconBounds = mutableListOf<RectF>()

        GraphRenderUtils.drawHourLabels(
            canvas = canvas,
            items = hours,
            points = layout.points,
            widthPx = widthPx,
            heightPx = heightPx,
            minHourLabelSpacing = minHourLabelSpacing,
            hourLabelTextPaint = paints.hourLabelTextPaint,
            dpToPx = { dpToPx(context, it) },
            showLabel = { it.showLabel },
            labelText = { it.label },
        ) { index, clampedX ->
            if (!showHourlyIcons) return@drawHourLabels
            val hour = hours[index]
            val iconRes = hour.iconRes ?: return@drawHourLabels
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, iconRes) ?: return@drawHourLabels

            val iconSize = dpToPx(context, 22.4f).toInt()
            val iconY = layout.graphBottom + dpToPx(context, 0f)
            val iconX = clampedX - iconSize / 2f
            val iconRect = RectF(iconX, iconY, iconX + iconSize, iconY + iconSize)
            drawnIconBounds.add(iconRect)

            drawable.setBounds(iconRect.left.toInt(), iconRect.top.toInt(), iconRect.right.toInt(), iconRect.bottom.toInt())
            if (!hour.isRainy && !hour.isMixed) {
                val iconTint = when {
                    hour.isNight -> Color.parseColor("#BBBBBB")
                    hour.isTwilight -> Color.parseColor("#FFA726")
                    hour.isSunny -> Color.parseColor("#FFD60A")
                    else -> Color.parseColor("#BBBBBB")
                }
                drawable.setTint(iconTint)
            }
            drawable.draw(canvas)
            onHourIconDrawn?.invoke(index)
        }

        for (placement in layout.probabilityPlacements) {
            canvas.drawText(placement.text, placement.x, placement.baselineY, paints.percentLabelPaint)
            if (placement.index == hours.lastIndex) {
                val logMsg = "PLACED end label: ${placement.text} at right edge"
                Log.d("PrecipGraph", logMsg)
                onDebugLog?.invoke(logMsg)
            }
            onLabelPlaced?.invoke(placement.debug)
        }

        for (placement in layout.rainAmountPlacements) {
            canvas.drawText(placement.text, placement.x, placement.y, paints.rainAmountPaint)
            val logMsg = "rainAmountPlaced: \"${placement.text}\" at x=${placement.x} y=${placement.y} widgetSize=${widthPx}x${heightPx} overlapArea=${placement.overlapArea}"
            Log.d(TAG, logMsg)
            onDebugLog?.invoke(logMsg)
        }

        val drawnLabelBounds = (layout.probabilityPlacements.map { it.bounds.toRectF() } + layout.rainAmountPlacements.map { it.bounds.toRectF() }).toMutableList()

        // Day of week indicators
        val today = currentTime.toLocalDate()
        val leftDate  = hours.first().dateTime.toLocalDate()
        val rightDate = hours.last().dateTime.toLocalDate()
        val leftText  = hours.first().dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val rightText = hours.last().dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val leftTextWidth  = (if (leftDate  == today) paints.todayDayLabelPaint else paints.dayLabelTextPaint).measureText(leftText)
        val rightTextWidth = (if (rightDate == today) paints.todayDayLabelPaint else paints.dayLabelTextPaint).measureText(rightText)

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
            graphTop = layout.graphTop,
            graphBottom = layout.graphBottom,
            heightPx = heightPx,
            dayLabelTextPaint = paints.dayLabelTextPaint,
            todayDayLabelPaint = paints.todayDayLabelPaint,
            drawnLabelBounds = drawnLabelBounds,
            drawnIconBounds = drawnIconBounds,
            dpToPx = { dpToPx(context, it) },
            onDayLabelPlaced = if (onDayLabelPlaced != null) { side, text, date, x, y, placement, isToday ->
                onDayLabelPlaced.invoke(DayLabelPlacementDebug(side, text, date, x, y, placement, isToday))
            } else null,
        )

        GraphRenderUtils.drawNowIndicator(
            canvas = canvas,
            nowX = layout.nowX,
            graphTop = layout.graphTop,
            graphHeight = layout.graphHeight,
            currentTimePaint = paints.currentTimePaint,
            nowLabelTextPaint = paints.nowLabelTextPaint,
            dpToPx = { dpToPx(context, it) },
        )

        // Rain cloud icon watermark
        val rainDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_weather_rain)
        if (rainDrawable != null && layout.watermarkPlacement != null) {
            val placement = layout.watermarkPlacement
            val iconSizePx = dpToPx(context, 24f).toInt()
            rainDrawable.alpha = 96
            rainDrawable.setBounds(placement.x.toInt(), placement.y.toInt(), (placement.x + iconSizePx).toInt(), (placement.y + iconSizePx).toInt())
            rainDrawable.draw(canvas)
            onWatermarkPlaced?.invoke(placement)
        }
        return bitmap
    }

    private fun shouldShowHourlyIcons(widthPx: Int): Boolean {
        return widthPx >= MIN_ICON_GRAPH_WIDTH_PX
    }

    fun findHighProbRainPeriods(hours: List<PrecipHourData>, highProbThreshold: Int = 99): List<RainPeriod> {
        val periods = mutableListOf<RainPeriod>()
        var i = 0
        while (i < hours.size) {
            if (hours[i].precipProbability >= highProbThreshold) {
                val start = i
                var totalMm = 0f
                while (i < hours.size && hours[i].precipProbability >= highProbThreshold) {
                    totalMm += hours[i].precipAmountMm ?: 0f
                    i++
                }
                val end = i - 1
                if (totalMm > 0f) {
                    periods.add(
                        RainPeriod(
                            startIndex = start,
                            endIndex = end,
                            totalAmountMm = totalMm,
                            startLabel = hours[start].label,
                            endLabel = hours[end].label,
                        ),
                    )
                }
            } else {
                i++
            }
        }
        return periods
    }

    fun findFixedWindowRainPeriods(hours: List<PrecipHourData>, windowHours: Int): List<RainPeriod> {
        if (windowHours <= 0 || hours.size < windowHours) return emptyList()
        var bestPeriod: RainPeriod? = null
        var bestTotal = 0f
        var i = 0
        while (i <= hours.size - windowHours) {
            val window = hours.subList(i, i + windowHours)
            val totalMm = window.sumOf { (it.precipAmountMm ?: 0f).toDouble() }.toFloat()
            if (totalMm > bestTotal) {
                bestTotal = totalMm
                bestPeriod = RainPeriod(
                    startIndex = i,
                    endIndex = i + windowHours - 1,
                    totalAmountMm = totalMm,
                    startLabel = hours[i].label,
                    endLabel = hours[i + windowHours - 1].label,
                )
            }
            i++
        }
        return if (bestPeriod != null) listOf(bestPeriod) else emptyList()
    }

    private fun dpToPx(context: Context, dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}
