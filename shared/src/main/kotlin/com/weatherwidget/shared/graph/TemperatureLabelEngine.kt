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
    private const val MAX_LEADER_DISPLACEMENT_STEPS = 3


    private val LOGGED_ROLES: Set<TemperatureRole> = setOf(
        TemperatureRole.ACTUAL_LOW, TemperatureRole.LOW,
        TemperatureRole.ACTUAL_HIGH, TemperatureRole.HIGH,
        TemperatureRole.ACTUAL_END, TemperatureRole.LOCAL,
        TemperatureRole.START, TemperatureRole.END,
    )

    internal fun shouldLogPlacement(role: TemperatureRole): Boolean = role in LOGGED_ROLES

    // prefersAbovePlacement looks at ±VALUE_NEIGHBOR_WINDOW samples and places a value above its
    // curve when the local maximum is within SIGNIFICANT_MAX_GAP degrees of it — i.e. the value sits
    // on (or very near) the top of its neighbourhood. 5 samples and 1° were tuned so a START/END/LOCAL
    // value on a shallow local crest stays above the line instead of flipping below into a pocket.
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
    // would both be force-placed above and stack (the actual via ActualExtremePlacers.place); drop
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
    // ACTUAL_HIGH uses ActualExtremePlacers.place; START/END/ACTUAL_END keep full avoidance.)
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

        Log.v(TAG, "EngineInput: heightPx=$heightPx widthPx=$widthPx fetchDotX=${fetchDotX?.let { String.format("%.1f", it) }} transitionX=${transitionX?.let { String.format("%.1f", it) }} labelHeight=${String.format("%.1f", labelHeight)} hardBounds=${reservedHardBounds.map { "(${String.format("%.1f", it.left)},${String.format("%.1f", it.top)},${String.format("%.1f", it.right)},${String.format("%.1f", it.bottom)})" }}")

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
                ActualExtremePlacers.place(
                    placeAbove = true,
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

            // A forced-above ACTUAL_LOW is in the same predicament as the observed high: it has been
            // pushed to the WRONG side of its own trough (so it reads in temperature order above a
            // cooler neighbouring low), which puts the whole local hump of the observed line between
            // the label and the sky. The normal loop anchors the box a fixed gap off the trough and
            // draws it straight across that hump — the Samsung "60.9° on the pink line" report. Use
            // the same forced placer the observed high uses: it sits above the highest observed
            // point across the label's own x-span. If it cannot fit, fall through to the normal loop
            // rather than dropping the label, since below is still a legitimate slot for a low.
            if (candidate.role == TemperatureRole.ACTUAL_LOW && forceAbove && labelKey !in leftEdgeOrder) {
                val before = resultPlacements.size
                ActualExtremePlacers.place(
                    placeAbove = true,
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
                if (resultPlacements.size > before) continue
            }

            // The left-edge START/actual pair sits flush against its own line start (color-matched,
            // ordered by value), so skip curve avoidance entirely for it.
            val isCurveAvoidanceExempt = labelKey in leftEdgeOrder

            // Forecast-series labels avoid only the FORECAST curve, never the actual curve: a
            // forecast extreme nested under a much taller/deeper actual curve must sit flush on its
            // own forecast peak/valley rather than being driven off-anchor with a long leader line.
            // START/END/ACTUAL_END keep full avoidance.
            //
            // ACTUAL_LOW's carve-out is DIRECTIONAL. Below its trough it ignores its own observed
            // line, because the sub-hourly points dip a few px under the labeled hourly minimum and
            // that graze used to shove the label off-anchor. Above, that reasoning inverts: the
            // whole diurnal hump of the observed line stands between the label and the sky, so an
            // ACTUAL_LOW flipped above (value-ordering via forcedAboveLows) must see it or it gets
            // stamped straight onto the pink line. The cascade-flip case keeps its own graze
            // exemption via CollisionTester's allowFlippedAboveCurveGraze.
            val avoidanceActualPointsFor = { placeAbove: Boolean ->
                val exempt = candidate.role in FORECAST_ONLY_AVOIDANCE_ROLES ||
                    (candidate.role == TemperatureRole.ACTUAL_LOW && !placeAbove)
                if (exempt) emptyList() else actualVisiblePoints
            }

            if (candidate.role in CollisionTester.CURVE_AVOIDANCE_ROLES && !isCurveAvoidanceExempt) {
                placed = CurveFitPlacer.tryExactFit(
                    heightPx = heightPx,
                    density = density,
                    avoidanceActualPointsFor = avoidanceActualPointsFor,
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
            val allowedCurveDipPxFor = { placeAbove: Boolean ->
                CollisionTester.allowedDipPxFor(candidate.role, density, labelDescent - labelAscent, placeAbove)
            }

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
                        avoidanceActualPoints = avoidanceActualPointsFor(placeAbove),
                        forecastPoints = forecastPoints,
                        allowedDipPx = allowedCurveDipPxFor(placeAbove),
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
                        val outcome = ValleyCascade.tryBelow(
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
                            is ValleyCascade.Outcome.Below -> {
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
                            ValleyCascade.Outcome.FlipAbove -> {
                                flipDecided = true
                                continue
                            }
                            ValleyCascade.Outcome.None -> Unit
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

            // FORCED is the essential-label floor: an essential label that could not be placed cleanly
            // is still emitted at its last computed slot rather than dropped. This is deliberately
            // stricter than the forced-direction placers (ActualExtremePlacers), which DROP a
            // near-coincident second ACTUAL_HIGH/LOW: those are redundant by construction, whereas an
            // essential label must never vanish.
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

}
