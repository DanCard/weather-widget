package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.DailyForecastWrite
import com.weatherwidget.shared.util.ForecastPersistRun
import java.time.LocalDate

/**
 * Desktop SQLite side of [ForecastPersistRun] for one source at one site. Reads are on the site's exact
 * quantized key, as Android's `RoomForecastPersistHost`; pinned to it by `ForecastPersistHostContract`.
 * [snapshotLabel] is the fetch's `HourlySnapshotRun` decision (the repository's, which knows the config).
 */
internal class DesktopForecastPersistHost(
    private val weatherDao: DesktopWeatherDao,
    override val sourceId: String,
    override val latitude: Double,
    override val longitude: Double,
    private val snapshotLabel: suspend (nowMs: Long) -> Long?,
) : ForecastPersistRun.Host<DesktopWeatherDao.ForecastKey> {
    private val keyLat = LocationMatch.quantize(latitude)
    private val keyLon = LocationMatch.quantize(longitude)

    override suspend fun storedHourly(startMs: Long, endMs: Long): List<HourlyForecast> =
        weatherDao.getHourlyForecasts(keyLat, keyLon, sourceId, startMs, endMs)
            .filter { it.locationLat == keyLat && it.locationLon == keyLon }

    override suspend fun writeHourly(rows: List<HourlyForecast>) =
        weatherDao.upsertHourlyForecasts(latitude, longitude, sourceId, rows)

    override suspend fun snapshotLabel(nowMs: Long) = snapshotLabel.invoke(nowMs)

    override suspend fun writeSnapshot(label: Long, rows: List<HourlyForecast>) =
        weatherDao.upsertHourlyForecastHistory(latitude, longitude, sourceId, label, rows)

    override fun elapsedStore(label: Long, nowMs: Long) =
        DesktopElapsedHistoryStore(weatherDao, latitude, longitude, sourceId, label)

    override suspend fun storedDaily(from: LocalDate, to: LocalDate) =
        weatherDao.storedForecastsAtSite(latitude, longitude, sourceId, from, to)

    override suspend fun writeDaily(write: List<DailyForecastWrite.Row>, restamp: List<DesktopWeatherDao.ForecastKey>, nowMs: Long) =
        weatherDao.writeForecastPlan(latitude, longitude, sourceId, write, restamp, nowMs)

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)
}
