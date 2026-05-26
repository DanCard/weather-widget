package com.weatherwidget.widget

import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.weatherwidget.BuildConfig
import com.weatherwidget.util.WeatherConditionColors
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal object TemperatureLabelResolver {
    private const val TAG = "TempLabelResolver"
    private const val MAX_TEMP_LABEL_CANDIDATES = 6
    private val DENSE_TEMP_DIFF_THRESHOLDS = listOf(3, 4, 5)
    private const val MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES = 2.5f

    internal val ESSENTIAL_LABEL_ROLES: Set<TemperatureRole> = setOf(
        TemperatureRole.LOW,
        TemperatureRole.HIGH,
        TemperatureRole.FORECAST_LOW,
        TemperatureRole.FORECAST_HIGH,
        TemperatureRole.ACTUAL_LOW,
        TemperatureRole.ACTUAL_HIGH,
        TemperatureRole.PAST_FORECAST_LOW,
        TemperatureRole.PAST_FORECAST_HIGH,
        TemperatureRole.LOCAL,
        TemperatureRole.START,
        TemperatureRole.END,
        TemperatureRole.ACTUAL_END,
    )

    data class SuppressionResult(
        val suppressed: Boolean,
        val overriddenRole: TemperatureRole? = null,
    )

    data class CandidatePlacement(
        val sx: Float,
        val sy: Float,
        val label: String,
        val labelPaint: Paint,
        val textWidth: Float,
        val clampedX: Float,
        val isFuture: Boolean,
        val isValley: Boolean,
        val isEssential: Boolean,
        val leaderLinePaint: Paint,
    )

    fun computeExtremaIndices(
        hours: List<HourData>,
        transitionX: Float?,
        effectiveActualEndIndex: Int,
        fetchTime: LocalDateTime?,
    ): TemperatureExtrema.ExtremaIndices {
        val prominenceThreshold = when {
            hours.size <= 10 -> MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES
            hours.size <= 24 -> 1.5f // Narrow zoom: more detail, but still reject minor noise
            else -> MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES
        }
        return TemperatureExtrema.compute(hours, transitionX, effectiveActualEndIndex, fetchTime, prominenceThreshold)
    }

    fun collectLabelCandidates(
        hours: List<HourData>,
        extrema: TemperatureExtrema.ExtremaIndices,
        effectiveActualEndIndex: Int,
        transitionX: Float?,
        observedAt: Long?,
        numColumns: Int = 0,
    ): List<TempLabelCandidate> {
        val labelTemps = extrema.labelTemps
        val actualLabelTemps = extrema.actualLabelTemps

        val potentialAnchors = buildPotentialAnchors(extrema, hours.size)
        extrema.significantLocalExtrema.forEach { potentialAnchors.add(it to TemperatureRole.LOCAL) }

        val deduplicatedIndices = deduplicateAnchors(potentialAnchors, labelTemps, actualLabelTemps)
        val explicitAnchors = deduplicatedIndices.filter { idx ->
            potentialAnchors.any { it.first == idx && it.second != TemperatureRole.LOCAL }
        }.toSet()

        val filteredIndices = GraphLabelPlacementUtils.filterDenseLabelCandidates(
            items = labelTemps,
            candidates = deduplicatedIndices.toList(),
            globalMaxIdx = extrema.dailyHighIndex,
            globalMinIdx = extrema.dailyLowIndex,
            maxCandidates = MAX_TEMP_LABEL_CANDIDATES,
            diffThresholds = DENSE_TEMP_DIFF_THRESHOLDS,
            valueFunction = { it.roundToInt() },
            logTag = TAG,
            protectedIndices = deduplicatedIndices.filter { it in extrema.significantLocalExtrema && it > effectiveActualEndIndex }.toSet(),
            immovableIndices = explicitAnchors,
        )

        val suppressLeftEdgeLabel = GraphLabelPlacementUtils.shouldSuppressLeftEdgeLabel(
            items = labelTemps,
            candidates = filteredIndices,
            globalMaxIdx = extrema.dailyHighIndex,
            globalMinIdx = extrema.dailyLowIndex,
            valueFunction = { it.roundToInt() },
            nearbyWindow = min(5, (hours.lastIndex / 3).coerceAtLeast(1))
        )

        val finalIndices =
            if (numColumns >= 5 && filteredIndices.size == 2 && filteredIndices.containsAll(listOf(0, hours.lastIndex))) {
                val midIndex = hours.lastIndex / 2
                if (midIndex != 0 && midIndex != hours.lastIndex) {
                    (filteredIndices + midIndex).sorted()
                } else {
                    filteredIndices
                }
            } else {
                filteredIndices
            }

        val specialCandidates = mutableListOf<TempLabelCandidate>()
        val suppressedIndices = mutableSetOf<Int>()
        for (idx in finalIndices.distinct()) {
            var role = resolveExtremaRole(idx, extrema, hours)

            val leftEdgeResult = checkLeftEdgeSuppression(idx, role, suppressLeftEdgeLabel)
            if (leftEdgeResult.suppressed) {
                if (role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.HIGH || role == TemperatureRole.ACTUAL_LOW || role == TemperatureRole.LOW || role == TemperatureRole.ACTUAL_END || role == TemperatureRole.END) {
                    Log.d(TAG, "LabelSuppressed: role=$role idx=$idx reason=LEFT_EDGE")
                }
                suppressedIndices.add(idx)
                continue
            }

            val fetchResult = checkFetchDotSuppression(idx, role, extrema, observedAt, hours)
            if (fetchResult.suppressed) {
                if (role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.HIGH || role == TemperatureRole.ACTUAL_LOW || role == TemperatureRole.LOW || role == TemperatureRole.ACTUAL_END || role == TemperatureRole.END) {
                    Log.d(TAG, "LabelSuppressed: role=$role idx=$idx reason=FETCH_DOT")
                }
                suppressedIndices.add(idx)
                continue
            }
            fetchResult.overriddenRole?.let { role = it }

            if (checkRedundantPairSuppression(idx, role, extrema, suppressedIndices, labelTemps, actualLabelTemps)) {
                if (role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.HIGH || role == TemperatureRole.ACTUAL_LOW || role == TemperatureRole.LOW || role == TemperatureRole.ACTUAL_END || role == TemperatureRole.END) {
                    Log.d(TAG, "LabelSuppressed: role=$role idx=$idx reason=REDUNDANT")
                }
                suppressedIndices.add(idx)
                continue
            }

            if (checkTransitionBoundarySuppression(idx, role, effectiveActualEndIndex, transitionX, hours)) {
                if (role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.HIGH || role == TemperatureRole.ACTUAL_LOW || role == TemperatureRole.LOW || role == TemperatureRole.ACTUAL_END || role == TemperatureRole.END) {
                    Log.d(TAG, "LabelSuppressed: role=$role idx=$idx reason=TRANSITION")
                }
                suppressedIndices.add(idx)
                continue
            }

            if (checkEndpointSuppression(idx, role, hours)) {
                if (role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.HIGH || role == TemperatureRole.ACTUAL_LOW || role == TemperatureRole.LOW || role == TemperatureRole.ACTUAL_END || role == TemperatureRole.END) {
                    Log.d(TAG, "LabelSuppressed: role=$role idx=$idx reason=ENDPOINT")
                }
                suppressedIndices.add(idx)
                continue
            }

            val isActualRole = role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.ACTUAL_LOW || role == TemperatureRole.ACTUAL_END
            val forceForecast = role in listOf(TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW, TemperatureRole.LOCAL, TemperatureRole.START, TemperatureRole.END)
            val temps = if (isActualRole) actualLabelTemps else labelTemps

            if (role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.HIGH || role == TemperatureRole.ACTUAL_LOW || role == TemperatureRole.LOW || role == TemperatureRole.ACTUAL_END || role == TemperatureRole.END) {
                Log.d(TAG, "LabelAccepted: role=$role idx=$idx val=${temps[idx]}")
            }
            specialCandidates.add(TempLabelCandidate(idx, role, temps, hours[idx].temperature, forceForecast))
        }
        return specialCandidates
    }

    private fun resolveExtremaRole(
        idx: Int,
        extrema: TemperatureExtrema.ExtremaIndices,
        hours: List<HourData>,
    ): TemperatureRole = when (idx) {
        extrema.dailyHighIndex -> TemperatureRole.HIGH
        extrema.dailyLowIndex -> TemperatureRole.LOW
        0 -> TemperatureRole.START
        hours.lastIndex -> TemperatureRole.END
        extrema.actualHighIndex -> TemperatureRole.ACTUAL_HIGH
        extrema.actualLowIndex -> TemperatureRole.ACTUAL_LOW
        extrema.forecastHighIndex -> TemperatureRole.FORECAST_HIGH
        extrema.forecastLowIndex -> TemperatureRole.FORECAST_LOW
        extrema.pastForecastHighIndex -> TemperatureRole.PAST_FORECAST_HIGH
        extrema.pastForecastLowIndex -> TemperatureRole.PAST_FORECAST_LOW
        extrema.actualEndIndex -> TemperatureRole.ACTUAL_END
        else -> TemperatureRole.LOCAL
    }

    private fun buildPotentialAnchors(
        extrema: TemperatureExtrema.ExtremaIndices,
        hoursCount: Int,
    ): MutableList<Pair<Int, TemperatureRole>> {
        val anchors = mutableListOf<Pair<Int, TemperatureRole>>()
        if (extrema.dailyHighIndex >= 0) anchors.add(extrema.dailyHighIndex to TemperatureRole.HIGH)
        if (extrema.dailyLowIndex >= 0) anchors.add(extrema.dailyLowIndex to TemperatureRole.LOW)
        if (extrema.actualHighIndex >= 0) anchors.add(extrema.actualHighIndex to TemperatureRole.ACTUAL_HIGH)
        if (extrema.actualLowIndex >= 0) anchors.add(extrema.actualLowIndex to TemperatureRole.ACTUAL_LOW)
        if (extrema.forecastHighIndex >= 0) anchors.add(extrema.forecastHighIndex to TemperatureRole.FORECAST_HIGH)
        if (extrema.forecastLowIndex >= 0) anchors.add(extrema.forecastLowIndex to TemperatureRole.FORECAST_LOW)
        if (extrema.pastForecastHighIndex >= 0) anchors.add(extrema.pastForecastHighIndex to TemperatureRole.PAST_FORECAST_HIGH)
        if (extrema.pastForecastLowIndex >= 0) anchors.add(extrema.pastForecastLowIndex to TemperatureRole.PAST_FORECAST_LOW)
        if (extrema.actualEndIndex >= 0) anchors.add(extrema.actualEndIndex to TemperatureRole.ACTUAL_END)
        anchors.add(0 to TemperatureRole.START)
        if (hoursCount > 1) anchors.add(hoursCount - 1 to TemperatureRole.END)
        return anchors
    }

    private fun deduplicateAnchors(
        potentialAnchors: List<Pair<Int, TemperatureRole>>,
        labelTemps: List<Float>,
        actualLabelTemps: List<Float>,
    ): Set<Int> {
        val rolePriority = listOf(
            TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.START, TemperatureRole.END,
            TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_LOW, TemperatureRole.ACTUAL_END,
            TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW,
            TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW,
            TemperatureRole.LOCAL
        )
        val slotToAnchor = mutableMapOf<Triple<String, Int, Int>, Int>()
        for ((idx, role) in potentialAnchors) {
            val isActualRole = role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.ACTUAL_LOW || role == TemperatureRole.ACTUAL_END
            val temps = if (isActualRole) actualLabelTemps else labelTemps
            val v = temps[idx]
            val formattedValue = TemperatureGraphStyle.formatTemp(v)
            var first = idx; var last = idx
            while (first > 0 && temps[first - 1] == v) first--
            while (last < temps.lastIndex && temps[last + 1] == v) last++
            val slotKey = Triple(formattedValue, first, last)
            val existingIdx = slotToAnchor[slotKey]
            if (existingIdx == null) {
                slotToAnchor[slotKey] = idx
            } else {
                val existingRole = potentialAnchors.find { it.first == existingIdx }?.second ?: TemperatureRole.LOCAL
                val existingPriority = rolePriority.indexOf(existingRole).let { if (it == -1) Int.MAX_VALUE else it }
                val currentPriority = rolePriority.indexOf(role).let { if (it == -1) Int.MAX_VALUE else it }
                if (currentPriority < existingPriority) {
                    slotToAnchor[slotKey] = idx
                }
            }
        }
        return slotToAnchor.values.toSet()
    }

    private fun checkLeftEdgeSuppression(
        idx: Int,
        role: TemperatureRole,
        suppressLeftEdgeLabel: Boolean,
    ): SuppressionResult {
        val isBoundary = role == TemperatureRole.START || role == TemperatureRole.END
        if (idx == 0 && suppressLeftEdgeLabel && !isBoundary) {
            return SuppressionResult(true)
        }
        return SuppressionResult(false)
    }

    private fun checkFetchDotSuppression(
        idx: Int,
        role: TemperatureRole,
        extrema: TemperatureExtrema.ExtremaIndices,
        observedAt: Long?,
        hours: List<HourData>,
    ): SuppressionResult {
        if (idx != extrema.fetchIdx || observedAt == null) return SuppressionResult(false)
        if (idx == 0 || idx == hours.lastIndex) {
            return SuppressionResult(false, overriddenRole = if (idx == 0) TemperatureRole.START else TemperatureRole.END)
        }
        if (role in listOf(TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW)) {
            return SuppressionResult(false)
        }
        return SuppressionResult(true)
    }

    private fun checkRedundantPairSuppression(
        idx: Int,
        role: TemperatureRole,
        extrema: TemperatureExtrema.ExtremaIndices,
        suppressedIndices: Set<Int>,
        labelTemps: List<Float>,
        actualLabelTemps: List<Float>,
    ): Boolean {
        val redundantPairWindow = min(8, labelTemps.lastIndex / 5)
        val redundantValueThreshold = 2f

        // Relaxed thresholds for actuals to ensure they show up even when close to forecast extrema
        val actualRedundantWindow = min(4, labelTemps.lastIndex / 10)
        val actualRedundantThreshold = 1.0f

        return when (role) {
            TemperatureRole.ACTUAL_HIGH -> isRedundantNear(idx, role, extrema.dailyHighIndex, suppressedIndices, actualLabelTemps[idx], labelTemps[extrema.dailyHighIndex], actualRedundantWindow, actualRedundantThreshold, "HIGH")
            // The observed low is always worth its own label. It only resolves at an index distinct
            // from the daily low (when the global min IS an actual point, resolveExtremaRole returns
            // LOW first), so never treat it as redundant against a nearby forecast/daily low — the
            // two are stacked and ordered by value at placement time instead.
            TemperatureRole.ACTUAL_LOW -> false
            TemperatureRole.FORECAST_HIGH, TemperatureRole.PAST_FORECAST_HIGH -> isRedundantNear(idx, role, extrema.actualHighIndex, suppressedIndices, labelTemps[idx], actualLabelTemps[extrema.actualHighIndex], redundantPairWindow, redundantValueThreshold, "ACTUAL_HIGH")
            TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_LOW -> isRedundantNear(idx, role, extrema.actualLowIndex, suppressedIndices, labelTemps[idx], actualLabelTemps[extrema.actualLowIndex], redundantPairWindow, redundantValueThreshold, "ACTUAL_LOW")
            TemperatureRole.LOCAL, TemperatureRole.END, TemperatureRole.ACTUAL_END -> {
                val candidates = listOf(
                    extrema.dailyHighIndex, extrema.dailyLowIndex,
                    extrema.forecastHighIndex, extrema.forecastLowIndex,
                    extrema.pastForecastHighIndex, extrema.pastForecastLowIndex,
                    extrema.actualHighIndex, extrema.actualLowIndex,
                )
                candidates.any { tIdx ->
                    tIdx >= 0 && tIdx != idx && tIdx !in suppressedIndices &&
                        abs(idx - tIdx) <= redundantPairWindow &&
                        abs(labelTemps[idx] - labelTemps[tIdx]) < redundantValueThreshold
                }
            }
            else -> false
        }
    }

    private fun isRedundantNear(
        idx: Int,
        role: TemperatureRole,
        targetIdx: Int,
        suppressedIndices: Set<Int>,
        currentVal: Float,
        targetVal: Float,
        window: Int,
        threshold: Float,
        reasonSuffix: String
    ): Boolean {
        if (targetIdx >= 0 && targetIdx !in suppressedIndices && abs(idx - targetIdx) <= window) {
            if (abs(currentVal - targetVal) < threshold) {
                return true
            }
        }
        return false
    }

    private fun checkTransitionBoundarySuppression(
        idx: Int,
        role: TemperatureRole,
        effectiveActualEndIndex: Int,
        transitionX: Float?,
        hours: List<HourData>,
    ): Boolean {
        if (role !in listOf(TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW)) return false
        if (transitionX == null) return false
        val boundaryIdx = effectiveActualEndIndex
        val transitionWindow = min(3, hours.lastIndex / 20)
        if (boundaryIdx >= 0 && abs(idx - boundaryIdx) <= transitionWindow) {
            return true
        }
        return false
    }

    private fun checkEndpointSuppression(
        idx: Int,
        role: TemperatureRole,
        hours: List<HourData>,
    ): Boolean {
        // HIGH and LOW (global daily extrema) are the most important labels on the graph and
        // must never be dropped by edge-proximity decluttering — even if the daily high happens
        // to fall within the edgeWindow (e.g. the curve peaks at the right edge of the graph).
        val extremaRoles = listOf(TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW)
        if (role !in extremaRoles) return false
        val edgeWindow = when {
            hours.lastIndex > 50 -> min(5, hours.lastIndex / 15)
            hours.lastIndex > 24 -> min(8, hours.lastIndex / 6)
            hours.lastIndex > 10 -> 1
            else -> 0
        }
        val edgeDist = min(idx, hours.lastIndex - idx)
        val isEndpoint = idx == 0 || idx == hours.lastIndex
        if (edgeDist <= edgeWindow && !isEndpoint) {
            return true
        }
        return false
    }

    fun sortLabelCandidates(candidates: MutableList<TempLabelCandidate>) {
        candidates.sortWith(
            compareBy<TempLabelCandidate> {
                when (it.role) {
                    TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_LOW -> 0
                    TemperatureRole.LOCAL, TemperatureRole.ACTUAL_END -> 1
                    else -> 2 // START, END
                }
            }.thenBy {
                val displayTemp = it.labelTemps[it.index]
                val leftVal = findPrevDifferent(it.labelTemps, it.index)
                val rightVal = findNextDifferent(it.labelTemps, it.index)
                val isPeak = it.role in listOf(TemperatureRole.HIGH, TemperatureRole.FORECAST_HIGH, TemperatureRole.ACTUAL_HIGH, TemperatureRole.PAST_FORECAST_HIGH) || (it.role == TemperatureRole.LOCAL && displayTemp > leftVal && displayTemp > rightVal)
                if (isPeak) -displayTemp else displayTemp
            }
        )
    }

    private fun findPrevDifferent(temps: List<Float>, idx: Int): Float {
        val target = temps[idx]
        for (i in idx - 1 downTo 0) {
            if (temps[i] != target) return temps[i]
        }
        return target
    }

    private fun findNextDifferent(temps: List<Float>, idx: Int): Float {
        val target = temps[idx]
        for (i in idx + 1..temps.lastIndex) {
            if (temps[i] != target) return temps[i]
        }
        return target
    }

    fun resolveCandidatePlacement(
        ctx: RenderContext,
        hours: List<HourData>,
        candidate: TempLabelCandidate,
    ): CandidatePlacement? {
        val idx = candidate.index
        val temps = candidate.labelTemps
        val isFuture = candidate.forceForecastSeries || ctx.originalPoints[idx].first > (ctx.transitionX ?: -1f)
        val points = if (isFuture) ctx.forecastPoints else ctx.originalPoints
        val sx = if (candidate.role in listOf(TemperatureRole.LOW, TemperatureRole.HIGH, TemperatureRole.FORECAST_LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.LOCAL)) {
            centerOfRun(idx, temps, candidate.forceForecastSeries, ctx.originalPoints, ctx.forecastPoints, ctx.transitionX).first
        } else points[idx].first
        val sy = ctx.tempToY(temps[idx])

        val label = TemperatureGraphStyle.formatTemp(temps[idx]) + "°"
        val labelPaint = if (isFuture) {
            val hour = hours[idx.coerceAtMost(hours.lastIndex)]
            Paint(ctx.paints.forecastTempLabelTextPaint).also {
                it.color = com.weatherwidget.util.WeatherConditionColors.forecastColor(hour.isSunny, hour.isRainy, hour.isMixed, hour.isNight, hour.isTwilight)
            }
        } else ctx.paints.actualTempLabelTextPaint
        val textWidth = labelPaint.measureText(label)
        val clampedX = sx.coerceIn(textWidth / 2f, ctx.widthPx - textWidth / 2f)

        val fetchDotX = ctx.fetchDotX
        val lastObservedTemp = ctx.lastObservedTemp
        if (fetchDotX != null && lastObservedTemp != null && candidate.role !in setOf(TemperatureRole.START, TemperatureRole.END)) {
            val fetchDotLabel = TemperatureGraphStyle.formatTemp(lastObservedTemp) + "°"
            val dist = kotlin.math.abs(clampedX - fetchDotX)
            if (label == fetchDotLabel && dist < TemperatureGraphStyle.dpToPx(ctx.context, 12f)) {
                return null
            }
        }

        val leftVal = findPrevDifferent(temps, idx)
        val rightVal = findNextDifferent(temps, idx)
        val isValley = candidate.role in listOf(TemperatureRole.LOW, TemperatureRole.FORECAST_LOW, TemperatureRole.ACTUAL_LOW, TemperatureRole.PAST_FORECAST_LOW) || (candidate.role == TemperatureRole.LOCAL && temps[idx] < leftVal && temps[idx] < rightVal)
        val isEssential = candidate.role in ESSENTIAL_LABEL_ROLES

        val leaderLinePaint = if (isFuture) {
            Paint(ctx.paints.forecastLeaderLinePaint).also { it.color = TemperatureGraphStyle.withAlpha(labelPaint.color, 80) }
        } else ctx.paints.actualLeaderLinePaint

        return CandidatePlacement(sx, sy, label, labelPaint, textWidth, clampedX, isFuture, isValley, isEssential, leaderLinePaint)
    }

    private fun centerOfRun(idx: Int, temps: List<Float>, forceForecast: Boolean, original: List<Pair<Float, Float>>, forecast: List<Pair<Float, Float>>, transitionX: Float?): Pair<Float, Float> {
        val v = temps[idx]; var first = idx; var last = idx
        while (first > 0 && abs(temps[first - 1] - v) < 0.01f) first--
        while (last < temps.lastIndex && abs(temps[last + 1] - v) < 0.01f) last++
        val points = if (forceForecast || original[idx].first > (transitionX ?: -1f)) forecast else original
        return (points[first].first + points[last].first) / 2f to (points[first].second + points[last].second) / 2f
    }
}
