package com.weatherwidget.widget

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import android.content.Context
import android.graphics.*
import android.util.Log
import com.weatherwidget.R
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import com.weatherwidget.widget.handlers.formatPrecipAmount
import kotlin.math.abs
import kotlin.math.roundToInt

object PrecipitationGraphRenderer {

    private const val TAG = "PrecipGraphRenderer"
    private const val MAX_PRECIP_LABEL_CANDIDATES = HourlyGraphDefaults.MAX_LABEL_CANDIDATES
    private val DENSE_LABEL_DIFF_THRESHOLDS = HourlyGraphDefaults.DENSE_LABEL_DIFF_THRESHOLDS

    private const val COLOR_CURVE_FILL_TOP = "#445AC8FA"
    private const val COLOR_CURVE_FILL_BOTTOM = "#005AC8FA"

    private const val GRAPH_TOP_PADDING_DP = 44f
    private const val FAR_OUT_DATA_HOURS_THRESHOLD = 72L
    private const val Y_SCALE_HEADROOM_FACTOR = 1.15f
    private const val Y_SCALE_MIN = 10f
    private const val SOFT_DIP_MAX_PROBABILITY = 65
    private const val SOFT_DIP_MIN_ELEVATION = 8
    private const val NEAR_CENTER_FRACTION = 0.2f
    private const val DEFAULT_PREFER_BELOW_THRESHOLD = 50
    private const val LOW_PREFER_BELOW_MAX_PROBABILITY = 55
    private const val ELEVATED_PEAK_CROWD_WINDOW = 6
    private const val ELEVATED_PEAK_MIN_DELTA = 10
    private val ELEVATED_PEAK_PROBABILITY_RANGE = 55..85
    private const val RAIN_AMOUNT_PADDING_DP = 4f
    private const val NOW_LABEL_TEXT = "NOW"

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

    data class NowLabelPlacementDebug(
        val x: Float,
        val baselineY: Float,
        val bounds: PrecipRect,
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

    data class TextMeasurer(
        val measureProbabilityText: (String) -> Float,
        val getProbabilityTextBounds: (String) -> Pair<Float, Float>,
        val measureRainAmountText: (String) -> Float,
        val getRainAmountTextBounds: (String) -> Pair<Float, Float>,
        val dpToPx: (Float) -> Float,
        val measureNowText: (String) -> Float,
        val getNowTextBounds: (String) -> Pair<Float, Float>,
        val measureDayText: (String, Boolean) -> Float,
        val getDayTextBounds: (Boolean) -> Pair<Float, Float>,
    )

    data class PrecipGraphLayout(
        val points: List<Pair<Float, Float>>,
        val probabilityPlacements: List<ProbabilityLabelPlacement>,
        val rainAmountPlacements: List<RainAmountPlacement>,
        val graphTop: Float,
        val graphBottom: Float,
        val graphHeight: Float,
        val hourWidth: Float,
        val nowX: Float?,
        val labelSignal: List<Int>,
        val nowLabelPlacement: NowLabelPlacementDebug? = null,
        val dayLabelPlacements: List<DayLabelPlacementDebug> = emptyList(),
        val watermarkPlacement: WatermarkPlacementDebug? = null,
        val iconBounds: List<PrecipRect> = emptyList(),
    )

    fun calculateLayout(
        hours: List<PrecipHourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        smoothIterations: Int = 2,
        rainAmountWindowHours: Int = 0,
        showHourlyIcons: Boolean,
        textMeasurer: TextMeasurer,
        onDebugLog: ((String) -> Unit)? = null,
    ): PrecipGraphLayout {
        val labelScale = bitmapScale.coerceAtMost(1f)
        val topPadding = textMeasurer.dpToPx(GRAPH_TOP_PADDING_DP * labelScale)
        val iconSize = textMeasurer.dpToPx(HourlyGraphDefaults.WEATHER_ICON_SIZE_DP).toInt()
        val labelHeight = textMeasurer.dpToPx(HourlyGraphDefaults.BOTTOM_LABEL_HEIGHT_DP * labelScale)

        val graphTop = topPadding
        val graphBottom =
            if (showHourlyIcons) {
                heightPx - labelHeight - iconSize
            } else {
                heightPx - labelHeight
            }
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)
        val hourWidth = widthPx.toFloat() / (hours.size - 1).coerceAtLeast(1)

        val points = mutableListOf<Pair<Float, Float>>()
        val rawProbs = hours.map { it.precipProbability.coerceIn(0, 100).toFloat() }
        val isFarOutData = hours.isNotEmpty() && abs(
            Duration.between(hours[hours.size / 2].dateTime, currentTime).toHours()
        ) > FAR_OUT_DATA_HOURS_THRESHOLD

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
        val yScaleMax = (rawMax * Y_SCALE_HEADROOM_FACTOR).coerceAtLeast(Y_SCALE_MIN).coerceAtMost(100f)

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
            if (prob <= 0 || prob > SOFT_DIP_MAX_PROBABILITY) { jIdx++; continue }
            var runEnd = jIdx
            while (runEnd < labelSignal.lastIndex && labelSignal[runEnd + 1] == prob) runEnd++
            val left = (jIdx - HourlyGraphDefaults.SOFT_DIP_WINDOW_SIZE).coerceAtLeast(0)
            val right = (runEnd + HourlyGraphDefaults.SOFT_DIP_WINDOW_SIZE).coerceAtMost(labelSignal.lastIndex)
            if (left < jIdx && right > runEnd) {
                val leftMax = (left until jIdx).maxOfOrNull { labelSignal[it] } ?: prob
                val rightMax = ((runEnd + 1)..right).maxOfOrNull { labelSignal[it] } ?: prob
                if (leftMax >= prob + SOFT_DIP_MIN_ELEVATION && rightMax >= prob + SOFT_DIP_MIN_ELEVATION) softDipCandidates.add(jIdx + (runEnd - jIdx) / 2)
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
            nearbyWindow = HourlyGraphDefaults.LABEL_FILTER_NEARBY_WINDOW,
            immovableIndices = buildSet {
                if (hours.isNotEmpty()) {
                    add(0)
                    add(hours.lastIndex)
                }
            },
        )

        val suppressLeftEdgeLabel = GraphLabelPlacementUtils.shouldSuppressLeftEdgeLabel(
            items = labelSignal,
            candidates = filteredCandidates,
            globalMaxIdx = globalMaxIdx,
            globalMinIdx = globalMinIdx,
            valueFunction = { v -> v },
        )

        val finalCandidates =
            if (filteredCandidates.size == 2 && filteredCandidates == listOf(0, hours.lastIndex)) {
                val midIndex = hours.lastIndex / 2
                val midValue = labelSignal.getOrNull(midIndex) ?: 0
                val leftValue = labelSignal.firstOrNull()
                val rightValue = labelSignal.lastOrNull()
                if (
                    midIndex != 0 &&
                    midIndex != hours.lastIndex &&
                    midValue > 0 &&
                    midValue != leftValue &&
                    midValue != rightValue
                ) {
                    (filteredCandidates + midIndex).sorted()
                } else {
                    filteredCandidates
                }
            } else {
                filteredCandidates
            }

        // Pre-calculate icon bounds for collision detection
        val drawnIconBounds = mutableListOf<PrecipRect>()
        if (showHourlyIcons) {
            hours.forEachIndexed { index, hour ->
                if (hour.iconRes != null) {
                    val x = hourWidth * index
                    val clampedX = x.coerceIn(iconSize / 2f, widthPx - iconSize / 2f)
                    val iconY = graphBottom
                    val iconX = clampedX - iconSize / 2f
                    drawnIconBounds.add(PrecipRect(iconX, iconY, iconX + iconSize, iconY + iconSize))
                }
            }
        }

        val probabilityPlacements = calculateProbabilityLabelPlacements(
            labelSignal = labelSignal,
            hours = hours,
            points = points,
            geometry = GraphGeometry(widthPx, heightPx, graphTop, graphBottom, graphHeight),
            globalMaxIdx = globalMaxIdx,
            globalMinIdx = globalMinIdx,
            firstPositive = firstPositive,
            firstLabeledPositive = firstLabeledPositive,
            softDipCandidates = softDipCandidates,
            filteredCandidates = finalCandidates,
            suppressLeftEdgeLabel = suppressLeftEdgeLabel,
            drawnIconBounds = drawnIconBounds,
            measureText = textMeasurer.measureProbabilityText,
            getTextBounds = textMeasurer.getProbabilityTextBounds,
            dpToPx = textMeasurer.dpToPx,
            onDebugLog = onDebugLog,
        )

        val rainPeriods = if (rainAmountWindowHours > 0) {
            findFixedWindowRainPeriods(hours, rainAmountWindowHours)
        } else {
            findVisibleWindowRainPeriods(hours)
        }
        val rainCollisionBounds = probabilityPlacements.map { it.bounds }.toMutableList()

        val nowLabelPlacement = if (nowX != null) {
            val nowText = NOW_LABEL_TEXT
            val nowTextWidth = textMeasurer.measureNowText(nowText)
            val (nowTextAscent, nowTextDescent) = textMeasurer.getNowTextBounds(nowText)
            GraphRenderUtils.computeNowLabelBounds(
                nowX = nowX,
                graphTop = graphTop,
                graphHeight = graphHeight,
                textWidth = nowTextWidth,
                fontAscent = nowTextAscent,
                fontDescent = nowTextDescent,
                drawnBounds = rainCollisionBounds.map { it.toRectF() },
                dpToPx = textMeasurer.dpToPx,
            )?.let {
                val bounds = PrecipRect.fromRectF(it.bounds)
                rainCollisionBounds.add(bounds)
                NowLabelPlacementDebug(x = nowX, baselineY = it.labelY, bounds = bounds)
            }
        } else {
            null
        }

        val rainPlacements = calculateRainAmountPlacements(
            rainPeriods = rainPeriods,
            geometry = GraphGeometry(widthPx, heightPx, graphTop, graphBottom, graphHeight),
            initialCollisionBounds = rainCollisionBounds,
            measureText = textMeasurer.measureRainAmountText,
            getTextBounds = textMeasurer.getRainAmountTextBounds,
            dpToPx = textMeasurer.dpToPx
        )

        val overlayBounds = (probabilityPlacements.map { it.bounds } + rainPlacements.map { it.bounds }).toMutableList()
        nowLabelPlacement?.let { overlayBounds.add(it.bounds) }

        val today = currentTime.toLocalDate()
        val leftDate = hours.first().dateTime.toLocalDate()
        val rightDate = hours.last().dateTime.toLocalDate()
        val leftText = hours.first().dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val rightText = hours.last().dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val dayLabelFontMetrics = textMeasurer.getDayTextBounds(false)
        val todayLabelFontMetrics = textMeasurer.getDayTextBounds(true)
        val computedDayPlacements = GraphRenderUtils.computeDayLabelPlacements(
            leftDate = leftDate,
            rightDate = rightDate,
            leftText = leftText,
            rightText = rightText,
            leftX = textMeasurer.measureDayText(leftText, leftDate == today) / 2f,
            rightX = widthPx - textMeasurer.measureDayText(rightText, rightDate == today) / 2f,
            today = today,
            graphTop = graphTop,
            graphBottom = graphBottom,
            heightPx = heightPx,
            dayLabelFontMetrics = dayLabelFontMetrics,
            todayLabelFontMetrics = todayLabelFontMetrics,
            measureDayText = textMeasurer.measureDayText,
            drawnLabelBounds = overlayBounds.map { it.toRectF() },
            drawnIconBounds = drawnIconBounds.map { it.toRectF() },
            dpToPx = textMeasurer.dpToPx,
        )
        val dayPlacements = computedDayPlacements.map {
            DayLabelPlacementDebug(
                side = it.side,
                dayText = it.text,
                date = it.date,
                x = it.x,
                y = it.y,
                placement = it.placement,
                isToday = it.isToday,
            )
        }
        overlayBounds.addAll(computedDayPlacements.map { PrecipRect.fromRectF(it.bounds) })

        var watermarkPlacement: WatermarkPlacementDebug? = null
        if (hours.size >= HourlyGraphDefaults.WATERMARK_MIN_HOURS) {
            val iconSizePx = textMeasurer.dpToPx(HourlyGraphDefaults.WATERMARK_ICON_SIZE_DP).toInt()
            val halfIcon = iconSizePx / 2f
            val xFractions = HourlyGraphDefaults.OVERLAY_X_FRACTIONS
            val yFractions = HourlyGraphDefaults.OVERLAY_Y_FRACTIONS

            var placed = false
            for (yFrac in yFractions) {
                for (xFrac in xFractions) {
                    val cx = widthPx * xFrac
                    val cy = graphTop + graphHeight * yFrac
                    val bounds = PrecipRect(cx - halfIcon, cy - halfIcon, cx + halfIcon, cy + halfIcon)
                    if (bounds.left < 0f || bounds.right > widthPx) continue
                    if (bounds.top < graphTop || bounds.bottom > graphBottom) continue
                    if (overlayBounds.any { it.intersects(bounds) }) continue

                    watermarkPlacement = WatermarkPlacementDebug(x = bounds.left, y = bounds.top, xFrac = xFrac, yFrac = yFrac)
                    placed = true
                    break
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
            hourWidth = hourWidth,
            nowX = nowX,
            labelSignal = labelSignal,
            nowLabelPlacement = nowLabelPlacement,
            dayLabelPlacements = dayPlacements,
            watermarkPlacement = watermarkPlacement,
            iconBounds = drawnIconBounds,
        )
    }

    private fun ensurePaints(context: Context, heightDp: Float, labelScale: Float) =
        PrecipitationGraphStyle.ensurePaints(context, heightDp, labelScale)

    data class GraphGeometry(
        val widthPx: Int,
        val heightPx: Int,
        val graphTop: Float,
        val graphBottom: Float,
        val graphHeight: Float,
    )

    internal fun calculateProbabilityLabelPlacements(
        labelSignal: List<Int>,
        hours: List<PrecipHourData>,
        points: List<Pair<Float, Float>>,
        geometry: GraphGeometry,
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
        dpToPx: (Float) -> Float,
        onDebugLog: ((String) -> Unit)? = null,
    ): List<ProbabilityLabelPlacement> {
        val placements = mutableListOf<ProbabilityLabelPlacement>()
        val drawnLabelBounds = mutableListOf<PrecipRect>()

        val normalGap = GraphLabelPlacementUtils.getLabelGapDp(isFallback = false)
        val fallbackGap = GraphLabelPlacementUtils.getLabelGapDp(isFallback = true)
        val gapPxAboveNormal = dpToPx(normalGap.aboveDp)
        val gapPxBelowNormal = dpToPx(normalGap.belowDp)
        val gapPxAboveFallback = dpToPx(fallbackGap.aboveDp)
        val gapPxBelowFallback = dpToPx(fallbackGap.belowDp)
        val safeBottom = geometry.graphBottom - dpToPx(HourlyGraphDefaults.LABEL_SAFE_BOTTOM_INSET_DP)

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
            
            val graphMidY = (geometry.graphTop + geometry.graphBottom) / 2f
            val isNearGraphCenter = abs(y - graphMidY) <= geometry.graphHeight * NEAR_CENTER_FRACTION
            val isNearRightEdge = index >= labelSignal.lastIndex - 1
            val isTrendingDownAtRightEdge = index > 0 && points[index].second > points[index - 1].second + HourlyGraphDefaults.TRENDING_THRESHOLD_PX
            val isTrendingUpAtRightEdge = index > 0 && points[index].second < points[index - 1].second - HourlyGraphDefaults.TRENDING_THRESHOLD_PX
            val isFirstRising = (index == firstPositive || index == firstLabeledPositive) && prob > 0
            
            val preferBelow = when {
                isFirstRising -> true
                isPeak -> false
                isValley || isSoftDip -> true
                isNearRightEdge && isTrendingDownAtRightEdge -> true
                isNearRightEdge && isTrendingUpAtRightEdge -> false
                else -> prob > DEFAULT_PREFER_BELOW_THRESHOLD
            }
            val directions = if (preferBelow) listOf(false, true) else listOf(true, false)

            for ((attemptIndex, placeAbove) in directions.withIndex()) {
                val isFallbackAttempt = attemptIndex > 0
                val gapPx = when {
                    placeAbove && !isFallbackAttempt -> gapPxAboveNormal
                    !placeAbove && !isFallbackAttempt -> gapPxBelowNormal
                    placeAbove -> gapPxAboveFallback
                    else -> gapPxBelowFallback
                }
                val x = centerX.coerceIn(textWidth / 2f, geometry.widthPx - textWidth / 2f)
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

                val exceedsTop = bounds.top < 0f
                val exceedsBottom = bounds.bottom > safeBottom

                val isLowPreferredBelow = !placeAbove && prob <= LOW_PREFER_BELOW_MAX_PROBABILITY
                val actualExceedsBottom = if (isLowPreferredBelow) bounds.bottom > geometry.heightPx else exceedsBottom

                if (exceedsTop || actualExceedsBottom) continue

                val overlapsLabel = drawnLabelBounds.any { it.intersects(bounds) }
                val overlapsIcon = drawnIconBounds.any { it.intersects(bounds) }
                val hasCollision = overlapsLabel || overlapsIcon

                if (hasCollision) continue

                val dipBelowRuleApplied = (isValley || isSoftDip) && !placeAbove && isNearGraphCenter
                val crowdWindow = ELEVATED_PEAK_CROWD_WINDOW
                val nearbyLowerCandidates = filteredCandidates.filter { cid ->
                    cid != index && abs(cid - index) <= crowdWindow && labelSignal[cid] <= prob - ELEVATED_PEAK_MIN_DELTA
                }
                val hasLowerNeighbors = nearbyLowerCandidates.any { it < index } && nearbyLowerCandidates.any { it > index }
                val elevatedPeakRuleApplied = isPeak && placeAbove && prob in ELEVATED_PEAK_PROBABILITY_RANGE && hasLowerNeighbors

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
                
                val logMsg = "PLACED prob label: \"$labelText\" at x=$x baselineY=$baselineY " +
                    "above=$placeAbove reason=$reason gapPx=$gapPx prob=$prob index=$index"
                Log.d(TAG, logMsg)
                onDebugLog?.invoke(logMsg)

                placements.add(ProbabilityLabelPlacement(index, labelText, x, baselineY, bounds, debug))
                drawnLabelBounds.add(bounds)
                break
            }
        }
        return placements
    }

    internal fun calculateRainAmountPlacements(
        rainPeriods: List<RainPeriod>,
        geometry: GraphGeometry,
        initialCollisionBounds: List<PrecipRect>,
        measureText: (String) -> Float,
        getTextBounds: (String) -> Pair<Float, Float>,
        dpToPx: (Float) -> Float
    ): List<RainAmountPlacement> {
        val placements = mutableListOf<RainAmountPlacement>()
        val rainCollisionBounds = initialCollisionBounds.toMutableList()
        val xFractions = HourlyGraphDefaults.OVERLAY_X_FRACTIONS
        val yFractions = HourlyGraphDefaults.OVERLAY_Y_FRACTIONS
        val rainPadPx = dpToPx(RAIN_AMOUNT_PADDING_DP)

        for (period in rainPeriods) {
            val amountText = formatPrecipAmount(period.totalAmountMm)
            val textWidth = measureText(amountText)
            val (textAscent, textDescent) = getTextBounds(amountText)

            var best: RainCandidate? = null

            outer@ for (yFrac in yFractions) {
                for (xFrac in xFractions) {
                    val cx = (geometry.widthPx * xFrac).coerceIn(textWidth / 2f, geometry.widthPx - textWidth / 2f)
                    val cy = geometry.graphTop + geometry.graphHeight * yFrac
                    val candidateBounds = PrecipRect(
                        cx - textWidth / 2f,
                        cy + textAscent,
                        cx + textWidth / 2f,
                        cy + textDescent,
                    )
                    if (candidateBounds.top < geometry.graphTop || candidateBounds.bottom > geometry.graphBottom) continue

                    val paddedBounds = PrecipRect(
                        candidateBounds.left - rainPadPx,
                        candidateBounds.top - rainPadPx,
                        candidateBounds.right + rainPadPx,
                        candidateBounds.bottom + rainPadPx,
                    )
                    val overlapping = rainCollisionBounds.filter { it.intersects(paddedBounds) }
                    if (overlapping.isEmpty()) {
                        best = RainCandidate(cx, cy, candidateBounds, 0f)
                        break@outer
                    }
                    val overlapArea = overlapping.sumOf { existing ->
                        val intersectLeft = maxOf(existing.left, paddedBounds.left)
                        val intersectTop = maxOf(existing.top, paddedBounds.top)
                        val intersectRight = minOf(existing.right, paddedBounds.right)
                        val intersectBottom = minOf(existing.bottom, paddedBounds.bottom)
                        if (intersectLeft < intersectRight && intersectTop < intersectBottom) {
                            ((intersectRight - intersectLeft) * (intersectBottom - intersectTop)).toDouble()
                        } else 0.0
                    }.toFloat()
                    if (overlapArea < (best?.overlapArea ?: Float.MAX_VALUE)) {
                        best = RainCandidate(cx, cy, candidateBounds, overlapArea)
                    }
                }
            }

            best?.let { b ->
                placements.add(RainAmountPlacement(amountText, b.x, b.y, b.bounds, b.overlapArea))
                rainCollisionBounds.add(
                    PrecipRect(
                        b.bounds.left - rainPadPx,
                        b.bounds.top - rainPadPx,
                        b.bounds.right + rainPadPx,
                        b.bounds.bottom + rainPadPx,
                    )
                )
            }
        }
        return placements
    }

    private data class RainCandidate(
        val x: Float,
        val y: Float,
        val bounds: PrecipRect,
        val overlapArea: Float,
    )

    fun renderGraph(
        context: Context,
        hours: List<PrecipHourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        smoothIterations: Int = 2,
        hourLabelSpacingDp: Float = HourlyGraphDefaults.DEFAULT_HOUR_LABEL_SPACING_DP,
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
        val showHourlyIcons = hours.any { it.iconRes != null } && widthPx >= HourlyGraphDefaults.MIN_ICON_GRAPH_WIDTH_PX

        val textMeasurer = TextMeasurer(
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
            dpToPx = { dpToPx(context, it) },
            measureNowText = { paints.nowLabelTextPaint.measureText(it) },
            getNowTextBounds = {
                val fm = paints.nowLabelTextPaint.fontMetrics
                (fm?.ascent ?: -paints.nowLabelTextPaint.textSize) to (fm?.descent ?: 0f)
            },
            measureDayText = { text, isToday ->
                (if (isToday) paints.todayDayLabelPaint else paints.dayLabelTextPaint).measureText(text)
            },
            getDayTextBounds = { isToday ->
                val paint = if (isToday) paints.todayDayLabelPaint else paints.dayLabelTextPaint
                val fm = paint.fontMetrics
                (fm?.ascent ?: -paint.textSize) to (fm?.descent ?: 0f)
            },
        )

        val layout = calculateLayout(
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            currentTime = currentTime,
            bitmapScale = bitmapScale,
            smoothIterations = smoothIterations,
            rainAmountWindowHours = rainAmountWindowHours,
            showHourlyIcons = showHourlyIcons,
            textMeasurer = textMeasurer,
            onDebugLog = onDebugLog,
        )

        paints.gradientPaint.shader = LinearGradient(
            0f, layout.graphTop, 0f, layout.graphBottom,
            Color.parseColor(COLOR_CURVE_FILL_TOP), Color.parseColor(COLOR_CURVE_FILL_BOTTOM), Shader.TileMode.CLAMP,
        )

        val (curvePath, fillPath) = GraphRenderUtils.buildSmoothCurveAndFillPaths(layout.points, layout.graphBottom)
        canvas.drawPath(fillPath, paints.gradientPaint)
        canvas.drawPath(curvePath, paints.curvePaint)

        // --- Draw labels and current-time indicator ---
        val minHourLabelSpacing = dpToPx(context, hourLabelSpacingDp)

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

            val iconSize = dpToPx(context, HourlyGraphDefaults.WEATHER_ICON_SIZE_DP).toInt()
            val iconY = layout.graphBottom
            val iconX = clampedX - iconSize / 2f

            drawable.setBounds(iconX.toInt(), iconY.toInt(), (iconX + iconSize).toInt(), (iconY + iconSize).toInt())
            if (!hour.isRainy && !hour.isMixed) {
                val iconTint = when {
                    hour.isNight -> Color.parseColor(HourlyGraphDefaults.ICON_TINT_NIGHT)
                    hour.isTwilight -> Color.parseColor(HourlyGraphDefaults.ICON_TINT_TWILIGHT)
                    hour.isSunny -> Color.parseColor(HourlyGraphDefaults.ICON_TINT_SUNNY)
                    else -> Color.parseColor(HourlyGraphDefaults.ICON_TINT_DEFAULT)
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
                Log.d(TAG, logMsg)
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

        val lineHeight = layout.graphHeight * HourlyGraphDefaults.NOW_LINE_HEIGHT_FRACTION
        val lineTop = layout.graphTop + (layout.graphHeight - lineHeight) / 2f
        val lineBottom = lineTop + lineHeight
        layout.nowX?.let { nowX ->
            canvas.drawLines(floatArrayOf(nowX, lineTop, nowX, lineBottom), paints.currentTimePaint)
        }
        layout.nowLabelPlacement?.let { placement ->
            canvas.drawText(NOW_LABEL_TEXT, placement.x, placement.baselineY, paints.nowLabelTextPaint)
            drawnLabelBounds.add(placement.bounds.toRectF())
        }

        for (placement in layout.dayLabelPlacements) {
            val paint = if (placement.isToday) paints.todayDayLabelPaint else paints.dayLabelTextPaint
            canvas.drawText(placement.dayText, placement.x, placement.y, paint)
            onDayLabelPlaced?.invoke(placement)
        }

        // Rain cloud icon watermark
        val rainDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_weather_rain)
        if (rainDrawable != null && layout.watermarkPlacement != null) {
            val placement = layout.watermarkPlacement
            val iconSizePx = dpToPx(context, HourlyGraphDefaults.WATERMARK_ICON_SIZE_DP).toInt()
            rainDrawable.alpha = HourlyGraphDefaults.WATERMARK_ALPHA
            rainDrawable.setBounds(placement.x.toInt(), placement.y.toInt(), (placement.x + iconSizePx).toInt(), (placement.y + iconSizePx).toInt())
            rainDrawable.draw(canvas)
            onWatermarkPlaced?.invoke(placement)
        }
        return bitmap
    }

    internal fun findVisibleWindowRainPeriods(hours: List<PrecipHourData>): List<RainPeriod> {
        if (hours.isEmpty()) return emptyList()
        val totalMm = hours.sumOf { (it.precipAmountMm ?: 0f).toDouble() }.toFloat()
        if (totalMm <= 0f) return emptyList()
        return listOf(
            RainPeriod(
                startIndex = 0,
                endIndex = hours.lastIndex,
                totalAmountMm = totalMm,
                startLabel = hours.first().label,
                endLabel = hours.last().label,
            ),
        )
    }

    internal fun findFixedWindowRainPeriods(hours: List<PrecipHourData>, windowHours: Int): List<RainPeriod> {
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
        PrecipitationGraphStyle.dpToPx(context, dp)
}
