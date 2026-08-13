package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.Log

/**
 * The valley-below cascade. When a below-placed label lands on an already-drawn label in a valley,
 * this tries, in order: a horizontal shift to either side, a relaxed vertical overlap (within
 * budget), then flipping above when the candidate is warmer than the label it sits on. Only the
 * main step loop's full leader-line displacement is left to the caller when none of these fit.
 *
 * Extracted from [TemperatureLabelEngine] so the engine's placement loop stays a thin orchestrator.
 */
internal object ValleyCascade {
    private const val TAG = "ValleyCascade"

    data class Result(
        val x: Float,
        val baselineY: Float,
        val bounds: GraphRect,
        val reason: String,
    )

    sealed class Outcome {
        data class Below(val result: Result) : Outcome()
        object FlipAbove : Outcome()
        object None : Outcome()
    }

    fun tryBelow(
        widthPx: Int,
        heightPx: Int,
        candidate: TempLabelCandidate,
        geometry: ResolvedLabelGeometry,
        verticalPlacement: GraphLabelPlacementUtils.LabelVerticalPlacement,
        drawnLabelMetas: List<PlacedLabelMeta>,
        drawnIconBounds: List<GraphRect>,
        reservedHardBounds: List<GraphRect>,
        labelHeight: Float,
    ): Outcome {
        val centerX = geometry.clampedX
        val halfWidth = geometry.textWidth / 2f

        val centeredBounds = GraphRect(centerX - halfWidth, verticalPlacement.top, centerX + halfWidth, verticalPlacement.bottom)
        val drawnBoundsList = drawnLabelMetas.map { it.bounds }

        val collidingMeta = drawnLabelMetas.firstOrNull { it.bounds.intersects(centeredBounds) }
        if (collidingMeta == null) return Outcome.None

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
                return Outcome.Below(
                    Result(
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
            if (TemperatureLabelEngine.shouldLogPlacement(candidate.role)) {
                Log.d(TAG, "LabelCascade: role=${candidate.role} option2-accepted ratio=${String.format("%.2f", overlapRatio)} threshold=${GraphLabelPlacementUtils.VALLEY_BELOW_LABEL_OVERLAP_RATIO}")
            }
            return Outcome.Below(
                Result(
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
                if (TemperatureLabelEngine.shouldLogPlacement(candidate.role)) {
                    Log.d(TAG, "LabelCascade: role=${candidate.role} flip-above-warmer current=${String.format("%.1f", currentTemp)} colliding=${String.format("%.1f", collidingTemp)} collidingRole=${collidingMeta.role} ratio=${String.format("%.2f", overlapRatio)}")
                }
                return Outcome.FlipAbove
            }
            if (!centeredOverlapsHard && overlapRatio <= GraphLabelPlacementUtils.VALLEY_VS_VALLEY_OVERLAP_RATIO) {
                if (TemperatureLabelEngine.shouldLogPlacement(candidate.role)) {
                    Log.d(TAG, "LabelCascade: role=${candidate.role} option1-accepted ratio=${String.format("%.2f", overlapRatio)} threshold=${GraphLabelPlacementUtils.VALLEY_VS_VALLEY_OVERLAP_RATIO} collidingRole=${collidingMeta.role}")
                }
                return Outcome.Below(
                    Result(
                        x = centerX,
                        baselineY = verticalPlacement.baselineY,
                        bounds = centeredBounds,
                        reason = "below-valley-overlap",
                    )
                )
            }
        }

        if (TemperatureLabelEngine.shouldLogPlacement(candidate.role)) {
            Log.d(TAG, "LabelCascade: role=${candidate.role} all-options-exhausted ratio=${String.format("%.2f", overlapRatio)} collidingIsValleyBelow=${collidingMeta.isValleyBelow}")
        }

        return Outcome.None
    }
}
