package com.weatherwidget.stats.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.toReading
import com.weatherwidget.data.model.HourlyActual
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.stats.RainAccuracyRunner

/** Desktop SQLite rows for [RainAccuracyRunner]; reads only (Android: `RoomRainAccuracyHost`). */
class DesktopRainAccuracyHost(private val dao: DesktopWeatherDao) : RainAccuracyRunner.Host {
    override suspend fun forecastSnapshots(
        source: WeatherSource,
        startMs: Long,
        endMs: Long,
        bucketStartMs: Long,
        bucketEndMs: Long,
        lat: Double,
        lon: Double,
    ): List<RainAccuracyRunner.PrecipSnapshot> =
        dao.getHourlyPrecipSnapshots(lat, lon, source.id, startMs, endMs, bucketStartMs, bucketEndMs)

    override suspend fun observations(startMs: Long, endMs: Long, lat: Double, lon: Double): List<ObservationReading> =
        dao.getObservationsInRange(startMs, endMs, lat, lon).map { it.toReading() }

    override suspend fun archivedActuals(startMs: Long, endMs: Long, lat: Double, lon: Double, sourceId: String): List<HourlyActual> =
        dao.getHourlyActuals(lat, lon, sourceId, startMs, endMs)
}
