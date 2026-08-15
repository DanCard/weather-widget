package com.weatherwidget.shared.graph

/**
 * Single source of truth for the two collision questions the temperature label engine asks about a
 * candidate box:
 *
 * 1. [obstacles] — does the box overlap an already-drawn label, icon, or hard bound, beyond the
 *    engine's standard minor-overlap budgets?
 * 2. [curve] — does the observed/forecast curve pass through the box, beyond the role's dip
 *    tolerance?
 *
 * Extracted because the full rule was previously spelled out independently in three places — the
 * main placement loop, the curve-avoidance pre-pass, and the forced-direction placers — and had
 * already drifted: the forced placers checked *only* placed labels, never hard bounds or icons, so
 * an ACTUAL_HIGH/LOW could be stamped over the fetch-dot's pink value/age label (the "631°" class
 * of bug the hard-bound test exists to prevent).
 */
object CollisionTester {

    /**
     * The label/icon/hard-obstacle components of a collision test. Kept separate from [Curve]
     * because the three callers combine them differently: the main loop ORs everything, the
     * curve-avoidance pre-pass must distinguish "curve only" from "label/icon blocked", and the
     * forced placers check obstacles but never the curve (they are pinned to it by design).
     */
    data class Obstacles(
        val overlapsLabel: Boolean,
        val labelOverlapPx: Float,
        val allowMinorLabelOverlap: Boolean,
        val overlapsIcon: Boolean,
        val iconOverlapPx: Float,
        val allowMinorIconOverlap: Boolean,
        val overlapsHard: Boolean,
        val hardOverlapPx: Float,
        val allowMinorHardOverlap: Boolean,
    ) {
        val labelOrHardBlocked: Boolean
            get() = (overlapsLabel && !allowMinorLabelOverlap) || (overlapsHard && !allowMinorHardOverlap)
        val iconBlocked: Boolean
            get() = overlapsIcon && !allowMinorIconOverlap
        val anyBlocked: Boolean
            get() = labelOrHardBlocked || iconBlocked
    }

    data class Curve(
        val intrusion: CurveIntrusion,
        val curveDipDepth: Float,
        val curveWithinDip: Boolean,
        val curveBlocked: Boolean,
    )

    /**
     * Computes the label/icon/hard components. Minor overlaps are the engine's standard budgets:
     * labels use [GraphLabelPlacementUtils.MINOR_OVERLAP_HEIGHT_RATIO] (whitespace-level); icons use
     * a looser ratio ([GraphLabelPlacementUtils.MINOR_OVERLAP_ICON_RATIO] for valley-below) because
     * a label box is taller than its glyphs; hard bounds additionally require the overlap to be
     * *side-only* ([GraphLabelPlacementUtils.hardOverlapIsSideOnly]) so two labels at the same x can
     * never collide glyph-on-glyph however small the vertical overlap is.
     */
    fun obstacles(
        role: TemperatureRole,
        bounds: GraphRect,
        isValley: Boolean,
        placeAbove: Boolean,
        drawnLabelMetas: List<PlacedLabelMeta>,
        drawnIconBounds: List<GraphRect>,
        reservedHardBounds: List<GraphRect>,
        labelHeight: Float,
    ): Obstacles {
        val drawnBoundsList = drawnLabelMetas.map { it.bounds }
        val overlapsLabel = drawnBoundsList.any { it.intersects(bounds) }
        val overlapsIcon = drawnIconBounds.any { it.intersects(bounds) }
        val overlapsHard = reservedHardBounds.any { it.intersects(bounds) }

        val labelOverlapPx = if (overlapsLabel) GraphLabelPlacementUtils.maxVerticalOverlap(bounds, drawnBoundsList) else 0f
        val iconOverlapPx = if (overlapsIcon) GraphLabelPlacementUtils.maxVerticalOverlap(bounds, drawnIconBounds) else 0f
        val hardOverlapPx = if (overlapsHard) GraphLabelPlacementUtils.maxVerticalOverlap(bounds, reservedHardBounds) else 0f

        val currentIconRatio = if (!placeAbove && isValley) GraphLabelPlacementUtils.MINOR_OVERLAP_ICON_RATIO else GraphLabelPlacementUtils.MINOR_OVERLAP_HEIGHT_RATIO

        val allowMinorLabelOverlap = overlapsLabel && GraphLabelPlacementUtils.shouldAllowMinorOverlap(role, labelOverlapPx, labelHeight)
        val allowMinorIconOverlap = overlapsIcon && GraphLabelPlacementUtils.isMinorOverlapEligible(role) && iconOverlapPx <= labelHeight * currentIconRatio
        val allowMinorHardOverlap = overlapsHard &&
            GraphLabelPlacementUtils.shouldAllowMinorOverlap(role, hardOverlapPx, labelHeight) &&
            GraphLabelPlacementUtils.hardOverlapIsSideOnly(bounds, reservedHardBounds)

        return Obstacles(
            overlapsLabel, labelOverlapPx, allowMinorLabelOverlap,
            overlapsIcon, iconOverlapPx, allowMinorIconOverlap,
            overlapsHard, hardOverlapPx, allowMinorHardOverlap,
        )
    }

    /**
     * Computes the curve component: the extent to which the observed/forecast curves penetrate
     * [bounds] and whether that penetration blocks the label. ACTUAL_LOW and LOCAL tolerate a
     * shallow dip ([curveWithinDip]) so they stay closer to the curve; [isCurveAvoidanceExempt] and
     * [flipDecided] are the two call-site overrides the main loop already applies.
     */
    fun curve(
        role: TemperatureRole,
        bounds: GraphRect,
        placeAbove: Boolean,
        avoidanceActualPoints: List<Pair<Float, Float>>,
        forecastPoints: List<Pair<Float, Float>>,
        allowedDipPx: Float,
        isCurveAvoidanceExempt: Boolean,
        flipDecided: Boolean,
    ): Curve {
        val curveAvoidanceEligible = role in CURVE_AVOIDANCE_ROLES
        val intrusion = if (curveAvoidanceEligible) combinedCurveIntrusion(avoidanceActualPoints, forecastPoints, bounds) else CurveIntrusion.NONE
        val curveDipDepth = when {
            intrusion.isEmpty -> 0f
            placeAbove -> bounds.bottom - intrusion.minY
            else -> intrusion.maxY - bounds.top
        }
        val curveWithinDip = (role == TemperatureRole.ACTUAL_LOW || role == TemperatureRole.LOCAL) &&
            !intrusion.isEmpty && curveDipDepth <= allowedDipPx
        val allowFlippedAboveCurveGraze = flipDecided && placeAbove && curveAvoidanceEligible
        val curveBlocked = curveAvoidanceEligible && !intrusion.isEmpty && !allowFlippedAboveCurveGraze && !isCurveAvoidanceExempt && !curveWithinDip
        return Curve(intrusion, curveDipDepth, curveWithinDip, curveBlocked)
    }

    // Roles whose candidate boxes must clear the curve (vs. free-floating labels that only avoid
    // obstacles). Shared by every pass through [curve], and referenced by the engine to gate the
    // curve-avoidance pre-pass.
    internal val CURVE_AVOIDANCE_ROLES: Set<TemperatureRole> = setOf(
        TemperatureRole.ACTUAL_END,
        TemperatureRole.ACTUAL_HIGH,
        TemperatureRole.ACTUAL_LOW,
        TemperatureRole.HIGH,
        TemperatureRole.LOW,
        TemperatureRole.LOCAL,
        TemperatureRole.START,
        TemperatureRole.END,
    )

    private const val CURVE_AVOIDANCE_ALLOWED_DIP_DP = 5f

    // ACTUAL_LOW tolerates more forecast-curve overlap than other roles: a low label belongs below
    // its valley, and a shallow forecast dip under that valley is acceptable partial overlap rather
    // than a reason to push the label off-anchor with a long leader line. Expressed as a fraction of
    // label height so the tolerance scales with the glyphs.
    //
    // That rationale is direction-specific — it is about what sits under a valley — so it applies
    // only when the label is placed BELOW. An ACTUAL_LOW flipped ABOVE (value-ordering) has the
    // observed hump above its trough, and half a label height of tolerance there is half the glyphs
    // sitting on a drawn line; it falls back to the standard graze.
    private const val ACTUAL_LOW_FORECAST_OVERLAP_RATIO = 0.5f

    // LOCAL (forecast midpoints and some interior value labels) get a modest curve graze tolerance.
    // Non-extremum points on the body of the forecast curve frequently produce tiny line intrusions
    // into the label box even at the preferred 1dp gap (due to slope + box width + sampling margins).
    // Allowing a small graze (3dp) avoids unnecessary full-height leaders while still preventing
    // obvious visual overlap with the drawn line.
    private const val LOCAL_CURVE_GRAZE_DP = 3f

    /**
     * How deep (px) a curve may dip into [role]'s label box before it counts as a collision.
     */
    fun allowedDipPxFor(role: TemperatureRole, density: Float, labelHeight: Float, placeAbove: Boolean): Float =
        when (role) {
            TemperatureRole.ACTUAL_LOW ->
                if (placeAbove) CURVE_AVOIDANCE_ALLOWED_DIP_DP * density
                else labelHeight * ACTUAL_LOW_FORECAST_OVERLAP_RATIO
            TemperatureRole.LOCAL -> LOCAL_CURVE_GRAZE_DP * density
            else -> CURVE_AVOIDANCE_ALLOWED_DIP_DP * density
        }
}
