package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiKeyRedaction
import com.weatherwidget.shared.observations.CurrentConditionsRun
import com.weatherwidget.widget.WidgetStateManager

/**
 * Android side of [CurrentConditionsRun]: the per-site last run and per-feed last attempt in prefs
 * (`FetchMetadata`, [WidgetStateManager]); the fetch and its error code are passed in. The stores are
 * pinned to desktop's by `CurrentConditionsHostContract` (`:shared` testFixtures).
 */
internal class AndroidCurrentConditionsHost(
    private val context: Context,
    private val widgetStateManager: WidgetStateManager,
    private val appLogDao: AppLogDao,
    private val fetch: suspend (feed: WeatherSource, latitude: Double, longitude: Double) -> CurrentConditionsRun.Reading?,
    private val errorCode: (Exception) -> String,
) : CurrentConditionsRun.Host {
    override fun visibleSources() = widgetStateManager.getVisibleSourcesOrder()

    override fun displayedSourceIds() = widgetStateManager.getActiveDisplaySourceIds()

    override fun lastRunMs(latitude: Double, longitude: Double) =
        FetchMetadata.getLastCurrentTempFetchTime(context, latitude, longitude)

    override fun setLastRunMs(latitude: Double, longitude: Double, ms: Long) =
        FetchMetadata.setLastCurrentTempFetchTime(context, latitude, longitude, ms)

    override fun lastAttemptMs(feedId: String) = widgetStateManager.currentTempAttemptMs(feedId)

    override fun markAttempt(feedId: String, ms: Long) = widgetStateManager.markCurrentTempFetched(feedId, ms)

    override suspend fun fetch(feed: WeatherSource, latitude: Double, longitude: Double) = fetch.invoke(feed, latitude, longitude)

    override fun recordSuccess(feed: WeatherSource) = widgetStateManager.recordSourceFetchSuccess(feed)

    override fun recordFailure(feed: WeatherSource, error: Exception) =
        widgetStateManager.recordSourceFetchFailure(
            feed,
            errorCode(error),
            ApiKeyRedaction.redact(error.message ?: error.javaClass.simpleName),
        )

    override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)
}
