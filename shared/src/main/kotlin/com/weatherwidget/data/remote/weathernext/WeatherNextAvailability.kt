package com.weatherwidget.data.remote.weathernext

import com.weatherwidget.shared.rainmap.RainLoopLoadError

/**
 * Whether the precipitation graph offers the "Sim loop" pill at all.
 *
 * Only an off-map location hides it: the widest domain ([WeatherNextDomain.WIDE]) is North America
 * and the Pacific, and `resolve` falls back to it everywhere else, so in Kyiv the viewer would play a
 * map with the pin off the edge.
 *
 * **A gone site does not hide the pill** (user, 2026-10-20). The frames come from a personal GitHub
 * Pages site; a 404/410 on the manifest means it moved or was taken down. The viewer then says so and
 * offers the Open-Meteo rain map. For [GONE_HIDE_MS] after a 404/410 it opens straight into that
 * message without asking the host again ([knownGone]); after that the next tap re-checks. No
 * background request is ever spent on it.
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

    fun showPill(lat: Double?, lon: Double?): Boolean =
        lat != null && lon != null && covers(lat, lon)

    /** True while a 404/410 seen at [goneAtMs] still stands, so the viewer skips the manifest request. */
    fun knownGone(goneAtMs: Long?, nowMs: Long): Boolean =
        goneAtMs != null && nowMs - goneAtMs in 0 until GONE_HIDE_MS

    /** Where the frames come from, named in every error ("ajoros.github.io"). */
    val HOST: String = RainLoopLoadError.hostOf(WeatherNextForecastClient.BASE_URL)

    /** 404/410 = the host dropped the loop ([RainLoopLoadError.Kind.GONE]). */
    fun loadError(error: Throwable): RainLoopLoadError = RainLoopLoadError.of(error, HOST, setOf(404, 410))
}

/** The host answered a non-2xx status. */
open class WeatherNextHttpException(status: Int) : com.weatherwidget.shared.rainmap.RainLoopHttpException(status, "WeatherNext")

/** The manifest answered 404/410: the loop's host no longer serves this domain. */
class WeatherNextGoneException(status: Int) : WeatherNextHttpException(status)
