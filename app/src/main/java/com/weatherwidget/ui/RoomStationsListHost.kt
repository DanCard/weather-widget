package com.weatherwidget.ui

import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.StationsList
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Current Observations screen's [StationsList] reads over Room; desktop's is
 * `DesktopStationsListHost`. Both pass `StationsListHostContract`.
 */
internal class RoomStationsListHost(
    private val observationDao: ObservationDao,
    /** The active location, for [latestMainReading]; null with no location (nothing to pick). */
    private val location: Pair<Double, Double>?,
) : StationsList.Host<ObservationEntity> {
    override fun stationId(row: ObservationEntity) = row.stationId
    override fun api(row: ObservationEntity) = row.api
    override fun timestamp(row: ObservationEntity) = row.timestamp
    override fun distanceKm(row: ObservationEntity) = row.distanceKm
    override fun latitude(row: ObservationEntity) = row.locationLat
    override fun longitude(row: ObservationEntity) = row.locationLon
    override fun fetchedAt(row: ObservationEntity) = row.fetchedAt

    override suspend fun near(sinceMs: Long, lat: Double, lon: Double) = observationDao.getRecentObservationsNear(sinceMs, lat, lon)

    override suspend fun anywhere(sinceMs: Long) = observationDao.getRecentObservations(sinceMs)

    /** Today's `*_MAIN` row filed under [source] at the nearest site in the box, newest first. */
    override suspend fun latestMainReading(source: WeatherSource): ObservationEntity? {
        val (lat, lon) = location ?: return null
        val todayStartMs = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return observationDao.getLatestMainObservationsExcludingNws(lat, lon, todayStartMs - 1).firstOrNull { it.api == source.id }
    }
}
