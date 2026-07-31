package com.weatherwidget.data.repository

import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WidgetConstants
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
)

private data class DailyBackfillNeed(
    val dates: Set<Long>,
    val rangeStartEpoch: Long,
    val rangeEndEpoch: Long,
    val today: LocalDate,
)

@Singleton
class NwsObservationBackfiller @Inject constructor(
    private val observationSource: NwsObservationSource,
    private val observationDao: ObservationDao,
    private val dailyHistoryDao: DailyHistoryDao,
    private val appLogDao: AppLogDao,
    private val dailyActualsStore: DailyActualsStore,
) {
    internal suspend fun backfillNwsObservationsIfNeeded(
        latitude: Double,
        longitude: Double,
    ) {
        val localZone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(localZone)
        val need = dailyBackfillNeed(latitude, longitude, localZone, now)
        if (need.dates.isEmpty()) return

        val stations = observationSource
            .stationsForLocation(latitude, longitude)
            .take(MAX_NWS_STATIONS)
        if (stations.isEmpty()) {
            appLogDao.log(
                "NWS_DAILY_BACKFILL_FAIL",
                "lat=$latitude lon=$longitude reason=no_stations",
                "WARN",
            )
            return
        }

        val startTime = DateTimeFormatter.ISO_INSTANT.format(
            now.minusDays(WeatherConfig.NWS_BACKFILL_DAYS.toLong()).toInstant(),
        )
        val endTime = DateTimeFormatter.ISO_INSTANT.format(now.toInstant())
        val remainingDates = need.dates.toMutableSet()
        for ((index, station) in stations.withIndex()) {
            val entities = fetchAndStoreStation(
                station = station,
                stationIndex = index,
                latitude = latitude,
                longitude = longitude,
                startTime = startTime,
                endTime = endTime,
                webWindowMinutes = WeatherConfig.NWS_BACKFILL_DAYS * 24 * 60L,
                fallbackLogTag = "NWS_DAILY_SYNOPTIC_FALLBACK",
                stationLogTag = "NWS_DAILY_BACKFILL_STATION",
            )
            if (entities.isEmpty()) continue

            val distinctDays = entities
                .map { java.time.Instant.ofEpochMilli(it.timestamp).atZone(localZone).toLocalDate() }
                .distinct()
            distinctDays.forEach { day ->
                dailyActualsStore.recomputeDailyExtremesForDay(
                    latitude,
                    longitude,
                    day,
                    emptyList(),
                )
            }
            val rowDates = dailyHistoryDao
                .getExtremesInRange(
                    need.rangeStartEpoch,
                    need.rangeEndEpoch,
                    latitude,
                    longitude,
                )
                .filter { it.source == WeatherSource.NWS.id }
                .map { it.date }
                .toSet()
            val stillIncomplete = dailyActualsStore.incompletelyCoveredPastDates(
                rowDates,
                latitude,
                longitude,
                localZone,
                need.today,
            )
            remainingDates.removeAll(rowDates - stillIncomplete)
            if (remainingDates.isEmpty()) break
        }

        if (remainingDates.isNotEmpty()) {
            appLogDao.log(
                "NWS_DAILY_BACKFILL_INCOMPLETE",
                "lat=$latitude lon=$longitude remaining=${remainingDates.toLocalDates()}",
                "WARN",
            )
            Log.w(TAG, "Daily backfill still missing/incomplete for ${remainingDates.toLocalDates()}")
        }
    }

    private suspend fun dailyBackfillNeed(
        latitude: Double,
        longitude: Double,
        localZone: ZoneId,
        now: ZonedDateTime,
    ): DailyBackfillNeed {
        val dayMinus2 = now.minusDays(2).toLocalDate()
        val yesterday = now.minusDays(1).toLocalDate()
        val today = now.toLocalDate()
        val rangeStartEpoch = dayMinus2.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val rangeEndEpoch = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val requiredDates = buildSet {
            add(rangeStartEpoch)
            add(yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY)
            if (now.hour >= 2) add(rangeEndEpoch)
        }
        val existingDates = dailyHistoryDao
            .getExtremesInRange(rangeStartEpoch, rangeEndEpoch, latitude, longitude)
            .filter { it.source == WeatherSource.NWS.id }
            .map { it.date }
            .toSet()
        val incompleteDates = dailyActualsStore.incompletelyCoveredPastDates(
            existingDates,
            latitude,
            longitude,
            localZone,
            today,
        )
        val missingDates = requiredDates - existingDates
        Log.d(
            TAG,
            "History check required=${requiredDates.toLocalDates()} existing=${existingDates.toLocalDates()} " +
                "missing=${missingDates.toLocalDates()} incomplete=${incompleteDates.toLocalDates()} " +
                "hour=${now.hour}",
        )
        return DailyBackfillNeed(
            dates = missingDates + incompleteDates,
            rangeStartEpoch = rangeStartEpoch,
            rangeEndEpoch = rangeEndEpoch,
            today = today,
        )
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

        val stations = observationSource
            .stationsForLocation(latitude, longitude)
            .take(MAX_NWS_STATIONS)
        if (stations.isEmpty()) {
            appLogDao.log(
                "OBS_HOURLY_BACKFILL_FAIL",
                "lat=$latitude lon=$longitude reason=no_stations",
                "WARN",
            )
            return RecentBackfillResult(0, 0, emptySet())
        }

        var totalRows = 0
        val affectedDates = mutableSetOf<LocalDate>()
        for ((index, station) in stations.withIndex()) {
            val entities = fetchAndStoreStation(
                station = station,
                stationIndex = index,
                latitude = latitude,
                longitude = longitude,
                startTime = startTime,
                endTime = endTime,
                webWindowMinutes = lookbackHours * 60L,
                fallbackLogTag = "OBS_HOURLY_SYNOPTIC_FALLBACK",
                stationLogTag = "OBS_HOURLY_BACKFILL_STATION",
            )
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
            )
        }
        appLogDao.log(
            "OBS_HOURLY_BACKFILL_DONE",
            "lat=$latitude lon=$longitude stations=${stations.size} rows=$totalRows " +
                "affectedDates=${affectedDates.sorted()}",
            "INFO",
        )
        return RecentBackfillResult(stations.size, totalRows, affectedDates)
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
    ): List<ObservationEntity> {
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
            appLogDao.log(
                stationLogTag,
                "station=${station.id} rows=${result.entities.size} web=${result.usedWebFallback}",
                "INFO",
            )
            if (result.entities.isNotEmpty()) observationDao.insertAll(result.entities)
            result.entities
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogDao.log(
                "${stationLogTag}_FAIL",
                "station=${station.id} error=${e::class.simpleName}:${e.message}",
                "WARN",
            )
            Log.e(TAG, "Observation backfill failed for ${station.id}", e)
            emptyList()
        }
    }

    private fun Set<Long>.toLocalDates(): List<LocalDate> =
        map { LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY) }
}
