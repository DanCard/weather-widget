package com.weatherwidget.data.repository

import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.SpatialInterpolator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NwsCurrentObsUpdate"
internal const val MAX_NWS_STATIONS = 5

@Singleton
class NwsCurrentObservationUpdater @Inject constructor(
    private val observationSource: NwsObservationSource,
    private val observationDao: ObservationDao,
    private val appLogDao: AppLogDao,
    private val dailyActualsStore: DailyActualsStore,
) {
    internal suspend fun fetchNwsCurrent(
        latitude: Double,
        longitude: Double,
    ): CurrentReadingPayload? = coroutineScope {
        val stations = observationSource
            .stationsForLocation(latitude, longitude)
            .take(MAX_NWS_STATIONS)
        if (stations.isEmpty()) return@coroutineScope null

        val fetchStartMs = System.currentTimeMillis()
        val closestDeferred = async {
            val retryDelaysMs = listOf(10_000L, 30_000L)
            var entity = fetchAndStoreStation(stations.first(), latitude, longitude, attempt = 0, stationIndex = 0)
            for ((index, delayMs) in retryDelaysMs.withIndex()) {
                if (entity != null) break
                delay(delayMs)
                entity = fetchAndStoreStation(
                    stations.first(),
                    latitude,
                    longitude,
                    attempt = index + 1,
                    stationIndex = 0,
                )
            }
            entity
        }
        val otherDeferreds = stations.drop(1).mapIndexed { index, station ->
            async {
                fetchAndStoreStation(
                    station,
                    latitude,
                    longitude,
                    stationIndex = index + 1,
                )
            }
        }
        val successful = (listOf(closestDeferred) + otherDeferreds).mapNotNull { it.await() }
        val totalMs = System.currentTimeMillis() - fetchStartMs
        if (successful.isEmpty()) {
            appLogDao.log(
                "NWS_FETCH_FAIL_ALL",
                "stationsTried=${stations.size} totalMs=$totalMs",
                "WARN",
            )
            return@coroutineScope null
        }

        val blendedTemp = SpatialInterpolator.interpolateIDW(
            latitude,
            longitude,
            successful.map { it.toReading() },
        ) ?: return@coroutineScope null
        val closest = successful.minBy { it.distanceKm }
        val stationSummary = successful.joinToString { "${it.stationId}(${it.distanceKm}km)" }
        appLogDao.log(
            "NWS_IDW",
            "blended=${blendedTemp}°F from ${successful.size} stations: $stationSummary totalMs=$totalMs",
        )
        Log.d(TAG, "NWS IDW blend: $blendedTemp°F from $stationSummary totalMs=$totalMs")

        CurrentReadingPayload(
            WeatherSource.NWS,
            blendedTemp,
            closest.condition,
            successful.maxOf { it.timestamp },
        )
    }

    private suspend fun fetchAndStoreStation(
        station: com.weatherwidget.data.remote.NwsApi.StationInfo,
        latitude: Double,
        longitude: Double,
        attempt: Int = 0,
        stationIndex: Int,
    ): ObservationEntity? {
        val result = try {
            observationSource.fetchLatest(station, latitude, longitude, stationIndex)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogDao.log(
                "NWS_STATION_FAIL",
                "station=${station.id} attempt=$attempt error=${e::class.simpleName}:${e.message}",
                "WARN",
            )
            Log.e(TAG, "NWS station ${station.id} attempt $attempt failed", e)
            return null
        }

        if (result.qcFlagged.isNotEmpty()) {
            observationDao.insertAll(result.qcFlagged)
            appLogDao.log(
                "OBS_QC_FLAGGED",
                "station=${station.id} count=${result.qcFlagged.size} " +
                    "timestamps=${result.qcFlagged.joinToString(",") { it.timestamp.toString() }} " +
                    "temps=${result.qcFlagged.joinToString(",") { it.temperature.toString() }}",
                "WARN",
            )
        }

        val chosen = result.chosen
        if (chosen == null) {
            if (result.shouldTouchFetchedAt) {
                observationDao.touchLatestFetchedAt(
                    station.id,
                    LocationMatch.quantize(latitude),
                    LocationMatch.quantize(longitude),
                    System.currentTimeMillis(),
                )
                appLogDao.log(
                    "OBS_ATTEMPT_TOUCH",
                    "station=${station.id} reason=no_valid_observation attempt=$attempt",
                    "INFO",
                )
            } else {
                appLogDao.log(
                    "NWS_STATION_FAIL",
                    "station=${station.id} attempt=$attempt " +
                        "nws=${result.nwsFailureReason ?: "unknown"} " +
                        "synoptic=${result.synopticFailureReason ?: "not_tried"}",
                    "WARN",
                )
            }
            return null
        }

        if (attempt > 0) {
            appLogDao.log(
                "NWS_STATION_RETRY_OK",
                "station=${station.id} attempt=$attempt",
                "INFO",
            )
        }
        observationDao.insertAll(listOf(chosen))
        observationDao.touchLatestFetchedAt(
            chosen.stationId,
            chosen.locationLat,
            chosen.locationLon,
            System.currentTimeMillis(),
        )
        logCurrentObservationInsert(chosen)
        val observationDate = Instant.ofEpochMilli(chosen.timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        dailyActualsStore.recomputeDailyExtremesForDay(
            latitude,
            longitude,
            observationDate,
            emptyList(),
        )
        return chosen
    }

    private suspend fun logCurrentObservationInsert(observation: ObservationEntity) {
        val nowMs = System.currentTimeMillis()
        appLogDao.log(
            "OBS_CURRENT_INSERT",
            "source=${observation.api} station=${observation.stationId} " +
                "timestamp=${observation.timestamp} fetchedAt=${observation.fetchedAt} " +
                "temp=${observation.temperature} " +
                "timestampAgeMin=${(nowMs - observation.timestamp) / 60_000L} " +
                "fetchAgeMin=${(nowMs - observation.fetchedAt) / 60_000L}",
            "INFO",
        )
    }
}
