package com.weatherwidget.shared.util

/**
 * How much of the Synoptic timeseries window to ask for on a routine refresh.
 *
 * A full sync used to always request `recent=1440` (24 h) and keep ~5% of it. Observations are
 * stored for 10 days, so earlier hours are already on disk — only the gap since the newest stored
 * reading is missing. First run / new location / a gap past [GAP_MS] still gets the deep window,
 * because there is nothing local to fall back on.
 */
object SynopticFetchWindow {
    /** Full deep window: the whole past 24 h. */
    const val DEEP_MINUTES = 1440

    /** Never ask for less than this — daily extremes want a few hours of context. */
    const val MIN_MINUTES = 120

    /** Extra minutes past the gap, so late or re-QC'd reports in the overlap are not missed. */
    const val MARGIN_MINUTES = 30

    /** No stored rows newer than this means "start over" rather than "fill the gap". */
    const val GAP_MS = 24 * 60 * 60 * 1000L

    /**
     * `recent=` minutes for the next Synoptic request.
     *
     * @param newestStoredMs timestamp of the newest stored SYNOPTIC reading at this site, or null
     *   when there is none.
     */
    fun recentMinutes(newestStoredMs: Long?, nowMs: Long): Int {
        if (newestStoredMs == null || nowMs - newestStoredMs >= GAP_MS) return DEEP_MINUTES
        val minutesSince = (nowMs - newestStoredMs) / 60_000L
        return (minutesSince + MARGIN_MINUTES).toInt().coerceIn(MIN_MINUTES, DEEP_MINUTES)
    }
}
