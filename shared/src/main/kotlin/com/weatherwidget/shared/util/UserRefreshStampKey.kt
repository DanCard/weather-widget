package com.weatherwidget.shared.util

import com.weatherwidget.data.local.LocationMatch

/**
 * The key a user-refresh time is stored under: the source (or a sub-key such as
 * `"NWS:observations"`) at the [LocationMatch.quantize]d site, so it follows the same site identity
 * as the last-fetch time it is paired with ([UserRefreshThrottle.latest]).
 *
 * 2026-11-22: Settings → Refresh data at Scottsdale stamped six sources; two minutes after moving to
 * Mountain View, where only NWS had been fetched, the next Refresh data skipped all six as fresh
 * ("Already up to date"). plans/261122-user-refresh-stamps-per-site.md.
 */
object UserRefreshStampKey {
    /** [id] at the quantized ([lat], [lon]); [id] alone when there is no site. */
    fun of(id: String, lat: Double?, lon: Double?): String =
        if (lat == null || lon == null) id else "$id@${LocationMatch.quantize(lat)},${LocationMatch.quantize(lon)}"
}
