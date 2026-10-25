package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.toEntity
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.shared.observations.ObservationWriteRun

/**
 * Desktop SQLite side of [ObservationWriteRun] at one site: key reads and deletes on the site's exact
 * quantized key, as Android's `RoomObservationWriteHost`; pinned to it by `ObservationWriteHostContract`.
 */
internal class DesktopObservationWriteHost(
    private val weatherDao: DesktopWeatherDao,
    override val latitude: Double,
    override val longitude: Double,
) : ObservationWriteRun.Host {
    private val keyLat = LocationMatch.quantize(latitude)
    private val keyLon = LocationMatch.quantize(longitude)

    override suspend fun storedKeys(api: String, stationIds: Set<String>, startMs: Long, endMs: Long) =
        weatherDao.observationKeysAtSite(api, stationIds, keyLat, keyLon, startMs, endMs)

    // The run has already settled each row's fetchedAt; toEntity's argument only fills the column.
    override suspend fun insert(rows: List<ObservationReading>) =
        weatherDao.upsertObservations(rows.map { it.toEntity(it.fetchedAt) })

    override suspend fun delete(api: String, stationId: String, timestamps: List<Long>) =
        weatherDao.deleteObservationsAtSite(api, stationId, keyLat, keyLon, timestamps)

    override suspend fun retag(api: String, stationIds: Set<String>, stationType: StationType) =
        weatherDao.retagStationType(api, stationIds, stationType)

    override suspend fun retireProducts(api: String) {
        weatherDao.retireProductsIfCovered(api, keyLat, keyLon)
    }

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)
}
