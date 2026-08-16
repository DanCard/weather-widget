package com.weatherwidget.shared.graph

/**
 * The user-configurable part of the hourly zoom: how many hours the tight ([ZoomStage.NARROW]) view
 * spans, and how far a nav-arrow press scrolls at that span.
 *
 * Shared by Android (Settings → "Hourly Zoom", driving the widget's discrete NARROW stage) and
 * desktop (`DesktopConfig.narrowZoomSpanHours`, driving the stage a popup click snaps to) so the two
 * platforms cannot drift.
 */
object HourlyZoomRules {
    const val MIN_NARROW_SPAN_HOURS = 4
    const val MAX_NARROW_SPAN_HOURS = 8

    /**
     * Span for a fresh install. Before this setting existed NARROW was a fixed 4h (2 back / 2
     * forward); 5h is a deliberate widening, so installs with no stored preference — new *and*
     * existing — open the tight view one hour wider than they used to.
     */
    const val DEFAULT_NARROW_SPAN_HOURS = 5

    fun clampNarrowSpan(hours: Int): Int =
        hours.coerceIn(MIN_NARROW_SPAN_HOURS, MAX_NARROW_SPAN_HOURS)

    /**
     * How many hours a left/right nav press shifts the view. Single source of truth for Android's
     * [ZoomWindow.navJump] and desktop's continuous `DesktopGraphUtils.navJumpHours`.
     *
     * Tight views step 1 hour across the entire configurable NARROW range (up to [MAX_NARROW_SPAN_HOURS]).
     * Above that it falls back to the half-span rule desktop's wide zooms have always used.
     */
    fun navJumpHours(spanHours: Int): Int = when {
        spanHours <= MAX_NARROW_SPAN_HOURS -> 1
        else -> (spanHours / 2).coerceAtLeast(1)
    }

    /**
     * Footer hour-label cadence for the tight view on a *physically narrow* widget (2–3 columns).
     *
     * The tight view normally labels every hour, which was fine at its old fixed 4h. A user-widened
     * span would keep that cadence and draw up to 8 `<hour><icon>` groups — roughly double what a
     * narrow widget fits: WIDE already thins itself to
     * [HourlyGraphDefaults.NARROW_WIDE_LABEL_INTERVAL] there, budgeting ~4 labels across its span. Every
     * other hour from 6h up keeps this view inside the same budget. Wide widgets are unaffected and
     * keep labelling every hour.
     */
    fun narrowWidgetLabelInterval(spanHours: Int): Int = if (spanHours >= 6) 2 else 1
}

/**
 * The *resolved geometry* of a zoom view: what the renderers, query windows and touch-zone math
 * actually need. Produced by [ZoomStage.window] from a stage plus the configured narrow span.
 *
 * This is deliberately separate from [ZoomStage], which is only the user's discrete *selection*
 * (persisted, cycled on tap, compared by identity). A Kotlin enum constant is frozen at compile
 * time, so it cannot carry a runtime-configurable span — and leaving the geometry properties on the
 * enum would let a call site silently keep reading the old fixed 2h/2h. Holding them here instead
 * means the compiler forces every geometry consumer to resolve a window first.
 */
data class ZoomWindow(
    val stage: ZoomStage,
    val backHours: Long,
    val forwardHours: Long,
    val navJump: Int,
    val labelInterval: Int,
    val smoothIterations: Int,
) {
    /** Total visible span (back + forward) in hours. */
    val totalSpanHours: Long get() = backHours + forwardHours

    /**
     * Compact form for logs: `NARROW(-3/+2 jump=1)`. Several widget logs used to print the bare
     * stage name; keeping them one short token preserves their grep-ability while now also showing
     * the configured span, which is the first thing to check when a tight view looks wrong.
     */
    override fun toString(): String = "$stage(-$backHours/+$forwardHours jump=$navJump)"
}
