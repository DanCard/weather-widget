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
        rowLatitude: Double = latitude,
        rowLongitude: Double = longitude,
        nowMs: Long = System.currentTimeMillis(),
    ): ObservationReading? {
        val usable = latestUsableByStation(readings)
        if (usable.isEmpty()) return null
        val temperature = SpatialInterpolator.interpolateIDW(latitude, longitude, usable, nowMs) ?: return null
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
