package com.weatherwidget.data.repository

import android.util.Log
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.selectNearestObservationSite
import com.weatherwidget.data.local.toReading
import com.weatherwidget.shared.observations.NwsBlend
import com.weatherwidget.shared.observations.ObservationOrigin
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
        nowMs: Long = System.currentTimeMillis(),
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
        // The blend's inputs are chosen by the shared rule (NwsBlend.current: NWS only, the blend's own
        // 3 h window rather than [sinceMs], sites merged rather than collapsed) — the raw candidate
        // rows, uncollapsed: a row's site is fetch provenance (ObservationSiteMerge).
        // fetchedAt >= timestamp, so this fetchedAt-filtered query is a superset of the window.
        val stationRows = observationDao.getLatestNwsObservationCandidatesByStationAllTime(
            latitude,
            longitude,
            nowMs - ObservationOrigin.BLEND_MAX_AGE_MS,
        )
        val readings = stationRows.map { it.toReading() }
        Log.v(
            TAG,
            "computedNwsBlend persisted=${persistedMain.size} stationRows=${stationRows.size} " +
                "usableStations=${NwsBlend.latestUsableByStation(readings).size}",
        )
        // No usable station, or all too stale to weight → no row.
        val blend = NwsBlend.current(
            readings,
            latitude,
            longitude,
            nowMs,
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
