package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.sourceview.SourceSwitchRunner
import com.weatherwidget.shared.sourceview.SourceSwitchRunner.RefreshOutcome
import com.weatherwidget.shared.sourceview.SourceViewTrigger
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
 * plans/261029-shared-source-switch-runner.md
 */
internal object DesktopSourceSwitch {
    @Volatile
    private var dao: DesktopWeatherDao? = null

    @Volatile
    private var deferredScope: CoroutineScope = defaultScope()

    private fun defaultScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("source-switch"))

    /** Installed by the UI process; until then (tests, the daemon) the app_logs row is skipped. */
    fun install(dao: DesktopWeatherDao?, deferredScope: CoroutineScope? = null) {
        this.dao = dao
        this.deferredScope = deferredScope ?: defaultScope()
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
        val host = Host(config, targetSourceId, trigger, onUpdateConfig)
        val runner = SourceSwitchRunner(host, platform = "desktop")
        // UNDISPATCHED + Unconfined: the critical path runs here, on the caller's thread, before this
        // returns (no host step suspends). The runner has logged any failure; the handler keeps a
        // rethrown one from reaching the UI thread's uncaught-exception handler.
        scope.launch(Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> }, start = CoroutineStart.UNDISPATCHED) {
            runner.run(WeatherSource.fromDisplaySource(config.displaySource), trigger, scope)
        }
    }

    private class Host(
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
