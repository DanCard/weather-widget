package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.util.ViewingForecastRefresh

/** Desktop's host for the shared [ViewingForecastRefresh]: one displayed source; requests kept for the process. */
internal class DesktopViewingForecastHost(
    private val dao: DesktopWeatherDao,
    private val displaySource: String,
    power: PowerDetector.PowerState,
    private val requests: MutableMap<String, Long>,
    private val fetchForecast: suspend () -> Unit,
) : ViewingForecastRefresh.Host {
    override val isCharging = power.isCharging
    override val batteryLevel = power.batteryLevel
    override fun viewedSourceIds() = listOf(displaySource)
    override fun lastFetchMs(sourceId: String) = dao.getLastSuccessfulFetch(sourceId)
    override fun lastRequestMs(sourceId: String) = requests[sourceId]
    override fun markRequested(sourceId: String, atMs: Long) { requests[sourceId] = atMs }
    override suspend fun fetch(sourceId: String) = fetchForecast()
    override suspend fun log(tag: String, message: String) = dao.log(tag, message, "INFO")
}
