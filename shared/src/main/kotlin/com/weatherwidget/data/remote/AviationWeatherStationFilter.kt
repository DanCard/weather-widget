package com.weatherwidget.data.remote

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns an `aviationweather.gov/api/data/stationinfo` result into the N nearest METAR-reporting
 * stations, in the shape the existing observation pipeline already consumes.
 *
 * Two things this must get right:
 *
 * 1. **Not every returned site reports METARs.** `stationinfo` includes sites with
 *    `"siteType": []` (observed 2026-08-23: `AAMC1`, Alameda). Requesting those ids from
 *    `/metar?ids=` returns nothing for them, so they must be filtered out at discovery rather than
 *    quietly wasting a slot in the N-nearest list.
 * 2. **Ordering must be stable.** The blend re-reads this list every cycle; a set that reshuffles
 *    between cycles feeds the IDW different inputs for the same location. Ties are broken by id so
 *    two equidistant stations never swap places.
 */
object AviationWeatherStationFilter {

    /** Matches the 5-station depth the NWS path already uses. */
    const val DEFAULT_LIMIT = 5

    private const val EARTH_RADIUS_KM = 6371.0

    /** One row of `stationinfo`, before distance ranking. */
    data class Candidate(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val elevationMeters: Double?,
        val siteTypes: List<String>,
        val country: String?,
    )

    /** A station that reports METARs, with its distance from the query point. */
    data class RankedStation(
        val info: NwsApi.StationInfo,
        val distanceKm: Double,
        val elevationMeters: Double?,
    )

    fun reportsMetar(candidate: Candidate): Boolean =
        candidate.siteTypes.any { it.equals("METAR", ignoreCase = true) }

    /**
     * METAR-reporting candidates, nearest first, capped at [limit].
     *
     * Every station here is an airport reporting station, so all are typed [NwsApi.StationType.OFFICIAL]
     * — there is no personal-weather-station equivalent in this feed, and mislabelling one as
     * PERSONAL would wrongly apply `DEFAULT_PERSONAL_STATION_DISCOUNT` in the blend.
     */
    fun nearest(
        candidates: List<Candidate>,
        latitude: Double,
        longitude: Double,
        limit: Int = DEFAULT_LIMIT,
    ): List<RankedStation> =
        candidates
            .asSequence()
            .filter(::reportsMetar)
            .filter { it.lat.isFinite() && it.lon.isFinite() }
            .distinctBy { it.id }
            .map { c ->
                RankedStation(
                    info = NwsApi.StationInfo(
                        id = c.id,
                        name = c.name.ifBlank { c.id },
                        lat = c.lat,
                        lon = c.lon,
                        type = NwsApi.StationType.OFFICIAL,
                    ),
                    distanceKm = distanceKm(latitude, longitude, c.lat, c.lon),
                    elevationMeters = c.elevationMeters,
                )
            }
            // Deterministic: distance first, id as the tie-break so equidistant stations never swap
            // between cycles and hand the blend a different input set for the same place.
            .sortedWith(compareBy({ it.distanceKm }, { it.info.id }))
            .take(limit)
            .toList()

    /** Haversine, matching `NwsObservationMapper.distanceKm` so both paths rank identically. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return EARTH_RADIUS_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
