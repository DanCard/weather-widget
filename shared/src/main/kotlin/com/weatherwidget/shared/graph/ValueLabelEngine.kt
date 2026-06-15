package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.Log

/**
 * Pure, platform-free placement engine for the small "%" value labels drawn on the hourly
 * precipitation- and cloud-cover graphs (peak / dip / start / end / soft-dip labels). Mirrors the
 * design of [NowIndicatorGeometry] / [TemperatureLabelEngine]: it takes pre-measured text metrics
 * and already-mapped curve points, owns the single source of truth for *where* each label goes, and
 * returns placement data in BOTH coordinate conventions so each platform draws with its native API.
 *
 * It is the deduplicated home of logic that previously existed four times (Android precip + cloud
 * renderers, desktop precip + cloud graphs). Per-graph differences (soft-dip thresholds, whether to
 * add zero-run / first-positive candidates, etc.) are expressed via [Config] rather than copies.
 *
 * Candidate selection and dense-thinning reuse [GraphLabelPlacementUtils]; vertical placement reuses
 * [GraphLabelPlacementUtils.computeLabelVerticalPlacement]; rects use [GraphRect].
 */
object ValueLabelEngine {

    /** A curve point in pixel space (x across the graph, y down). */
    data class GraphPoint(val x: Float, val y: Float)

    /** Plot-area geometry in pixels. [heightPx] is the full canvas height (incl. footer band). */
    data class Geometry(
        val graphTop: Float,
        val graphBottom: Float,
        val graphHeight: Float,
        val widthPx: Float,
        val heightPx: Float,
    )

    /**
     * Per-graph tuning. Defaults match the Android **precipitation** renderer (the richest variant);
     * [cloud] returns the cloud-cover variant. The algorithm is identical — only these knobs differ.
     */
    data class Config(
        val softDipMaxValue: Int = 65,
        val softDipMinElevation: Int = 8,
        val softDipWindowSize: Int = HourlyGraphDefaults.SOFT_DIP_WINDOW_SIZE,
        val maxCandidates: Int = HourlyGraphDefaults.MAX_LABEL_CANDIDATES,
        val denseDiffThresholds: List<Int> = HourlyGraphDefaults.DENSE_LABEL_DIFF_THRESHOLDS,
        val nearbyWindow: Int = HourlyGraphDefaults.LABEL_FILTER_NEARBY_WINDOW,
        // Candidate sources
        val includeZeroRunMidpoints: Boolean = true,
        val includeFirstPositive: Boolean = true,
        val requireNonZeroExtrema: Boolean = true,
        val addMidpointWhenOnlyEdges: Boolean = true,
        // Minimum column count required before the "only edges -> inject a midpoint" rule fires.
        // Precip applies it always (0); cloud only on wide widgets (5).
        val midpointMinColumns: Int = 0,
        // Whether the injected midpoint must have a non-zero value distinct from both edges. Precip
        // requires it (avoids a redundant duplicate); cloud injects unconditionally (e.g. flat 100%).
        val midpointRequiresDistinctValue: Boolean = true,
        val suppressLeftEdge: Boolean = true,
        // Placement preference
        val preferBelowThreshold: Int = 50,
        val lowPreferBelowMaxValue: Int = 55,
        val nearCenterFraction: Float = 0.2f,
        val logTag: String = "ValueLabelEngine",
    ) {
        companion object {
            /** Cloud-cover variant: higher soft-dip ceiling, no zero-run / first-positive candidates. */
            fun cloud(): Config = Config(
                softDipMaxValue = 85,
                softDipMinElevation = 15,
                includeZeroRunMidpoints = false,
                includeFirstPositive = false,
                requireNonZeroExtrema = false,
                midpointMinColumns = 5,
                midpointRequiresDistinctValue = false,
                logTag = "CloudValueLabelEngine",
            )

            /** Precipitation variant (explicit alias for the defaults). */
            fun precip(): Config = Config(logTag = "PrecipValueLabelEngine")
        }
    }

    /**
     * A placed label. Carries both conventions: [centerX] + [baselineY] for a center-aligned Android
     * `Canvas.drawText`, and [box] (top-left) for Compose `drawText(topLeft = box.topLeft)`.
     */
    data class ValueLabelPlacement(
        val index: Int,
        val text: String,
        val centerX: Float,
        val baselineY: Float,
        val box: GraphRect,
        val placedAbove: Boolean,
        val reason: String,
        // Classification (lets platform adapters rebuild their own debug structs without recomputing).
        val isGlobalMax: Boolean = false,
        val isGlobalMin: Boolean = false,
        val isPeak: Boolean = false,
        val isValley: Boolean = false,
        val isSoftDip: Boolean = false,
        val isFirstRising: Boolean = false,
    )

    /**
     * Computes the final set of value-label placements.
     *
     * @param labelSignal rounded integer values per index (e.g. `smoothed.map{ round.coerceIn(0,100) }`).
     * @param points one curve point per index (already mapped to pixels).
     * @param measureText returns the pixel width of a label string.
     * @param textAscent font ascent (negative); [textDescent] font descent (positive). Uniform font.
     * @param drawnIconBounds obstacles already on the canvas (footer icons, etc.).
     * @param firstLabeledPositive optional renderer-supplied "first labeled positive" index, or -1.
     * @param labelText formats a value into its label string (default `"$v%"`).
     */
    fun computePlacements(
        labelSignal: List<Int>,
        points: List<GraphPoint>,
        geometry: Geometry,
        config: Config,
        measureText: (String) -> Float,
        textAscent: Float,
        textDescent: Float,
        dpToPx: (Float) -> Float,
        drawnIconBounds: List<GraphRect> = emptyList(),
        firstLabeledPositive: Int = -1,
        numColumns: Int = Int.MAX_VALUE,
        labelText: (Int) -> String = { "$it%" },
    ): List<ValueLabelPlacement> {
        if (labelSignal.isEmpty() || points.size != labelSignal.size) return emptyList()

        val finalCandidates = selectCandidates(labelSignal, config, firstLabeledPositive, numColumns)
        val suppressLeftEdgeLabel = config.suppressLeftEdge && GraphLabelPlacementUtils.shouldSuppressLeftEdgeLabel(
            items = labelSignal,
            candidates = finalCandidates,
            globalMaxIdx = globalMaxIdx(labelSignal),
            globalMinIdx = globalMinIdx(labelSignal),
            valueFunction = { it },
        )

        return place(
            labelSignal = labelSignal,
            points = points,
            geometry = geometry,
            config = config,
            candidates = finalCandidates,
            softDipIndices = softDipCandidates(labelSignal, config),
            firstPositive = labelSignal.indexOfFirst { it > 0 },
            firstLabeledPositive = firstLabeledPositive,
            suppressLeftEdgeLabel = suppressLeftEdgeLabel,
            measureText = measureText,
            textAscent = textAscent,
            textDescent = textDescent,
            dpToPx = dpToPx,
            drawnIconBounds = drawnIconBounds,
            labelText = labelText,
        )
    }

    // --- Candidate selection -----------------------------------------------------------------------

    private fun globalMaxIdx(s: List<Int>): Int {
        val maxV = s.maxOrNull() ?: -1
        return GraphLabelPlacementUtils.findLocalExtremaIndices(s, isMax = true).firstOrNull { s[it] == maxV }
            ?: s.indexOfFirst { it == maxV }
    }

    private fun globalMinIdx(s: List<Int>): Int {
        val minV = s.minOrNull() ?: -1
        return GraphLabelPlacementUtils.findLocalExtremaIndices(s, isMax = false).firstOrNull { s[it] == minV }
            ?: s.indexOfFirst { it == minV }
    }

    private fun softDipCandidates(labelSignal: List<Int>, config: Config): List<Int> {
        val out = mutableListOf<Int>()
        var j = 0
        while (j < labelSignal.size) {
            val v = labelSignal[j]
            if (v <= 0 || v > config.softDipMaxValue) { j++; continue }
            var runEnd = j
            while (runEnd < labelSignal.lastIndex && labelSignal[runEnd + 1] == v) runEnd++
            val left = (j - config.softDipWindowSize).coerceAtLeast(0)
            val right = (runEnd + config.softDipWindowSize).coerceAtMost(labelSignal.lastIndex)
            if (left < j && right > runEnd) {
                val leftMax = (left until j).maxOfOrNull { labelSignal[it] } ?: v
                val rightMax = ((runEnd + 1)..right).maxOfOrNull { labelSignal[it] } ?: v
                if (leftMax >= v + config.softDipMinElevation && rightMax >= v + config.softDipMinElevation) {
                    out.add(j + (runEnd - j) / 2)
                }
            }
            j = runEnd + 1
        }
        return out
    }

    private fun zeroRunMidpoints(labelSignal: List<Int>): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i < labelSignal.size) {
            if (labelSignal[i] == 0) {
                val runStart = i
                while (i < labelSignal.size && labelSignal[i] == 0) i++
                val runEnd = i - 1
                if (runStart > 0 && labelSignal[runStart - 1] > 0 &&
                    runEnd < labelSignal.lastIndex && labelSignal[runEnd + 1] > 0
                ) {
                    out.add((runStart + runEnd) / 2)
                }
            } else i++
        }
        return out
    }

    private fun selectCandidates(labelSignal: List<Int>, config: Config, firstLabeledPositive: Int, numColumns: Int): List<Int> {
        val lastIndex = labelSignal.lastIndex
        val localMaxima = GraphLabelPlacementUtils.findLocalExtremaIndices(labelSignal, isMax = true)
        val localMinima = GraphLabelPlacementUtils.findLocalExtremaIndices(labelSignal, isMax = false)
        val gMax = globalMaxIdx(labelSignal)
        val gMin = globalMinIdx(labelSignal)
        val firstPositive = labelSignal.indexOfFirst { it > 0 }
        val softDips = softDipCandidates(labelSignal, config)
        val zeroRuns = if (config.includeZeroRunMidpoints) zeroRunMidpoints(labelSignal) else emptyList()

        fun nonZeroOk(idx: Int) = !config.requireNonZeroExtrema || labelSignal[idx] > 0

        val candidates = mutableListOf<Int>()
        if (gMax >= 0 && nonZeroOk(gMax)) candidates.add(gMax)
        if (gMin >= 0 && gMin != gMax && nonZeroOk(gMin)) candidates.add(gMin)
        candidates.addAll(localMaxima.filter { !config.requireNonZeroExtrema || labelSignal[it] > 0 })
        candidates.addAll(localMinima.filter { !config.requireNonZeroExtrema || labelSignal[it] > 0 })
        candidates.addAll(softDips)
        if (0 !in candidates) candidates.add(0)
        if (lastIndex !in candidates && labelSignal.isNotEmpty()) candidates.add(lastIndex)
        if (config.includeFirstPositive) {
            if (firstPositive != -1 && firstPositive !in candidates) candidates.add(firstPositive)
            if (firstLabeledPositive != -1 && firstLabeledPositive !in candidates) candidates.add(firstLabeledPositive)
        }

        val protectedIndices = buildSet {
            addAll(softDips)
            addAll(zeroRuns)
            if (config.includeFirstPositive) {
                if (firstPositive != -1) add(firstPositive)
                if (firstLabeledPositive != -1) add(firstLabeledPositive)
            }
        }
        candidates.sortBy { it }

        val filtered = GraphLabelPlacementUtils.filterDenseLabelCandidates(
            items = labelSignal,
            candidates = candidates,
            globalMaxIdx = gMax,
            globalMinIdx = gMin,
            maxCandidates = config.maxCandidates,
            diffThresholds = config.denseDiffThresholds,
            valueFunction = { it },
            logTag = config.logTag,
            protectedIndices = protectedIndices,
            nearbyWindow = config.nearbyWindow,
            immovableIndices = buildSet {
                if (labelSignal.isNotEmpty()) { add(0); add(lastIndex) }
            },
        )

        // When only the two edges survive, optionally inject a distinct mid label so a long flat span
        // isn't labeled at the corners only.
        if (config.addMidpointWhenOnlyEdges && numColumns >= config.midpointMinColumns &&
            filtered.size == 2 && filtered == listOf(0, lastIndex)
        ) {
            val mid = lastIndex / 2
            val midV = labelSignal.getOrNull(mid) ?: 0
            val distinctOk = !config.midpointRequiresDistinctValue ||
                (midV > 0 && midV != labelSignal.firstOrNull() && midV != labelSignal.lastOrNull())
            if (mid != 0 && mid != lastIndex && distinctOk) {
                return (filtered + mid).sorted()
            }
        }
        return filtered
    }

    // --- Placement ---------------------------------------------------------------------------------

    private fun place(
        labelSignal: List<Int>,
        points: List<GraphPoint>,
        geometry: Geometry,
        config: Config,
        candidates: List<Int>,
        softDipIndices: List<Int>,
        firstPositive: Int,
        firstLabeledPositive: Int,
        suppressLeftEdgeLabel: Boolean,
        measureText: (String) -> Float,
        textAscent: Float,
        textDescent: Float,
        dpToPx: (Float) -> Float,
        drawnIconBounds: List<GraphRect>,
        labelText: (Int) -> String,
    ): List<ValueLabelPlacement> {
        val placements = mutableListOf<ValueLabelPlacement>()
        val drawn = mutableListOf<GraphRect>()
        val gMax = globalMaxIdx(labelSignal)
        val gMin = globalMinIdx(labelSignal)

        val normalGap = GraphLabelPlacementUtils.getLabelGapDp(isFallback = false)
        val fallbackGap = GraphLabelPlacementUtils.getLabelGapDp(isFallback = true)
        val gapAboveNormal = dpToPx(normalGap.aboveDp)
        val gapBelowNormal = dpToPx(normalGap.belowDp)
        val gapAboveFallback = dpToPx(fallbackGap.aboveDp)
        val gapBelowFallback = dpToPx(fallbackGap.belowDp)
        val safeBottom = geometry.graphBottom - dpToPx(HourlyGraphDefaults.LABEL_SAFE_BOTTOM_INSET_DP)
        val lastIndex = labelSignal.lastIndex

        for (index in candidates) {
            if (index !in labelSignal.indices) continue
            if (index == 0 && suppressLeftEdgeLabel) continue

            val value = labelSignal[index]
            val text = labelText(value)
            val textWidth = measureText(text)
            val centerX = points[index].x
            val y = points[index].y

            val kind = GraphLabelPlacementUtils.candidateKind(index, labelSignal, gMax, gMin) { it }
            val isPeak = kind == GraphLabelPlacementUtils.CandidateKind.PEAK || kind == GraphLabelPlacementUtils.CandidateKind.GLOBAL_MAX
            val isValley = kind == GraphLabelPlacementUtils.CandidateKind.VALLEY || kind == GraphLabelPlacementUtils.CandidateKind.GLOBAL_MIN
            val isSoftDip = index in softDipIndices

            val isNearRightEdge = index >= lastIndex - 1
            val isTrendingDownAtRightEdge = index > 0 && points[index].y > points[index - 1].y + HourlyGraphDefaults.TRENDING_THRESHOLD_PX
            val isTrendingUpAtRightEdge = index > 0 && points[index].y < points[index - 1].y - HourlyGraphDefaults.TRENDING_THRESHOLD_PX
            val isFirstRising = config.includeFirstPositive &&
                (index == firstPositive || index == firstLabeledPositive) && value > 0

            val preferBelow = when {
                isFirstRising -> true
                isPeak -> false
                isValley || isSoftDip -> true
                isNearRightEdge && isTrendingDownAtRightEdge -> true
                isNearRightEdge && isTrendingUpAtRightEdge -> false
                else -> value > config.preferBelowThreshold
            }
            val directions = if (preferBelow) listOf(false, true) else listOf(true, false)

            for ((attemptIndex, placeAbove) in directions.withIndex()) {
                val isFallback = attemptIndex > 0
                val gapPx = when {
                    placeAbove && !isFallback -> gapAboveNormal
                    !placeAbove && !isFallback -> gapBelowNormal
                    placeAbove -> gapAboveFallback
                    else -> gapBelowFallback
                }
                val x = centerX.coerceIn(textWidth / 2f, geometry.widthPx - textWidth / 2f)
                val vp = GraphLabelPlacementUtils.computeLabelVerticalPlacement(
                    pointY = y, placeAbove = placeAbove, gapPx = gapPx,
                    textAscent = textAscent, textDescent = textDescent,
                )
                val bounds = GraphRect(x - textWidth / 2f, vp.top, x + textWidth / 2f, vp.bottom)

                val exceedsTop = bounds.top < 0f
                // A low value placed below may extend past the safeBottom buffer (down to the full
                // canvas) — this is what keeps a low edge label (curve near the bottom) from vanishing.
                val isLowPreferredBelow = !placeAbove && value <= config.lowPreferBelowMaxValue
                val exceedsBottom = if (isLowPreferredBelow) bounds.bottom > geometry.heightPx else bounds.bottom > safeBottom
                if (exceedsTop || exceedsBottom) continue

                val collides = drawn.any { it.intersects(bounds) } || drawnIconBounds.any { it.intersects(bounds) }
                if (collides) continue

                val reason = when {
                    isPeak -> "peak"
                    isValley -> "valley"
                    isSoftDip -> "softDip"
                    index == 0 -> "start"
                    index == lastIndex -> "end"
                    else -> "other"
                }
                Log.d(config.logTag, "PLACED \"$text\" idx=$index x=$x baselineY=${vp.baselineY} above=$placeAbove reason=$reason")
                placements.add(
                    ValueLabelPlacement(
                        index = index, text = text, centerX = x, baselineY = vp.baselineY, box = bounds,
                        placedAbove = placeAbove, reason = reason,
                        isGlobalMax = index == gMax, isGlobalMin = index == gMin,
                        isPeak = isPeak, isValley = isValley, isSoftDip = isSoftDip,
                        isFirstRising = isFirstRising,
                    )
                )
                drawn.add(bounds)
                break
            }
        }
        return placements
    }
}
