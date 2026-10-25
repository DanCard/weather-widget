package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.shared.observations.ObservationWriteRun

/**
 * Room side of [ObservationWriteRun] at one site: key reads and deletes on the site's exact quantized
 * key, as desktop's `DesktopObservationWriteHost`; pinned to it by `ObservationWriteHostContract`
 * (`:shared` testFixtures).
 */
internal class RoomObservationWriteHost(
    private val observationDao: ObservationDao,
    private val dailyHistoryDao: DailyHistoryDao,
    private val appLogDao: AppLogDao,
    override val latitude: Double,
    override val longitude: Double,
) : ObservationWriteRun.Host {
    private val keyLat = LocationMatch.quantize(latitude)
    private val keyLon = LocationMatch.quantize(longitude)

    override suspend fun storedKeys(api: String, stationIds: Set<String>, startMs: Long, endMs: Long) =
        if (stationIds.isEmpty()) {
            emptyList()
        } else {
            observationDao.getKeysAtSite(api, stationIds.toList(), keyLat, keyLon, startMs, endMs)
                .map { ObservationWriteRun.StoredKey(it.stationId, it.timestamp, it.stationType) }
        }

    override suspend fun insert(rows: List<ObservationReading>) = observationDao.insertAll(rows.map { it.toEntity() })

    override suspend fun delete(api: String, stationId: String, timestamps: List<Long>) =
        timestamps.chunked(DELETE_CHUNK).sumOf { observationDao.deleteAtSite(api, stationId, keyLat, keyLon, it) }

    override suspend fun retag(api: String, stationIds: Set<String>, stationType: StationType) =
        if (stationIds.isEmpty()) 0 else observationDao.retagStationType(api, stationIds.toList(), stationType)

    override suspend fun retireProducts(api: String) =
        RetiredProductCleanupRunner.runFor(api, keyLat, keyLon, observationDao, dailyHistoryDao, appLogDao)

    override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)

    private companion object {
        /** SQLite's default bound-parameter limit is 999; stay under it per delete. */
        const val DELETE_CHUNK = 500
    }
}

/** One [ObservationWriteRun] write at [latitude]/[longitude] on Room. */
internal suspend fun writeObservations(
    observationDao: ObservationDao,
    dailyHistoryDao: DailyHistoryDao,
    appLogDao: AppLogDao,
    latitude: Double,
    longitude: Double,
    readings: List<ObservationReading>,
    tomorrowIoWindowHours: Int? = null,
    nowMs: Long = System.currentTimeMillis(),
): ObservationWriteRun.Result =
    ObservationWriteRun(RoomObservationWriteHost(observationDao, dailyHistoryDao, appLogDao, latitude, longitude))
        .write(ObservationWriteRun.Write(readings, tomorrowIoWindowHours), nowMs)
