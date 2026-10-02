package com.weatherwidget.data.repository

import android.util.Log
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.selectNearestObservationSite
import com.weatherwidget.data.local.toReading
import com.weatherwidget.shared.observations.NwsBlend
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CurrentObservationRead"

@Singleton
class CurrentObservationReader @Inject constructor(
    private val observationDao: ObservationDao,
) {
    suspend fun getMainObservationsWithComputedNwsBlend(
        latitude: Double,
        longitude: Double,
        sinceMs: Long,
    ): List<ObservationEntity> {
        val persistedMain = selectNearestObservationSite(
            observationDao.getLatestMainObservationsExcludingNws(
                latitude,
                longitude,
                sinceMs,
            ),
            latitude,
            longitude,
        )
        val stationRows = selectNearestObservationSite(
            observationDao.getLatestNwsObservationsByStationAllTime(
                latitude,
                longitude,
                sinceMs,
            ),
            latitude,
            longitude,
        )
            .filter { it.timestamp > sinceMs }
        val readings = stationRows.map { it.toReading() }
        Log.v(
            TAG,
            "computedNwsBlend persisted=${persistedMain.size} stationRows=${stationRows.size} " +
                "usableStations=${NwsBlend.latestUsableByStation(readings).size} sinceMs=$sinceMs",
        )
        // Shared with desktop's fetch path: no usable station, or all too stale to weight → no row.
        val blend = NwsBlend.build(
            readings,
            latitude,
            longitude,
            rowLatitude = LocationMatch.quantize(latitude),
            rowLongitude = LocationMatch.quantize(longitude),
        ) ?: return persistedMain
        return persistedMain + ObservationEntity(
            stationId = blend.stationId,
            stationName = blend.stationName,
            timestamp = blend.timestamp,
            temperature = blend.temperature,
            condition = blend.condition,
            locationLat = blend.locationLat,
            locationLon = blend.locationLon,
            distanceKm = blend.distanceKm,
            stationType = blend.stationType,
            fetchedAt = blend.fetchedAt,
            api = blend.api,
            isWebFallback = blend.isWebFallback,
        )
    }
}
