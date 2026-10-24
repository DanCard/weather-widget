package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.SpatialInterpolator

/**
 * The synthetic `NWS_BLEND` observation: an IDW blend of each NWS station's newest usable reading,
 * which the header, graph and daily blend all treat as the NWS "truth" for now.
 *
 * Shared by Android's `CurrentObservationReader` and desktop's NWS observation fetches. It was built
 * three times and the copies disagreed on what to do when the blend had nothing to blend (every
 * station past the IDW's 3h decay): Android emitted no row; desktop wrote one anyway, carrying the
 * hourly forecast (`fetchNwsObservations`) or the nearest station's stale reading
 * (`fetchNwsObservationsOnly`) — a forecast stored as an observation. Here there is no row then;
 * callers that need a header value fall back on their own, outside the observations table.
 * See plans/261002-share-nws-blend.md.
 */
object NwsBlend {
    const val STATION_ID = "NWS_BLEND"
    const val STATION_NAME = "NWS Blended"
    val STATION_TYPE = StationType.BLENDED

    /**
     * The blend at [nowMs] from whatever observation rows the caller has loaded — the one rule both
     * platforms use to choose its inputs, so the same rows give the same blend everywhere.
     *
     * - **NWS rows only.** The blend is NWS's truth; other feeds' rows are not inputs.
     * - **Window = the blend's own decay**, [ObservationOrigin.BLEND_MAX_AGE_MS] before [nowMs], never
     *   a caller's calendar boundary. Android used to pass local midnight, so at 00:15 a 23:30 reading
     *   the IDW still weights was excluded and the header blended fewer stations than desktop.
     * - **Sites merged, not collapsed** ([com.weatherwidget.data.local.ObservationSiteMerge]): a row's
     *   location is fetch provenance, and collapsing to the nearest site dropped the newest readings
     *   after a short walk. Desktop's reads were already merged; Android's blend collapsed.
     *
     * See plans/261009-desktop-stops-storing-nws-blend.md (step 3).
     */
    fun current(
        readings: List<ObservationReading>,
        latitude: Double,
        longitude: Double,
        personalStationWeight: Double,
        nowMs: Long,
        rowLatitude: Double = latitude,
        rowLongitude: Double = longitude,
    ): ObservationReading? {
        val windowStartMs = nowMs - ObservationOrigin.BLEND_MAX_AGE_MS
        val inputs = com.weatherwidget.data.local.ObservationSiteMerge.merge(
            readings.filter { it.api == WeatherSource.NWS.id && it.timestamp > windowStartMs },
            latitude,
            longitude,
            latOf = { it.locationLat },
            lonOf = { it.locationLon },
            stationOf = { it.stationId },
            timestampOf = { it.timestamp },
            apiOf = { it.api },
            fetchedAtOf = { it.fetchedAt },
        )
        return build(inputs, latitude, longitude, personalStationWeight, rowLatitude, rowLongitude, nowMs)
    }

    /** The current reading an NWS observation cycle reports from the stations it just fetched. */
    data class FetchReading(val temperature: Float, val condition: String, val observedAt: Long)

    const val IDW_TAG = "NWS_IDW"

    /**
     * The fetch-time current reading from each station's [latestReadings]: [build]'s temperature,
     * nearest usable condition and newest usable time, or null when nothing blends. No fallback to a
     * forecast or a stale station's reading on either platform (user, 2026-10-24): "nothing blends"
     * is no reading, and the header shows the bare forecast.
     * See plans/261024-nws-fetch-time-current-reading-shared.md.
     */
    fun fetchReading(
        latestReadings: List<ObservationReading>,
        latitude: Double,
        longitude: Double,
        personalStationWeight: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): FetchReading? =
        build(latestReadings, latitude, longitude, personalStationWeight, nowMs = nowMs)
            ?.let { FetchReading(it.temperature, it.condition, it.timestamp) }

    /** The [IDW_TAG] line for a cycle's reading (both platforms). */
    fun idwLine(reading: FetchReading?, latestReadings: List<ObservationReading>, storedCount: Int, totalMs: Long): String {
        val stations = latestReadings.sortedBy { it.distanceKm }.joinToString { "${it.stationId}(${it.distanceKm}km)" }
        return "blended=${reading?.temperature ?: "none"}°F from ${latestReadings.size} stations: $stations " +
            "stored=$storedCount totalMs=$totalMs"
    }

    /** Each station's newest reading that passed upstream QC, sorted by station id. */
    fun latestUsableByStation(readings: List<ObservationReading>): List<ObservationReading> =
        readings
            .asSequence()
            .filterNot { it.qcFailed || it.stationId == STATION_ID }
            .groupBy { it.stationId }
            .values
            .map { stationRows -> stationRows.maxBy { it.timestamp } }
            .sortedBy { it.stationId }

    /**
     * The blend row for [readings] (any number per station), or null when no station has a usable
     * reading or every one is too stale for the IDW to weight.
     *
     * [latitude]/[longitude] are the blend centre; the row is filed at [rowLatitude]/[rowLongitude]
     * (Android passes the quantized storage key).
     */
    fun build(
        readings: List<ObservationReading>,
        latitude: Double,
        longitude: Double,
        /** The user's personal-station weight ([SpatialInterpolator.interpolateIDW]). */
        personalStationWeight: Double,
        rowLatitude: Double = latitude,
        rowLongitude: Double = longitude,
        nowMs: Long = System.currentTimeMillis(),
    ): ObservationReading? {
        val usable = latestUsableByStation(readings)
        if (usable.isEmpty()) return null
        val temperature = SpatialInterpolator.interpolateIDW(latitude, longitude, usable, personalStationWeight, nowMs) ?: return null
        return ObservationReading(
            stationId = STATION_ID,
            stationName = STATION_NAME,
            timestamp = usable.maxOf { it.timestamp },
            temperature = temperature,
            condition = usable.minBy { it.distanceKm }.condition,
            locationLat = rowLatitude,
            locationLon = rowLongitude,
            distanceKm = 0f,
            stationType = STATION_TYPE,
            fetchedAt = usable.maxOf { it.fetchedAt },
            api = WeatherSource.NWS.id,
            isWebFallback = false,
        )
    }
}
