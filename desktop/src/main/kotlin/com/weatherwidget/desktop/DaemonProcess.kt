package com.weatherwidget.desktop

import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopDbPaths
import com.weatherwidget.data.local.desktop.WakeEventLog
import com.weatherwidget.shared.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG = "DaemonProcess"

internal fun daemonFetchRestartReason(
    previous: DesktopConfig?,
    updated: DesktopConfig,
): String? {
    val locationSourceOrVisibilityChanged = previous == null ||
        updated.lat != previous.lat ||
        updated.lon != previous.lon ||
        updated.settings.weatherSource != previous.settings.weatherSource ||
        updated.settings.visibleSources != previous.settings.visibleSources
    return when {
        locationSourceOrVisibilityChanged -> "source_or_location_change"
        previous.settings.actualsProviders != updated.settings.actualsProviders ->
            "actuals_provider_change"
        else -> null
    }
}

fun runDaemon() {
    // As the very first statement: java.awt.headless = true
    System.setProperty("java.awt.headless", "true")

    // Never cache failed DNS lookups (JVM default: 10s). Right after the network returns, a
    // negative entry cached during the outage would make the network-restored kick's first fetch
    // fail from the cache even though the resolver is back.
    java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0")

    // Set thread name to WeatherDaemon
    Thread.currentThread().name = "WeatherDaemon"

    Log.i(TAG, "Starting headless WeatherDaemon...")
    Log.i(TAG, "Java: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")}) @ ${System.getProperty("java.home")}")

    val appDir = appDataDir()
    runCatching { signalIncumbentToQuit(appDir, appLaunchId) }

    val configStore = DesktopConfigStore()
    var currentConfig = configStore.load()
    // Same shape as the Log sink: a :shared seam only the platform can fill. The daemon renders the
    // panel markup, so it needs the user's per-source actuals choice just as much as the UI does.
    DesktopActualsPreference.install()
    DesktopActualsPreference.update(currentConfig?.settings)

    val weatherDb = DesktopWeatherDatabase(DesktopDbPaths.defaultDbPath()).apply { initialize() }
    val weatherDao = DesktopWeatherDao(weatherDb)

    // Daemon launch is a wake-equivalent transition: login autostart races the network stack just
    // like resume-from-suspend does. Anchors the UI's network-warm-up grace window (WAKE_EVENT is
    // the explicit contract behind getLatestWakeEventMs; the RESUME_DETECT/NETWORK_DETECT rows are
    // diagnostics only).
    weatherDao.log(WakeEventLog.TAG, WakeEventLog.message("startup"), "INFO")

    com.weatherwidget.widget.CurrentTemperatureResolver.dbLogger = { tag, message, level ->
        // Persistence boundary: VERBOSE = high-frequency render/poll trace — visible only in the
        // ephemeral desktop console (DesktopLogSink keeps the full trace in the autostart log), never
        // persisted, so the queryable DB log stays sparse. DEBUG+ persist.
        if (level != "VERBOSE") weatherDao.log(tag, message, level)
    }



    val forecastState = MutableStateFlow<ForecastSnapshot?>(null)
    val dataStatusState = MutableStateFlow<DataStatus>(DataStatus.Loading)
    val configState = MutableStateFlow<DesktopConfig?>(currentConfig)

    val daemonScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    DaemonRuntime(
        appDir = appDir,
        configStore = configStore,
        weatherDao = weatherDao,
        initialConfig = currentConfig,
        daemonScope = daemonScope,
        forecastState = forecastState,
        dataStatusState = dataStatusState,
        configState = configState,
    ).start()
}
