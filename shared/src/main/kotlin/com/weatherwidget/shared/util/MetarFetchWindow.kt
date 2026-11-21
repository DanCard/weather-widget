package com.weatherwidget.shared.util

/**
 * How many hours of METAR history to ask aviationweather.gov for on a routine (recent) pull.
 *
 * A fixed 2-hour window assumed earlier hours were already stored. When they were not — the user had
 * just switched a source's actuals to METAR, or the host had been asleep — the day was computed from
 * whatever two hours came back: measured on desktop 2026-11-21, switching Google's actuals NWS → METAR
 * stored 16 rows spanning 05:35–07:15 and Google's today high fell 45.9 → 41.1 °F
 * (plans/261121-metar-gap-fetch-window.md).
 *
 * The rule is [SynopticFetchWindow]'s — the gap since the newest stored row plus a margin, at least
 * two hours, the whole 24 h when nothing usable is stored — expressed in the whole hours the
 * aviationweather `hours=` parameter takes. One rule for both station networks.
 */
object MetarFetchWindow {
    /** The deep window: the whole past day. Equals `SynopticFetchWindow.DEEP_MINUTES` in hours. */
    const val DEEP_HOURS = SynopticFetchWindow.DEEP_MINUTES / 60

    /** The minimum routine window. Equals `SynopticFetchWindow.MIN_MINUTES` in hours. */
    const val MIN_HOURS = SynopticFetchWindow.MIN_MINUTES / 60

    /**
     * `hours=` for the next METAR request at this site.
     *
     * @param newestStoredMs timestamp of the newest stored METAR reading at this site, or null when
     *   there is none.
     */
    fun hours(newestStoredMs: Long?, nowMs: Long): Int {
        val minutes = SynopticFetchWindow.recentMinutes(newestStoredMs, nowMs)
        return ((minutes + 59) / 60).coerceIn(MIN_HOURS, DEEP_HOURS)
    }
}
