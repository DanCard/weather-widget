package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.CurrentConditionsRun

/**
 * Desktop side of [CurrentConditionsRun]: the per-site last run and per-feed last attempt in
 * [DesktopCurrentConditionsMemory]; the fetch and the failure record are passed in. The stores are
 * pinned to Android's by `CurrentConditionsHostContract` (`:shared` testFixtures).
 */
internal class DesktopCurrentConditionsHost(
    private val visibleSources: List<WeatherSource>,
    private val displayedSourceIds: Set<String>,
    private val memory: DesktopCurrentConditionsMemory,
    private val weatherDao: DesktopWeatherDao,
    private val fetch: suspend (feed: WeatherSource) -> CurrentConditionsRun.Reading?,
    private val recordFailure: (feed: WeatherSource, error: Exception) -> Unit,
) : CurrentConditionsRun.Host {
    override fun visibleSources() = visibleSources

    override fun displayedSourceIds() = displayedSourceIds

    override fun lastRunMs(latitude: Double, longitude: Double) = memory.lastRunMs(latitude, longitude)

    override fun setLastRunMs(latitude: Double, longitude: Double, ms: Long) = memory.setLastRunMs(latitude, longitude, ms)

    override fun lastAttemptMs(feedId: String) = memory.lastAttemptMs(feedId)

    override fun markAttempt(feedId: String, ms: Long) = memory.markAttempt(feedId, ms)

    override suspend fun fetch(feed: WeatherSource, latitude: Double, longitude: Double) = fetch.invoke(feed)

    override fun recordSuccess(feed: WeatherSource) = Unit

    override fun recordFailure(feed: WeatherSource, error: Exception) = recordFailure.invoke(feed, error)

    override suspend fun log(tag: String, message: String, level: String) = weatherDao.log(tag, message, level)
}
