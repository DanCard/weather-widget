package com.weatherwidget.shared.graph

import kotlin.math.abs

/**
 * The discrete hourly-graph zoom stages, shared by Android and desktop.
 *
 * This enum is *identity only*: which stage the user selected. Android persists the selection by
 * name and advances it on tap via [next]; desktop keeps a continuous zoom factor for
 * mouse-wheel/drag but snaps its click to these stages (mapping the factor back to a stage with
 * [nearestByTotalSpan], then taking [next]).
 *
 * The rendering geometry — back/forward hours, nav jump, label cadence, smoothing — lives in
 * [ZoomWindow] and is resolved via [window], because [NARROW]'s span is user-configurable
 * (Settings → "Hourly Zoom", 4–8h) and an enum constant cannot vary at runtime. Keeping the two
 * apart means no call site can accidentally render a stale fixed span: asking a stage for hours is
 * a compile error.
 *
 * IMPORTANT: declaration order is load-bearing — legacy widget state persisted the selected stage by
 * ordinal (WIDE=0, NARROW=1, THREE_DAY=2) and is still decoded that way, so reordering would
 * silently remap saved state.
 */
enum class ZoomStage {
    WIDE,
    NARROW,
    THREE_DAY,
    ;

    /** The next stage in the tap cycle: WIDE → NARROW → THREE_DAY → WIDE. */
    fun next(): ZoomStage = when (this) {
        WIDE -> NARROW
        NARROW -> THREE_DAY
        THREE_DAY -> WIDE
    }

    /**
     * Resolves this stage's rendering geometry. [narrowSpanHours] is the user's configured tight-view
     * span and is ignored by every stage but [NARROW].
     *
     * NARROW splits its span back-heavy — `back = ceil(n/2)`, `forward = floor(n/2)`, so 5h reads 3h
     * of history against 2h of forecast. That matches [THREE_DAY]'s 48/24 bias and desktop's
     * "wider views lean into history" curve.
     *
     * [backHours]/[forwardHours] are [Long] because every Android query-window call site feeds them
     * straight into `LocalDateTime.minusHours/plusHours`, which take `Long`.
     */
    fun window(
        narrowSpanHours: Int = HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS,
    ): ZoomWindow = when (this) {
        WIDE -> ZoomWindow(
            stage = WIDE,
            backHours = 12,
            forwardHours = 12,
            navJump = 6,
            labelInterval = 4,
            smoothIterations = 3,
        )

        NARROW -> {
            val span = HourlyZoomRules.clampNarrowSpan(narrowSpanHours)
            ZoomWindow(
                stage = NARROW,
                backHours = ((span + 1) / 2).toLong(),
                forwardHours = (span / 2).toLong(),
                navJump = HourlyZoomRules.navJumpHours(span),
                labelInterval = 1,
                smoothIterations = 1,
            )
        }

        THREE_DAY -> ZoomWindow(
            stage = THREE_DAY,
            backHours = 48,
            forwardHours = 24,
            navJump = 12,
            labelInterval = 12,
            smoothIterations = 3,
        )
    }

    companion object {
        /** The starting/default stage (also the decode fallback on the Android side). */
        val DEFAULT = WIDE

        /**
         * The stage whose resolved span is closest to [totalSpanHours]. Lets desktop map an
         * arbitrary continuous wheel-zoom position back onto a discrete stage before cycling, so a
         * click always advances exactly one stage relative to where the view currently sits.
         * [narrowSpanHours] must be the same configured span used to render, or the snap can land on
         * a different stage than the one on screen.
         */
        fun nearestByTotalSpan(
            totalSpanHours: Int,
            narrowSpanHours: Int = HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS,
        ): ZoomStage =
            entries.minByOrNull { abs(it.window(narrowSpanHours).totalSpanHours - totalSpanHours) }!!
    }
}
