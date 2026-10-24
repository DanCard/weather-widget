package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.graph.PriorDayCloudRun

/**
 * Desktop SQLite side of [PriorDayCloudRun] at one site. Store → read-back
 * (`DesktopWeatherDao.getPriorDayCloudForecast`) is pinned to Android's by
 * `PriorDayCloudHostContract` (`:shared` testFixtures).
 */
internal class DesktopPriorDayCloudHost(
    private val weatherDao: DesktopWeatherDao,
    private val latitude: Double,
    private val longitude: Double,
    lastAttempt: PriorDayCloudRun.LastAttempt,
    private val fetch: suspend (pastDays: Int, nowMs: Long) -> Map<Long, Int>,
) : PriorDayCloudRun.Host {
    override var lastAttemptMs by lastAttempt::ms

    override suspend fun fetch(pastDays: Int, nowMs: Long) = fetch.invoke(pastDays, nowMs)

    override suspend fun store(coverByHour: Map<Long, Int>, nowMs: Long) =
        weatherDao.upsertPriorDayCloudForecast(latitude, longitude, coverByHour)

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)
}
