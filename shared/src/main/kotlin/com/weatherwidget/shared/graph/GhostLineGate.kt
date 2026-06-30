package com.weatherwidget.shared.graph

/**
 * When the hourly temperature graph should draw/process the **ghost line** (forecast + observed
 * delta) and its labels.
 *
 * The ghost line is a near-present affordance: it projects "what we'd expect" from the last
 * observation while browsing a few hours ahead in a **narrow** window. It must not run on
 * far-anchored future views (wide zoom, many days ahead) where the fetch dot extrapolates far
 * off-screen and hourly gaps produce meaningless NaN-heavy candidates.
 */
object GhostLineGate {

    /**
     * @param fetchDotX Screen x of the fetch/observation anchor (may be negative when extrapolated).
     * @param graphWidthPx Plot width in pixels.
     * @param spanHours Visible window span in hours (first→last hour).
     * @param nowIndicatorVisible True when the NOW line is drawn in the visible plot.
     */
    fun shouldProcess(
        fetchDotX: Float?,
        graphWidthPx: Float,
        spanHours: Long,
        nowIndicatorVisible: Boolean,
        maxSpanHoursForOffLeftExtension: Long = GhostLineLabel.MAX_HOURS_SPAN,
        /** Hours from the caller's current time to the first hour in the visible window; negative when the window still overlaps now. */
        hoursFromNowToWindowStart: Long? = null,
    ): Boolean {
        if (fetchDotX == null || graphWidthPx <= 0f) return false
        if (nowIndicatorVisible) return true
        if (fetchDotX in 0f..graphWidthPx) return true
        // Narrow near-term future scroll: fetch scrolled modestly off-left (within one viewport).
        val nearTermWindow = hoursFromNowToWindowStart == null ||
            hoursFromNowToWindowStart <= maxSpanHoursForOffLeftExtension
        return spanHours <= maxSpanHoursForOffLeftExtension &&
            fetchDotX > -graphWidthPx &&
            nearTermWindow
    }
}