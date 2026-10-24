package com.weatherwidget.data.repository

import com.weatherwidget.shared.observations.NwsStationBundles
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.shared.observations.NwsStationAttemptLog
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

    /**
     * One NWS observation cycle: per station its latest reading AND the last
     * [NwsStationBundles.RECENT_WINDOW_MINUTES] of readings (`:shared` [NwsStationBundles], the same
     * pass desktop runs), stored, then the header's IDW blend of the latest readings. It fetched the
     * latest only and repaired the skipped readings later (user, 2026-10-23,
     * plans/261023-nws-observation-cycle-latest-plus-recent-window-both-platforms.md).
     */
    internal suspend fun fetchNwsCurrent(
        latitude: Double,
        longitude: Double,
    ): CurrentReadingPayload? {
        val stations = observationSource
            .stationsForLocation(latitude, longitude)
            .take(MAX_NWS_STATIONS)
        if (stations.isEmpty()) return null

        val fetchStartMs = System.currentTimeMillis()
        val end = Instant.ofEpochMilli(fetchStartMs).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        // Every station gets exactly one attempt per cycle; the observation loop and the
        // network-restored / resume kicks re-fetch within a cycle.
        val bundles = NwsStationBundles.fetch(
            stations = stations,
            latitude = latitude,
            longitude = longitude,
            latestFetcher = observationSource.latestFetcher,
            historyFetcher = observationSource.historyFetcher,
            metarTextApi = metarTextApi,
            synopticApi = observationSource.synopticApi,
            windowStart = end.minus(NwsStationBundles.RECENT_WINDOW_MINUTES, java.time.temporal.ChronoUnit.MINUTES),
            windowEnd = end,
            recentOnly = true,
            host = object : NwsStationBundles.Host {
                override suspend fun touchLatest(stationId: String) = observationDao.touchLatestFetchedAt(
                    stationId,
                    LocationMatch.quantize(latitude),
                    LocationMatch.quantize(longitude),
                    System.currentTimeMillis(),
                )

                override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)
            },
        )

        val stored = bundles.flatMap { it.rows }.map { it.toEntity() }
        if (stored.isNotEmpty()) observationDao.insertAll(stored)
        val latest = bundles.mapNotNull { it.latest?.toEntity() }
        latest.forEach { reading ->
            observationDao.touchLatestFetchedAt(reading.stationId, reading.locationLat, reading.locationLon, System.currentTimeMillis())
            logCurrentObservationInsert(reading)
        }
        // Once per fetch, after every station is stored — not once per station (several reductions
        // of the same day at once, the last one kept; performance/261010-nws-current-fetch-recomputes-today-once-not-per-station.md).
        val zone = ZoneId.systemDefault()
        stored
            .map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .distinct()
            .forEach { date -> dailyActualsStore.recomputeDailyExtremesForDay(latitude, longitude, date, emptyList()) }
        val totalMs = System.currentTimeMillis() - fetchStartMs
        if (latest.isEmpty()) {
            appLogDao.log("NWS_FETCH_FAIL_ALL", "stationsTried=${stations.size} totalMs=$totalMs", "WARN")
            return null
        }

        val blendedTemp = SpatialInterpolator.interpolateIDW(
            latitude,
            longitude,
            latest.map { it.toReading() },
            personalStationWeightProvider.currentWeight(),
        ) ?: return null
        val closest = latest.minBy { it.distanceKm }
        val stationSummary = latest.joinToString { "${it.stationId}(${it.distanceKm}km)" }
        appLogDao.log(
            "NWS_IDW",
            "blended=${blendedTemp}°F from ${latest.size} stations: $stationSummary " +
                "stored=${stored.size} totalMs=$totalMs",
        )
        Log.d(TAG, "NWS IDW blend: $blendedTemp°F from $stationSummary stored=${stored.size} totalMs=$totalMs")

        return CurrentReadingPayload(
            WeatherSource.NWS,
            blendedTemp,
            closest.condition,
            latest.maxOf { it.timestamp },
        )
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
