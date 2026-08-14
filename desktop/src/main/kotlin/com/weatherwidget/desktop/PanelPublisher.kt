package com.weatherwidget.desktop

import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Owns the daemon's panel + current-status publishing: serves the XFCE genmon panel via
 * [PanelIpcServer] and persists the resolved current_status as its single writer, re-resolving it
 * each minute so the published value keeps interpolating between fetches.
 *
 * Extracted from `runDaemon` so the "publish one status, serve it everywhere" responsibility is a
 * focused component rather than three free functions threaded through a 700-line god function.
 */
class PanelPublisher(
    private val appDataDir: Path,
    private val weatherDao: DesktopWeatherDao,
    private val forecastState: MutableStateFlow<ForecastSnapshot?>,
    private val dataStatusState: MutableStateFlow<DataStatus>,
    private val configState: MutableStateFlow<DesktopConfig?>,
    private val resolverProvider: () -> CurrentStatusResolver?,
) {
    private val ipcServer = PanelIpcServer(appDataDir) {
        val dataStatus = dataStatusState.value
        val config = configState.value
        // Serve the daemon's published current_status (a single DB read) instead of re-running the
        // ~350ms IDW blend on every panel connect, so panel and popup consume the same value.
        val status = config?.let { weatherDao.getCurrentStatus(it.lat, it.lon, it.settings.weatherSource) }
        generateMarkup(
            observedAtMs = status?.observedAtMs,
            currentTemp = status?.displayTempF,
            deltaFromYesterday = status?.deltaFromYesterdayF,
            dataStatus = dataStatus,
            config = config,
        )
    }.apply { start() }

    /**
     * Single write path for the forecast snapshot: set the flow AND persist the resolved status so
     * the panel/UI consume one published value. Persistence is best-effort — a current_status write
     * failure must never break a fetch.
     */
    fun publishForecastState(result: ForecastSnapshot?) {
        forecastState.value = result
        val resolver = resolverProvider() ?: return
        if (result == null) return
        runCatching {
            weatherDao.upsertCurrentStatus(resolver.resolve(result, System.currentTimeMillis()))
        }.onFailure { Log.w(TAG, "current_status persist failed: $it") }
    }

    /** Re-resolves + persists the status from the current in-memory snapshot (the minute loop). */
    fun refreshCurrentStatus() {
        val snapshot = forecastState.value ?: return
        val resolver = resolverProvider() ?: return
        runCatching {
            weatherDao.upsertCurrentStatus(resolver.resolve(snapshot, System.currentTimeMillis()))
        }.onFailure { Log.w(TAG, "current_status persist failed: $it") }
    }

    fun triggerPanelRefresh() = ipcServer.triggerRefresh()

    companion object {
        private const val TAG = "PanelPublisher"
    }
}
