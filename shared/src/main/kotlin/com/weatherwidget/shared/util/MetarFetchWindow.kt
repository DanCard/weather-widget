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
 * The rule is the gap since the newest stored row plus a margin, at least two hours, or the whole
 * 24 h when nothing usable is stored — expressed in the whole hours the aviationweather `hours=`
 * parameter takes.
 */
object MetarFetchWindow {
    /** The deep window: the whole past day. */
    const val DEEP_HOURS = 24

    /** The minimum routine window. */
    const val MIN_HOURS = 2

    private const val DEEP_MINUTES = DEEP_HOURS * 60
    private const val MIN_MINUTES = MIN_HOURS * 60
    private const val MARGIN_MINUTES = 30
    private const val GAP_MS = 24 * 60 * 60 * 1000L

    /**
     * `hours=` for the next METAR request at this site.
     *
     * @param newestStoredMs timestamp of the newest stored METAR reading at this site, or null when
     *   there is none.
     */
    fun hours(newestStoredMs: Long?, nowMs: Long): Int {
        val minutes = when {
            newestStoredMs == null -> DEEP_MINUTES
            nowMs - newestStoredMs >= GAP_MS -> DEEP_MINUTES
            else -> {
                val gapMinutes = ((nowMs - newestStoredMs).coerceAtLeast(0L) / 60_000L).toInt()
                (gapMinutes + MARGIN_MINUTES).coerceIn(MIN_MINUTES, DEEP_MINUTES)
            }
        }
        return ((minutes + 59) / 60).coerceIn(MIN_HOURS, DEEP_HOURS)
    }
}
