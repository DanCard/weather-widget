package com.weatherwidget.shared.graph

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The four label-suppression passes applied to candidate indices before they become labels: left
 * edge, fetch-dot, redundant-pair, and transition-boundary. Each returns a bare verdict (or an
 * overridden role for the fetch-dot pass); the caller owns the logging. Extracted from
 * [TemperatureLabelResolver] so the resolver stays a thin facade.
 */
internal object LabelSuppression {

    data class SuppressionResult(
        val suppressed: Boolean,
        val overriddenRole: TemperatureRole? = null,
    )

    internal fun checkLeftEdgeSuppression(
        idx: Int,
        role: TemperatureRole,
        suppressLeftEdgeLabel: Boolean,
    ): SuppressionResult {
        // An actual high/low keeps its own label at the left edge: an observed extreme at the start of
        // the observed data (e.g. the coldest point sits at midnight in the 24h day view) is a real
        // boundary value the user wants to see, mirroring the right-edge exemptions in
        // checkFetchDotSuppression / checkEndpointSuppression.
        if (role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.ACTUAL_LOW) {
            return SuppressionResult(false)
        }
        val isBoundary = role == TemperatureRole.START || role == TemperatureRole.END
        if (idx == 0 && suppressLeftEdgeLabel && !isBoundary) {
            return SuppressionResult(true)
        }
        return SuppressionResult(false)
    }

    internal fun checkFetchDotSuppression(
        idx: Int,
        role: TemperatureRole,
        extrema: TemperatureExtrema.ExtremaIndices,
        observedAt: Long?,
        hours: List<HourData>,
    ): SuppressionResult {
        if (idx != extrema.fetchIdx || observedAt == null) return SuppressionResult(false)
        // An actual high/low keeps its own label even when the fetch dot lands on it: the observed
        // extreme value is the whole point of the label, so the dot must not relabel it as a START/END
        // boundary (which shows the forecast endpoint value) nor suppress it. Mirrors the HIGH/LOW
        // exemption below; without this, a curve still cooling at NOW loses its absolute-low label.
        if (role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.ACTUAL_LOW) {
            return SuppressionResult(false)
        }
        if (idx == 0 || idx == hours.lastIndex) {
            return SuppressionResult(false, overriddenRole = if (idx == 0) TemperatureRole.START else TemperatureRole.END)
        }
        if (role in listOf(TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW)) {
            return SuppressionResult(false)
        }
        return SuppressionResult(true)
    }

    internal fun checkRedundantPairSuppression(
        idx: Int,
        role: TemperatureRole,
        extrema: TemperatureExtrema.ExtremaIndices,
        suppressedIndices: Set<Int>,
        labelTemps: List<Float>,
        actualLabelTemps: List<Float>,
        boundaryWindow: Int,
        hours: List<HourData>,
        widthPx: Int,
    ): Boolean {
        val redundantValueThreshold = 2f
        // Legacy index window for same-semantic extrema pairs (forecast vs actual high/low): these
        // represent the same quantity, so a slightly wider, zoom-independent window is appropriate.
        val extremaWindow = min(8, labelTemps.lastIndex / 5)

        return when (role) {
            // The observed high is always worth its own label, mirroring ACTUAL_LOW below. Even
            // when the observed peak lands within a degree of the forecast/daily high, the two are
            // distinct data points the user wants to compare side by side, so never treat the actual
            // high as redundant — placement stacks/orders the two nearby labels by value instead.
            TemperatureRole.ACTUAL_HIGH -> false
            // The observed low is always worth its own label. It only resolves at an index distinct
            // from the daily low (when the global min IS an actual point, resolveExtremaRole returns
            // LOW first), so never treat it as redundant against a nearby forecast/daily low — the
            // two are stacked and ordered by value at placement time instead.
            TemperatureRole.ACTUAL_LOW -> false
            // Guard the actual index: with no actual data in view (forecast-only widget, or panned
            // into a forecast gap) actualHighIndex/actualLowIndex is -1, so there is nothing for the
            // forecast high/low to be redundant against — keep the label (and avoid an OOB access).
            TemperatureRole.FORECAST_HIGH, TemperatureRole.PAST_FORECAST_HIGH ->
                extrema.actualHighIndex >= 0 && isRedundantNear(idx, role, extrema.actualHighIndex, suppressedIndices, labelTemps[idx], actualLabelTemps[extrema.actualHighIndex], extremaWindow, redundantValueThreshold, "ACTUAL_HIGH")
            TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_LOW ->
                extrema.actualLowIndex >= 0 && isRedundantNear(idx, role, extrema.actualLowIndex, suppressedIndices, labelTemps[idx], actualLabelTemps[extrema.actualLowIndex], extremaWindow, redundantValueThreshold, "ACTUAL_LOW")
            // Edge (boundary) labels are positional anchors, so "nearby" is a visual pixel budget, not
            // an index window. In the densely-sampled observed region near an edge many indices collapse
            // into a few pixels, so a START at idx 0 can be pixel-adjacent to a HIGH/ACTUAL_HIGH 30
            // indices away (a 3-day view packs idx 0..30 into ~5px). Measure the gap from real
            // timestamps (pixelGapByTime) when geometry is known; fall back to the legacy index window
            // for geometry-less callers (unit tests). Targets include the global forecast/actual extrema
            // AND the per-day actual extrema — the per-day observed high sitting beside the edge is what
            // makes the forecast boundary value redundant (the global actual high may be far to the right).
            TemperatureRole.START, TemperatureRole.END -> {
                // The GLOBAL daily extrema (the single most important forecast labels) get the looser
                // displayed-value tolerance: a START reading 73 beside the daily HIGH reading 75 is
                // redundant. SECONDARY forecast extrema (forecast-region / past-forecast high/low) keep
                // the strict raw < 2 gate, so e.g. an END reading 57 beside a forecast LOW of 55 — a
                // distinct local valley, not THE daily low — is still its own boundary label.
                val dailyTargets = listOf(extrema.dailyHighIndex, extrema.dailyLowIndex)
                val secondaryForecastTargets = listOf(
                    extrema.forecastHighIndex, extrema.forecastLowIndex,
                    extrema.pastForecastHighIndex, extrema.pastForecastLowIndex,
                )
                val actualTargets = listOf(extrema.actualHighIndex, extrema.actualLowIndex) +
                    extrema.actualDailyHighIndices + extrema.actualDailyLowIndices
                fun isTarget(tIdx: Int): Boolean = tIdx >= 0 && tIdx != idx && tIdx !in suppressedIndices
                fun nearEnough(tIdx: Int): Boolean {
                    val tRole = TemperatureLabelResolver.resolveExtremaRole(tIdx, extrema, hours)
                    // Same flat run on the SAME (forecast) series: the plateau already carries a label
                    // of this exact value, so repeating it at the boundary is redundant at ANY
                    // distance — one plateau labeled twice, not two labels that happen to sit close.
                    // A pixel budget cannot express that, and trying made the outcome depend on width
                    // and pan (58px suppressed on one device, 73px survived on the next). Index-based,
                    // so it holds for geometry-less callers too.
                    if (tRole !in TemperatureLabelResolver.ACTUAL_DISPLAY_ROLES && tIdx in LabelGeometryResolver.runBounds(labelTemps, idx)) return true
                    if (widthPx <= 0) return abs(idx - tIdx) <= boundaryWindow
                    // Otherwise measure to where the target is DRAWN: a run-centered role sits at the
                    // middle of its run, not on its own index. Resolving the role here (rather than
                    // assuming it from the list it came from) applies the renderer's own precedence.
                    val tTemps = if (tRole in TemperatureLabelResolver.ACTUAL_DISPLAY_ROLES) actualLabelTemps else labelTemps
                    return LabelGeometryResolver.pixelGapByTime(
                        hours, idx, role, labelTemps, tIdx, tRole, tTemps, widthPx,
                    ) <= LabelGeometryResolver.REDUNDANT_PAIR_PX
                }
                dailyTargets.any { tIdx ->
                    isTarget(tIdx) && nearEnough(tIdx) &&
                        abs(labelTemps[idx].roundToInt() - labelTemps[tIdx].roundToInt()) <= LabelGeometryResolver.SAME_SERIES_BOUNDARY_REDUNDANT_DEGREES
                } || secondaryForecastTargets.any { tIdx ->
                    isTarget(tIdx) && nearEnough(tIdx) &&
                        abs(labelTemps[idx] - labelTemps[tIdx]) < redundantValueThreshold
                } || actualTargets.any { tIdx ->
                    isTarget(tIdx) && nearEnough(tIdx) &&
                        abs(labelTemps[idx] - actualLabelTemps[tIdx]) < redundantValueThreshold
                }
            }
            TemperatureRole.LOCAL, TemperatureRole.ACTUAL_END -> {
                val forecastCandidates = listOf(
                    extrema.dailyHighIndex, extrema.dailyLowIndex,
                    extrema.forecastHighIndex, extrema.forecastLowIndex,
                    extrema.pastForecastHighIndex, extrema.pastForecastLowIndex,
                )
                val actualCandidates = listOf(
                    extrema.actualHighIndex, extrema.actualLowIndex,
                )
                forecastCandidates.any { tIdx ->
                    tIdx >= 0 && tIdx != idx && tIdx !in suppressedIndices &&
                        abs(idx - tIdx) <= boundaryWindow &&
                        abs(labelTemps[idx] - labelTemps[tIdx]) < redundantValueThreshold
                } || actualCandidates.any { tIdx ->
                    tIdx >= 0 && tIdx != idx && tIdx !in suppressedIndices &&
                        abs(idx - tIdx) <= boundaryWindow &&
                        abs(labelTemps[idx] - actualLabelTemps[tIdx]) < redundantValueThreshold
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
        reasonSuffix: String,
    ): Boolean {
        if (targetIdx >= 0 && targetIdx !in suppressedIndices && abs(idx - targetIdx) <= window) {
            if (abs(currentVal - targetVal) < threshold) {
                return true
            }
        }
        return false
    }

    internal fun checkTransitionBoundarySuppression(
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
}
