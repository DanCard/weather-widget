package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleHistoryRun

/** The JDBC side of [GoogleHistoryRun] at one site. */
internal class DesktopGoogleHistoryHost(
    private val weatherDao: DesktopWeatherDao,
    latitude: Double,
    longitude: Double,
) : GoogleHistoryRun.Host {
    private val keyLat = LocationMatch.quantize(latitude)
    private val keyLon = LocationMatch.quantize(longitude)

    override suspend fun coveredHistoryHours(startMs: Long, endMs: Long): Collection<Long> =
        weatherDao.getHourlyHistoryCoveredHours(keyLat, keyLon, WeatherSource.GOOGLE_WEATHER.id, startMs, endMs)
}
