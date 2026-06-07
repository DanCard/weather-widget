package com.weatherwidget.desktop

import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.deriveDataStatus
import com.weatherwidget.data.model.isOfflineException
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopDbPaths
import com.weatherwidget.shared.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import kotlin.system.exitProcess

private const val TAG = "DaemonProcess"

fun runDaemon() {
    // As the very first statement: java.awt.headless = true
    System.setProperty("java.awt.headless", "true")

    // Set thread name to WeatherDaemon
    Thread.currentThread().name = "WeatherDaemon"

    Log.i(TAG, "Starting headless WeatherDaemon...")
    Log.i(TAG, "Java: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")}) @ ${System.getProperty("java.home")}")

    val appDir = appDataDir()
    runCatching { signalIncumbentToQuit(appDir, appLaunchId) }
    // Packaged-only first-run setup (extract the genmon script to a stable XDG path). The daemon owns
    // this now that it is the long-lived process; the UI process is ephemeral and must not.
    maybePackagedSetup()

    val configStore = DesktopConfigStore()
    var currentConfig = configStore.load()

    // Persistence layer
    val weatherDb = DesktopWeatherDatabase(DesktopDbPaths.defaultDbPath()).apply { initialize() }
    val weatherDao = DesktopWeatherDao(weatherDb)

    // IPC server
    val ipcServer = PanelIpcServer(appDir).apply { start() }

    val forecastState = MutableStateFlow<ForecastResult?>(null)
    val dataStatusState = MutableStateFlow<DataStatus>(DataStatus.Loading)
    val configState = MutableStateFlow<DesktopConfig?>(currentConfig)

    var uiProcess: Process? = null

    val daemonScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var fetchJob: Job? = null
    var weatherService: DesktopWeatherService? = null
    var repo: DesktopWeatherRepository? = null

    fun quit(killUi: Boolean = true) {
        Log.i(TAG, "Quitting daemon (killUi=$killUi)...")
        // Kill UI process if running
        if (killUi) {
            uiProcess?.destroy()
        }
        daemonScope.cancel()

        kotlin.concurrent.thread(isDaemon = true, name = "quit-hard-exit") {
            Thread.sleep(400)
            exitProcess(0)
        }

        runCatching { weatherService?.close() }
        runCatching {
            val myQuitFile = appDir.resolve("$QUIT_PREFIX$appLaunchId")
            Files.deleteIfExists(myQuitFile)
        }
        exitProcess(0)
    }

    // Sync the state flows with IPC server updates
    daemonScope.launch {
        combine(forecastState, dataStatusState, configState) { forecast, dataStatus, config ->
            ipcServer.update(forecast, dataStatus, config)
        }.collect {}
    }

    fun startFetchLoops() {
        fetchJob?.cancel()
        runCatching { weatherService?.close() }

        val config = currentConfig ?: return
        val svc = DesktopWeatherService(config.lat, config.lon, config.weatherSource, config.apiKeys, weatherDao)
        weatherService = svc
        val newRepo = DesktopWeatherRepository(svc, weatherDao, config.lat, config.lon, config.weatherSource)
        repo = newRepo

        fetchJob = daemonScope.launch {
            // 1. Startup refresh
            launch {
                try {
                    Log.i(TAG, "Loading cached data...")
                    val cached = newRepo.loadCached()
                    Log.i(TAG, "Cached data loaded. Null? ${cached == null}")
                    if (cached != null) {
                        forecastState.value = cached
                        val lastFetch = weatherDao.getLastSuccessfulFetch(config.weatherSource)
                        dataStatusState.value = DataStatus.Live(lastFetch ?: System.currentTimeMillis())
                        Log.i(TAG, "DataStatus updated to Live (cached). lastFetch: $lastFetch")
                    }

                    val now = System.currentTimeMillis()
                    val lastForecastFetch = weatherDao.getLastSuccessfulFetch(config.weatherSource)
                    val lastObservationFetch = weatherDao.getLastSuccessfulObservationFetch(config.weatherSource)
                    val launchRefreshAction = determineLaunchRefreshAction(
                        cachePresent = cached != null,
                        lastObservationFetchMs = lastObservationFetch,
                        nowMs = now,
                    )

                    Log.i(TAG, "Launch refresh action: $launchRefreshAction. lastForecastFetch: $lastForecastFetch lastObservationFetch: $lastObservationFetch")

                    weatherDao.log(
                        tag = "LAUNCH_REFRESH_CHECK",
                        message = "source=${config.weatherSource} cachePresent=${cached != null} action=$launchRefreshAction " +
                            "lastForecastFetch=$lastForecastFetch forecastAgeMs=${lastForecastFetch?.let { now - it }} " +
                            "lastObservationFetch=$lastObservationFetch observationAgeMs=${lastObservationFetch?.let { now - it }}",
                        level = "INFO"
                    )

                    if (launchRefreshAction != LaunchRefreshAction.NONE) {
                        try {
                            val result = when (launchRefreshAction) {
                                LaunchRefreshAction.FULL_FORECAST -> {
                                    Log.i(TAG, "Refreshing full forecast from network...")
                                    newRepo.refresh()
                                }
                                LaunchRefreshAction.OBSERVATIONS -> {
                                    Log.i(TAG, "Refreshing current observations from network...")
                                    newRepo.refreshObservations()
                                }
                                LaunchRefreshAction.NONE -> forecastState.value
                            }
                            forecastState.value = result
                            dataStatusState.value = DataStatus.Live(System.currentTimeMillis())
                            Log.i(TAG, "Launch refresh successful. DataStatus updated to Live.")
                        } catch (e: CancellationException) {
                            Log.i(TAG, "Refresh cancelled.")
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Refresh failed: ${e.message}")
                            e.printStackTrace()
                            val isOffline = isOfflineException(e)
                            val reason = if (isOffline) "offline" else "source_error"
                            weatherDao.log("REFRESH_FAIL", "launch fetch: $reason ${e.message}", "WARN")
                            val lastSuccess = weatherDao.getLastSuccessfulFetch(config.weatherSource)
                            dataStatusState.value = deriveDataStatus(
                                cachePresent = forecastState.value != null,
                                lastFetchMs = lastSuccess,
                                refreshFailed = true,
                                failureIsOffline = isOffline,
                            )
                            Log.i(TAG, "DataStatus updated to: ${dataStatusState.value}")
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Initialization failure: ${e.message}")
                    e.printStackTrace()
                    dataStatusState.value = DataStatus.Error("Initialization failed: ${e.message}")
                }
            }

            // 3a. Current-temp UI loop
            launch {
                while (true) {
                    delay(CURRENT_TEMP_UI_INTERVAL_MS)
                    try {
                        newRepo.loadCached()?.let { forecastState.value = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Current-temp UI update failed: ${e.message}")
                    }
                }
            }

            // 3b. Temp actuals (observations) fetch loop
            launch {
                while (true) {
                    val (isCharging, level) = PowerDetector.getPowerState()
                    val delayMs = DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging, level)

                    if (delayMs == null) {
                        Log.i(TAG, "Observation loop: background fetch suspended due to low battery ($level%). Re-checking in 5 min.")
                        delay(SUSPEND_RECHECK_INTERVAL_MS)
                        continue
                    }

                    delay(delayMs)

                    try {
                        Log.i(TAG, "Temp actuals loop refresh starting for ${config.weatherSource} (charging=$isCharging, level=$level%)...")
                        val result = newRepo.refreshObservations()
                        forecastState.value = result
                        dataStatusState.value = DataStatus.Live(weatherDao.getLastSuccessfulFetch(config.weatherSource) ?: System.currentTimeMillis())
                        Log.i(TAG, "Temp actuals loop refresh successful.")
                    } catch (e: CancellationException) {
                        Log.i(TAG, "Temp actuals loop refresh cancelled.")
                        throw e
                    } catch (e: Exception) {
                        Log.i(TAG, "Temp actuals loop refresh failed: ${e.message}")
                        val isOffline = isOfflineException(e)
                        val reason = if (isOffline) "offline" else "source_error"
                        weatherDao.log("REFRESH_FAIL", "temp actuals: $reason ${e.message}", "WARN")
                        val lastSuccess = weatherDao.getLastSuccessfulFetch(config.weatherSource)
                        dataStatusState.value = deriveDataStatus(
                            cachePresent = forecastState.value != null,
                            lastFetchMs = lastSuccess,
                            refreshFailed = true,
                            failureIsOffline = isOffline,
                        )
                    }
                }
            }

            // 3c. Forecast fetch loop
            launch {
                while (true) {
                    val (isCharging, level) = PowerDetector.getPowerState()
                    val delayMs = DesktopFetchStrategy.getForecastRefreshDelayMs(isCharging, level, isActiveSource = true)

                    if (delayMs == null) {
                        Log.i(TAG, "Forecast loop: background fetch suspended due to low battery ($level%). Re-checking in 5 min.")
                        delay(SUSPEND_RECHECK_INTERVAL_MS)
                        continue
                    }

                    delay(delayMs)

                    val activeSource = config.weatherSource
                    val allVisible = config.visibleSources

                    try {
                        Log.i(TAG, "Loop forecast refresh starting for active source: $activeSource (charging=$isCharging, level=$level%)...")
                        val result = newRepo.refresh()
                        forecastState.value = result
                        dataStatusState.value = DataStatus.Live(System.currentTimeMillis())
                        Log.i(TAG, "Active source forecast refresh successful.")
                    } catch (e: CancellationException) {
                        Log.i(TAG, "Loop refresh cancelled.")
                        throw e
                    } catch (e: Exception) {
                        Log.i(TAG, "Active source forecast refresh failed: ${e.message}")
                        val isOffline = isOfflineException(e)
                        val reason = if (isOffline) "offline" else "source_error"
                        weatherDao.log("REFRESH_FAIL", "$reason ${e.message}", "WARN")
                        val lastSuccess = weatherDao.getLastSuccessfulFetch(config.weatherSource)
                        dataStatusState.value = deriveDataStatus(
                            cachePresent = forecastState.value != null,
                            lastFetchMs = lastSuccess,
                            refreshFailed = true,
                            failureIsOffline = isOffline,
                        )
                    }

                    // Slower forecast fetch for other APIs
                    val nonActiveSources = allVisible.filter { it != activeSource }
                    for (otherSource in nonActiveSources) {
                        try {
                            val lastOtherFetch = weatherDao.getLastSuccessfulFetch(otherSource)
                            val otherDelayMs = DesktopFetchStrategy.getForecastRefreshDelayMs(isCharging, level, isActiveSource = false)
                                ?: continue

                            val isDue = lastOtherFetch == null ||
                                (System.currentTimeMillis() - lastOtherFetch) >= otherDelayMs

                            if (isDue) {
                                Log.i(TAG, "Refreshing forecast for non-active source: $otherSource...")
                                val otherService = DesktopWeatherService(
                                    config.lat,
                                    config.lon,
                                    otherSource,
                                    config.apiKeys,
                                    weatherDao
                                )
                                val otherRepo = DesktopWeatherRepository(
                                    otherService,
                                    weatherDao,
                                    config.lat,
                                    config.lon,
                                    otherSource
                                )
                                otherRepo.refresh()
                                otherService.close()
                                Log.i(TAG, "Non-active source $otherSource forecast refresh successful.")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.i(TAG, "Non-active source $otherSource forecast refresh failed: ${e.message}")
                            val isOffline = isOfflineException(e)
                            val reason = if (isOffline) "offline" else "source_error"
                            weatherDao.log("REFRESH_FAIL", "forecast other $otherSource: $reason ${e.message}", "WARN")
                        }
                    }
                }
            }
        }
    }

    if (currentConfig != null) {
        startFetchLoops()
    } else {
        ipcServer.update(null, DataStatus.Loading, null)
    }

    // WatchService loop
    daemonScope.launch(Dispatchers.IO) {
        // Clean up any old .quit files (except our own signature file)
        if (Files.exists(appDir)) {
            Files.list(appDir).use { paths ->
                paths.forEach { path ->
                    val name = path.fileName.toString()
                    if (name == QUIT_TRIGGER || (name.startsWith(QUIT_PREFIX) && name != "$QUIT_PREFIX$appLaunchId")) {
                        runCatching { Files.deleteIfExists(path) }
                    }
                }
            }
        }
        val watchService = FileSystems.getDefault().newWatchService()
        appDir.register(
            watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY
        )

        try {
            while (true) {
                val key = watchService.take()
                for (event in key.pollEvents()) {
                    val name = (event.context() as? Path)?.toString()
                    if (name != null) {
                        when (name) {
                            SHOW_TRIGGER -> {
                                Log.i(TAG, "WatchService: .show trigger detected.")
                                runCatching { Files.deleteIfExists(appDir.resolve(SHOW_TRIGGER)) }
                                if (uiProcess?.isAlive == true) {
                                    Log.i(TAG, "UI process is already alive. Bumping UI via .ui-show...")
                                    val uiShowFile = appDir.resolve(UI_SHOW_TRIGGER)
                                    runCatching {
                                        Files.writeString(uiShowFile, "", java.nio.charset.StandardCharsets.UTF_8)
                                    }
                                } else {
                                    Log.i(TAG, "UI process is not alive. Spawning a new UI process...")
                                    uiProcess = runCatching { launchUiProcess() }.getOrElse { e ->
                                        Log.e(TAG, "Failed to launch UI process: ${e.message}", e)
                                        null
                                    }
                                }
                            }
                            CONFIG_CHANGED_TRIGGER -> {
                                Log.i(TAG, "WatchService: .config-changed trigger detected. Reloading config...")
                                runCatching { Files.deleteIfExists(appDir.resolve(CONFIG_CHANGED_TRIGGER)) }
                                val newConfig = configStore.load()
                                if (newConfig == null) {
                                    Log.w(TAG, "Config loaded was null. Stopping loops.")
                                    fetchJob?.cancel()
                                    currentConfig = null
                                    configState.value = null
                                    forecastState.value = null
                                } else {
                                    val localConfig = currentConfig
                                    val locationOrSourceChanged = localConfig == null ||
                                            newConfig.lat != localConfig.lat ||
                                            newConfig.lon != localConfig.lon ||
                                            newConfig.weatherSource != localConfig.weatherSource ||
                                            newConfig.visibleSources != localConfig.visibleSources
                                    
                                    currentConfig = newConfig
                                    configState.value = newConfig
                                    
                                    if (locationOrSourceChanged) {
                                        Log.i(TAG, "Location/source changed. Restarting fetch loops...")
                                        startFetchLoops()
                                    } else {
                                        Log.i(TAG, "Config changed but location/source are identical. Ignoring loop restart.")
                                    }
                                }
                            }
                            QUIT_TRIGGER -> {
                                Log.i(TAG, "Script or manual quit trigger detected. Exiting.")
                                quit()
                            }
                            else -> {
                                if (name.startsWith(QUIT_PREFIX)) {
                                    val suffix = name.substring(QUIT_PREFIX.length)
                                    if (suffix != appLaunchId) {
                                        Log.i(TAG, "Newer instance detected (launchId=$suffix, mine=$appLaunchId). Exiting.")
                                        quit(killUi = false)
                                    } else {
                                        Log.i(TAG, "Ignored quit trigger (launchId=$suffix, mine=$appLaunchId).")
                                    }
                                }
                            }
                        }
                    }
                }
                if (!key.reset()) break
            }
        } finally {
            watchService.close()
        }
    }

    // Safety net for the lossy WatchService: the `.quit-<id>` interrupt above is the fast path, but
    // Java's WatchService can drop events, leaving a superseded daemon alive (the cause of stacked
    // instances). Actively re-check on a slow timer so an older daemon still exits even if it never
    // received the file-watch event. Newest-launch-wins, same as the interrupt path.
    daemonScope.launch(Dispatchers.IO) {
        while (true) {
            delay(INSTANCE_RECHECK_INTERVAL_MS)
            if (supersededByNewerInstance(appDir, appLaunchId)) {
                Log.i(TAG, "Instance re-check: a newer instance is active (mine=$appLaunchId). Exiting.")
                quit(killUi = false)
            }
        }
    }

    runBlocking {
        awaitCancellation()
    }
}
