package com.weatherwidget.desktop

/**
 * The desktop daily header's one fit decision: does the day-of-week/date still fit beside the
 * centre buttons?
 *
 * The centre cluster is the header's only weighted cluster, so it gets whatever the content-sized
 * left and right clusters leave. Everything in it is laid out at a known size — icons are square at
 * a fixed dp, spacing is fixed — so the whole question is arithmetic once the date has been
 * measured, no `SubcomposeLayout` needed.
 *
 * The buttons never yield; only the date does. That is the same priority Android's daily header
 * settles on, where the painted date reserves space around
 * `HeaderWidthChecker.resolveDailyIconLayout`'s icon count and drops itself when nothing is left.
 * Keeping the rule in one small pure function (rather than inline in `WidgetHeader`) is what lets
 * it be asserted without a window.
 *
 * See `plans/260901-daily-home-button-when-source-not-preferred.md`.
 */
object DailyHeaderCentreFit {

    /** Width the button row occupies: [iconCount] square icons with [spacingDp] between them. */
    fun iconsWidthDp(iconCount: Int, iconSizeDp: Float, spacingDp: Float): Float =
        if (iconCount <= 0) 0f else iconCount * iconSizeDp + (iconCount - 1) * spacingDp

    /**
     * Whether the date may share the centre cluster with the buttons.
     *
     * [availableDp] is the cluster's own leftover width, so a header whose left cluster has grown
     * (long temperature + delta + "from yest" + rain chance) squeezes the date out here rather than
     * truncating anything on either side.
     */
    fun showDate(
        availableDp: Float,
        iconCount: Int,
        iconSizeDp: Float,
        spacingDp: Float,
        dateWidthDp: Float,
    ): Boolean {
        if (dateWidthDp <= 0f) return false
        val icons = iconsWidthDp(iconCount, iconSizeDp, spacingDp)
        val gap = if (iconCount <= 0) 0f else spacingDp
        return icons + gap + dateWidthDp <= availableDp
    }
}
