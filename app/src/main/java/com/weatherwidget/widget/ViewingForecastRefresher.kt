package com.weatherwidget.widget

import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.SourceQuotaBlocks
import com.weatherwidget.shared.util.RefreshNotice
import com.weatherwidget.shared.util.ViewingForecastRefresh
import com.weatherwidget.widget.handlers.RefreshScheduler
import kotlinx.coroutines.CancellationException

/**
 * Android's host for the shared [ViewingForecastRefresh]. Android cannot tell whether a widget is on
 * screen, so a tap on a widget is the only sign it is being viewed: the tapped widget's source is
 * checked, and nothing else (user, 2026-11-01). A refresh it starts shows on that widget.
 */
internal object ViewingForecastRefresher {
    private const val TAG = "ViewingForecastRefresher"

    /** Displayed sources a screen-on replans for: visible, displayed on a widget, not quota-blocked. */
    fun displayedSourceIds(stateManager: WidgetStateManager, nowMs: Long): List<String> {
        val displayed = stateManager.getActiveDisplaySourceIds()
        return stateManager.getVisibleSourcesOrder()
            .filter { it != WeatherSource.GENERIC_GAP && it.id in displayed }
            .filter { !SourceQuotaBlocks.isFullyBlocked(it.id, nowMs) }
            .map { it.id }
    }

    class AndroidHost(
        private val stateManager: WidgetStateManager,
        private val viewedSource: WeatherSource,
        override val isCharging: Boolean,
        override val batteryLevel: Int,
        private val lat: Double,
        private val lon: Double,
        private val nowMs: Long,
        private val appLogDao: AppLogDao,
        private val fetchSource: suspend (String) -> Unit,
    ) : ViewingForecastRefresh.Host {
        override fun viewedSourceIds() = listOf(viewedSource)
            .filter { it != WeatherSource.GENERIC_GAP && !SourceQuotaBlocks.isFullyBlocked(it.id, nowMs) }
            .map { it.id }
        override fun lastFetchMs(sourceId: String) = stateManager.getLastLongFetch(sourceId, lat, lon)
        override fun lastRequestMs(sourceId: String) = stateManager.getViewingForecastRequest(sourceId)
        override fun markRequested(sourceId: String, atMs: Long) = stateManager.markViewingForecastRequest(sourceId, atMs)
        override suspend fun fetch(sourceId: String) = fetchSource(sourceId)
        override suspend fun log(tag: String, message: String) = appLogDao.log(tag, message, "INFO")
    }

    /** After a tap's paint, so a stale source never delays the feedback. */
    suspend fun onTap(context: Context, appWidgetId: Int) {
        try {
            val stateManager = WidgetStateManager(context)
            val (lat, lon) = ActiveLocationResolver.current(context) ?: return
            val battery = BatterySnapshotProvider.snapshot(context)
            val nowMs = System.currentTimeMillis()
            val host = AndroidHost(
                stateManager, stateManager.getCurrentDisplaySource(appWidgetId), battery.isCharging, battery.batteryLevel,
                lat, lon, nowMs, WeatherDatabase.getDatabase(context).appLogDao(),
            ) { sourceId ->
                val notice = RefreshScheduler.enqueueForcedRefresh(context, reason = SyncDoneNotice.TAP_REASON, targetSourceId = sourceId)
                // Absorbed by a sync already pending, it owes no message: that sync is not ours to report.
                if (notice is RefreshNotice.Queued) {
                    RefreshToast.show(
                        context,
                        "widget_tap_refresh",
                        notice.copy(sourceName = WeatherSource.fromId(sourceId).shortDisplayName),
                        intArrayOf(appWidgetId),
                    )
                }
            }
            ViewingForecastRefresh.run(host, "widget_tap", nowMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Viewing forecast check failed", e)
        }
    }
}
