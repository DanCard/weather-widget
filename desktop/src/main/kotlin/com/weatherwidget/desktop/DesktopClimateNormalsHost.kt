package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.shared.util.ClimateNormalsRun

/**
 * Desktop SQLite side of [ClimateNormalsRun]. Pinned to Android's Room Host by
 * `ClimateNormalsHostContract` (`:shared` testFixtures).
 */
internal class DesktopClimateNormalsHost(
    private val weatherDao: DesktopWeatherDao,
    private val fetch: suspend (start: String, end: String) -> List<DailyForecast>,
) : ClimateNormalsRun.Host {
    override suspend fun cached(locationKey: String) = weatherDao.getClimateNormals(locationKey)

    override suspend fun fetchDailyTemps(start: String, end: String) = fetch(start, end)

    override suspend fun store(locationKey: String, monthlyHigh: Map<Int, Float>, monthlyLow: Map<Int, Float>) =
        weatherDao.upsertClimateNormals(locationKey, monthlyHigh, monthlyLow)

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)
}
