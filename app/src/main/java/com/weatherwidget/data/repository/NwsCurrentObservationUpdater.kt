package com.weatherwidget.data.repository

import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
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

// Retry cadence for the CLOSEST station only (see fetchNwsCurrent). The other stations get a
// single attempt each.
private val CLOSEST_STATION_RETRY_DELAYS_MS = listOf(10_000L, 30_000L)

@Singleton
class NwsCurrentObservationUpdater private constructor(
    private val observationSource: NwsObservationSource,
    private val observationDao: ObservationDao,
    private val appLogDao: AppLogDao,
    private val dailyActualsStore: DailyActualsStore,
    private val metarObservationSource: MetarObservationSource?,
    @Suppress("UNUSED_PARAMETER") constructionMarker: Unit,
) {
    @Inject
    constructor(
        observationSource: NwsObservationSource,
        observationDao: ObservationDao,
        appLogDao: AppLogDao,
        dailyActualsStore: DailyActualsStore,
        metarObservationSource: MetarObservationSource,
    ) : this(
        observationSource,
        observationDao,
        appLogDao,
        dailyActualsStore,
        metarObservationSource,
        Unit,
    )

    /** Compatibility seam for repository tests that exercise only the NWS/Synoptic source. */
    internal constructor(
        observationSource: NwsObservationSource,
        observationDao: ObservationDao,
        appLogDao: AppLogDao,
        dailyActualsStore: DailyActualsStore,
    ) : this(observationSource, observationDao, appLogDao, dailyActualsStore, null, Unit)

    internal suspend fun fetchNwsCurrent(
        latitude: Double,
        longitude: Double,
    ): CurrentReadingPayload? = coroutineScope {
        val stations = observationSource
            .stationsForLocation(latitude, longitude)
            .take(MAX_NWS_STATIONS)
        if (stations.isEmpty()) return@coroutineScope null

        val fetchStartMs = System.currentTimeMillis()
        // One token-free Aviation Weather batch starts before any station job. Each station awaits
        // its own row only after api.weather.gov returns, so the transports overlap rather than the
        // old per-station NWS-then-Synoptic sequence.
        val parallelWebDeferred = metarObservationSource?.let { source ->
            async {
                source.fetchObservationsResult(
                    latitude,
                    longitude,
                    hours = PARALLEL_WEB_HOURS,
                    limit = MAX_NWS_STATIONS,
                )
            }
        }
        suspend fun webOutcomeFor(stationId: String): FetchOutcome<ObservationEntity>? =
            when (val batch = parallelWebDeferred?.await() ?: return null) {
                is FetchOutcome.Success -> batch.value
                    .asSequence()
                    .filter { it.stationId == stationId }
                    .filterNot { it.qcFailed }
                    .filter {
                        it.timestamp <= System.currentTimeMillis() +
                            com.weatherwidget.shared.observations.ObservationFallbackPolicy.MAX_WEB_FUTURE_SKEW_MS
                    }
                    .maxByOrNull { it.timestamp }
                    ?.let { FetchOutcome.Success(it) }
                    ?: FetchOutcome.NoData
                is FetchOutcome.NoData -> FetchOutcome.NoData
                is FetchOutcome.Failed -> batch
            }

        // Only the CLOSEST station is retried (10s, then 30s); the other stations get a single
        // attempt each. The closest station dominates the IDW blend, so its freshness is worth the
        // extra latency; retrying all five would multiply the worst-case fetch time.
        val closestDeferred = async {
            var entity = fetchAndStoreStation(
                stations.first(), latitude, longitude, attempt = 0, stationIndex = 0,
                parallelWebOutcome = parallelWebDeferred?.let { { webOutcomeFor(stations.first().id)!! } },
            )
            for ((index, delayMs) in CLOSEST_STATION_RETRY_DELAYS_MS.withIndex()) {
                if (entity != null) break
                delay(delayMs)
                entity = fetchAndStoreStation(
                    stations.first(),
                    latitude,
                    longitude,
                    attempt = index + 1,
                    stationIndex = 0,
                    parallelWebOutcome = parallelWebDeferred?.let { { webOutcomeFor(stations.first().id)!! } },
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
                    parallelWebOutcome = parallelWebDeferred?.let { { webOutcomeFor(station.id)!! } },
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
        parallelWebOutcome: (suspend () -> FetchOutcome<ObservationEntity>)? = null,
    ): ObservationEntity? {
        val result = try {
            observationSource.fetchLatest(
                station,
                latitude,
                longitude,
                stationIndex,
                parallelWebOutcome,
            )
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

        // Stored before the chosen row and independently of it: this is the nearest station's real
        // sky condition, which the freshness-based web swap would otherwise discard entirely.
        result.cloudCarrier?.let { carrier ->
            observationDao.insertAll(listOf(carrier))
            appLogDao.log(
                "OBS_CLOUD_CARRIER",
                "station=${station.id} timestamp=${carrier.timestamp} cloudLow=${carrier.cloudCoverLow} " +
                    "reason=preserve_independent_api_observation",
                "INFO",
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
                        "secondary=${result.secondaryFailureReason ?: "not_tried"}",
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

    private companion object {
        const val PARALLEL_WEB_HOURS = 2
    }
}
