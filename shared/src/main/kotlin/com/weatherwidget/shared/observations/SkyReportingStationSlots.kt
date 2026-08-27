package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.SynopticApi

/**
 * Keeps the nearest stations for temperature, then makes room for stations that can answer the
 * cloud question at all.
 *
 * Synoptic's radius query returns ~197 stations and the fetcher kept the nearest ten. Measured
 * 2026-08-27, eight of those ten were personal weather stations that have never reported sky
 * condition once — 601 rows between them, zero cloud — and they won their slots purely by being
 * close. `NwsApi.Observation.cloudLayers` says as much: "Empty = not reported; personal stations
 * always return empty."
 *
 * That left the cloud curve resting on two stations, and it broke when both went quiet together:
 * KNUQ omitted its sky group at 12:55 and 13:15 and KPAO reports hourly, so cloud-carrying reports
 * ran 12:47 → 13:35. Forty-eight minutes is past `CloudActualSeries`' 30-minute bridge, so the line
 * split — correctly, since nothing was measured.
 *
 * **Raising the limit is the wrong fix.** Reaching a 16 km airport by proximity means admitting
 * every closer station first, plausibly ~100 of them, for roughly ten times the stored rows and
 * parse cost — and it would still be luck rather than a rule. The limit is not too small; the
 * ranking is measuring the wrong axis for this question, and no limit is high enough to fix that.
 *
 * **Additive, never displacing.** Proximity is the right axis for the temperature IDW blend, so
 * every station kept today is still kept. The set grows by at most [MIN_SKY_STATIONS].
 */
object SkyReportingStationSlots {

    /**
     * How many sky-reporting stations the selection guarantees, when that many exist.
     *
     * Two is what shipped and is what broke: a 20-minute reporter plus an hourly one left a
     * 48-minute hole the moment the first omitted a sky group. Three is the smallest number that
     * still has two independent reporters when one goes quiet.
     */
    const val MIN_SKY_STATIONS = 3

    /** A station answers the cloud question if any of its observations carries a sky condition. */
    fun reportsSky(station: SynopticApi.Companion.RadiusStation): Boolean =
        station.observations.any { it.cloudLayers.isNotEmpty() }

    /**
     * The nearest [limit] stations, plus the nearest sky-reporting stations needed to reach
     * [MIN_SKY_STATIONS].
     *
     * Returns fewer than the quota when fewer exist — an area with one sky-reporting station is a
     * fact about the area, not an error. Ordered nearest-first, and a station already kept is never
     * added twice.
     */
    fun select(
        stations: List<SynopticApi.Companion.RadiusStation>,
        limit: Int,
    ): List<SynopticApi.Companion.RadiusStation> {
        val byDistance = stations.sortedBy { it.distanceKm }
        val nearest = byDistance.take(limit)
        val skyShortfall = MIN_SKY_STATIONS - nearest.count { reportsSky(it) }
        if (skyShortfall <= 0) return nearest

        val keptIds = nearest.map { it.info.id }.toSet()
        val added = byDistance.asSequence()
            .filter { it.info.id !in keptIds }
            .filter { reportsSky(it) }
            .take(skyShortfall)
            .toList()
        return (nearest + added).sortedBy { it.distanceKm }
    }
}
