package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.Log

/**
 * The forced-direction placers for the observed (actual) extremes. ACTUAL_HIGH is always placed
 * ABOVE its spike; ACTUAL_LOW tight BELOW its trough. Both force a direction instead of searching,
 * so they must yield to what is already drawn or they simply overprint it — they step up/down over
 * each substantial blocker via CollisionTester's shared rule, and drop the label if clearing it
 * would push it out of the plot.
 *
 * Dropping is the right end state here: a second extreme this close to a placed one comes from
 * near-equal turning points on the same plateau, where it says nothing the survivor does not.
 *
 * [place] is the unified form of the former placeActualHighAboveCurve / placeActualLowBelowCurve
 * pair; the only direction-specific differences are the gap constant, the curve-extreme scan, and
 * the stack/leader direction.
 */
internal object ActualExtremePlacers {
    private const val TAG = "ActualExtremePlacers"

    private const val ACTUAL_EXTREME_STACK_GAP_DP = 1.5f
    private const val ACTUAL_EXTREME_STACK_MAX_STEPS = 4

    fun place(
        placeAbove: Boolean,
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

        // Extreme of the visible actual LINE across the label's x-span (fall back to the hourly
        // anchor): above → highest (smallest y), below → lowest (largest y). This clears the jagged
        // high-resolution observed line rather than the hourly anchor. Measured by interpolating the
        // segments crossing the span, not by testing which vertices land inside it: on a sparsely
        // sampled line the neighbouring vertices sit outside a narrow label, which would leave the
        // label hugging its anchor while the line runs straight through it.
        var curveExtremeY = geometry.sy
        val extent = curveYExtentInXSpan(actualVisiblePoints, left, right)
        if (!extent.isEmpty) {
            if (placeAbove) {
                if (extent.minY < curveExtremeY) curveExtremeY = extent.minY
            } else {
                if (extent.maxY > curveExtremeY) curveExtremeY = extent.maxY
            }
        }

        // The observed high rides right on its spike and the low hugs its trough, so use the tighter
        // actual-specific gaps rather than the shared above/below gaps.
        val gapPx = (if (placeAbove) GraphLabelPlacementUtils.TEMP_ACTUAL_HIGH_ABOVE_GAP_DP else GraphLabelPlacementUtils.TEMP_ACTUAL_LOW_BELOW_GAP_DP) * density
        val placement = GraphLabelPlacementUtils.computeLabelVerticalPlacement(
            pointY = curveExtremeY,
            placeAbove = placeAbove,
            gapPx = gapPx,
            textAscent = labelAscent,
            textDescent = labelDescent,
        )
        var top = placement.top
        var bottom = placement.bottom
        var baselineY = placement.baselineY
        if (placeAbove && top < 0f) {
            val shift = -top
            top += shift
            bottom += shift
            baselineY += shift
        } else if (!placeAbove && bottom > heightPx) {
            val shift = heightPx - bottom
            top += shift
            bottom += shift
            baselineY += shift
        }

        val labelHeight = labelDescent - labelAscent
        var bounds = GraphRect(left, top, right, bottom)
        val stackGapPx = ACTUAL_EXTREME_STACK_GAP_DP * density
        var steps = 0
        while (steps++ < ACTUAL_EXTREME_STACK_MAX_STEPS) {
            if (!blocked(candidate.role, bounds, placeAbove, drawnLabelMetas, drawnIconBounds, reservedHardBounds, labelHeight)) break
            val blocker = firstBlockerBounds(bounds, drawnLabelMetas, drawnIconBounds, reservedHardBounds) ?: break
            val shift = if (placeAbove) bounds.bottom - blocker.top + stackGapPx else blocker.bottom - bounds.top + stackGapPx
            if (placeAbove) {
                top -= shift
                bottom -= shift
                baselineY -= shift
            } else {
                top += shift
                bottom += shift
                baselineY += shift
            }
            bounds = GraphRect(left, top, right, bottom)
        }

        val offscreen = if (placeAbove) top < 0f else bottom > heightPx
        if (offscreen || blocked(candidate.role, bounds, placeAbove, drawnLabelMetas, drawnIconBounds, reservedHardBounds, labelHeight)) {
            Log.d(
                TAG,
                "PlaceDrop: role=${candidate.role} idx=$idx reason=${if (placeAbove) "noRoomAboveActualCurve" else "noRoomBelowActualCurve"} " +
                    "edge=${if (placeAbove) top.toInt() else bottom.toInt()} heightPx=$heightPx steps=${steps - 1} " +
                    "overlap=${GraphLabelPlacementUtils.maxVerticalOverlap(bounds, drawnLabelMetas.map { it.bounds }).toInt()} " +
                    "labelHeight=${labelHeight.toInt()}",
            )
            return
        }

        // Only the above placer may draw a leader line (the low hugs its trough with none).
        val drawLeader = placeAbove && geometry.sy - bottom > labelDescent

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
                leaderToY = if (placeAbove) bottom else geometry.sy,
                isFuture = geometry.isFuture,
                rawTemperature = candidate.rawTemperature,
                displayTemperature = temps[idx],
                reason = if (placeAbove) "aboveActualCurve" else "belowActualCurve",
                displacementSteps = 0,
            )
        )
        drawnLabelMetas.add(PlacedLabelMeta(bounds, isValleyBelow = !placeAbove, role = candidate.role, temperature = temps[idx]))
    }

    /**
     * True when the placer must yield: the candidate box overlaps an already-drawn label, icon, or
     * hard bound beyond CollisionTester's standard minor-overlap budgets — the same rule the main
     * placement loop and the curve-avoidance pre-pass apply. (The forced placers never test the curve
     * itself: they are pinned to it by design.)
     */
    private fun blocked(
        role: TemperatureRole,
        bounds: GraphRect,
        placeAbove: Boolean,
        drawnLabelMetas: List<PlacedLabelMeta>,
        drawnIconBounds: List<GraphRect>,
        reservedHardBounds: List<GraphRect>,
        labelHeight: Float,
    ): Boolean = CollisionTester.obstacles(
        role = role,
        bounds = bounds,
        isValley = !placeAbove,
        placeAbove = placeAbove,
        drawnLabelMetas = drawnLabelMetas,
        drawnIconBounds = drawnIconBounds,
        reservedHardBounds = reservedHardBounds,
        labelHeight = labelHeight,
    ).anyBlocked

    /**
     * The first already-drawn obstacle (label, icon, or hard bound) intersecting [bounds], in draw
     * order, or null when none intersects. The placer steps the candidate over this blocker.
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
}
