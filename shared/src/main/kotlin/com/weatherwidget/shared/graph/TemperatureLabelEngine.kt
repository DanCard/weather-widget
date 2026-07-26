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
    private const val MIN_INTERPOLATION_SPAN = 0.0001f
    private const val CURVE_AVOIDANCE_MARGIN_PX = 0.5f
    private const val CURVE_AVOIDANCE_CLEAR_PX = 1.5f
    private const val CURVE_AVOIDANCE_ALLOWED_DIP_DP = 5f
    private const val MAX_LEADER_DISPLACEMENT_STEPS = 3

    // ACTUAL_LOW tolerates more forecast-curve overlap than other roles: a low label belongs
    // below its valley, and a shallow forecast dip under that valley is acceptable partial
    // overlap rather than a reason to push the label off-anchor with a long leader line.
    // Expressed as a fraction of label height so the tolerance scales with the glyphs.
    private const val ACTUAL_LOW_FORECAST_OVERLAP_RATIO = 0.5f

    // LOCAL (forecast midpoints and some interior value labels) get a modest curve graze tolerance.
    // Non-extremum points on the body of the forecast curve frequently produce tiny line intrusions
    // into the label box even at the preferred 1dp gap (due to slope + box width + sampling margins).
    // Allowing a small graze (3dp) avoids unnecessary full-height leaders while still preventing
    // obvious visual overlap with the drawn line.
    private const val LOCAL_CURVE_GRAZE_DP = 3f

    private fun allowedDipPxFor(role: TemperatureRole, density: Float, labelHeight: Float): Float =
        when (role) {
            TemperatureRole.ACTUAL_LOW -> labelHeight * ACTUAL_LOW_FORECAST_OVERLAP_RATIO
            TemperatureRole.LOCAL -> LOCAL_CURVE_GRAZE_DP * density
            else -> CURVE_AVOIDANCE_ALLOWED_DIP_DP * density
        }

    private val CURVE_AVOIDANCE_ROLES: Set<TemperatureRole> = setOf(
        TemperatureRole.ACTUAL_END,
        TemperatureRole.ACTUAL_HIGH,
        TemperatureRole.ACTUAL_LOW,
        TemperatureRole.HIGH,
        TemperatureRole.LOW,
        TemperatureRole.LOCAL,
        TemperatureRole.START,
        TemperatureRole.END,
    )

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

            if (candidate.role in CURVE_AVOIDANCE_ROLES && !isCurveAvoidanceExempt) {
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
            val allowedCurveDipPx = allowedDipPxFor(candidate.role, density, labelDescent - labelAscent)

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

                    val drawnBoundsList = drawnLabelMetas.map { it.bounds }
                    val overlapsLabel = drawnBoundsList.any { it.intersects(bounds) }
                    val overlapsIcon = drawnIconBounds.any { it.intersects(bounds) }
                    val labelOverlap = if (overlapsLabel) GraphLabelPlacementUtils.maxVerticalOverlap(bounds, drawnBoundsList) else 0f
                    val iconOverlap = if (overlapsIcon) GraphLabelPlacementUtils.maxVerticalOverlap(bounds, drawnIconBounds) else 0f

                    val currentIconRatio = if (!placeAbove && geometry.isValley) GraphLabelPlacementUtils.MINOR_OVERLAP_ICON_RATIO else GraphLabelPlacementUtils.MINOR_OVERLAP_HEIGHT_RATIO

                    val allowMinorLabelOverlap = overlapsLabel && GraphLabelPlacementUtils.shouldAllowMinorOverlap(candidate.role, labelOverlap, labelHeight)
                    val allowMinorIconOverlap = overlapsIcon && GraphLabelPlacementUtils.isMinorOverlapEligible(candidate.role) && iconOverlap <= labelHeight * currentIconRatio

                    val curveAvoidanceEligible = candidate.role in CURVE_AVOIDANCE_ROLES
                    val curveIntrusion = if (curveAvoidanceEligible) combinedCurveIntrusion(avoidanceActualPoints, forecastPoints, bounds) else CurveIntrusion.NONE
                    // ACTUAL_LOW and LOCAL (midpoints) tolerate a shallow forecast-curve dip as partial
                    // overlap (keeps the label closer to the curve instead of displacing with a long leader);
                    // other roles do not. This softens the check for non-extrema points on the line.
                    val curveDipDepth = when {
                        curveIntrusion.isEmpty -> 0f
                        placeAbove -> bounds.bottom - curveIntrusion.minY
                        else -> curveIntrusion.maxY - bounds.top
                    }
                    val curveWithinDip = (candidate.role == TemperatureRole.ACTUAL_LOW || candidate.role == TemperatureRole.LOCAL) &&
                        !curveIntrusion.isEmpty && curveDipDepth <= allowedCurveDipPx
                    val allowFlippedAboveCurveGraze = flipDecided && placeAbove && curveAvoidanceEligible
                    val overlapsCurve = curveAvoidanceEligible && !curveIntrusion.isEmpty && !allowFlippedAboveCurveGraze && !isCurveAvoidanceExempt && !curveWithinDip

                    // Hard obstacles (fetch-dot value/age labels) tolerate the SAME minor (whitespace-
                    // level) overlap as placed labels: a label rect is built from full font
                    // ascent/descent, so a sub-budget overlap is empty leading, not glyph ink. This
                    // lets e.g. a NOW-valley ACTUAL_LOW hug its valley instead of dropping a full
                    // label-height with a long leader. A real overlap still exceeds the budget, so a
                    // forecast LOW landing squarely on the dot value still flips above (260612).
                    val overlapsHard = reservedHardBounds.any { it.intersects(bounds) }
                    val hardOverlap = if (overlapsHard) GraphLabelPlacementUtils.maxVerticalOverlap(bounds, reservedHardBounds) else 0f
                    val allowMinorHardOverlap = overlapsHard &&
                        GraphLabelPlacementUtils.shouldAllowMinorOverlap(candidate.role, hardOverlap, labelHeight) &&
                        GraphLabelPlacementUtils.hardOverlapIsSideOnly(bounds, reservedHardBounds)

                    val hasCollision = (overlapsLabel && !allowMinorLabelOverlap) || (overlapsIcon && !allowMinorIconOverlap) || overlapsCurve || (overlapsHard && !allowMinorHardOverlap)

                    if (hasCollision && shouldLogPlacement(candidate.role)) {
                        val curveDepth = if (overlapsCurve) curveDipDepth else 0f
                        Log.d(TAG, "PlaceReject: role=${candidate.role} idx=$idx step=$step above=$placeAbove " +
                            "blocker=[label=$overlapsLabel/${String.format("%.1f", labelOverlap)}(minorOK=$allowMinorLabelOverlap) " +
                            "icon=$overlapsIcon/${String.format("%.1f", iconOverlap)}(minorOK=$allowMinorIconOverlap) " +
                            "hard=$overlapsHard/${String.format("%.1f", hardOverlap)}(minorOK=$allowMinorHardOverlap) " +
                            "curve=$overlapsCurve depth=${String.format("%.2f", curveDepth)}] bounds=(${String.format("%.1f", bounds.top)},${String.format("%.1f", bounds.bottom)})")
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
                            Log.d(TAG, "PlaceAccept: role=${candidate.role} idx=$idx step=$step above=$placeAbove leader=$drawLeader hardOverlap=${String.format("%.1f", hardOverlap)} allowMinorHardOverlap=$allowMinorHardOverlap")
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
    private fun placeActualHighAboveCurve(
        heightPx: Int,
        density: Float,
        actualVisiblePoints: List<Pair<Float, Float>>,
        candidate: TempLabelCandidate,
        geometry: ResolvedLabelGeometry,
        labelAscent: Float,
        labelDescent: Float,
        drawnLabelMetas: MutableList<PlacedLabelMeta>,
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
        val bounds = GraphRect(left, top, right, bottom)
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
        val bounds = GraphRect(left, top, right, bottom)

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
        val allowedDipPx = allowedDipPxFor(candidate.role, density, labelDescent - labelAscent)
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
        val intrusion = combinedCurveIntrusion(actualVisiblePoints, forecastPoints, baseBounds)
        val baseOverlapsLabel = drawnLabelMetas.any { it.bounds.intersects(baseBounds) }
        val baseOverlapsIcon = drawnIconBounds.any { it.intersects(baseBounds) }
        val baseOverlapsHard = reservedHardBounds.any { it.intersects(baseBounds) }

        val labelHeight = labelDescent - labelAscent
        val drawnLabelBoundsList = drawnLabelMetas.map { it.bounds }
        val labelOverlapPx = if (baseOverlapsLabel) GraphLabelPlacementUtils.maxVerticalOverlap(baseBounds, drawnLabelBoundsList) else 0f
        val iconOverlapPx = if (baseOverlapsIcon) GraphLabelPlacementUtils.maxVerticalOverlap(baseBounds, drawnIconBounds) else 0f
        val iconOverlapRatio = if (!placeAbove && geometry.isValley) GraphLabelPlacementUtils.MINOR_OVERLAP_ICON_RATIO else GraphLabelPlacementUtils.MINOR_OVERLAP_HEIGHT_RATIO
        val hardOverlapPx = if (baseOverlapsHard) GraphLabelPlacementUtils.maxVerticalOverlap(baseBounds, reservedHardBounds) else 0f
        val allowMinorLabelOverlap = baseOverlapsLabel && GraphLabelPlacementUtils.shouldAllowMinorOverlap(candidate.role, labelOverlapPx, labelHeight)
        val allowMinorIconOverlap = baseOverlapsIcon && GraphLabelPlacementUtils.isMinorOverlapEligible(candidate.role) && iconOverlapPx <= labelHeight * iconOverlapRatio
        // Hard obstacles (fetch-dot value/age labels) tolerate the same minor (whitespace-level)
        // overlap as placed labels, so the pre-pass does not pre-emptively reject the below direction
        // for a sub-budget hit; a real (> budget) overlap still blocks and the caller flips above.
        val allowMinorHardOverlap = baseOverlapsHard &&
            GraphLabelPlacementUtils.shouldAllowMinorOverlap(candidate.role, hardOverlapPx, labelHeight) &&
            GraphLabelPlacementUtils.hardOverlapIsSideOnly(baseBounds, reservedHardBounds)
        val effectiveLabelBlocker = (baseOverlapsLabel && !allowMinorLabelOverlap) || (baseOverlapsHard && !allowMinorHardOverlap)
        val effectiveIconBlocker = baseOverlapsIcon && !allowMinorIconOverlap

        val curveDipDepth = if (intrusion.isEmpty) 0f else if (placeAbove) baseBounds.bottom - intrusion.minY else intrusion.maxY - baseBounds.top
        val isCurveTolerant = candidate.role == TemperatureRole.ACTUAL_LOW || candidate.role == TemperatureRole.LOCAL
        val thisAllowedDip = allowedDipPxFor(candidate.role, density, labelDescent - labelAscent)

        Log.d(TAG, "ExactFitPreCheck: role=${candidate.role} idx=$idx placeAbove=$placeAbove anchorY=${String.format("%.1f", geometry.sy)} baseBounds=(${baseBounds.left},${baseBounds.top},${baseBounds.right},${baseBounds.bottom}) intrusion=${if (intrusion.isEmpty) "none" else "minY=${String.format("%.1f", intrusion.minY)} maxY=${String.format("%.1f", intrusion.maxY)}"} labelBlocker=$effectiveLabelBlocker iconBlocker=$effectiveIconBlocker hardBlocker=$baseOverlapsHard allowedDip=${String.format("%.1f", thisAllowedDip)} curveDip=${String.format("%.2f", curveDipDepth)} tolerant=$isCurveTolerant")

        if ((intrusion.isEmpty || (isCurveTolerant && curveDipDepth <= thisAllowedDip)) && !effectiveLabelBlocker && !effectiveIconBlocker) {
            return ExactFitBlockerResult.NaturalFits
        }
        if (effectiveLabelBlocker || effectiveIconBlocker) {
            return ExactFitBlockerResult.LabelOrIconBlocked
        }
        return ExactFitBlockerResult.CurveOnly(intrusion, baseBounds, baseGapPx)
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

    internal data class CurveIntrusion(val minY: Float, val maxY: Float) {
        val isEmpty: Boolean get() = minY > maxY
        companion object {
            val NONE = CurveIntrusion(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
            fun merge(a: CurveIntrusion, b: CurveIntrusion): CurveIntrusion = when {
                a.isEmpty -> b
                b.isEmpty -> a
                else -> CurveIntrusion(minOf(a.minY, b.minY), maxOf(a.maxY, b.maxY))
            }
        }
    }

    private fun curveIntrusionInLabel(
        points: List<Pair<Float, Float>>,
        bounds: GraphRect,
    ): CurveIntrusion {
        if (points.size < 2) return CurveIntrusion.NONE
        val left = bounds.left - CURVE_AVOIDANCE_MARGIN_PX
        val right = bounds.right + CURVE_AVOIDANCE_MARGIN_PX
        val top = bounds.top - CURVE_AVOIDANCE_MARGIN_PX
        val bottom = bounds.bottom + CURVE_AVOIDANCE_MARGIN_PX
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val segMinX = minOf(a.first, b.first)
            val segMaxX = maxOf(a.first, b.first)
            if (segMaxX < left || segMinX > right) continue
            val span = (b.first - a.first)
            val ySegMin: Float
            val ySegMax: Float
            if (abs(span) < MIN_INTERPOLATION_SPAN) {
                ySegMin = a.second
                ySegMax = a.second
            } else {
                val xL = maxOf(segMinX, left)
                val xR = minOf(segMaxX, right)
                val tL = ((xL - a.first) / span).coerceIn(0f, 1f)
                val tR = ((xR - a.first) / span).coerceIn(0f, 1f)
                val yL = a.second + (b.second - a.second) * tL
                val yR = a.second + (b.second - a.second) * tR
                ySegMin = minOf(yL, yR)
                ySegMax = maxOf(yL, yR)
            }
            if (ySegMax < top || ySegMin > bottom) continue
            val clipMin = maxOf(ySegMin, top)
            val clipMax = minOf(ySegMax, bottom)
            if (clipMin < minY) minY = clipMin
            if (clipMax > maxY) maxY = clipMax
        }
        return CurveIntrusion(minY, maxY)
    }

    private fun combinedCurveIntrusion(
        actualVisiblePoints: List<Pair<Float, Float>>,
        forecastPoints: List<Pair<Float, Float>>,
        bounds: GraphRect
    ): CurveIntrusion {
        val a = curveIntrusionInLabel(actualVisiblePoints, bounds)
        val f = curveIntrusionInLabel(forecastPoints, bounds)
        return CurveIntrusion.merge(a, f)
    }
}
