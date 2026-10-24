package com.weatherwidget.shared.graph

import kotlin.math.roundToInt

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
     * At or above this visible span the hourly footer stops labelling time-of-day ("12a"/"12p") and
     * switches to one date per visible day ("Tue 23"), because bare clock labels cannot tell you
     * which day a region of a multi-day window belongs to.
     *
     * Single source of truth for both platforms. It has to be, and the reason is a trap worth
     * recording: Android used to decide this by stage identity (`stage == THREE_DAY`) while desktop
     * decided it by span (`> 48`). Those agreed only because the old multi-day stage was 72h. When
     * [ZoomStage.TWO_DAY] landed on exactly 48h the two rules disagreed about the *same window* —
     * dates on the phone, clock hours on the desktop. Comparing with `>=` rather than `>` is what
     * puts a 48h stage inside date mode; the only span whose behaviour changed is exactly 48.
     */
    const val DATE_FOOTER_MIN_SPAN_HOURS = 48L

    /** Whether a visible span of [totalSpanHours] labels its footer with dates rather than clock hours. */
    fun isDateMode(totalSpanHours: Long): Boolean = totalSpanHours >= DATE_FOOTER_MIN_SPAN_HOURS

    /**
     * Footer clock-label cadence for a visible span of [spanHours], every value a divisor of 24 so
     * `hour % interval == 0` lands on tidy clock hours. One table for both platforms, keyed by span
     * rather than stage: the widget's tap stages read it through their span ([ZoomStage.window]),
     * desktop's continuous zoom calls it directly (user, 2026-10-21; was a per-stage table on Android
     * and this table on desktop, and they disagreed at NARROW 7–8h).
     */
    fun labelIntervalForSpan(spanHours: Int): Int = when {
        spanHours <= 6 -> 1
        spanHours <= 14 -> 2
        spanHours <= 28 -> 4
        spanHours <= 56 -> 6
        spanHours <= 96 -> 12
        else -> 24
    }

    /** Curve smoothing passes for a visible span: tight views stay crisp, wide ones read as a trend. Both platforms. */
    fun smoothIterationsForSpan(spanHours: Int): Int = when {
        spanHours <= 6 -> 1
        spanHours <= 36 -> 3
        else -> 4
    }

    /**
     * How many hours a left/right nav press shifts the view. Single source of truth for Android's
     * [ZoomWindow.navJump] and desktop's continuous `DesktopGraphUtils.navJumpHours`.
     *
     * Tight views step 1 hour across the entire configurable NARROW range (up to [MAX_NARROW_SPAN_HOURS]).
     * Above that a press moves a **sixth of the visible span**, so five sixths of what you were
     * looking at is still on screen afterwards. That fraction is not arbitrary: it reproduces both
     * fixed stages' hand-picked jumps exactly — WIDE's 18h span steps 3h, TWO_DAY's 48h steps 8h —
     * so the continuous desktop zoom interpolates between the same two points the staged Android
     * widget lands on. It was a half-span before, which threw away half the view per press (a 9h jump
     * on the 18h default) and disagreed with both stages.
     */
    fun navJumpHours(spanHours: Int): Int = when {
        spanHours <= MAX_NARROW_SPAN_HOURS -> 1
        else -> (spanHours / 6).coerceAtLeast(1)
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

    /**
     * The footer clock-label cadence of [zoom] on a widget [narrowWidget] or not: wide widgets keep
     * [ZoomWindow.labelInterval]; a narrow one thins WIDE to [HourlyGraphDefaults.NARROW_WIDE_LABEL_INTERVAL]
     * and NARROW per [narrowWidgetLabelInterval], so its `<hour><icon>` groups don't crowd. One rule
     * for the temperature, precipitation and cloud graphs.
     */
    fun footerLabelInterval(zoom: ZoomWindow, narrowWidget: Boolean): Int = when {
        !narrowWidget -> zoom.labelInterval
        zoom.stage == ZoomStage.WIDE -> HourlyGraphDefaults.NARROW_WIDE_LABEL_INTERVAL
        zoom.stage == ZoomStage.NARROW -> narrowWidgetLabelInterval(zoom.totalSpanHours.toInt())
        else -> zoom.labelInterval
    }

    /**
     * The hour (offset from now) a tap centres the zoom on: the one at [fraction] (0 = left edge,
     * 1 = right) of a window whose left edge is [startOffsetHours] from now and which spans
     * [spanHours]. Android's 13 hour zones pass zone / 12, desktop the pointer's x / width. In hour
     * offsets, not epoch millis: desktop computed the instant as a Float, ±65 s at epoch size, so a
     * tap near a half hour could centre the wrong hour.
     */
    fun tapOffsetHours(fraction: Double, startOffsetHours: Double, spanHours: Double): Int =
        (startOffsetHours + fraction * spanHours).roundToInt()
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
