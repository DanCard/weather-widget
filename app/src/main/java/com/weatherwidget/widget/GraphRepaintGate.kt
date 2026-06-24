package com.weatherwidget.widget

/**
 * Pure decision function for gating graph-bitmap rebuilds on real change.
 * Extracted for testability — no Android dependencies.
 *
 * On opportunistic UI-only repaints (~2-min cadence while charging), the full graph bitmap
 * rebuild is expensive (~800 ms) but the visible change is negligible: the current-temp dot
 * moves ~1–2 px and the displayed temp string rarely crosses its formatted boundary. This gate
 * skips the rebuild when nothing perceptible would change, falling back to a cheap header-only
 * partial update.
 */
object GraphRepaintGate {
    const val NOW_DRIFT_PX = 4f
    const val MAX_BITMAP_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

    /**
     * Returns true when the graph bitmap should be rebuilt, false when a header-only partial
     * update suffices.
     *
     * @param displayedTemp the formatted temp string from the last full render (e.g. "72.3°")
     * @param currentDisplayedTemp the formatted temp string about to be displayed now
     * @param lastRenderMs elapsed-realtime ms of the last full graph render (0 or absent ⇒ must render)
     * @param nowMs current elapsed-realtime ms
     * @param windowSpanMinutes total span of the visible hourly window in minutes
     * @param bitmapWidthPx width of the graph bitmap in pixels
     */
    fun shouldRebuildBitmap(
        displayedTemp: String?,
        currentDisplayedTemp: String?,
        lastRenderMs: Long,
        nowMs: Long,
        windowSpanMinutes: Long,
        bitmapWidthPx: Int,
    ): Decision {
        if (lastRenderMs <= 0L) return Decision(true, "no_prior_render")

        if (displayedTemp != currentDisplayedTemp) return Decision(true, "temp_changed")

        val elapsedMs = nowMs - lastRenderMs
        if (elapsedMs >= MAX_BITMAP_INTERVAL_MS) return Decision(true, "max_interval")

        if (windowSpanMinutes > 0 && bitmapWidthPx > 0) {
            val elapsedMin = elapsedMs / 60_000f
            val driftPx = elapsedMin * (bitmapWidthPx.toFloat() / windowSpanMinutes)
            if (driftPx >= NOW_DRIFT_PX) return Decision(true, "now_drift=${"%.1f".format(driftPx)}")
        }

        return Decision(false, "header_only_live")
    }

    data class Decision(val shouldRebuild: Boolean, val reason: String)
}
