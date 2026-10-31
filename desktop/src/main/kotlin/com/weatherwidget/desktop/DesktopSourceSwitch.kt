package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.sourceview.SourceSwitchRunner
import com.weatherwidget.shared.sourceview.SourceSwitchRunner.RefreshOutcome
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import com.weatherwidget.shared.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Desktop's [SourceSwitchRunner] host. The API button (header and popup), the months view's "another
 * source" line and the daily home button's source reset all switch through [switchTo].
 *
 * - persist: `onUpdateConfig`, i.e. `saveConfigAndNotify`, which writes config.json and the
 *   `.config-changed` trigger and sets the `config` state. It runs on the calling (UI) thread and
 *   writes no app_logs row before the save.
 * - paint: nothing to do. Compose repaints from the `config` state the persist step set, and the
 *   daemon's `.data-updated` reload follows. `paintMs` is therefore ~0 on desktop.
 * - refresh: [RefreshOutcome.DELEGATED]. The daemon decides on `.config-changed`
 *   (`LAUNCH_REFRESH_CHECK reason=source_change`, `SourceToggleRefreshPolicy`).
 * - record / log: on [deferredScope] (IO), off the UI thread.
 * - data ready: [onDataShown] times the wait until the UI holds the new source's data.
 * plans/261029-shared-source-switch-runner.md
 */
internal object DesktopSourceSwitch {
    private const val TAG = "DesktopSourceSwitch"

    @Volatile
    private var dao: DesktopWeatherDao? = null

    @Volatile
    private var deferredScope: CoroutineScope = defaultScope()

    /** The switch whose new data has not reached the UI yet, for [onDataShown]. */
    private data class Pending(val from: String, val to: String, val trigger: SourceViewTrigger, val startMs: Long)

    @Volatile
    private var pending: Pending? = null

    @Volatile
    private var elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 }

    private fun defaultScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("source-switch"))

    /** Installed by the UI process; until then (tests, the daemon) the app_logs row is skipped. */
    fun install(dao: DesktopWeatherDao?, deferredScope: CoroutineScope? = null, elapsedMs: (() -> Long)? = null) {
        this.dao = dao
        this.deferredScope = deferredScope ?: defaultScope()
        this.elapsedMs = elapsedMs ?: { System.nanoTime() / 1_000_000 }
        pending = null
    }

    /**
     * Shows [targetSourceId] (a display-source string from `effectiveSources`) and counts the switch
     * as [trigger]. Returns once the config is saved and the state set; the rest runs afterwards.
     */
    fun switchTo(
        config: DesktopConfig,
        targetSourceId: String,
        trigger: SourceViewTrigger,
        onUpdateConfig: (DesktopConfig) -> Unit,
    ) {
        val scope = deferredScope
        val from = WeatherSource.fromDisplaySource(config.displaySource)
        pending = Pending(from.id, WeatherSource.fromDisplaySource(targetSourceId).id, trigger, elapsedMs())
        val host = Host(config, targetSourceId, trigger, onUpdateConfig)
        val runner = SourceSwitchRunner(host, platform = "desktop")
        // UNDISPATCHED + Unconfined: the critical path runs here, on the caller's thread, before this
        // returns (no host step suspends). The runner has logged any failure; the handler keeps a
        // rethrown one from reaching the UI thread's uncaught-exception handler.
        scope.launch(Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> }, start = CoroutineStart.UNDISPATCHED) {
            runner.run(from, trigger, scope)
        }
    }

    /**
     * The UI applied a snapshot holding [sourceId]. The first time that is the source of the pending
     * switch, logs `SOURCE_SWITCH_E2E phase=data_ready sinceSwitchMs=`: the graph's wait, which desktop's
     * `paintMs` (~0, a config-state change) cannot show. That wait was 5.6 s behind a held SQLite lock
     * on 2026-10-29 while `criticalMs` read 1.
     */
    fun onDataShown(sourceId: String) {
        val switch = pending ?: return
        if (sourceId != switch.to) return
        pending = null
        val line = SourceSwitchRunner.dataReadyLine("desktop", switch.from, switch.to, switch.trigger, elapsedMs() - switch.startMs)
        Log.i(TAG, "${SourceSwitchRunner.E2E_TAG} $line")
        DesktopAppLogQueue.log(SourceSwitchRunner.E2E_TAG, line, "INFO")
    }

    internal class Host(
        private val config: DesktopConfig,
        private val targetSourceId: String,
        private val trigger: SourceViewTrigger,
        private val onUpdateConfig: (DesktopConfig) -> Unit,
    ) : SourceSwitchRunner.Host {
        override suspend fun persistSelection(): WeatherSource {
            onUpdateConfig(config.copy(settings = config.settings.copy(weatherSource = targetSourceId)))
            return WeatherSource.fromDisplaySource(targetSourceId)
        }

        override suspend fun showFromCache(source: WeatherSource) = Unit

        override suspend fun refreshIfStale(source: WeatherSource) = RefreshOutcome.DELEGATED

        override suspend fun recordView(source: WeatherSource) =
            DesktopSourceViews.record(targetSourceId, config.effectiveSources, config.viewMode, trigger)

        override suspend fun persistLog(tag: String, message: String, level: String) {
            dao?.log(tag, message, level)
        }
    }
}
