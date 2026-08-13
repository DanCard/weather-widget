package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.Log
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

data class PlacedLabel(
    val index: Int,
    val role: TemperatureRole,
    val text: String,
    val x: Float,
    val baselineY: Float,
    val placedAbove: Boolean,
    val drawLeaderLine: Boolean,
    val leaderFromY: Float,
    val leaderToY: Float,
    val isFuture: Boolean,
    val rawTemperature: Float,
    val displayTemperature: Float,
    val reason: String = "",
    val displacementSteps: Int = 0,
)

object TemperatureLabelEngine {
    private const val TAG = "TempLabelEngine"
    private const val CURVE_AVOIDANCE_CLEAR_PX = 1.5f
    private const val MAX_LEADER_DISPLACEMENT_STEPS = 3

    // Gap left between an actual-extreme label and a label it had to step over, and the step budget.
    // Both bypass placers (placeActualHighAboveCurve / placeActualLowBelowCurve) force a direction
    // rather than searching, so without this they emit straight through whatever is already drawn.
    //
    // What counts as "through" is now CollisionTester's single rule (shared with the main placement
    // loop and the curve-avoidance pre-pass). A pink ACTUAL_LOW deliberately shares the valley with
    // an amber FORECAST_LOW a couple of degrees below it, and the two are *supposed* to graze. Only
    // a substantial overlap — the near-coincident stacking that made three actual highs unreadable —
    // is resolved.
    private const val ACTUAL_EXTREME_STACK_GAP_DP = 1.5f
    private const val ACTUAL_EXTREME_STACK_MAX_STEPS = 4


    private val LOGGED_ROLES: Set<TemperatureRole> = setOf(
        TemperatureRole.ACTUAL_LOW, TemperatureRole.LOW,
        TemperatureRole.ACTUAL_HIGH, TemperatureRole.HIGH,
        TemperatureRole.ACTUAL_END, TemperatureRole.LOCAL,
        TemperatureRole.START, TemperatureRole.END,
    )

    private fun shouldLogPlacement(role: TemperatureRole): Boolean = role in LOGGED_ROLES

    private const val VALUE_NEIGHBOR_WINDOW = 5
    private const val SIGNIFICANT_MAX_GAP = 1.0f

    private fun prefersAbovePlacement(candidate: TempLabelCandidate): Boolean {
        val temps = candidate.labelTemps
        val i = candidate.index
        if (i !in temps.indices) return true
        val v = temps[i]
        val lo = maxOf(0, i - VALUE_NEIGHBOR_WINDOW)
        val hi = minOf(temps.lastIndex, i + VALUE_NEIGHBOR_WINDOW)
        var nearMax = v
        for (k in lo..hi) {
            val t = temps[k]
            if (t > nearMax) nearMax = t
        }
        return (nearMax - v) < SIGNIFICANT_MAX_GAP
    }

    private fun computeForcedAboveLowIndices(candidates: List<TempLabelCandidate>): Set<Int> {
        val lowRoles = setOf(
            TemperatureRole.LOW, TemperatureRole.ACTUAL_LOW,
            TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_LOW,
        )
        val lows = candidates.filter { it.role in lowRoles }
        if (lows.size < 2) return emptySet()
        val window = GraphLabelPlacementUtils.NEARBY_LABEL_WINDOW
        val forced = mutableSetOf<Int>()
        for (c in lows) {
            if (c.role != TemperatureRole.ACTUAL_LOW) continue
            val cVal = c.labelTemps[c.index].roundToInt()
            val hasLowerNeighbor = lows.any { other ->
                other.index != c.index &&
                    abs(other.index - c.index) <= window &&
                    other.labelTemps[other.index].roundToInt() < cVal
            }
            if (hasLowerNeighbor) forced.add(c.index)
        }
        return forced
    }

    // At the graph's LEFT EDGE the START (forecast) label and the nearest actual extreme label often
    // sit at nearly the same x but on opposite sides of their lines, which can invert their reading
    // order (cooler forecast above warmer actual). Order this start pair by temperature: warmer
    // above, cooler below. Scoped to the left edge (the actual label must be within
    // LEFT_EDGE_START_WINDOW of START) so the rest of the tuned placement logic is untouched.
    // Key by index AND role so a forecast and actual label sharing the same sample can still receive
    // independent directions.
    private const val LEFT_EDGE_START_WINDOW = 8

    private data class LabelKey(
        val index: Int,
        val role: TemperatureRole,
    )

    private val LEFT_EDGE_ACTUAL_ROLES = setOf(
        TemperatureRole.ACTUAL_LOW, TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_END,
    )

    private fun computeLeftEdgeStartOrdering(candidates: List<TempLabelCandidate>, useCelsius: Boolean): Map<LabelKey, Boolean> {
        val start = candidates.firstOrNull { it.role == TemperatureRole.START } ?: return emptyMap()
        val actual = candidates
            .filter {
                it.role in LEFT_EDGE_ACTUAL_ROLES && it.index != start.index &&
                    abs(it.index - start.index) <= LEFT_EDGE_START_WINDOW
            }
            .minByOrNull { abs(it.index - start.index) } ?: return emptyMap()
        val startVal = start.labelTemps[start.index]
        val actualVal = actual.labelTemps[actual.index]
        if (TemperatureLabelResolver.formatTemp(startVal, useCelsius) == TemperatureLabelResolver.formatTemp(actualVal, useCelsius)) return emptyMap()
        val startAbove = startVal > actualVal
        return mapOf(
            LabelKey(start.index, start.role) to startAbove,
            LabelKey(actual.index, actual.role) to !startAbove,
        )
    }

    private val LEFT_EDGE_FORECAST_HIGH_ROLES = setOf(
        TemperatureRole.HIGH, TemperatureRole.FORECAST_HIGH, TemperatureRole.PAST_FORECAST_HIGH,
    )

    // Left-edge counterpart to computeLeftEdgeStartOrdering for when the forecast partner is a HIGH
    // (not a START). A left-edge forecast high and a near-coincident equal-or-cooler ACTUAL_HIGH
    // would both be force-placed above and stack (the actual via placeActualHighAboveCurve); drop
    // the cooler observed high BELOW its own peak while the warmer forecast high stays above. Scoped
    // to the left edge AND the equal-or-cooler case, so the lone actual high and the genuinely
    // warmer actual are untouched. Returns ONLY the actual override; the forecast high keeps its
    // existing default-above path.
    private fun computeLeftEdgeHighOrdering(candidates: List<TempLabelCandidate>, useCelsius: Boolean): Map<LabelKey, Boolean> {
        val forecast = candidates
            .filter { it.role in LEFT_EDGE_FORECAST_HIGH_ROLES }
            .minByOrNull { it.index } ?: return emptyMap()
        if (forecast.index > LEFT_EDGE_START_WINDOW) return emptyMap() // left edge only
        val actual = candidates
            .filter {
                it.role == TemperatureRole.ACTUAL_HIGH &&
                    abs(it.index - forecast.index) <= LEFT_EDGE_START_WINDOW
            }
            .minByOrNull { abs(it.index - forecast.index) } ?: return emptyMap()
        val forecastVal = forecast.labelTemps[forecast.index]
        val actualVal = actual.labelTemps[actual.index]
        if (TemperatureLabelResolver.formatTemp(forecastVal, useCelsius) == TemperatureLabelResolver.formatTemp(actualVal, useCelsius)) return emptyMap()
        // Only act when the forecast is the warmer one; a genuinely warmer actual keeps default above.
        if (forecastVal < actualVal) return emptyMap()
        return mapOf(LabelKey(actual.index, actual.role) to false)
    }

    // Forecast-series labels avoid only the FORECAST curve, never the actual curve. A forecast
    // extreme nested under a much taller/deeper actual curve must sit flush on its OWN forecast peak
    // or valley; treating the towering actual curve as an obstacle drives the label far off-anchor
    // with a long, unhelpful leader line. (ACTUAL_LOW is handled the same way via its own carve-out;
    // ACTUAL_HIGH uses placeActualHighAboveCurve; START/END/ACTUAL_END keep full avoidance.)
    private val FORECAST_ONLY_AVOIDANCE_ROLES = setOf(
        TemperatureRole.HIGH, TemperatureRole.LOW, TemperatureRole.LOCAL,
        TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW,
        TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW,
    )

    fun computePlacements(
        hours: List<HourData>,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        originalPoints: List<Pair<Float, Float>>,
        forecastPoints: List<Pair<Float, Float>>,
        actualVisiblePoints: List<Pair<Float, Float>>,
        transitionX: Float?,
        fetchDotX: Float?,
        lastObservedTemp: Float?,
        observedAt: Long?,
        effectiveActualEndIndex: Int,
        fetchTime: LocalDateTime?,
        numColumns: Int,
        tempToY: (Float) -> Float,
        metrics: LabelTextMetrics,
        drawnIconBounds: List<GraphRect> = emptyList(),
        // Bounds (fetch-dot value/age labels) treated as HARD obstacles: a candidate overlapping
        // one is always a real collision — never softened by minor-overlap allowance — so e.g. a
        // valley forecast LOW landing on the fetch-dot's pink actual-temp label flips above the
        // curve instead of drawing on top of it. See plans/samsung-clash-of-labels-*.md.
        reservedHardBounds: List<GraphRect> = emptyList(),
        useCelsius: Boolean,
    ): List<PlacedLabel> {
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, transitionX, effectiveActualEndIndex, fetchTime, useCelsius)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = effectiveActualEndIndex,
            transitionX = transitionX,
            observedAt = observedAt,
            numColumns = numColumns,
            widthPx = widthPx,
            useCelsius = useCelsius,
        ).toMutableList()

        TemperatureLabelResolver.sortLabelCandidates(candidates)

        val forcedAboveLows = computeForcedAboveLowIndices(candidates)
        // computeLeftEdgeStartOrdering wins on any key collision (its START pairing is the tuned case).
        val leftEdgeOrder = computeLeftEdgeHighOrdering(candidates, useCelsius) + computeLeftEdgeStartOrdering(candidates, useCelsius)
        val drawnLabelMetas = mutableListOf<PlacedLabelMeta>()
        val resultPlacements = mutableListOf<PlacedLabel>()

        val labelAscent = metrics.ascent
        val labelDescent = metrics.descent
        val labelHeight = metrics.height
        // Tighten the above-curve gap so high/peak temperature labels sit closer to the peak.
        // Below-gap (lows) is unchanged; precip/cloud %-labels are unaffected (own getLabelGapDp call).
        val gapDp = GraphLabelPlacementUtils.getLabelGapDp(isFallback = false)
            .copy(aboveDp = GraphLabelPlacementUtils.TEMP_PREFERRED_ABOVE_GAP_DP)

        Log.d(TAG, "EngineInput: heightPx=$heightPx widthPx=$widthPx fetchDotX=${fetchDotX?.let { String.format("%.1f", it) }} transitionX=${transitionX?.let { String.format("%.1f", it) }} labelHeight=${String.format("%.1f", labelHeight)} hardBounds=${reservedHardBounds.map { "(${String.format("%.1f", it.left)},${String.format("%.1f", it.top)},${String.format("%.1f", it.right)},${String.format("%.1f", it.bottom)})" }}")

        for (candidate in candidates) {
            val idx = candidate.index
            val labelKey = LabelKey(idx, candidate.role)
            val temps = candidate.labelTemps
            val geometry = TemperatureLabelResolver.resolveCandidateGeometry(
                candidate = candidate,
                originalPoints = originalPoints,
                forecastPoints = forecastPoints,
                transitionX = transitionX,
                widthPx = widthPx,
                density = density,
                fetchDotX = fetchDotX,
                lastObservedTemp = lastObservedTemp,
                tempToY = tempToY,
                metrics = metrics,
                useCelsius = useCelsius,
            ) ?: continue

            val forceAbove = idx in forcedAboveLows
            val valueBasedRoles = candidate.role == TemperatureRole.ACTUAL_END ||
                candidate.role == TemperatureRole.LOCAL ||
                candidate.role == TemperatureRole.START ||
                candidate.role == TemperatureRole.END
            val preferAbove = when {
                labelKey in leftEdgeOrder -> leftEdgeOrder.getValue(labelKey)
                forceAbove -> true
                valueBasedRoles -> prefersAbovePlacement(candidate)
                else -> !geometry.isValley
            }
            val directions = if (preferAbove) listOf(true, false) else listOf(false, true)

            var placed = false
            var forceBaselineY = Float.NaN
            var forceBounds: GraphRect? = null
            var forceX = geometry.clampedX
            var forceDrawBelow = false
            var forceStep = 0
            var flipDecided = false

            // The observed high is the topmost point of its (jagged) line, so it should always sit
            // ABOVE that line — not flip below into a pocket when the high-resolution actual curve
            // spikes past the hourly anchor. Place it above the actual line's local peak under the
            // label, clamping into the header band if there is no room. Above the global observed
            // peak there is only headroom, so this never collides with meaningful data.
            // A left-edge ACTUAL_HIGH paired with a warmer forecast high is rerouted below the curve
            // via leftEdgeOrder; let it flow through the normal loop instead of forcing it above.
            if (candidate.role == TemperatureRole.ACTUAL_HIGH && labelKey !in leftEdgeOrder) {
                placeActualHighAboveCurve(
                    heightPx = heightPx,
                    density = density,
                    actualVisiblePoints = actualVisiblePoints,
                    candidate = candidate,
                    geometry = geometry,
                    labelAscent = labelAscent,
                    labelDescent = labelDescent,
                    drawnLabelMetas = drawnLabelMetas,
                    drawnIconBounds = drawnIconBounds,
                    reservedHardBounds = reservedHardBounds,
                    idx = idx,
                    temps = temps,
                    resultPlacements = resultPlacements,
                )
                continue
            }

            // The left-edge START/actual pair sits flush against its own line start (color-matched,
            // ordered by value), so skip curve avoidance entirely for it.
            val isCurveAvoidanceExempt = labelKey in leftEdgeOrder

            // Forecast-series labels (and ACTUAL_LOW) avoid only the FORECAST curve, never the actual
            // curve: a forecast extreme nested under a much taller/deeper actual curve must sit flush
            // on its own forecast peak/valley rather than being driven off-anchor with a long leader
            // line. ACTUAL_LOW additionally ignores its own observed line's sub-hourly graze below
            // the labeled minimum. START/END/ACTUAL_END keep full avoidance.
            val avoidanceActualPoints =
                if (candidate.role == TemperatureRole.ACTUAL_LOW || candidate.role in FORECAST_ONLY_AVOIDANCE_ROLES) {
                    emptyList()
                } else {
                    actualVisiblePoints
                }

            if (candidate.role in CollisionTester.CURVE_AVOIDANCE_ROLES && !isCurveAvoidanceExempt) {
                placed = tryExactFitCurveAvoidance(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    density = density,
                    actualVisiblePoints = avoidanceActualPoints,
                    forecastPoints = forecastPoints,
                    candidate = candidate,
                    geometry = geometry,
                    directions = directions,
                    gapDp = gapDp,
                    labelAscent = labelAscent,
                    labelDescent = labelDescent,
                    drawnLabelMetas = drawnLabelMetas,
                    drawnIconBounds = drawnIconBounds,
                    reservedHardBounds = reservedHardBounds,
                    idx = idx,
                    temps = temps,
                    resultPlacements = resultPlacements,
                    allActualVisiblePoints = actualVisiblePoints,
                )
                if (placed) continue
            }

            val gapAbovePx = gapDp.aboveDp * density
            val gapBelowPx = gapDp.belowDp * density
            val allowedCurveDipPx = CollisionTester.allowedDipPxFor(candidate.role, density, labelDescent - labelAscent)

            outer@ for (step in 0..MAX_LEADER_DISPLACEMENT_STEPS) {
                for (placeAbove in directions) {
                    if (flipDecided && !placeAbove) continue

                    val currentGapPx = if (placeAbove) gapAbovePx else gapBelowPx
                    val displacement = step * labelHeight

                    val verticalPlacement = GraphLabelPlacementUtils.computeLabelVerticalPlacement(
                        pointY = geometry.sy,
                        placeAbove = placeAbove,
                        gapPx = currentGapPx + displacement,
                        textAscent = labelAscent,
                        textDescent = labelDescent
                    )

                    val baselineY = verticalPlacement.baselineY
                    val bounds = GraphRect(
                        geometry.clampedX - geometry.textWidth / 2f,
                        verticalPlacement.top,
                        geometry.clampedX + geometry.textWidth / 2f,
                        verticalPlacement.bottom
                    )

                    val onScreen = bounds.top >= 0f && bounds.bottom <= heightPx
                    if (!onScreen) continue

                    val obstacles = CollisionTester.obstacles(
                        role = candidate.role,
                        bounds = bounds,
                        isValley = geometry.isValley,
                        placeAbove = placeAbove,
                        drawnLabelMetas = drawnLabelMetas,
                        drawnIconBounds = drawnIconBounds,
                        reservedHardBounds = reservedHardBounds,
                        labelHeight = labelHeight,
                    )
                    val curveResult = CollisionTester.curve(
                        role = candidate.role,
                        bounds = bounds,
                        placeAbove = placeAbove,
                        avoidanceActualPoints = avoidanceActualPoints,
                        forecastPoints = forecastPoints,
                        allowedDipPx = allowedCurveDipPx,
                        isCurveAvoidanceExempt = isCurveAvoidanceExempt,
                        flipDecided = flipDecided,
                    )

                    val hasCollision = obstacles.anyBlocked || curveResult.curveBlocked

                    if (hasCollision && shouldLogPlacement(candidate.role)) {
                        val curveDepth = if (curveResult.curveBlocked) curveResult.curveDipDepth else 0f
                        Log.d(TAG, "PlaceReject: role=${candidate.role} idx=$idx step=$step above=$placeAbove " +
                            "blocker=[label=${obstacles.overlapsLabel}/${String.format("%.1f", obstacles.labelOverlapPx)}(minorOK=${obstacles.allowMinorLabelOverlap}) " +
                            "icon=${obstacles.overlapsIcon}/${String.format("%.1f", obstacles.iconOverlapPx)}(minorOK=${obstacles.allowMinorIconOverlap}) " +
                            "hard=${obstacles.overlapsHard}/${String.format("%.1f", obstacles.hardOverlapPx)}(minorOK=${obstacles.allowMinorHardOverlap}) " +
                            "curve=${curveResult.curveBlocked} depth=${String.format("%.2f", curveDepth)}] bounds=(${String.format("%.1f", bounds.top)},${String.format("%.1f", bounds.bottom)})")
                    }

                    if (hasCollision && !placeAbove && geometry.isValley && step == 0 && !flipDecided) {
                        val outcome = tryValleyBelowCascade(
                            widthPx = widthPx,
                            heightPx = heightPx,
                            candidate = candidate,
                            geometry = geometry,
                            verticalPlacement = verticalPlacement,
                            drawnLabelMetas = drawnLabelMetas,
                            drawnIconBounds = drawnIconBounds,
                            reservedHardBounds = reservedHardBounds,
                            labelHeight = labelHeight,
                        )
                        when (outcome) {
                            is ValleyCascadeOutcome.Below -> {
                                val cascadeResult = outcome.result
                                resultPlacements.add(
                                    PlacedLabel(
                                        index = idx,
                                        role = candidate.role,
                                        text = geometry.label,
                                        x = cascadeResult.x,
                                        baselineY = cascadeResult.baselineY,
                                        placedAbove = false,
                                        drawLeaderLine = false,
                                        leaderFromY = geometry.sy,
                                        leaderToY = geometry.sy,
                                        isFuture = geometry.isFuture,
                                        rawTemperature = candidate.rawTemperature,
                                        displayTemperature = temps[idx],
                                        reason = cascadeResult.reason,
                                        displacementSteps = 0,
                                    )
                                )
                                drawnLabelMetas.add(PlacedLabelMeta(cascadeResult.bounds, isValleyBelow = true, role = candidate.role, temperature = temps[idx]))
                                placed = true
                                break@outer
                            }
                            ValleyCascadeOutcome.FlipAbove -> {
                                flipDecided = true
                                continue
                            }
                            ValleyCascadeOutcome.None -> Unit
                        }
                    }

                    if (geometry.isEssential && forceBounds == null) {
                        forceBaselineY = baselineY
                        forceBounds = bounds
                        forceX = geometry.clampedX
                        forceDrawBelow = !placeAbove
                        forceStep = step
                    }

                    if (!hasCollision) {
                        val drawLeader = step > 0
                        val lineEndY = if (!placeAbove) bounds.top else bounds.bottom
                        if (shouldLogPlacement(candidate.role)) {
                            Log.d(TAG, "PlaceAccept: role=${candidate.role} idx=$idx step=$step above=$placeAbove leader=$drawLeader hardOverlap=${String.format("%.1f", obstacles.hardOverlapPx)} allowMinorHardOverlap=${obstacles.allowMinorHardOverlap}")
                        }
                        if (drawLeader && candidate.role == TemperatureRole.LOCAL) {
                            val distToTrans = transitionX?.let { kotlin.math.abs(geometry.clampedX - it) } ?: -1f
                            val distToFetch = fetchDotX?.let { kotlin.math.abs(geometry.clampedX - it) } ?: -1f
                            Log.d(TAG, "ForecastMidpointLeader: idx=$idx step=$step temp=${temps[idx]} x=${"%.1f".format(geometry.clampedX)} distToTransition=${"%.1f".format(distToTrans)} distToFetch=${"%.1f".format(distToFetch)} above=$placeAbove (driven by prior curve/hard rejections at step 0)")
                        }
                        resultPlacements.add(
                            PlacedLabel(
                                index = idx,
                                role = candidate.role,
                                text = geometry.label,
                                x = geometry.clampedX,
                                baselineY = baselineY,
                                placedAbove = placeAbove,
                                drawLeaderLine = drawLeader,
                                leaderFromY = geometry.sy,
                                leaderToY = lineEndY,
                                isFuture = geometry.isFuture,
                                rawTemperature = candidate.rawTemperature,
                                displayTemperature = temps[idx],
                                reason = if (step > 0) "${if (!placeAbove) "below" else "above"}+$step" else (if (!placeAbove) "below" else "above"),
                                displacementSteps = step,
                            )
                        )
                        drawnLabelMetas.add(PlacedLabelMeta(bounds, isValleyBelow = !placeAbove && geometry.isValley, role = candidate.role, temperature = temps[idx]))
                        placed = true
                        break@outer
                    }
                }
            }

            if (!placed && geometry.isEssential && forceBounds != null) {
                val drawLeader = forceStep > 0
                val lineEndY = if (forceDrawBelow) forceBounds.top else forceBounds.bottom
                resultPlacements.add(
                    PlacedLabel(
                        index = idx,
                        role = candidate.role,
                        text = geometry.label,
                        x = forceX,
                        baselineY = forceBaselineY,
                        placedAbove = !forceDrawBelow,
                        drawLeaderLine = drawLeader,
                        leaderFromY = geometry.sy,
                        leaderToY = lineEndY,
                        isFuture = geometry.isFuture,
                        rawTemperature = candidate.rawTemperature,
                        displayTemperature = temps[idx],
                        reason = "FORCED",
                        displacementSteps = forceStep,
                    )
                )
                drawnLabelMetas.add(PlacedLabelMeta(forceBounds, isValleyBelow = forceDrawBelow, role = candidate.role, temperature = temps[idx]))
            }
        }

        return resultPlacements
    }

    // Places the observed-high label above the actual line's local peak. Scans the high-resolution
    // actual curve across the label's x-span for the highest point (smallest y) so the label clears
    // the jagged spike rather than overlapping it, then sits one gap above. Clamps to the top edge
    // (spilling into the header band) when the peak is too close to the top to leave a full gap.
    /**
     * True when the forced-direction placers must yield: the candidate box overlaps an already-drawn
     * label, icon, or hard bound beyond CollisionTester's standard minor-overlap budgets. This is the
     * same rule the main placement loop and the curve-avoidance pre-pass apply, so the forced placers
     * resolve exactly what the rest of the engine resolves — no more, no less. (The forced placers
     * never test the curve itself: they are pinned to it by design.)
     */
    private fun forcedPlacerBlocked(
        role: TemperatureRole,
        bounds: GraphRect,
        isValley: Boolean,
        placeAbove: Boolean,
        drawnLabelMetas: List<PlacedLabelMeta>,
        drawnIconBounds: List<GraphRect>,
        reservedHardBounds: List<GraphRect>,
        labelHeight: Float,
    ): Boolean = CollisionTester.obstacles(
        role = role,
        bounds = bounds,
        isValley = isValley,
        placeAbove = placeAbove,
        drawnLabelMetas = drawnLabelMetas,
        drawnIconBounds = drawnIconBounds,
        reservedHardBounds = reservedHardBounds,
        labelHeight = labelHeight,
    ).anyBlocked

    /**
     * The first already-drawn obstacle (label, icon, or hard bound) intersecting [bounds], in draw
     * order, or null when none intersects. The forced placers step the candidate over this blocker.
     */
    private fun firstBlockerBounds(
        bounds: GraphRect,
        drawnLabelMetas: List<PlacedLabelMeta>,
        drawnIconBounds: List<GraphRect>,
        reservedHardBounds: List<GraphRect>,
    ): GraphRect? =
        drawnLabelMetas.firstOrNull { it.bounds.intersects(bounds) }?.bounds
            ?: drawnIconBounds.firstOrNull { it.intersects(bounds) }
            ?: reservedHardBounds.firstOrNull { it.intersects(bounds) }

    private fun placeActualHighAboveCurve(
        heightPx: Int,
        density: Float,
        actualVisiblePoints: List<Pair<Float, Float>>,
        candidate: TempLabelCandidate,
        geometry: ResolvedLabelGeometry,
        labelAscent: Float,
        labelDescent: Float,
        drawnLabelMetas: MutableList<PlacedLabelMeta>,
        drawnIconBounds: List<GraphRect>,
        reservedHardBounds: List<GraphRect>,
        idx: Int,
        temps: List<Float>,
        resultPlacements: MutableList<PlacedLabel>,
    ) {
        val halfWidth = geometry.textWidth / 2f
        val left = geometry.clampedX - halfWidth
        val right = geometry.clampedX + halfWidth

        // Highest visible actual point beneath the label (fall back to the hourly anchor).
        var curveTopY = geometry.sy
        for ((px, py) in actualVisiblePoints) {
            if (px in left..right && py < curveTopY) curveTopY = py
        }

        // The observed high rides right on its spike (this path never flips below), so use the tighter
        // actual-high gap rather than the shared above gap.
        val gapAbovePx = GraphLabelPlacementUtils.TEMP_ACTUAL_HIGH_ABOVE_GAP_DP * density
        val placement = GraphLabelPlacementUtils.computeLabelVerticalPlacement(
            pointY = curveTopY,
            placeAbove = true,
            gapPx = gapAbovePx,
            textAscent = labelAscent,
            textDescent = labelDescent,
        )
        var top = placement.top
        var bottom = placement.bottom
        var baselineY = placement.baselineY
        if (top < 0f) {
            val shift = -top
            top += shift
            bottom += shift
            baselineY += shift
        }
        // This path forces "above" instead of searching, so it must yield to what is already drawn or
        // it simply overprints it. Step up over each substantial blocker; if clearing them would push
        // the label out of the plot, drop it. Dropping is the right end state here: a second
        // ACTUAL_HIGH this close to a placed one comes from near-equal turning points on the same
        // plateau, where it says nothing the survivor does not.
        val labelHeight = labelDescent - labelAscent
        var bounds = GraphRect(left, top, right, bottom)
        val stackGapPx = ACTUAL_EXTREME_STACK_GAP_DP * density
        var steps = 0
        while (steps++ < ACTUAL_EXTREME_STACK_MAX_STEPS) {
            if (!forcedPlacerBlocked(candidate.role, bounds, isValley = false, placeAbove = true, drawnLabelMetas, drawnIconBounds, reservedHardBounds, labelHeight)) break
            val blocker = firstBlockerBounds(bounds, drawnLabelMetas, drawnIconBounds, reservedHardBounds) ?: break
            val shift = bounds.bottom - blocker.top + stackGapPx
            top -= shift
            bottom -= shift
            baselineY -= shift
            bounds = GraphRect(left, top, right, bottom)
        }
        if (top < 0f || forcedPlacerBlocked(candidate.role, bounds, isValley = false, placeAbove = true, drawnLabelMetas, drawnIconBounds, reservedHardBounds, labelHeight)) {
            Log.d(
                TAG,
                "PlaceDrop: role=${candidate.role} idx=$idx reason=noRoomAboveActualCurve " +
                    "top=${top.toInt()} steps=${steps - 1} " +
                    "overlap=${GraphLabelPlacementUtils.maxVerticalOverlap(bounds, drawnLabelMetas.map { it.bounds }).toInt()} " +
                    "labelHeight=${labelHeight.toInt()}",
            )
            return
        }
        val drawLeader = geometry.sy - bottom > labelDescent

        resultPlacements.add(
            PlacedLabel(
                index = idx,
                role = candidate.role,
                text = geometry.label,
                x = geometry.clampedX,
                baselineY = baselineY,
                placedAbove = true,
                drawLeaderLine = drawLeader,
                leaderFromY = geometry.sy,
                leaderToY = bottom,
                isFuture = geometry.isFuture,
                rawTemperature = candidate.rawTemperature,
                displayTemperature = temps[idx],
                reason = "aboveActualCurve",
                displacementSteps = 0,
            )
        )
        drawnLabelMetas.add(PlacedLabelMeta(bounds, isValleyBelow = false, role = candidate.role, temperature = temps[idx]))
    }

    // Places the observed-low label tight below the actual line's local trough. Used as a fallback
    // when the normal below direction is blocked by curve intrusion (instead of flipping above through
    // the lines). Scans the actual curve across the label's x-span for the lowest point (largest y)
    // so the label clears the jagged trough, then sits one tight gap below. Clamps to the bottom edge
    // when the trough is too close to the bottom to leave a full gap. No leader line — the label sits
    // right at the trough.
    private fun placeActualLowBelowCurve(
        heightPx: Int,
        density: Float,
        actualVisiblePoints: List<Pair<Float, Float>>,
        candidate: TempLabelCandidate,
        geometry: ResolvedLabelGeometry,
        labelAscent: Float,
        labelDescent: Float,
        drawnLabelMetas: MutableList<PlacedLabelMeta>,
        drawnIconBounds: List<GraphRect>,
        reservedHardBounds: List<GraphRect>,
        idx: Int,
        temps: List<Float>,
        resultPlacements: MutableList<PlacedLabel>,
    ) {
        val halfWidth = geometry.textWidth / 2f
        val left = geometry.clampedX - halfWidth
        val right = geometry.clampedX + halfWidth

        // Lowest visible actual point beneath the label (fall back to the hourly anchor).
        var curveBottomY = geometry.sy
        for ((px, py) in actualVisiblePoints) {
            if (px in left..right && py > curveBottomY) curveBottomY = py
        }

        val gapBelowPx = GraphLabelPlacementUtils.TEMP_ACTUAL_LOW_BELOW_GAP_DP * density
        val placement = GraphLabelPlacementUtils.computeLabelVerticalPlacement(
            pointY = curveBottomY,
            placeAbove = false,
            gapPx = gapBelowPx,
            textAscent = labelAscent,
            textDescent = labelDescent,
        )
        var top = placement.top
        var bottom = placement.bottom
        var baselineY = placement.baselineY
        if (bottom > heightPx) {
            val shift = heightPx - bottom
            top += shift
            bottom += shift
            baselineY += shift
        }
        // Same yield rule as placeActualHighAboveCurve, mirrored downward. Note this placer is reached
        // exactly when a LABEL blocked the normal direction, so a grazing overlap is the expected case,
        // not the exception — which is precisely why the budget, and not an any-pixel test, decides.
        val labelHeight = labelDescent - labelAscent
        var bounds = GraphRect(left, top, right, bottom)
        val stackGapPx = ACTUAL_EXTREME_STACK_GAP_DP * density
        var steps = 0
        while (steps++ < ACTUAL_EXTREME_STACK_MAX_STEPS) {
            if (!forcedPlacerBlocked(candidate.role, bounds, isValley = true, placeAbove = false, drawnLabelMetas, drawnIconBounds, reservedHardBounds, labelHeight)) break
            val blocker = firstBlockerBounds(bounds, drawnLabelMetas, drawnIconBounds, reservedHardBounds) ?: break
            val shift = blocker.bottom - bounds.top + stackGapPx
            top += shift
            bottom += shift
            baselineY += shift
            bounds = GraphRect(left, top, right, bottom)
        }
        if (bottom > heightPx || forcedPlacerBlocked(candidate.role, bounds, isValley = true, placeAbove = false, drawnLabelMetas, drawnIconBounds, reservedHardBounds, labelHeight)) {
            Log.d(
                TAG,
                "PlaceDrop: role=${candidate.role} idx=$idx reason=noRoomBelowActualCurve " +
                    "bottom=${bottom.toInt()} heightPx=$heightPx steps=${steps - 1} " +
                    "overlap=${GraphLabelPlacementUtils.maxVerticalOverlap(bounds, drawnLabelMetas.map { it.bounds }).toInt()} " +
                    "labelHeight=${labelHeight.toInt()}",
            )
            return
        }

        resultPlacements.add(
            PlacedLabel(
                index = idx,
                role = candidate.role,
                text = geometry.label,
                x = geometry.clampedX,
                baselineY = baselineY,
                placedAbove = false,
                drawLeaderLine = false,
                leaderFromY = geometry.sy,
                leaderToY = geometry.sy,
                isFuture = geometry.isFuture,
                rawTemperature = candidate.rawTemperature,
                displayTemperature = temps[idx],
                reason = "belowActualCurve",
                displacementSteps = 0,
            )
        )
        drawnLabelMetas.add(PlacedLabelMeta(bounds, isValleyBelow = true, role = candidate.role, temperature = temps[idx]))
    }

    private fun tryExactFitCurveAvoidance(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        actualVisiblePoints: List<Pair<Float, Float>>,
        forecastPoints: List<Pair<Float, Float>>,
        candidate: TempLabelCandidate,
        geometry: ResolvedLabelGeometry,
        directions: List<Boolean>,
        gapDp: GraphLabelPlacementUtils.LabelGapDp,
        labelAscent: Float,
        labelDescent: Float,
        drawnLabelMetas: MutableList<PlacedLabelMeta>,
        drawnIconBounds: List<GraphRect>,
        reservedHardBounds: List<GraphRect>,
        idx: Int,
        temps: List<Float>,
        resultPlacements: MutableList<PlacedLabel>,
        allActualVisiblePoints: List<Pair<Float, Float>>,
    ): Boolean {
        val allowedDipPx = CollisionTester.allowedDipPxFor(candidate.role, density, labelDescent - labelAscent)
        for (placeAbove in directions) {
            val outcome = tryExactFitForDirection(
                widthPx = widthPx,
                heightPx = heightPx,
                density = density,
                actualVisiblePoints = actualVisiblePoints,
                forecastPoints = forecastPoints,
                candidate = candidate,
                geometry = geometry,
                placeAbove = placeAbove,
                gapDp = gapDp,
                labelAscent = labelAscent,
                labelDescent = labelDescent,
                drawnLabelMetas = drawnLabelMetas,
                drawnIconBounds = drawnIconBounds,
                reservedHardBounds = reservedHardBounds,
                idx = idx,
                temps = temps,
                allowedDipPx = allowedDipPx,
                resultPlacements = resultPlacements,
            )
            Log.d(TAG, "ExactFitOutcome: role=${candidate.role} idx=$idx placeAbove=$placeAbove outcome=$outcome")
            when (outcome) {
                ExactFitOutcome.NATURAL_FITS -> return false
                ExactFitOutcome.PLACED -> return true
                ExactFitOutcome.LABEL_OR_ICON_BLOCKED -> {
                    // ACTUAL_LOW: when the normal below direction is blocked (curve intrusion),
                    // try tight-below-trough placement instead of falling through to above.
                    // Gated on idx !in leftEdgeOrder to preserve left-edge pairing behavior.
                    if (!placeAbove && candidate.role == TemperatureRole.ACTUAL_LOW) {
                        placeActualLowBelowCurve(
                            heightPx = heightPx,
                            density = density,
                            actualVisiblePoints = allActualVisiblePoints,
                            candidate = candidate,
                            geometry = geometry,
                            labelAscent = labelAscent,
                            labelDescent = labelDescent,
                            drawnLabelMetas = drawnLabelMetas,
                            drawnIconBounds = drawnIconBounds,
                            reservedHardBounds = reservedHardBounds,
                            idx = idx,
                            temps = temps,
                            resultPlacements = resultPlacements,
                        )
                        return true
                    }
                    continue
                }
                ExactFitOutcome.GAVE_UP -> return false
            }
        }
        return false
    }

    private enum class ExactFitOutcome { NATURAL_FITS, PLACED, LABEL_OR_ICON_BLOCKED, GAVE_UP }

    private sealed class ExactFitBlockerResult {
        object NaturalFits : ExactFitBlockerResult()
        object LabelOrIconBlocked : ExactFitBlockerResult()
        data class CurveOnly(val intrusion: CurveIntrusion, val baseBounds: GraphRect, val baseGapPx: Float) : ExactFitBlockerResult()
    }

    private fun checkExactFitBlockers(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        actualVisiblePoints: List<Pair<Float, Float>>,
        forecastPoints: List<Pair<Float, Float>>,
        candidate: TempLabelCandidate,
        geometry: ResolvedLabelGeometry,
        placeAbove: Boolean,
        gapDp: GraphLabelPlacementUtils.LabelGapDp,
        labelAscent: Float,
        labelDescent: Float,
        drawnLabelMetas: List<PlacedLabelMeta>,
        drawnIconBounds: List<GraphRect>,
        reservedHardBounds: List<GraphRect>,
        idx: Int,
    ): ExactFitBlockerResult {
        val baseGapPx = if (placeAbove) gapDp.aboveDp * density else gapDp.belowDp * density
        val baseV = GraphLabelPlacementUtils.computeLabelVerticalPlacement(
            pointY = geometry.sy, placeAbove = placeAbove,
            gapPx = baseGapPx, textAscent = labelAscent, textDescent = labelDescent
        )
        val baseBounds = GraphRect(
            geometry.clampedX - geometry.textWidth / 2f, baseV.top,
            geometry.clampedX + geometry.textWidth / 2f, baseV.bottom
        )
        val labelHeight = labelDescent - labelAscent
        val obstacles = CollisionTester.obstacles(
            role = candidate.role,
            bounds = baseBounds,
            isValley = geometry.isValley,
            placeAbove = placeAbove,
            drawnLabelMetas = drawnLabelMetas,
            drawnIconBounds = drawnIconBounds,
            reservedHardBounds = reservedHardBounds,
            labelHeight = labelHeight,
        )
        val allowedDip = CollisionTester.allowedDipPxFor(candidate.role, density, labelHeight)
        val curveResult = CollisionTester.curve(
            role = candidate.role,
            bounds = baseBounds,
            placeAbove = placeAbove,
            avoidanceActualPoints = actualVisiblePoints,
            forecastPoints = forecastPoints,
            allowedDipPx = allowedDip,
            isCurveAvoidanceExempt = false,
            flipDecided = false,
        )

        Log.d(TAG, "ExactFitPreCheck: role=${candidate.role} idx=$idx placeAbove=$placeAbove anchorY=${String.format("%.1f", geometry.sy)} baseBounds=(${baseBounds.left},${baseBounds.top},${baseBounds.right},${baseBounds.bottom}) intrusion=${if (curveResult.intrusion.isEmpty) "none" else "minY=${String.format("%.1f", curveResult.intrusion.minY)} maxY=${String.format("%.1f", curveResult.intrusion.maxY)}"} labelBlocker=${obstacles.labelOrHardBlocked} iconBlocker=${obstacles.iconBlocked} hardBlocker=${obstacles.overlapsHard} allowedDip=${String.format("%.1f", allowedDip)} curveDip=${String.format("%.2f", curveResult.curveDipDepth)} tolerant=${candidate.role == TemperatureRole.ACTUAL_LOW || candidate.role == TemperatureRole.LOCAL}")

        if ((curveResult.intrusion.isEmpty || curveResult.curveWithinDip) && !obstacles.labelOrHardBlocked && !obstacles.iconBlocked) {
            return ExactFitBlockerResult.NaturalFits
        }
        if (obstacles.labelOrHardBlocked || obstacles.iconBlocked) {
            return ExactFitBlockerResult.LabelOrIconBlocked
        }
        return ExactFitBlockerResult.CurveOnly(curveResult.intrusion, baseBounds, baseGapPx)
    }

    private fun tryExactFitForDirection(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        actualVisiblePoints: List<Pair<Float, Float>>,
        forecastPoints: List<Pair<Float, Float>>,
        candidate: TempLabelCandidate,
        geometry: ResolvedLabelGeometry,
        placeAbove: Boolean,
        gapDp: GraphLabelPlacementUtils.LabelGapDp,
        labelAscent: Float,
        labelDescent: Float,
        drawnLabelMetas: MutableList<PlacedLabelMeta>,
        drawnIconBounds: List<GraphRect>,
        reservedHardBounds: List<GraphRect>,
        idx: Int,
        temps: List<Float>,
        allowedDipPx: Float,
        resultPlacements: MutableList<PlacedLabel>,
    ): ExactFitOutcome {
        val blockerResult = checkExactFitBlockers(
            widthPx = widthPx, heightPx = heightPx, density = density,
            actualVisiblePoints = actualVisiblePoints, forecastPoints = forecastPoints,
            candidate = candidate, geometry = geometry, placeAbove = placeAbove,
            gapDp = gapDp, labelAscent = labelAscent, labelDescent = labelDescent,
            drawnLabelMetas = drawnLabelMetas, drawnIconBounds = drawnIconBounds,
            reservedHardBounds = reservedHardBounds, idx = idx
        )
        when (blockerResult) {
            is ExactFitBlockerResult.NaturalFits -> return ExactFitOutcome.NATURAL_FITS
            is ExactFitBlockerResult.LabelOrIconBlocked -> return ExactFitOutcome.LABEL_OR_ICON_BLOCKED
            is ExactFitBlockerResult.CurveOnly -> {
                // Forecast curve often dips below the actual valley. A shallow dip is acceptable
                // partial overlap: keep the low label flush below its valley (handled by the main
                // step loop) instead of flipping it above with a long leader line. Only a deep dip
                // warrants flipping above.
                if (candidate.role == TemperatureRole.ACTUAL_LOW && !placeAbove) {
                    val dipDepth = blockerResult.intrusion.maxY - blockerResult.baseBounds.top
                    return if (dipDepth > allowedDipPx) {
                        ExactFitOutcome.LABEL_OR_ICON_BLOCKED
                    } else {
                        ExactFitOutcome.GAVE_UP
                    }
                }
                // LOCAL (midpoints) also tolerates small grazes. If within tolerance, fall back
                // so the normal step-0 path can place it flush (no forced curveFit leader).
                if (candidate.role == TemperatureRole.LOCAL) {
                    val dipDepth = if (placeAbove) {
                        blockerResult.baseBounds.bottom - blockerResult.intrusion.minY
                    } else {
                        blockerResult.intrusion.maxY - blockerResult.baseBounds.top
                    }
                    if (dipDepth <= allowedDipPx) {
                        return ExactFitOutcome.GAVE_UP
                    }
                }
                // Actual curve rises from the start point into the above-space; prefer below.
                if (candidate.role == TemperatureRole.START && placeAbove) {
                    return ExactFitOutcome.LABEL_OR_ICON_BLOCKED
                }
                val extra = if (placeAbove) {
                    blockerResult.baseBounds.bottom - blockerResult.intrusion.minY + CURVE_AVOIDANCE_CLEAR_PX - allowedDipPx
                } else {
                    blockerResult.intrusion.maxY + CURVE_AVOIDANCE_CLEAR_PX - allowedDipPx - blockerResult.baseBounds.top
                }
                if (extra <= 0f) return ExactFitOutcome.GAVE_UP

                val newGapPx = blockerResult.baseGapPx + extra
                val newV = GraphLabelPlacementUtils.computeLabelVerticalPlacement(
                    pointY = geometry.sy, placeAbove = placeAbove,
                    gapPx = newGapPx, textAscent = labelAscent, textDescent = labelDescent
                )
                val newBounds = GraphRect(
                    geometry.clampedX - geometry.textWidth / 2f, newV.top,
                    geometry.clampedX + geometry.textWidth / 2f, newV.bottom
                )
                if (newBounds.top < 0f || newBounds.bottom > heightPx) {
                    Log.d(TAG, "CurveAdjust: role=${candidate.role} idx=$idx FAILED offscreen newBounds=(${newBounds.left},${newBounds.top},${newBounds.right},${newBounds.bottom})")
                    return ExactFitOutcome.GAVE_UP
                }
                val overlapsLabel = drawnLabelMetas.any { it.bounds.intersects(newBounds) }
                val overlapsIcon = drawnIconBounds.any { it.intersects(newBounds) }
                val overlapsHard = reservedHardBounds.any { it.intersects(newBounds) }
                if (overlapsLabel || overlapsIcon || overlapsHard) {
                    Log.d(TAG, "CurveAdjust: role=${candidate.role} idx=$idx FAILED overlapsLabel=$overlapsLabel overlapsIcon=$overlapsIcon overlapsHard=$overlapsHard")
                    return ExactFitOutcome.GAVE_UP
                }
                val residual = combinedCurveIntrusion(actualVisiblePoints, forecastPoints, newBounds)
                if (!residual.isEmpty) {
                    val residualDepth = if (placeAbove) newBounds.bottom - residual.maxY else residual.minY - newBounds.top
                    if (residualDepth > allowedDipPx + 1f) {
                        Log.d(TAG, "CurveAdjust: role=${candidate.role} idx=$idx FAILED residualCurveIntrusion depth=${String.format("%.1f", residualDepth)} allowedDip=${String.format("%.1f", allowedDipPx)}")
                        return ExactFitOutcome.GAVE_UP
                    }
                }

                val lineEndY = if (placeAbove) newBounds.bottom else newBounds.top
                resultPlacements.add(
                    PlacedLabel(
                        index = idx,
                        role = candidate.role,
                        text = geometry.label,
                        x = geometry.clampedX,
                        baselineY = newV.baselineY,
                        placedAbove = placeAbove,
                        drawLeaderLine = true,
                        leaderFromY = geometry.sy,
                        leaderToY = lineEndY,
                        isFuture = geometry.isFuture,
                        rawTemperature = candidate.rawTemperature,
                        displayTemperature = temps[idx],
                        reason = "${if (placeAbove) "above" else "below"}+curveFit(${String.format("%.1f", extra)}px)",
                        displacementSteps = 1,
                    )
                )
                drawnLabelMetas.add(PlacedLabelMeta(newBounds, isValleyBelow = !placeAbove && geometry.isValley, role = candidate.role, temperature = temps[idx]))
                return ExactFitOutcome.PLACED
            }
        }
    }

    private data class CascadeResult(
        val x: Float,
        val baselineY: Float,
        val bounds: GraphRect,
        val reason: String,
    )

    private sealed class ValleyCascadeOutcome {
        data class Below(val result: CascadeResult) : ValleyCascadeOutcome()
        object FlipAbove : ValleyCascadeOutcome()
        object None : ValleyCascadeOutcome()
    }

    private fun tryValleyBelowCascade(
        widthPx: Int,
        heightPx: Int,
        candidate: TempLabelCandidate,
        geometry: ResolvedLabelGeometry,
        verticalPlacement: GraphLabelPlacementUtils.LabelVerticalPlacement,
        drawnLabelMetas: List<PlacedLabelMeta>,
        drawnIconBounds: List<GraphRect>,
        reservedHardBounds: List<GraphRect>,
        labelHeight: Float,
    ): ValleyCascadeOutcome {
        val centerX = geometry.clampedX
        val halfWidth = geometry.textWidth / 2f

        val centeredBounds = GraphRect(centerX - halfWidth, verticalPlacement.top, centerX + halfWidth, verticalPlacement.bottom)
        val drawnBoundsList = drawnLabelMetas.map { it.bounds }

        val collidingMeta = drawnLabelMetas.firstOrNull { it.bounds.intersects(centeredBounds) }
        if (collidingMeta == null) return ValleyCascadeOutcome.None

        val horizontalOverlap = maxOf(0f, minOf(centeredBounds.right, collidingMeta.bounds.right) - maxOf(centeredBounds.left, collidingMeta.bounds.left))
        val verticalOverlap = maxOf(0f, minOf(centeredBounds.bottom, collidingMeta.bounds.bottom) - maxOf(centeredBounds.top, collidingMeta.bounds.top))

        val shiftAmount = horizontalOverlap * GraphLabelPlacementUtils.VALLEY_HORIZONTAL_SHIFT_FRACTION
        for (shiftSign in listOf(-1, 1)) {
            val shiftedX = centerX + shiftSign * shiftAmount
            val shiftedBounds = GraphRect(shiftedX - halfWidth, verticalPlacement.top, shiftedX + halfWidth, verticalPlacement.bottom)
            val onScreen = shiftedBounds.top >= 0f && shiftedBounds.bottom <= heightPx &&
                shiftedBounds.left >= 0f && shiftedBounds.right <= widthPx
            if (!onScreen) continue

            val overlapsLabel = drawnBoundsList.any { it.intersects(shiftedBounds) }
            val overlapsIcon = drawnIconBounds.any { it.intersects(shiftedBounds) }
            val overlapsHard = reservedHardBounds.any { it.intersects(shiftedBounds) }
            if (!overlapsLabel && !overlapsIcon && !overlapsHard) {
                return ValleyCascadeOutcome.Below(
                    CascadeResult(
                        x = shiftedX,
                        baselineY = verticalPlacement.baselineY,
                        bounds = shiftedBounds,
                        reason = "below-shifted",
                    )
                )
            }
        }

        // The centered below-fallbacks below would sit on the hard bound; only take them when the
        // centered slot is clear of hard obstacles, otherwise fall through so the label flips above.
        val centeredOverlapsHard = reservedHardBounds.any { it.intersects(centeredBounds) }

        val overlapRatio = verticalOverlap / labelHeight
        if (!centeredOverlapsHard && overlapRatio <= GraphLabelPlacementUtils.VALLEY_BELOW_LABEL_OVERLAP_RATIO) {
            if (shouldLogPlacement(candidate.role)) {
                Log.d(TAG, "LabelCascade: role=${candidate.role} option2-accepted ratio=${String.format("%.2f", overlapRatio)} threshold=${GraphLabelPlacementUtils.VALLEY_BELOW_LABEL_OVERLAP_RATIO}")
            }
            return ValleyCascadeOutcome.Below(
                CascadeResult(
                    x = centerX,
                    baselineY = verticalPlacement.baselineY,
                    bounds = centeredBounds,
                    reason = "below-relaxed",
                )
            )
        }

        if (collidingMeta.isValleyBelow) {
            val currentTemp = geometry.displayTemperature
            val collidingTemp = collidingMeta.temperature
            if (currentTemp > collidingTemp) {
                if (shouldLogPlacement(candidate.role)) {
                    Log.d(TAG, "LabelCascade: role=${candidate.role} flip-above-warmer current=${String.format("%.1f", currentTemp)} colliding=${String.format("%.1f", collidingTemp)} collidingRole=${collidingMeta.role} ratio=${String.format("%.2f", overlapRatio)}")
                }
                return ValleyCascadeOutcome.FlipAbove
            }
            if (!centeredOverlapsHard && overlapRatio <= GraphLabelPlacementUtils.VALLEY_VS_VALLEY_OVERLAP_RATIO) {
                if (shouldLogPlacement(candidate.role)) {
                    Log.d(TAG, "LabelCascade: role=${candidate.role} option1-accepted ratio=${String.format("%.2f", overlapRatio)} threshold=${GraphLabelPlacementUtils.VALLEY_VS_VALLEY_OVERLAP_RATIO} collidingRole=${collidingMeta.role}")
                }
                return ValleyCascadeOutcome.Below(
                    CascadeResult(
                        x = centerX,
                        baselineY = verticalPlacement.baselineY,
                        bounds = centeredBounds,
                        reason = "below-valley-overlap",
                    )
                )
            }
        }

        if (shouldLogPlacement(candidate.role)) {
            Log.d(TAG, "LabelCascade: role=${candidate.role} all-options-exhausted ratio=${String.format("%.2f", overlapRatio)} collidingIsValleyBelow=${collidingMeta.isValleyBelow}")
        }

        return ValleyCascadeOutcome.None
    }

}
