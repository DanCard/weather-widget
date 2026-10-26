package com.weatherwidget.data.repository

import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.NwsGapFillRun
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NwsObsBackfiller"

internal data class RecentBackfillResult(
    val stationsTried: Int,
    val rowsFetched: Int,
    val affectedDates: Set<LocalDate>,
    /**
     * NWS never answered: the station lookup failed, or every station's fetch failed. Distinct from
     * an answered-but-empty backfill, which is a real data limit and must not be retried quickly.
     */
    val unreachable: Boolean = false,
)

/** One station's backfill: the rows stored, and whether the fetch failed rather than came back empty. */
private data class StationBackfill(
    val entities: List<ObservationEntity>,
    val failed: Boolean,
)

@Singleton
class NwsObservationBackfiller @Inject constructor(
    private val observationSource: NwsObservationSource,
    private val observationDao: ObservationDao,
    private val dailyHistoryDao: DailyHistoryDao,
    private val appLogDao: AppLogDao,
    private val dailyActualsStore: DailyActualsStore,
) {
    /** The daily pass of the shared [NwsGapFillRun]: need-gated, 3 days, nearest station first. */
    internal suspend fun backfillNwsObservationsIfNeeded(
        latitude: Double,
        longitude: Double,
    ) {
        NwsGapFillRun.daily(gapFillHost, latitude, longitude, System.currentTimeMillis(), ZoneId.systemDefault())
    }

    /** Also run by `RoomNwsGapFillHostContractTest` against a real Room database. */
    internal val gapFillHost = object : NwsGapFillRun.Host {
        override suspend fun stations(latitude: Double, longitude: Double) =
            observationSource.stationsForLocationOutcome(latitude, longitude)

        override suspend fun fetchStation(
            station: com.weatherwidget.data.remote.NwsApi.StationInfo,
            index: Int,
            latitude: Double,
            longitude: Double,
            startIso: String,
            endIso: String,
            fallbackLogTag: String,
        ): NwsGapFillRun.StationRows {
            val result = observationSource.fetchHistorical(
                stationInfo = station,
                stationIndex = index,
                latitude = latitude,
                longitude = longitude,
                startTime = startIso,
                endTime = endIso,
                webWindowMinutes = NwsGapFillRun.BACKFILL_DAYS * 24 * 60L,
                fallbackLogTag = fallbackLogTag,
            )
            return NwsGapFillRun.StationRows(result.entities.map { it.toReading() }, result.usedWebFallback, result.apiFailure)
        }

        override suspend fun store(rows: List<ObservationReading>, latitude: Double, longitude: Double) {
            writeObservations(observationDao, dailyHistoryDao, appLogDao, latitude, longitude, rows)
        }

        override suspend fun dailyHistoryRows(startDayMs: Long, endDayMs: Long, latitude: Double, longitude: Double) =
            dailyHistoryDao.getExtremesInRange(startDayMs, endDayMs, latitude, longitude)
                .map { NwsGapFillRun.DayRow(it.date, it.source) }

        override suspend fun observations(startMs: Long, endMs: Long, latitude: Double, longitude: Double) =
            observationDao.getObservationsInRange(startMs, endMs, latitude, longitude, setOf(WeatherSource.NWS.id))
                .map { NwsGapFillRun.ObsStamp(it.timestamp, it.api, it.stationId) }

        override suspend fun recomputeExtremes(latitude: Double, longitude: Double, days: Set<LocalDate>) {
            days.forEach { day ->
                // Repair path: it distrusts what is stored, which is the whole reason it ran.
                dailyActualsStore.recomputeDailyExtremesForDay(latitude, longitude, day, emptyList(), force = true)
            }
        }

        override suspend fun log(tag: String, message: String, level: String) {
            appLogDao.log(tag, message, level)
        }
    }

    internal suspend fun backfillRecentNwsObservations(
        latitude: Double,
        longitude: Double,
        lookbackHours: Long,
    ): RecentBackfillResult {
        val localZone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(localZone)
        val startTime = DateTimeFormatter.ISO_INSTANT.format(now.minusHours(lookbackHours).toInstant())
        val endTime = DateTimeFormatter.ISO_INSTANT.format(now.toInstant())
        appLogDao.log(
            "OBS_HOURLY_BACKFILL_START",
            "lat=$latitude lon=$longitude lookbackHours=$lookbackHours start=$startTime end=$endTime",
            "INFO",
        )

        val stationsOutcome = observationSource.stationsForLocationOutcome(latitude, longitude)
        val stations = stationsOutcome.valueOrNull().orEmpty().take(MAX_NWS_STATIONS)
        if (stations.isEmpty()) {
            val unreachable = stationsOutcome is com.weatherwidget.data.remote.FetchOutcome.Failed
            appLogDao.log(
                "OBS_HOURLY_BACKFILL_FAIL",
                "lat=$latitude lon=$longitude reason=${if (unreachable) "stations_unreachable" else "no_stations"}",
                "WARN",
            )
            return RecentBackfillResult(0, 0, emptySet(), unreachable = unreachable)
        }

        var totalRows = 0
        var failedStations = 0
        val affectedDates = mutableSetOf<LocalDate>()
        for ((index, station) in stations.withIndex()) {
            val fetch = fetchAndStoreStation(
                station = station,
                stationIndex = index,
                latitude = latitude,
                longitude = longitude,
                startTime = startTime,
                endTime = endTime,
                webWindowMinutes = lookbackHours * 60L,
                fallbackLogTag = "OBS_HOURLY_WEB_FALLBACK",
                stationLogTag = "OBS_HOURLY_BACKFILL_STATION",
            )
            if (fetch.failed) failedStations++
            val entities = fetch.entities
            totalRows += entities.size
            affectedDates += entities.map {
                java.time.Instant.ofEpochMilli(it.timestamp).atZone(localZone).toLocalDate()
            }
        }

        if (affectedDates.isNotEmpty()) {
            dailyActualsStore.recomputeDailyExtremesFromStoredObservations(
                latitude = latitude,
                longitude = longitude,
                startDate = affectedDates.min(),
                endDateInclusive = affectedDates.max(),
                hourlyForecasts = emptyList(),
                // Repair path: it distrusts what is stored, which is the whole reason it ran.
                force = true,
            )
        }
        appLogDao.log(
            "OBS_HOURLY_BACKFILL_DONE",
            "lat=$latitude lon=$longitude stations=${stations.size} failedStations=$failedStations rows=$totalRows " +
                "affectedDates=${affectedDates.sorted()}",
            "INFO",
        )
        return RecentBackfillResult(
            stationsTried = stations.size,
            rowsFetched = totalRows,
            affectedDates = affectedDates,
            unreachable = failedStations == stations.size,
        )
    }

    private suspend fun fetchAndStoreStation(
        station: com.weatherwidget.data.remote.NwsApi.StationInfo,
        stationIndex: Int,
        latitude: Double,
        longitude: Double,
        startTime: String,
        endTime: String,
        webWindowMinutes: Long,
        fallbackLogTag: String,
        stationLogTag: String,
    ): StationBackfill {
        return try {
            val result = observationSource.fetchHistorical(
                stationInfo = station,
                stationIndex = stationIndex,
                latitude = latitude,
                longitude = longitude,
                startTime = startTime,
                endTime = endTime,
                webWindowMinutes = webWindowMinutes,
                fallbackLogTag = fallbackLogTag,
            )
            // A station past MAX_WEB_FALLBACK_STATIONS gets no web fallback, so a timeout here lands
            // as a bare `rows=0` that reads exactly like a station with nothing to report (KSJC,
            // 2026-08-21). The failure marker is the only thing separating the two on this line.
            appLogDao.log(
                stationLogTag,
                "station=${station.id} rows=${result.entities.size} web=${result.usedWebFallback}" +
                    (result.apiFailure?.let { " apiFetchFailed=$it" } ?: ""),
                "INFO",
            )
            if (result.entities.isNotEmpty()) {
                writeObservations(observationDao, dailyHistoryDao, appLogDao, latitude, longitude, result.entities.map { it.toReading() })
            }
            StationBackfill(result.entities, failed = result.entities.isEmpty() && result.apiFailure != null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogDao.log(
                "${stationLogTag}_FAIL",
                "station=${station.id} error=${e::class.simpleName}:${e.message}",
                "WARN",
            )
            Log.e(TAG, "Observation backfill failed for ${station.id}", e)
            StationBackfill(emptyList(), failed = true)
        }
    }
}
