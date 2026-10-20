package com.weatherwidget.data.remote.weathernext

/**
 * Whether the precipitation graph offers the "Sim loop" pill at all.
 *
 * Two things make the loop useless, and both hide the pill rather than leading to a dead viewer:
 * - **The location is off the map.** The widest domain ([WeatherNextDomain.WIDE]) is North America
 *   and the Pacific; `resolve` falls back to it everywhere else, so in Kyiv the viewer would play a
 *   map with the pin off the edge.
 * - **The site is gone.** The frames come from a personal GitHub Pages site; a 404/410 on the
 *   manifest means it moved or was taken down. The pill hides for [GONE_HIDE_MS], then shows again so
 *   the next tap re-checks — no background request is ever spent on it. Any other failure (offline,
 *   timeout, 5xx) leaves the pill up: the viewer's Retry covers it.
 */
object WeatherNextAvailability {
    const val GONE_HIDE_MS = 24L * 60 * 60 * 1000

    /** Inset from the WIDE image edge, so a pin is never drawn half off the map. */
    private const val EDGE_MARGIN_DEG = 1.0

    fun covers(lat: Double, lon: Double): Boolean {
        val d = WeatherNextDomain.WIDE
        val lon360 = (lon + 360.0) % 360.0
        return lat in (d.lat0 + EDGE_MARGIN_DEG)..(d.lat1 - EDGE_MARGIN_DEG) &&
            lon360 in (d.lon0 + EDGE_MARGIN_DEG)..(d.lon1 - EDGE_MARGIN_DEG)
    }

    fun showPill(lat: Double?, lon: Double?, goneAtMs: Long?, nowMs: Long): Boolean {
        if (lat == null || lon == null || !covers(lat, lon)) return false
        return goneAtMs == null || nowMs - goneAtMs !in 0 until GONE_HIDE_MS
    }
}

/** The manifest answered 404/410: the loop's host no longer serves this domain. */
class WeatherNextGoneException(val status: Int) : Exception("WeatherNext manifest HTTP $status")
