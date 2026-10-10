package com.weatherwidget.data.remote

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.shared.util.BatteryTier

/**
 * How far ahead a routine hourly fetch reaches: the **near** window at the normal forecast cadence,
 * the **full** window (8 days) once a day while charging
 * (`performance/261010-hourly-near-window-often-full-eight-days-daily.md`, user 2026-10-10).
 *
 * - [Window.NEAR]: [NEAR_HOURS] stored, live and snapshot. 48 h, not 24: the history readers need a
 *   copy captured the previous day for every hour of today (`HistorySnapshotRetention` rules 3 and 4),
 *   and the daily view's rain summary reaches tomorrow.
 * - [Window.FULL]: the source's whole reach is downloaded (Google: [FULL_HOURS], billed per page) so
 *   the far days' noon cloud and rain maxima on the `forecasts` row are refreshed
 *   ([com.weatherwidget.shared.util.DailyHourlySummaries]). What is *stored* stays
 *   [HourlyHorizons.ROUTINE_HOURS].
 *
 * Free sources answer with their whole horizon whatever is asked, so for them the window only decides
 * how much of it is persisted. Google is the one source where it also decides the request count.
 */
object HourlyWindowPolicy {
    enum class Window { NEAR, FULL }

    /** Hours a NEAR fetch stores. */
    const val NEAR_HOURS = 48

    /** 8 days: what a FULL fetch asks a billed source for (capped by its reach). */
    const val FULL_HOURS = 192

    /** A FULL fetch is due when the last one for the source at this site is at least this old. */
    const val FULL_INTERVAL_MS = 24L * 3_600_000L

    /**
     * FULL when the battery counts as charging ([BatteryTier.treatAsCharging], the same test the
     * forecast cadence uses) and the last FULL fetch at the site is a day old or absent; else NEAR.
     */
    fun choose(isCharging: Boolean, batteryLevel: Int, lastFullFetchedAtMs: Long?, nowMs: Long): Window =
        if (BatteryTier.treatAsCharging(isCharging, batteryLevel) &&
            (lastFullFetchedAtMs == null || nowMs - lastFullFetchedAtMs >= FULL_INTERVAL_MS)
        ) {
            Window.FULL
        } else {
            Window.NEAR
        }

    /** Hours a routine fetch of [sourceId] stores in [window]. */
    fun storeHours(sourceId: String, window: Window): Int {
        val routine = HourlyHorizons.of(sourceId).routineHours
        return when (window) {
            Window.NEAR -> minOf(NEAR_HOURS, routine)
            Window.FULL -> routine
        }
    }

    /**
     * Hours a routine fetch of [sourceId] asks for in [window]: only meaningful for a source whose
     * request count follows the horizon; others return their whole reach regardless.
     */
    fun askHours(sourceId: String, window: Window): Int {
        val horizon = HourlyHorizons.of(sourceId)
        return when (window) {
            Window.NEAR -> minOf(NEAR_HOURS, horizon.routineHours)
            Window.FULL -> if (horizon.costsPerExtraDay) minOf(FULL_HOURS, horizon.maxHours) else horizon.routineHours
        }
    }

    /**
     * The newest FULL fetch the app recorded, valid for one site only: a new site is FULL-due at
     * once, like every other cadence there. Both platforms keep one marker per source.
     */
    data class FullMarker(val lat: Double, val lon: Double, val atMs: Long) {
        fun forSite(lat: Double, lon: Double): Long? =
            atMs.takeIf { LocationMatch.sameSite(LocationMatch.quantize(lat), LocationMatch.quantize(lon), this.lat, this.lon) }

        fun encode(): String = "$lat|$lon|$atMs"

        companion object {
            fun at(lat: Double, lon: Double, atMs: Long) =
                FullMarker(LocationMatch.quantize(lat), LocationMatch.quantize(lon), atMs)

            fun decode(raw: String?): FullMarker? {
                val parts = raw?.split('|') ?: return null
                if (parts.size != 3) return null
                return FullMarker(
                    parts[0].toDoubleOrNull() ?: return null,
                    parts[1].toDoubleOrNull() ?: return null,
                    parts[2].toLongOrNull() ?: return null,
                )
            }
        }
    }
}
