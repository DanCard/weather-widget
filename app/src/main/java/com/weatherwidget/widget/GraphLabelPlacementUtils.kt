package com.weatherwidget.widget

import android.graphics.RectF
import android.util.Log
import kotlin.math.abs

object GraphLabelPlacementUtils {

    const val NEARBY_LABEL_WINDOW = 3
    const val PREFERRED_ABOVE_GAP_DP = 2f
    const val PREFERRED_BELOW_GAP_DP = 2f
    const val FALLBACK_ABOVE_GAP_DP = 8f
    const val FALLBACK_BELOW_GAP_DP = 14f

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
    ): List<Int> {
        if (candidates.size <= maxCandidates) {
            return candidates.sorted()
        }

        val retained = candidates.distinct().sorted().toMutableList()
        val protectedAnchors = buildSet {
            if (globalMaxIdx in items.indices) add(globalMaxIdx)
            if (globalMinIdx in items.indices) add(globalMinIdx)
            addAll(protectedIndices.filter { it in items.indices })
        }

        for (threshold in diffThresholds) {
            if (retained.size <= maxCandidates) break

            val toRemove = mutableSetOf<Int>()
            val optionalCandidates =
                retained
                    .filter { it !in protectedAnchors }
                    .sortedWith(
                        compareByDescending<Int> { candidatePriority(it, items, globalMaxIdx, globalMinIdx, valueFunction) }
                            .thenBy { candidateStrength(it, items, globalMaxIdx, globalMinIdx, valueFunction) }
                            .thenByDescending { it },
                    )
            for (candidateIdx in optionalCandidates) {
                if (retained.size - toRemove.size <= maxCandidates) break

                val nearbyRetained =
                    retained
                        .asSequence()
                        .filter { it != candidateIdx && it !in toRemove }
                        .filter { abs(it - candidateIdx) <= NEARBY_LABEL_WINDOW }
                        .sortedWith(compareBy<Int> { abs(it - candidateIdx) }.thenBy { it })
                        .toList()

                if (nearbyRetained.isEmpty()) continue

                val item = items.getOrNull(candidateIdx) ?: continue
                val candidateValue = valueFunction(item)
                val candidatePriority = candidatePriority(candidateIdx, items, globalMaxIdx, globalMinIdx, valueFunction)
                
                val competingRetained =
                    nearbyRetained.firstOrNull { otherIdx ->
                        val otherItem = items.getOrNull(otherIdx) ?: return@firstOrNull false
                        val otherValue = valueFunction(otherItem)
                        val otherPriority = candidatePriority(otherIdx, items, globalMaxIdx, globalMinIdx, valueFunction)
                        val valueDifference = abs(candidateValue - otherValue)
                        valueDifference < threshold && (
                            otherPriority < candidatePriority ||
                                (otherPriority == candidatePriority &&
                                    candidateStrength(otherIdx, items, globalMaxIdx, globalMinIdx, valueFunction) >
                                    candidateStrength(candidateIdx, items, globalMaxIdx, globalMinIdx, valueFunction))
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
            currentValue > prevValue && currentValue > nextValue -> CandidateKind.PEAK
            currentValue < prevValue && currentValue < nextValue -> CandidateKind.VALLEY
            else -> CandidateKind.EDGE
        }
    }

    fun <T> candidatePriority(
        index: Int,
        items: List<T>,
        globalMaxIdx: Int,
        globalMinIdx: Int,
        valueFunction: (T) -> Int,
    ): Int =
        when (candidateKind(index, items, globalMaxIdx, globalMinIdx, valueFunction)) {
            CandidateKind.GLOBAL_MAX -> 0
            CandidateKind.PEAK -> 1
            CandidateKind.GLOBAL_MIN -> 2
            CandidateKind.VALLEY -> 3
            CandidateKind.EDGE -> 4
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
    ): Boolean {
        if (0 !in candidates || 0 == globalMaxIdx) return false

        val leftEdgeItem = items.getOrNull(0) ?: return false
        val leftEdgeValue = valueFunction(leftEdgeItem)
        return candidates.any { candidateIdx ->
            candidateIdx in 1..NEARBY_LABEL_WINDOW &&
                candidateIdx != globalMaxIdx &&
                candidateKind(candidateIdx, items, globalMaxIdx, globalMinIdx, valueFunction) in setOf(CandidateKind.GLOBAL_MIN, CandidateKind.VALLEY) &&
                (items.getOrNull(candidateIdx)?.let(valueFunction) ?: leftEdgeValue) < leftEdgeValue
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
}
