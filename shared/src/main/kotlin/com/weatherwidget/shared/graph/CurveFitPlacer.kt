package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.Log

/**
 * The curve-avoidance pre-pass. Before the main step loop searches for a slot, this pass tries to
 * place the label in its preferred direction by fitting it *exactly* around the curve — shifting the
 * gap so the box clears the curve's intrusion — rather than flipping to the opposite direction with
 * a leader line. It reuses [CollisionTester] for the obstacle/curve verdict and delegates the
 * ACTUAL_LOW tight-below-trough fallback to [ActualExtremePlacers].
 *
 * Extracted from [TemperatureLabelEngine] so the engine's placement loop stays a thin orchestrator.
 */
internal object CurveFitPlacer {
    private const val TAG = "CurveFitPlacer"
    private const val CURVE_AVOIDANCE_CLEAR_PX = 1.5f

    enum class Outcome { NATURAL_FITS, PLACED, LABEL_OR_ICON_BLOCKED, GAVE_UP }

    private sealed class BlockerResult {
        object NaturalFits : BlockerResult()
        object LabelOrIconBlocked : BlockerResult()
        data class CurveOnly(val intrusion: CurveIntrusion, val baseBounds: GraphRect, val baseGapPx: Float) : BlockerResult()
    }

    fun tryExactFit(
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
            val outcome = tryDirection(
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
                Outcome.NATURAL_FITS -> return false
                Outcome.PLACED -> return true
                Outcome.LABEL_OR_ICON_BLOCKED -> {
                    // ACTUAL_LOW: when the normal below direction is blocked (curve intrusion),
                    // try tight-below-trough placement instead of falling through to above.
                    if (!placeAbove && candidate.role == TemperatureRole.ACTUAL_LOW) {
                        ActualExtremePlacers.place(
                            placeAbove = false,
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
                Outcome.GAVE_UP -> return false
            }
        }
        return false
    }

    private fun checkBlockers(
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
    ): BlockerResult {
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
            return BlockerResult.NaturalFits
        }
        if (obstacles.labelOrHardBlocked || obstacles.iconBlocked) {
            return BlockerResult.LabelOrIconBlocked
        }
        return BlockerResult.CurveOnly(curveResult.intrusion, baseBounds, baseGapPx)
    }

    private fun tryDirection(
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
    ): Outcome {
        val blockerResult = checkBlockers(
            widthPx = widthPx, heightPx = heightPx, density = density,
            actualVisiblePoints = actualVisiblePoints, forecastPoints = forecastPoints,
            candidate = candidate, geometry = geometry, placeAbove = placeAbove,
            gapDp = gapDp, labelAscent = labelAscent, labelDescent = labelDescent,
            drawnLabelMetas = drawnLabelMetas, drawnIconBounds = drawnIconBounds,
            reservedHardBounds = reservedHardBounds, idx = idx
        )
        when (blockerResult) {
            is BlockerResult.NaturalFits -> return Outcome.NATURAL_FITS
            is BlockerResult.LabelOrIconBlocked -> return Outcome.LABEL_OR_ICON_BLOCKED
            is BlockerResult.CurveOnly -> {
                // Forecast curve often dips below the actual valley. A shallow dip is acceptable
                // partial overlap: keep the low label flush below its valley (handled by the main
                // step loop) instead of flipping it above with a long leader line. Only a deep dip
                // warrants flipping above.
                if (candidate.role == TemperatureRole.ACTUAL_LOW && !placeAbove) {
                    val dipDepth = blockerResult.intrusion.maxY - blockerResult.baseBounds.top
                    return if (dipDepth > allowedDipPx) {
                        Outcome.LABEL_OR_ICON_BLOCKED
                    } else {
                        Outcome.GAVE_UP
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
                        return Outcome.GAVE_UP
                    }
                }
                // Actual curve rises from the start point into the above-space; prefer below.
                if (candidate.role == TemperatureRole.START && placeAbove) {
                    return Outcome.LABEL_OR_ICON_BLOCKED
                }
                val extra = if (placeAbove) {
                    blockerResult.baseBounds.bottom - blockerResult.intrusion.minY + CURVE_AVOIDANCE_CLEAR_PX - allowedDipPx
                } else {
                    blockerResult.intrusion.maxY + CURVE_AVOIDANCE_CLEAR_PX - allowedDipPx - blockerResult.baseBounds.top
                }
                if (extra <= 0f) return Outcome.GAVE_UP

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
                    return Outcome.GAVE_UP
                }
                val overlapsLabel = drawnLabelMetas.any { it.bounds.intersects(newBounds) }
                val overlapsIcon = drawnIconBounds.any { it.intersects(newBounds) }
                val overlapsHard = reservedHardBounds.any { it.intersects(newBounds) }
                if (overlapsLabel || overlapsIcon || overlapsHard) {
                    Log.d(TAG, "CurveAdjust: role=${candidate.role} idx=$idx FAILED overlapsLabel=$overlapsLabel overlapsIcon=$overlapsIcon overlapsHard=$overlapsHard")
                    return Outcome.GAVE_UP
                }
                val residual = combinedCurveIntrusion(actualVisiblePoints, forecastPoints, newBounds)
                if (!residual.isEmpty) {
                    val residualDepth = if (placeAbove) newBounds.bottom - residual.maxY else residual.minY - newBounds.top
                    if (residualDepth > allowedDipPx + 1f) {
                        Log.d(TAG, "CurveAdjust: role=${candidate.role} idx=$idx FAILED residualCurveIntrusion depth=${String.format("%.1f", residualDepth)} allowedDip=${String.format("%.1f", allowedDipPx)}")
                        return Outcome.GAVE_UP
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
                return Outcome.PLACED
            }
        }
    }
}
