package com.weatherwidget.shared.graph

import kotlin.math.abs

/**
 * The discrete hourly-graph zoom stages, shared by Android and desktop.
 *
 * Android consumes the full record: it renders one of these stages at a time and persists the
 * selection by [ordinal], advancing it on tap via [next]. Desktop keeps a *continuous* zoom factor
 * for mouse-wheel/drag but snaps its click to these stages (mapping the factor back to a stage with
 * [nearestByTotalSpan], then taking [next]). Keeping this here is the single source of truth so the
 * two platforms can't drift.
 *
 * IMPORTANT: declaration order is load-bearing — Android persists the selected stage by [ordinal]
 * (WIDE=0, NARROW=1, THREE_DAY=2), so reordering would silently remap saved widget state.
 *
 * [backHours]/[forwardHours] are [Long] because every Android query-window call site feeds them
 * straight into `LocalDateTime.minusHours/plusHours`, which take `Long`.
 */
enum class ZoomStage(
    val backHours: Long,
    val forwardHours: Long,
    val navJump: Int,
    val labelInterval: Int,
    val smoothIterations: Int,
) {
    WIDE(backHours = 12, forwardHours = 12, navJump = 6, labelInterval = 4, smoothIterations = 3),
    NARROW(backHours = 2, forwardHours = 2, navJump = 1, labelInterval = 1, smoothIterations = 1),
    THREE_DAY(backHours = 48, forwardHours = 24, navJump = 12, labelInterval = 12, smoothIterations = 3),
    ;

    /** Total visible span (back + forward) in hours. */
    val totalSpanHours: Long get() = backHours + forwardHours

    /** The next stage in the tap cycle: WIDE → NARROW → THREE_DAY → WIDE. */
    fun next(): ZoomStage = when (this) {
        WIDE -> NARROW
        NARROW -> THREE_DAY
        THREE_DAY -> WIDE
    }

    companion object {
        /** The starting/default stage (also the [ordinal] fallback on the Android side). */
        val DEFAULT = WIDE

        /**
         * The stage whose [totalSpanHours] is closest to [totalSpanHours]. Lets desktop map an
         * arbitrary continuous wheel-zoom position back onto a discrete stage before cycling, so a
         * click always advances exactly one stage relative to where the view currently sits.
         */
        fun nearestByTotalSpan(totalSpanHours: Int): ZoomStage =
            entries.minByOrNull { abs(it.totalSpanHours - totalSpanHours) }!!
    }
}
