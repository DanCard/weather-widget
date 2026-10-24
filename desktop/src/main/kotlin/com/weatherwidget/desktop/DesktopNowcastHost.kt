package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.nowcast.NowcastFetcher
import com.weatherwidget.shared.nowcast.NowcastRefreshRun
import com.weatherwidget.shared.nowcast.NowcastStore

/**
 * Desktop SQLite side of [NowcastRefreshRun] at one site; the fetch is passed in. Its hourly read is
 * pinned to Android's by `NowcastRefreshHostContract` (`:shared` testFixtures).
 */
internal class DesktopNowcastHost(
    private val weatherDao: DesktopWeatherDao,
    override val lat: Double,
    override val lon: Double,
    override val openMeteoDisplayed: Boolean,
    override val usableSourceIds: Set<String>,
    override val store: NowcastStore,
    private val fetch: suspend () -> NowcastFetcher.Outcome?,
) : NowcastRefreshRun.Host {
    // Every source's rows in the box; the run keeps the usable sources' and reads only their max chance.
    override suspend fun hourly(fromMs: Long, toMs: Long) = weatherDao.getHourlyForecastsAllSources(lat, lon, fromMs, toMs)

    override suspend fun fetch() = fetch.invoke()

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)
}
