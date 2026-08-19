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
 *
 * The displayed temp string alone is not a sufficient change signal: the bitmap also carries the
 * dominant-station label and its reading time, which move independently of it. [ObservationWatermark]
 * supplies the data-changed answer directly — see that class for why it keys on observation
 * `timestamp` rather than `fetchedAt`.
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
     * @param lastWatermarkMs [ObservationWatermark] recorded by the last full render. Null means the
     *   render predates watermark tracking (an app upgrade), which forces one rebuild rather than
     *   silently reading as "unchanged".
     * @param currentWatermarkMs [ObservationWatermark] of the rows about to be drawn.
     *   [ObservationWatermark.NONE] means "nothing to measure" and never forces a rebuild.
     * @param paintOwed a repaint fell due while the screen was off and was skipped entirely; the
     *   bitmap on screen may predate an arbitrary number of fetches, so no cheaper signal is
     *   trustworthy. See `WidgetPaintCoordinator`.
     */
    fun shouldRebuildBitmap(
        displayedTemp: String?,
        currentDisplayedTemp: String?,
        lastRenderMs: Long,
        nowMs: Long,
        windowSpanMinutes: Long,
        bitmapWidthPx: Int,
        lastWatermarkMs: Long? = null,
        currentWatermarkMs: Long = ObservationWatermark.NONE,
        paintOwed: Boolean = false,
    ): Decision {
        // Ahead of every other check: the other signals compare against the last *render*, and a
        // screen-off skip means no render happened to compare against.
        if (paintOwed) return Decision(true, "paint_owed")

        if (lastRenderMs <= 0L) return Decision(true, "no_prior_render")

        if (lastWatermarkMs == null) return Decision(true, "watermark_absent")

        // Strictly greater: a watermark that goes backwards (retention cleanup dropping the newest
        // row, a source toggle narrowing the scope) is not new data and must not trigger a rebuild.
        if (currentWatermarkMs > lastWatermarkMs) return Decision(true, "data_changed")

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
