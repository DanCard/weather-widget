package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.NwsGapFillRun
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

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
                dailyActualsStore.recomputeDailyExtremesForDay(latitude, longitude, day, force = true)
            }
        }

        override suspend fun log(tag: String, message: String, level: String) {
            appLogDao.log(tag, message, level)
        }
    }

    /** The recent-window pass of the shared [NwsGapFillRun]: every station's last [lookbackHours]. */
    internal suspend fun backfillRecentNwsObservations(
        latitude: Double,
        longitude: Double,
        lookbackHours: Long,
    ): NwsGapFillRun.RecentResult =
        NwsGapFillRun.recent(gapFillHost, latitude, longitude, lookbackHours, System.currentTimeMillis(), ZoneId.systemDefault())
}
