package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.SourceQuotaBlocks
import com.weatherwidget.shared.util.ViewingForecastRefresh
import com.weatherwidget.widget.handlers.RefreshScheduler

/** Android's host for the shared [ViewingForecastRefresh]: Android cannot see a widget, so "viewed" = displayed. */
internal object ViewingForecastRefresher {
    /** Displayed sources a look judges: visible, displayed on a widget, not quota-blocked. */
    fun displayedSourceIds(stateManager: WidgetStateManager, nowMs: Long): List<String> {
        val displayed = stateManager.getActiveDisplaySourceIds()
        return stateManager.getVisibleSourcesOrder()
            .filter { it != WeatherSource.GENERIC_GAP && it.id in displayed }
            .filter { !SourceQuotaBlocks.isFullyBlocked(it.id, nowMs) }
            .map { it.id }
    }

    class AndroidHost(
        private val stateManager: WidgetStateManager,
        override val isCharging: Boolean,
        override val batteryLevel: Int,
        private val lat: Double,
        private val lon: Double,
        private val nowMs: Long,
        private val appLogDao: AppLogDao,
        private val fetchSource: (String) -> Unit,
    ) : ViewingForecastRefresh.Host {
        override fun viewedSourceIds() = displayedSourceIds(stateManager, nowMs)
        override fun lastFetchMs(sourceId: String) = stateManager.getLastLongFetch(sourceId, lat, lon)
        override fun lastRequestMs(sourceId: String) = stateManager.getViewingForecastRequest(sourceId)
        override fun markRequested(sourceId: String, atMs: Long) = stateManager.markViewingForecastRequest(sourceId, atMs)
        override suspend fun fetch(sourceId: String) = fetchSource(sourceId)
        override suspend fun log(tag: String, message: String) = appLogDao.log(tag, message, "INFO")
    }

    suspend fun run(
        context: Context,
        stateManager: WidgetStateManager,
        isCharging: Boolean,
        batteryLevel: Int,
        trigger: String,
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> {
        val (lat, lon) = ActiveLocationResolver.current(context) ?: return emptyList()
        val host = AndroidHost(
            stateManager, isCharging, batteryLevel, lat, lon, nowMs, WeatherDatabase.getDatabase(context).appLogDao(),
        ) { sourceId ->
            RefreshScheduler.enqueueForcedRefresh(context, reason = "viewing_forecast_stale", targetSourceId = sourceId)
        }
        return ViewingForecastRefresh.run(host, trigger, nowMs)
    }
}
