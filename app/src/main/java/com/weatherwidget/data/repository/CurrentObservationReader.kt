package com.weatherwidget.data.repository

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.selectNearestObservationSite
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.SpatialInterpolator
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CurrentObservationRead"

@VisibleForTesting
internal fun latestUsableNwsObservationsByStation(
    observations: List<ObservationEntity>,
): List<ObservationEntity> =
    observations
        .asSequence()
        .filterNot { it.qcFailed }
        .groupBy { it.stationId }
        .values
        .map { stationRows -> stationRows.maxBy { it.timestamp } }
        .sortedBy { it.stationId }

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
        val usableLatest = latestUsableNwsObservationsByStation(stationRows)

        Log.v(
            TAG,
            "computedNwsBlend persisted=${persistedMain.size} stationRows=${stationRows.size} " +
                "usableStations=${usableLatest.size} sinceMs=$sinceMs",
        )
        if (usableLatest.isEmpty()) return persistedMain

        val blendedTemp = SpatialInterpolator.interpolateIDW(
            latitude,
            longitude,
            usableLatest.map { it.toReading() },
        ) ?: return persistedMain
        val closest = usableLatest.minBy { it.distanceKm }

        return persistedMain + ObservationEntity(
            stationId = "NWS_BLEND",
            stationName = "NWS Blended",
            timestamp = usableLatest.maxOf { it.timestamp },
            temperature = blendedTemp,
            condition = closest.condition,
            locationLat = LocationMatch.quantize(latitude),
            locationLon = LocationMatch.quantize(longitude),
            distanceKm = 0f,
            stationType = "BLENDED",
            fetchedAt = usableLatest.maxOf { it.fetchedAt },
            api = WeatherSource.NWS.id,
            isWebFallback = false,
        )
    }
}
