package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleHistoryRun

/** The JDBC side of [GoogleHistoryRun] at one site. */
internal class DesktopGoogleHistoryHost(
    private val weatherDao: DesktopWeatherDao,
    private val latitude: Double,
    private val longitude: Double,
) : GoogleHistoryRun.Host {
    private val keyLat = LocationMatch.quantize(latitude)
    private val keyLon = LocationMatch.quantize(longitude)

    override suspend fun coveredHistoryHours(startMs: Long, endMs: Long): Collection<Long> =
        weatherDao.getHourlyHistoryCoveredHours(keyLat, keyLon, WeatherSource.GOOGLE_WEATHER.id, startMs, endMs)

    override suspend fun liveHours(startMs: Long, endMs: Long): List<HourlyForecast> =
        weatherDao.getHourlyForecasts(keyLat, keyLon, WeatherSource.GOOGLE_WEATHER.id, startMs, endMs).filter { row ->
            val rowLat = row.locationLat
            val rowLon = row.locationLon
            rowLat == null || rowLon == null || LocationMatch.sameSite(keyLat, keyLon, rowLat, rowLon)
        }

    override suspend fun saveRefill(rows: List<HourlyForecast>) =
        weatherDao.upsertHourlyForecasts(latitude, longitude, WeatherSource.GOOGLE_WEATHER.id, rows)

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)
}
