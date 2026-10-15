package com.weatherwidget.data.repository

import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsMetarTextApi
import com.weatherwidget.shared.observations.NwsMetarTextWeb
import com.weatherwidget.shared.observations.NwsStationLatestFetcher
import com.weatherwidget.shared.util.SpatialInterpolator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NwsCurrentObsUpdate"
internal const val MAX_NWS_STATIONS = 5

@Singleton
class NwsCurrentObservationUpdater private constructor(
    private val observationSource: NwsObservationSource,
    private val observationDao: ObservationDao,
    private val appLogDao: AppLogDao,
    private val dailyActualsStore: DailyActualsStore,
    private val metarTextApi: NwsMetarTextApi?,
    private val personalStationWeightProvider: PersonalStationWeightProvider,
    @Suppress("UNUSED_PARAMETER") constructionMarker: Unit,
) {
    @Inject
    constructor(
        observationSource: NwsObservationSource,
        observationDao: ObservationDao,
        appLogDao: AppLogDao,
        dailyActualsStore: DailyActualsStore,
        metarTextApi: NwsMetarTextApi,
        personalStationWeightProvider: PersonalStationWeightProvider,
    ) : this(
        observationSource,
        observationDao,
        appLogDao,
        dailyActualsStore,
        metarTextApi,
        personalStationWeightProvider,
        Unit,
    )

    /** Compatibility seam for repository tests: the API leg only, no web leg. */
    internal constructor(
        observationSource: NwsObservationSource,
        observationDao: ObservationDao,
        appLogDao: AppLogDao,
        dailyActualsStore: DailyActualsStore,
        personalStationWeightProvider: PersonalStationWeightProvider = PersonalStationWeightProvider { 1.0 },
    ) : this(observationSource, observationDao, appLogDao, dailyActualsStore, null, personalStationWeightProvider, Unit)

    internal suspend fun fetchNwsCurrent(
        latitude: Double,
        longitude: Double,
    ): CurrentReadingPayload? = coroutineScope {
        val stations = observationSource
            .stationsForLocation(latitude, longitude)
            .take(MAX_NWS_STATIONS)
        if (stations.isEmpty()) return@coroutineScope null

        val fetchStartMs = System.currentTimeMillis()
        // The web leg: NWS's own latest-METAR file per station, every request started before any
        // api.weather.gov answer so the two transports overlap. No token, no third party
        // (plans/261015-nws-web-leg-from-nws-tgftp-metar-text.md).
        val web = metarTextApi?.let { api ->
            NwsMetarTextWeb.start(this, api, stations, latitude, longitude)
        }

        // Every station gets exactly one attempt. The old 10s/30s retry targeted stations.first(),
        // which is nearest by distance — often a personal station the blend discounts to ~5% — so
        // it retried the station that mattered least while blocking the whole fetch for up to 40s.
        // The observation loop and the network-restored/resume kicks re-fetch within a cycle.
        val stationDeferreds = stations.mapIndexed { index, station ->
            async { fetchAndStoreStation(station, latitude, longitude, stationIndex = index, web = web) }
        }
        val successful = stationDeferreds.mapNotNull { it.await() }
        // Once per fetch, after every station is stored — not once per station. The stations run
        // in parallel, so a per-station recompute started N full reductions of the same day at once
        // (~3.5 s each on the Pixel; the signature skip cannot catch them, since each insert changes
        // the signature and none is recorded until a run finishes), the current temperature waited
        // for all N, and whichever finished last was kept even when it had seen fewer stations.
        // See performance/261010-nws-current-fetch-recomputes-today-once-not-per-station.md.
        val zone = ZoneId.systemDefault()
        successful
            .map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .distinct()
            .forEach { date ->
                dailyActualsStore.recomputeDailyExtremesForDay(latitude, longitude, date, emptyList())
            }
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
            personalStationWeightProvider.currentWeight(),
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
        stationIndex: Int,
        web: NwsStationLatestFetcher.WebSource?,
    ): ObservationEntity? {
        val result = try {
            observationSource.latestFetcher.fetch(station, stationIndex, latitude, longitude, web)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogDao.log(
                "NWS_STATION_FAIL",
                "station=${station.id} error=${e::class.simpleName}:${e.message}",
                "WARN",
            )
            Log.e(TAG, "NWS station ${station.id} fetch failed", e)
            return null
        }

        // Stored before the chosen row and independently of it: this is the nearest station's real
        // sky condition, which the freshness-based web swap would otherwise discard entirely.
        result.cloudCarrier?.let { carrier ->
            val entity = carrier.toEntity()
            observationDao.insertAll(listOf(entity))
            appLogDao.log(
                "OBS_CLOUD_CARRIER",
                "station=${station.id} timestamp=${entity.timestamp} cloudLow=${entity.cloudCoverLow} " +
                    "reason=preserve_independent_api_observation",
                "INFO",
            )
        }

        val chosen = result.chosen?.toEntity()
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
                    "station=${station.id} reason=no_valid_observation",
                    "INFO",
                )
            } else {
                appLogDao.log(
                    "NWS_STATION_FAIL",
                    "station=${station.id} " +
                        "nws=${result.nwsFailureReason ?: "unknown"} " +
                        "secondary=${result.webFailureReason ?: "not_tried"}",
                    "WARN",
                )
            }
            return null
        }

        observationDao.insertAll(listOf(chosen))
        observationDao.touchLatestFetchedAt(
            chosen.stationId,
            chosen.locationLat,
            chosen.locationLon,
            System.currentTimeMillis(),
        )
        logCurrentObservationInsert(chosen)
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
