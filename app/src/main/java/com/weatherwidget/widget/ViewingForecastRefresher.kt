package com.weatherwidget.widget

import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.SourceQuotaBlocks
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.shared.util.RefreshNotice
import com.weatherwidget.shared.util.ViewingForecastRefresh
import com.weatherwidget.widget.handlers.RefreshScheduler
import kotlinx.coroutines.CancellationException

/**
 * Android's host for the shared [ViewingForecastRefresh]. Android cannot tell whether a widget is on
 * screen, so a tap on a widget or opening a data screen is the only sign of a look: that one source is
 * checked, and nothing else (user, 2026-11-01). A refresh it starts shows where the user is looking.
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
        /** The source's last successful check at this site, 0 if none: an unchanged response writes no marker. */
        private val lastSuccessMs: (String) -> Long,
        private val appLogDao: AppLogDao,
        private val fetchSource: suspend (String) -> Unit,
    ) : ViewingForecastRefresh.Host {
        override fun viewedSourceIds() = listOf(viewedSource)
            .filter { it != WeatherSource.GENERIC_GAP && !SourceQuotaBlocks.isFullyBlocked(it.id, nowMs) }
            .map { it.id }
        override fun lastFetchMs(sourceId: String) =
            listOfNotNull(stateManager.getLastLongFetch(sourceId, lat, lon), lastSuccessMs(sourceId).takeIf { it > 0L }).maxOrNull()
        override fun lastRequestMs(sourceId: String) = stateManager.getViewingForecastRequest(sourceId)
        override fun markRequested(sourceId: String, atMs: Long) = stateManager.markViewingForecastRequest(sourceId, atMs)
        override suspend fun fetch(sourceId: String) = fetchSource(sourceId)
        override suspend fun log(tag: String, message: String) = appLogDao.log(tag, message, "INFO")
    }

    /**
     * After a render's paint, so a stale source never delays the feedback. Only a tap's render counts:
     * the renderers also paint for refresh repaints and resizes, which say nothing about a look (a
     * screen-on paint-debt repaint fetched and showed the banner, emulator 2026-11-01).
     */
    suspend fun onRender(context: Context, appWidgetId: Int, origin: WidgetPushDispatcher.Origin) {
        if (origin != WidgetPushDispatcher.Origin.USER_INTERACTION) return
        val (lat, lon) = ActiveLocationResolver.current(context) ?: return
        val source = WidgetStateManager(context).getCurrentDisplaySource(appWidgetId)
        check(context, source, lat, lon, "widget_tap") { sourceId ->
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
    }

    /** Forecast History or Observations opened on [source]; the screen supplies a [fetch] that reports on screen. */
    suspend fun onScreenOpen(
        context: Context,
        source: WeatherSource,
        lat: Double,
        lon: Double,
        trigger: String,
        fetch: suspend () -> Unit,
    ) = check(context, source, lat, lon, trigger) { fetch() }

    private suspend fun check(
        context: Context,
        source: WeatherSource,
        lat: Double,
        lon: Double,
        trigger: String,
        fetchSource: suspend (String) -> Unit,
    ) {
        try {
            val battery = BatterySnapshotProvider.snapshot(context)
            val nowMs = System.currentTimeMillis()
            val host = AndroidHost(
                WidgetStateManager(context), source, battery.isCharging, battery.batteryLevel,
                lat, lon, nowMs,
                { FetchMetadata.getLastForecastSourceSuccessTime(context, it, lat, lon) },
                WeatherDatabase.getDatabase(context).appLogDao(),
                fetchSource,
            )
            ViewingForecastRefresh.run(host, trigger, nowMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Viewing forecast check failed", e)
        }
    }
}
