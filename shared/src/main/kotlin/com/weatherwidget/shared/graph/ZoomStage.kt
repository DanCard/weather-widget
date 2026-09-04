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
 * ordinal (WIDE=0, NARROW=1, TWO_DAY=2) and is still decoded that way, so reordering would
 * silently remap saved state.
 */
enum class ZoomStage {
    WIDE,
    NARROW,

    /**
     * The optional multi-day stage, off by default (Settings → "Hourly Zoom" → include 2-day view).
     *
     * Was `THREE_DAY` (72 h) until 2026-08-16. The rename is deliberate on the persistence side too:
     * Android stores the stage by *name*, so a widget still parked on the old `"THREE_DAY"` string
     * fails the name lookup in `WidgetPresentationStateStore.decodeZoom` and falls back to [WIDE] —
     * which is what we want, since the stage now ships disabled.
     */
    TWO_DAY,
    ;

    /**
     * The next stage in the tap cycle. [multiDayEnabled] is the user's "include 2-day view" setting:
     * when off the cycle is a two-stop toggle (WIDE ↔ NARROW), when on it is
     * WIDE → NARROW → TWO_DAY → WIDE.
     *
     * The widget's only input verb is a single tap ([com.weatherwidget.shared.graph] has no gesture
     * vocabulary to spare — RemoteViews gives no long-press, pinch or scroll), so cycle membership
     * *is* reachability on Android. Desktop's continuous wheel zoom is unaffected and still reaches
     * multi-day spans with the setting off; the gate applies only to its click-to-cycle.
     */
    fun next(multiDayEnabled: Boolean = false): ZoomStage = when (this) {
        WIDE -> NARROW
        NARROW -> if (multiDayEnabled) TWO_DAY else WIDE
        TWO_DAY -> WIDE
    }

    /**
     * Resolves this stage's rendering geometry. [narrowSpanHours] is the user's configured tight-view
     * span and is ignored by every stage but [NARROW].
     *
     * NARROW splits its span back-heavy — `back = ceil(n/2)`, `forward = floor(n/2)`, so 5h reads 3h
     * of history against 2h of forecast. That matches [TWO_DAY]'s 42/6 bias and desktop's
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
            // 18h, symmetric: 9h of history and 9h of forecast. The now-line therefore sits
            // at the exact physical center (50%) of the graph.
            backHours = 9,
            forwardHours = 9,
            // A sixth of the span, like TWO_DAY's 8h of 48h — see HourlyZoomRules.navJumpHours,
            // which desktop's continuous zoom reads.
            navJump = 3,
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

        TWO_DAY -> ZoomWindow(
            stage = TWO_DAY,
            // 48h, split 36 back / 12 forward (48h total).
            backHours = 36,
            forwardHours = 12,
            // A sixth of the span, matching HourlyZoomRules.navJumpHours(48).
            navJump = 8,
            // Only consulted below the date-footer threshold; at 48h the footer is in date mode
            // (HourlyZoomRules.isDateMode) and labels one date per day instead. Kept aligned with
            // desktop's labelIntervalFor(48) so the two agree if the threshold ever moves.
            labelInterval = 6,
            smoothIterations = 3,
        )
    }

    companion object {
        /** The starting/default stage (also the decode fallback on the Android side). */
        val DEFAULT = WIDE

        /**
         * Coerces a *persisted* stage against the current "include 2-day view" setting.
         *
         * Without this, enabling the setting, cycling to [TWO_DAY], then disabling it again strands
         * the widget on a stage [next] can no longer reach — on Android that is a view with no way
         * out, since the tap cycle is the only zoom affordance. Applied on read rather than on the
         * setting's write path so it also covers state restored from backup or written by an older
         * build.
         */
        fun resolve(stage: ZoomStage, multiDayEnabled: Boolean): ZoomStage =
            if (stage == TWO_DAY && !multiDayEnabled) WIDE else stage

        /**
         * The stage whose resolved span is closest to [totalSpanHours]. Lets desktop map an
         * arbitrary continuous wheel-zoom position back onto a discrete stage before cycling, so a
         * click always advances exactly one stage relative to where the view currently sits.
         * [narrowSpanHours] must be the same configured span used to render, or the snap can land on
         * a different stage than the one on screen.
         *
         * Deliberately *not* gated on the 2-day setting: the wheel can park the view at a multi-day
         * span whether or not the stage is in the cycle, and snapping such a view to [TWO_DAY] is
         * what lets the following [next] step return it to [WIDE] in one click.
         */
        fun nearestByTotalSpan(
            totalSpanHours: Int,
            narrowSpanHours: Int = HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS,
        ): ZoomStage =
            entries.minByOrNull { abs(it.window(narrowSpanHours).totalSpanHours - totalSpanHours) }!!
    }
}
