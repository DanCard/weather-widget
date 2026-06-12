package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.Log
import kotlin.math.abs

object GraphLabelPlacementUtils {

    const val NEARBY_LABEL_WINDOW = 4
    const val PREFERRED_ABOVE_GAP_DP = 2f
    const val PREFERRED_BELOW_GAP_DP = 1f
    const val FALLBACK_ABOVE_GAP_DP = 2f
    const val FALLBACK_BELOW_GAP_DP = 4f

    enum class CandidateKind {
        GLOBAL_MAX,
        GLOBAL_MIN,
        PEAK,
        VALLEY,
        EDGE,
    }

    data class LabelGapDp(
        val aboveDp: Float,
        val belowDp: Float,
    )

    data class LabelVerticalPlacement(
        val baselineY: Float,
        val top: Float,
        val bottom: Float,
    )

    /**
     * Filters out nearby candidates that are too similar in value, prioritizing more significant extrema.
     */
    fun <T> filterDenseLabelCandidates(
        items: List<T>,
        candidates: List<Int>,
        globalMaxIdx: Int,
        globalMinIdx: Int,
        maxCandidates: Int,
        diffThresholds: List<Int>,
        valueFunction: (T) -> Int,
        logTag: String,
        protectedIndices: Set<Int> = emptySet(),
        nearbyWindow: Int = NEARBY_LABEL_WINDOW,
        immovableIndices: Set<Int> = emptySet(),
        // Indices that are retained (never removed) but must NOT absorb/remove other candidates.
        // Used for actual-series anchors: they display a different series than the forecast value
        // this thinning compares on, so they must not declutter a nearby forecast/LOCAL extreme.
        nonAbsorbingAnchors: Set<Int> = emptySet(),
    ): List<Int> {
        val retained = candidates.distinct().sorted().toMutableList()
        val immovableAnchors = buildSet {
            if (globalMaxIdx in items.indices) add(globalMaxIdx)
            addAll(immovableIndices.filter { it in items.indices })
        }
        val softProtectedAnchors = protectedIndices.filter { it in items.indices && it !in immovableAnchors }.toSet()

        // Add a mandatory small decluttering threshold if not already present
        val effectiveThresholds = if ((diffThresholds.firstOrNull() ?: 0) > 5) {
            listOf(5) + diffThresholds
        } else {
            diffThresholds
        }

        for (threshold in effectiveThresholds) {
            // If we are already under the cap AND this is a "reduction" threshold (not a decluttering one), break.
            if (retained.size <= maxCandidates && threshold > 5) break

            val toRemove = mutableSetOf<Int>()
            val processingOrder =
                retained
                    .filter { it !in immovableAnchors }
                    .sortedWith(
                        // Sort so that the candidates we are MORE LIKELY to remove come first.
                        // Higher priority integer = lower priority = process first.
                        // Optional candidates (base 10) process before soft protected (base 0).
                        compareByDescending<Int> { 
                            val base = if (it in softProtectedAnchors) 0 else 10
                            base + candidatePriority(it, items, globalMaxIdx, globalMinIdx, valueFunction)
                        }.thenBy { candidateStrength(it, items, globalMaxIdx, globalMinIdx, valueFunction) }
                        .thenByDescending { it }
                    )

            for (candidateIdx in processingOrder) {
                // Mandatory decluttering threshold (5) should always run to completion.
                // Thresholds > 5 are "reduction" thresholds and should respect maxCandidates.
                if (retained.size - toRemove.size <= maxCandidates && threshold > 5) break
                if (candidateIdx in toRemove) continue


                val nearbyRetained =
                    retained
                        .asSequence()
                        .filter { it != candidateIdx && it !in toRemove }
                        .filter { abs(it - candidateIdx) <= nearbyWindow }
                        .sortedWith(compareBy<Int> { abs(it - candidateIdx) }.thenBy { it })
                        .toList()

                if (nearbyRetained.isEmpty()) continue

                val item = items.getOrNull(candidateIdx) ?: continue
                val candidateValue = valueFunction(item)
                val candidatePriVal = (if (candidateIdx in softProtectedAnchors) 0 else 10) + 
                    candidatePriority(candidateIdx, items, globalMaxIdx, globalMinIdx, valueFunction)
                
                val competingRetained =
                    nearbyRetained.firstOrNull { otherIdx ->
                        if (otherIdx in nonAbsorbingAnchors) return@firstOrNull false
                        val otherItem = items.getOrNull(otherIdx) ?: return@firstOrNull false
                        val otherValue = valueFunction(otherItem)
                        val otherPriVal = (if (otherIdx in immovableAnchors || otherIdx in softProtectedAnchors) 0 else 10) + 
                            candidatePriority(otherIdx, items, globalMaxIdx, globalMinIdx, valueFunction)
                        
                        val valueDifference = abs(candidateValue - otherValue)
                        
                        // otherPriVal < candidatePriVal means 'other' has HIGHER priority.
                        valueDifference < threshold && (
                            otherPriVal < candidatePriVal ||
                                (otherPriVal == candidatePriVal && (
                                    candidateStrength(otherIdx, items, globalMaxIdx, globalMinIdx, valueFunction) >
                                    candidateStrength(candidateIdx, items, globalMaxIdx, globalMinIdx, valueFunction) ||
                                    (candidateStrength(otherIdx, items, globalMaxIdx, globalMinIdx, valueFunction) ==
                                        candidateStrength(candidateIdx, items, globalMaxIdx, globalMinIdx, valueFunction) &&
                                        otherIdx < candidateIdx)
                                ))
                            )
                    }

                if (competingRetained != null) {
                    val competingItem = items.getOrNull(competingRetained) ?: continue
                    val competingValue = valueFunction(competingItem)
                    val valueDifference = abs(candidateValue - competingValue)
                    toRemove.add(candidateIdx)
                    Log.d(
                        logTag,
                        "labelCandidateFiltered: idx=$candidateIdx value=$candidateValue nearestIdx=$competingRetained " +
                            "nearestValue=$competingValue diff=$valueDifference threshold=$threshold " +
                            "candidateKind=${candidateKind(candidateIdx, items, globalMaxIdx, globalMinIdx, valueFunction)} " +
                            "retainedKind=${candidateKind(competingRetained, items, globalMaxIdx, globalMinIdx, valueFunction)}",
                    )
                }
            }

            if (toRemove.isNotEmpty()) {
                retained.removeAll(toRemove)
            }
        }

        return retained
    }

    fun <T> candidateKind(
        index: Int,
        items: List<T>,
        globalMaxIdx: Int,
        globalMinIdx: Int,
        valueFunction: (T) -> Int,
    ): CandidateKind {
        if (index == globalMaxIdx) return CandidateKind.GLOBAL_MAX
        if (index == globalMinIdx) return CandidateKind.GLOBAL_MIN
        if (index == 0 || index == items.lastIndex) return CandidateKind.EDGE


        val prevValue = items.getOrNull(index - 1)?.let(valueFunction) ?: return CandidateKind.EDGE
        val currentValue = items.getOrNull(index)?.let(valueFunction) ?: return CandidateKind.EDGE
        val nextValue = items.getOrNull(index + 1)?.let(valueFunction) ?: return CandidateKind.EDGE

        return when {
            currentValue >= prevValue && currentValue >= nextValue && (currentValue > prevValue || currentValue > nextValue) -> CandidateKind.PEAK
            currentValue <= prevValue && currentValue <= nextValue && (currentValue < prevValue || currentValue < nextValue) -> CandidateKind.VALLEY
            else -> CandidateKind.EDGE
        }
    }

    fun <T> candidatePriority(
        index: Int,
        items: List<T>,
        globalMaxIdx: Int,
        globalMinIdx: Int,
        valueFunction: (T) -> Int,
    ): Int {
        val kind = candidateKind(index, items, globalMaxIdx, globalMinIdx, valueFunction)
        val value = items.getOrNull(index)?.let(valueFunction) ?: 0
        
        return when (kind) {
            CandidateKind.GLOBAL_MAX -> 0
            CandidateKind.PEAK -> if (value < 15) 3 else 1 // Peak is normally 1, but 3 if low value
            CandidateKind.GLOBAL_MIN -> 2
            CandidateKind.VALLEY -> if (value < 15) 1 else 3 // Valley is normally 3, but 1 if low value
            CandidateKind.EDGE -> 4
        }
    }

    fun <T> candidateStrength(
        index: Int,
        items: List<T>,
        globalMaxIdx: Int,
        globalMinIdx: Int,
        valueFunction: (T) -> Int,
    ): Int {
        val item = items.getOrNull(index) ?: return Int.MIN_VALUE
        val value = valueFunction(item)
        return when (candidateKind(index, items, globalMaxIdx, globalMinIdx, valueFunction)) {
            CandidateKind.GLOBAL_MAX, CandidateKind.PEAK -> value
            CandidateKind.GLOBAL_MIN, CandidateKind.VALLEY -> -value // Lower is stronger for valleys
            CandidateKind.EDGE -> value
        }
    }

    fun <T> shouldSuppressLeftEdgeLabel(
        items: List<T>,
        candidates: List<Int>,
        globalMaxIdx: Int,
        globalMinIdx: Int,
        valueFunction: (T) -> Int,
        nearbyWindow: Int = NEARBY_LABEL_WINDOW,
    ): Boolean {
        if (0 !in candidates || 0 == globalMaxIdx) return false

        val leftEdgeItem = items.getOrNull(0) ?: return false
        val leftEdgeValue = valueFunction(leftEdgeItem)
        
        // Suppress if ANY nearby candidate has a similar value (within 5%)
        return candidates.any { candidateIdx ->
            candidateIdx in 1..nearbyWindow &&
                abs(valueFunction(items[candidateIdx]) - leftEdgeValue) < 5
        }
    }

    fun getLabelGapDp(isFallback: Boolean): LabelGapDp =
        if (isFallback) {
            LabelGapDp(
                aboveDp = FALLBACK_ABOVE_GAP_DP,
                belowDp = FALLBACK_BELOW_GAP_DP,
            )
        } else {
            LabelGapDp(
                aboveDp = PREFERRED_ABOVE_GAP_DP,
                belowDp = PREFERRED_BELOW_GAP_DP,
            )
        }

    fun computeLabelVerticalPlacement(
        pointY: Float,
        placeAbove: Boolean,
        gapPx: Float,
        textAscent: Float,
        textDescent: Float,
    ): LabelVerticalPlacement {
        val baselineY =
            if (placeAbove) {
                pointY - gapPx - textDescent
            } else {
                pointY + gapPx - textAscent
            }

        return LabelVerticalPlacement(
            baselineY = baselineY,
            top = baselineY + textAscent,
            bottom = baselineY + textDescent,
        )
    }

    const val MINOR_OVERLAP_HEIGHT_RATIO = 0.45f
    const val MINOR_OVERLAP_ICON_RATIO = 0.85f
    const val VALLEY_HORIZONTAL_SHIFT_FRACTION = 0.5f
    const val VALLEY_BELOW_LABEL_OVERLAP_RATIO = 0.65f
    const val VALLEY_VS_VALLEY_OVERLAP_RATIO = 0.85f

    fun isMinorOverlapEligible(role: TemperatureRole): Boolean =
        role in setOf(
            TemperatureRole.LOW, TemperatureRole.HIGH,
            TemperatureRole.FORECAST_LOW, TemperatureRole.FORECAST_HIGH,
            TemperatureRole.ACTUAL_LOW, TemperatureRole.ACTUAL_HIGH,
            TemperatureRole.PAST_FORECAST_LOW, TemperatureRole.PAST_FORECAST_HIGH,
            TemperatureRole.START, TemperatureRole.END,
            TemperatureRole.LOCAL
        )

    fun shouldAllowMinorOverlap(
        role: TemperatureRole,
        overlapHeight: Float,
        labelHeight: Float
    ): Boolean = isMinorOverlapEligible(role) && overlapHeight <= labelHeight * MINOR_OVERLAP_HEIGHT_RATIO

    fun maxVerticalOverlap(
        bounds: GraphRect,
        existingBounds: List<GraphRect>,
    ): Float {
        return existingBounds.maxOfOrNull { existing ->
            val l = maxOf(existing.left, bounds.left)
            val r = minOf(existing.right, bounds.right)
            val t = maxOf(existing.top, bounds.top)
            val b = minOf(existing.bottom, bounds.bottom)
            if (l < r && t < b) {
                b - t
            } else {
                0f
            }
        } ?: 0f
    }
}
