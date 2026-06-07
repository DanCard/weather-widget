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
    ): List<PlacedLabel> {
        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, transitionX, effectiveActualEndIndex, fetchTime)
        val candidates = TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = effectiveActualEndIndex,
            transitionX = transitionX,
            observedAt = observedAt,
            numColumns = numColumns,
        ).toMutableList()

        TemperatureLabelResolver.sortLabelCandidates(candidates)

        val forcedAboveLows = computeForcedAboveLowIndices(candidates)
        val drawnLabelMetas = mutableListOf<PlacedLabelMeta>()
        val resultPlacements = mutableListOf<PlacedLabel>()

        val labelAscent = metrics.ascent
        val labelDescent = metrics.descent
        val labelHeight = metrics.height
        val gapDp = GraphLabelPlacementUtils.getLabelGapDp(isFallback = false)

        for (candidate in candidates) {
            val idx = candidate.index
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
            ) ?: continue

            val forceAbove = idx in forcedAboveLows
            val valueBasedRoles = candidate.role == TemperatureRole.ACTUAL_END ||
                candidate.role == TemperatureRole.LOCAL ||
                candidate.role == TemperatureRole.START ||
                candidate.role == TemperatureRole.END
            val preferAbove = when {
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

            if (candidate.role in CURVE_AVOIDANCE_ROLES) {
                placed = tryExactFitCurveAvoidance(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    density = density,
                    actualVisiblePoints = actualVisiblePoints,
                    forecastPoints = forecastPoints,
                    candidate = candidate,
                    geometry = geometry,
                    directions = directions,
                    gapDp = gapDp,
                    labelAscent = labelAscent,
                    labelDescent = labelDescent,
                    drawnLabelMetas = drawnLabelMetas,
                    drawnIconBounds = drawnIconBounds,
                    idx = idx,
                    temps = temps,
                    resultPlacements = resultPlacements,
                )
                if (placed) continue
            }

            val gapAbovePx = gapDp.aboveDp * density
            val gapBelowPx = gapDp.belowDp * density

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
                    val curveIntrusion = if (curveAvoidanceEligible) combinedCurveIntrusion(actualVisiblePoints, forecastPoints, bounds) else CurveIntrusion.NONE
                    val allowFlippedAboveCurveGraze = flipDecided && placeAbove && curveAvoidanceEligible
                    val overlapsCurve = curveAvoidanceEligible && !curveIntrusion.isEmpty && !allowFlippedAboveCurveGraze

                    val hasCollision = (overlapsLabel && !allowMinorLabelOverlap) || (overlapsIcon && !allowMinorIconOverlap) || overlapsCurve

                    if (hasCollision && !placeAbove && geometry.isValley && step == 0 && !flipDecided) {
                        val outcome = tryValleyBelowCascade(
                            widthPx = widthPx,
                            heightPx = heightPx,
                            candidate = candidate,
                            geometry = geometry,
                            verticalPlacement = verticalPlacement,
                            drawnLabelMetas = drawnLabelMetas,
                            drawnIconBounds = drawnIconBounds,
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
        idx: Int,
        temps: List<Float>,
        resultPlacements: MutableList<PlacedLabel>,
    ): Boolean {
        val allowedDipPx = CURVE_AVOIDANCE_ALLOWED_DIP_DP * density
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
                idx = idx,
                temps = temps,
                allowedDipPx = allowedDipPx,
                resultPlacements = resultPlacements,
            )
            when (outcome) {
                ExactFitOutcome.NATURAL_FITS -> return false
                ExactFitOutcome.PLACED -> return true
                ExactFitOutcome.LABEL_OR_ICON_BLOCKED -> continue
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

        val labelHeight = labelDescent - labelAscent
        val drawnLabelBoundsList = drawnLabelMetas.map { it.bounds }
        val labelOverlapPx = if (baseOverlapsLabel) GraphLabelPlacementUtils.maxVerticalOverlap(baseBounds, drawnLabelBoundsList) else 0f
        val iconOverlapPx = if (baseOverlapsIcon) GraphLabelPlacementUtils.maxVerticalOverlap(baseBounds, drawnIconBounds) else 0f
        val iconOverlapRatio = if (!placeAbove && geometry.isValley) GraphLabelPlacementUtils.MINOR_OVERLAP_ICON_RATIO else GraphLabelPlacementUtils.MINOR_OVERLAP_HEIGHT_RATIO
        val allowMinorLabelOverlap = baseOverlapsLabel && GraphLabelPlacementUtils.shouldAllowMinorOverlap(candidate.role, labelOverlapPx, labelHeight)
        val allowMinorIconOverlap = baseOverlapsIcon && GraphLabelPlacementUtils.isMinorOverlapEligible(candidate.role) && iconOverlapPx <= labelHeight * iconOverlapRatio
        val effectiveLabelBlocker = baseOverlapsLabel && !allowMinorLabelOverlap
        val effectiveIconBlocker = baseOverlapsIcon && !allowMinorIconOverlap

        Log.d(TAG, "ExactFitPreCheck: role=${candidate.role} idx=$idx placeAbove=$placeAbove anchorY=${String.format("%.1f", geometry.sy)} baseBounds=(${baseBounds.left},${baseBounds.top},${baseBounds.right},${baseBounds.bottom}) intrusion=${if (intrusion.isEmpty) "none" else "minY=${String.format("%.1f", intrusion.minY)} maxY=${String.format("%.1f", intrusion.maxY)}"} labelBlocker=$effectiveLabelBlocker iconBlocker=$effectiveIconBlocker allowedDip=${String.format("%.1f", CURVE_AVOIDANCE_ALLOWED_DIP_DP * density)}")

        if (intrusion.isEmpty && !effectiveLabelBlocker && !effectiveIconBlocker) {
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
            drawnLabelMetas = drawnLabelMetas, drawnIconBounds = drawnIconBounds, idx = idx
        )
        when (blockerResult) {
            is ExactFitBlockerResult.NaturalFits -> return ExactFitOutcome.NATURAL_FITS
            is ExactFitBlockerResult.LabelOrIconBlocked -> return ExactFitOutcome.LABEL_OR_ICON_BLOCKED
            is ExactFitBlockerResult.CurveOnly -> {
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
                    Log.d(TAG, "ExactFitPreCheck: role=${candidate.role} idx=$idx FAILED offscreen newBounds=(${newBounds.left},${newBounds.top},${newBounds.right},${newBounds.bottom})")
                    return ExactFitOutcome.GAVE_UP
                }
                val overlapsLabel = drawnLabelMetas.any { it.bounds.intersects(newBounds) }
                val overlapsIcon = drawnIconBounds.any { it.intersects(newBounds) }
                if (overlapsLabel || overlapsIcon) {
                    Log.d(TAG, "ExactFitPreCheck: role=${candidate.role} idx=$idx FAILED overlapsLabel=$overlapsLabel overlapsIcon=$overlapsIcon")
                    return ExactFitOutcome.GAVE_UP
                }
                val residual = combinedCurveIntrusion(actualVisiblePoints, forecastPoints, newBounds)
                if (!residual.isEmpty) {
                    val residualDepth = if (placeAbove) newBounds.bottom - residual.maxY else residual.minY - newBounds.top
                    if (residualDepth > allowedDipPx + 1f) {
                        Log.d(TAG, "ExactFitPreCheck: role=${candidate.role} idx=$idx FAILED residualCurveIntrusion depth=${String.format("%.1f", residualDepth)} allowedDip=${String.format("%.1f", allowedDipPx)}")
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
            if (!overlapsLabel && !overlapsIcon) {
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

        val overlapRatio = verticalOverlap / labelHeight
        if (overlapRatio <= GraphLabelPlacementUtils.VALLEY_BELOW_LABEL_OVERLAP_RATIO) {
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
            if (overlapRatio <= GraphLabelPlacementUtils.VALLEY_VS_VALLEY_OVERLAP_RATIO) {
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
