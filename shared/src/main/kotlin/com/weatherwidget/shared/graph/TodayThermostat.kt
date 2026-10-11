package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.DailyDayValueResolver

/**
 * Where each piece of today's thermostat bar starts and ends, in temperatures. Pure numbers, so
 * Android (`DailyBarRenderer.drawTodayTripleBar`) and desktop (`DailyForecastGraph`) draw the same
 * shape; each platform keeps only its own draw calls and its pixel-level minimum bar height.
 *
 * - **Mercury** (solid, observed color): [Segments.mercuryTop] down to [Segments.mercuryBottom].
 *   The top is `TodayLineValues.barTopHigh` (current temp ?: observed high ?: forecast high).
 * - **Ghost** (translucent, [GHOST_ALPHA]): [Segments.ghostTop] down to the mercury top — today's
 *   earlier peak, only when it is warmer than the current reading.
 * - **Bulb**: at the mercury bottom ([DailyDayValueResolver.mercuryBottom]), never warmer than its top.
 *
 * Desktop once drew the mercury from max(barTopHigh, ghostHigh), painting the earlier peak solid
 * over the ghost (plans/261011-desktop-today-thermostat-ghost-overpainted.md); this object is the
 * one place that decision lives now.
 */
object TodayThermostat {

    /** Ghost alpha on Android's 0–255 scale (the value Android has always drawn). */
    const val GHOST_ALPHA_255 = 75

    /** [GHOST_ALPHA_255] as a 0–1 fraction (Compose). */
    const val GHOST_ALPHA = GHOST_ALPHA_255 / 255f

    data class Segments(
        /** Top of the solid mercury; null when nothing is known about today's high. */
        val mercuryTop: Float?,
        /** Bottom of the solid mercury, where the bulb sits. */
        val mercuryBottom: Float?,
        /** Top of the ghost, or null when there is no earlier peak above the mercury. */
        val ghostTop: Float?,
    ) {
        /** Warmest drawn point (ghost if present, else mercury) — for panel / label extents. */
        val top: Float? get() = ghostTop ?: mercuryTop
    }

    /**
     * @param barTopHigh `TodayLineValues.barTopHigh` — the mercury top.
     * @param solidLow `TodayLineValues.solidLow` — observed low ?: forecast low.
     * @param ghostHigh `TodayLineValues.ghostHigh` — today's observed peak so far.
     */
    fun segments(barTopHigh: Float?, solidLow: Float?, ghostHigh: Float?): Segments = Segments(
        mercuryTop = barTopHigh,
        mercuryBottom = DailyDayValueResolver.mercuryBottom(barTopHigh, solidLow),
        ghostTop = ghostHigh?.takeIf { barTopHigh != null && it > barTopHigh },
    )
}
