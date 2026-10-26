package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.HourlyOnDemand
import com.weatherwidget.data.remote.HourlyOnDemandRun

/**
 * Desktop SQLite side of [HourlyOnDemandRun] for one source at one site; the fetch (and its save) is
 * passed in. Its stored read is pinned to Android's by `HourlyOnDemandHostContract` (`:shared` testFixtures).
 */
internal class DesktopHourlyOnDemandHost(
    private val weatherDao: DesktopWeatherDao,
    private val sourceId: String,
    override val lat: Double,
    override val lon: Double,
    private val nowMs: Long,
    private val fetch: suspend (HourlyOnDemand.Request) -> Boolean,
) : HourlyOnDemandRun.Host {
    override suspend fun storedHourly() = weatherDao.getHourlyForecasts(
        LocationMatch.quantize(lat),
        LocationMatch.quantize(lon),
        sourceId,
        nowMs - 3_600_000L,
        nowMs + HourlyOnDemand.REACH_HOURS * 3_600_000L,
    )

    override suspend fun fetch(request: HourlyOnDemand.Request) = fetch.invoke(request)
}
