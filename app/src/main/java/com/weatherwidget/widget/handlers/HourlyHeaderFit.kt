package com.weatherwidget.widget.handlers

/**
 * How a narrow hourly header (below [HeaderWidthChecker.INLINE_NAV_MAX_WIDTH_DP], where the nav
 * icons ride inline after the current temperature) fits its left cluster before the API label.
 *
 * Compress before dropping (user, 2026-10-09): first the inline nav touch zones narrow toward
 * [MIN_ZONE_DP] — the icons move right into their own empty padding — then the weather icon and
 * current temperature shrink together toward [MIN_TEXT_SCALE]. Only when both floors are reached
 * does the caller drop something (the existing [HeaderDisclosureLevel] order). Before this, nothing
 * counted the inline row, so the delta was simply clipped ("−4.8" drawn as "8").
 *
 * Pure arithmetic in px so it can be tested without a device. See
 * plans/261009-hourly-header-compress-before-clipping.md.
 */
internal object HourlyHeaderFit {
    /** Narrowest inline touch zone: the 20 dp icon plus 4 dp either side. */
    const val MIN_ZONE_DP = 28f

    /** Smallest scale for the weather icon and current temperature. */
    const val MIN_TEXT_SCALE = 0.8f

    data class Input(
        /** From the left cluster's start to the API label, less the standard gap. */
        val availablePx: Float,
        /** Weather icon + its end margin and the current temperature at scale 1; both shrink. */
        val scalablePx: Float,
        /** Everything that keeps its size: delta, caption, rain %, the first zone's margin. */
        val fixedPx: Float,
        val zoneCount: Int,
        val nominalZonePx: Float,
        /** Equal to [nominalZonePx] where zones cannot be resized (below API 31). */
        val minZonePx: Float,
    )

    data class Plan(
        val zoneWidthPx: Float,
        val textScale: Float,
        val fits: Boolean,
    )

    fun plan(input: Input): Plan = with(input) {
        val nominal = Plan(nominalZonePx, 1f, fits = true)
        if (zoneCount <= 0) {
            val scale = scaleFor(availablePx - fixedPx, scalablePx)
            return if (scale >= 1f) nominal else Plan(nominalZonePx, scale.coerceAtLeast(MIN_TEXT_SCALE), scale >= MIN_TEXT_SCALE)
        }
        val zoneRoom = (availablePx - fixedPx - scalablePx) / zoneCount
        if (zoneRoom >= nominalZonePx) return nominal
        if (zoneRoom >= minZonePx) return Plan(zoneRoom, 1f, fits = true)

        val scale = scaleFor(availablePx - fixedPx - zoneCount * minZonePx, scalablePx)
        return if (scale >= MIN_TEXT_SCALE) {
            Plan(minZonePx, scale.coerceAtMost(1f), fits = true)
        } else {
            Plan(minZonePx, MIN_TEXT_SCALE, fits = false)
        }
    }

    private fun scaleFor(roomPx: Float, scalablePx: Float): Float =
        if (scalablePx <= 0f) (if (roomPx >= 0f) 1f else 0f) else roomPx / scalablePx
}
