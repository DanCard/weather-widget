package com.weatherwidget.widget

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.remote.HourlyOnDemand
import com.weatherwidget.data.remote.HourlyOnDemandRun
import com.weatherwidget.widget.handlers.NoHourlyDayClickCoordinator

/**
 * Room side of [HourlyOnDemandRun] for one source at one site; the fetch is passed in. Its stored
 * read is pinned to desktop's by `HourlyOnDemandHostContract` (`:shared` testFixtures).
 */
internal class RoomHourlyOnDemandHost(
    private val database: WeatherDatabase,
    private val sourceId: String,
    override val lat: Double,
    override val lon: Double,
    private val nowMs: Long,
    private val fetch: suspend (HourlyOnDemand.Request) -> Boolean,
) : HourlyOnDemandRun.Host {
    override suspend fun storedHourly() = NoHourlyDayClickCoordinator.storedHourlyForSource(database, sourceId, lat, lon, nowMs)

    override suspend fun fetch(request: HourlyOnDemand.Request) = fetch.invoke(request)
}
