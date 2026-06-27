package com.weatherwidget.desktop

import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.deriveDataStatus
import com.weatherwidget.data.model.isOfflineException
import com.weatherwidget.data.model.WeatherSource
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

    val weatherDb = DesktopWeatherDatabase(DesktopDbPaths.defaultDbPath()).apply { initialize() }
    val weatherDao = DesktopWeatherDao(weatherDb)

    com.weatherwidget.widget.CurrentTemperatureResolver.dbLogger = { tag, message, level ->
        // Persistence boundary: VERBOSE = high-frequency render/poll trace — visible only in the
        // ephemeral desktop console (DesktopLogSink keeps the full trace in the autostart log), never
        // persisted, so the queryable DB log stays sparse. DEBUG+ persist.
        if (level != "VERBOSE") weatherDao.log(tag, message, level)
    }

    // IPC server
    val ipcServer = PanelIpcServer(appDir).apply { start() }

    val forecastState = MutableStateFlow<ForecastResult?>(null)
    val dataStatusState = MutableStateFlow<DataStatus>(DataStatus.Loading)
    val configState = MutableStateFlow<DesktopConfig?>(currentConfig)

    var uiProcess: Process? = null
    var logindMonitor: Process? = null

    val daemonScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var fetchJob: Job? = null
    var weatherService: DesktopWeatherService? = null
    var repo: DesktopWeatherRepository? = null
    var lastResumeKickMs = 0L

    fun quit(killUi: Boolean = true) {
        Log.i(TAG, "Quitting daemon (killUi=$killUi)...")
        // Kill UI process if running
        if (killUi) {
            uiProcess?.destroy()
        }
        // Unblock the gdbus reader (coroutine cancellation can't interrupt its blocking read).
        runCatching { logindMonitor?.destroy() }
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

    // Shared by daemon startup and resume-from-suspend: load the cache, then fetch exactly what is
    // stale per determineLaunchRefreshAction (full forecast > 1h old, observations > 10m old, else
    // nothing). [reason] is for log provenance only.
    suspend fun runLaunchRefresh(activeRepo: DesktopWeatherRepository, config: DesktopConfig, reason: String) {
        try {
            Log.i(TAG, "[$reason] Loading cached data...")
            val cached = activeRepo.loadCached()
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
                lastForecastFetchMs = lastForecastFetch,
                nowMs = now,
            )

            Log.i(TAG, "[$reason] Launch refresh action: $launchRefreshAction. lastForecastFetch: $lastForecastFetch lastObservationFetch: $lastObservationFetch")

            weatherDao.log(
                tag = "LAUNCH_REFRESH_CHECK",
                message = "reason=$reason source=${config.weatherSource} cachePresent=${cached != null} action=$launchRefreshAction " +
                    "lastForecastFetch=$lastForecastFetch forecastAgeMs=${lastForecastFetch?.let { now - it }} " +
                    "lastObservationFetch=$lastObservationFetch observationAgeMs=${lastObservationFetch?.let { now - it }}",
                level = "INFO"
            )

            if (launchRefreshAction != LaunchRefreshAction.NONE) {
                try {
                    val result = when (launchRefreshAction) {
                        LaunchRefreshAction.FULL_FORECAST -> {
                            Log.i(TAG, "Refreshing full forecast from network...")
                            activeRepo.refresh()
                        }
                        LaunchRefreshAction.OBSERVATIONS -> {
                            Log.i(TAG, "Refreshing current observations from network...")
                            activeRepo.refreshObservations()
                        }
                        LaunchRefreshAction.NONE -> forecastState.value
                    }
                    forecastState.value = result
                    dataStatusState.value = DataStatus.Live(System.currentTimeMillis())
                    Log.i(TAG, "[$reason] refresh successful. DataStatus updated to Live.")
                } catch (e: CancellationException) {
                    Log.i(TAG, "Refresh cancelled.")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Refresh failed: ${e.message}")
                    e.printStackTrace()
                    val isOffline = isOfflineException(e)
                    val failReason = if (isOffline) "offline" else "source_error"
                    weatherDao.log("REFRESH_FAIL", "$reason fetch: $failReason ${e.message}", "WARN")
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

            // Best-effort, action-independent: populate climate normals for the future-day fallback.
            // Cheap (DB-read early-return) once cached, so safe to run on every launch/resume even
            // when the forecast wasn't stale enough to trigger a full refresh.
            try {
                if (activeRepo.ensureClimateNormals()) {
                    // Newly populated → reload so future-day gap bars appear without waiting for the
                    // next refresh cycle.
                    activeRepo.loadCached()?.let { forecastState.value = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[$reason] ensureClimateNormals failed: ${e.message}")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "[$reason] Initialization failure: ${e.message}")
            e.printStackTrace()
            dataStatusState.value = DataStatus.Error("Initialization failed: ${e.message}")
        }
    }

    // Called on resume-from-suspend (logind signal or heartbeat). Debounced so the two detectors
    // observing the same wake produce a single catch-up fetch. All outcomes write a durable
    // RESUME_DETECT row to app_logs (queryable), not just the ephemeral console Log, so a wake that
    // fired but did nothing (debounced / repo-not-ready) is still diagnosable after the fact.
    fun kickResumeRefresh(reason: String) {
        val now = System.currentTimeMillis()
        val activeRepo = repo
        val activeConfig = currentConfig
        if (activeRepo == null || activeConfig == null) {
            weatherDao.log("RESUME_DETECT", "kick ($reason) skipped: no active repo/config yet", "WARN")
            return
        }
        if (now - lastResumeKickMs < RESUME_DEBOUNCE_MS) {
            weatherDao.log("RESUME_DETECT", "kick ($reason) ignored: debounced (${now - lastResumeKickMs}ms since last kick)", "INFO")
            return
        }
        lastResumeKickMs = now
        weatherDao.log("RESUME_DETECT", "resume detected ($reason) — kicking catch-up refresh", "INFO")
        Log.i(TAG, "Resume detected ($reason) — kicking catch-up refresh.")
        daemonScope.launch { runLaunchRefresh(activeRepo, activeConfig, "resume:$reason") }
    }

    fun startFetchLoops() {
        fetchJob?.cancel()
        runCatching { weatherService?.close() }

        val config = currentConfig ?: return
        val svc = DesktopWeatherService(config.lat, config.lon, config.weatherSource, config.apiKeys, weatherDao)
        weatherService = svc
        val newRepo = DesktopWeatherRepository(svc, weatherDao, config.lat, config.lon, config.weatherSource, config.personalStationWeight())
        repo = newRepo

        fetchJob = daemonScope.launch {
            // 1. Startup refresh
            launch {
                runLaunchRefresh(newRepo, config, "startup")
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

                    val src = WeatherSource.fromDisplaySource(config.weatherSource).id
                    try {
                        Log.i(TAG, "Temp actuals loop refresh starting for ${config.weatherSource} (charging=$isCharging, level=$level%)...")
                        val result = newRepo.refreshObservations()
                        forecastState.value = result
                        dataStatusState.value = DataStatus.Live(weatherDao.getLastSuccessfulFetch(config.weatherSource) ?: System.currentTimeMillis())
                        weatherDao.log("CURRENT_TEMP_STATUS", "source=$src ok=true", "INFO")
                        Log.i(TAG, "Temp actuals loop refresh successful.")
                    } catch (e: CancellationException) {
                        Log.i(TAG, "Temp actuals loop refresh cancelled.")
                        throw e
                    } catch (e: Exception) {
                        Log.i(TAG, "Temp actuals loop refresh failed: ${e.message}")
                        val isOffline = isOfflineException(e)
                        val reason = if (isOffline) "offline" else "source_error"
                        weatherDao.log("REFRESH_FAIL", "temp actuals: $reason ${e.message}", "WARN")
                        weatherDao.log("CURRENT_TEMP_STATUS", "source=$src ok=false class=${e::class.simpleName} detail=${e.message}", "WARN")
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
                                    otherSource,
                                    config.personalStationWeight()
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

            // 3d. Non-primary actuals (observations) loop — 30 min, only while charging + screen on.
            // Keeps the non-displayed sources' actual/current temp fresh for an instant toggle,
            // without paying for it on battery or while the monitor is asleep. Off-charger or
            // screen-off, non-primary actuals fall back to the slower non-active forecast loop (3c).
            launch {
                while (true) {
                    val (isCharging, _) = PowerDetector.getPowerState()
                    val delayMs = DesktopFetchStrategy.getNonPrimaryObservationDelayMs(
                        isCharging = isCharging,
                        screenOn = ScreenStateDetector.isScreenOn(),
                    )
                    if (delayMs == null) {
                        // Gated off (battery or screen off): re-check soon so non-primary actuals
                        // resume within minutes of plugging in / the screen waking, not 30 min later.
                        delay(SUSPEND_RECHECK_INTERVAL_MS)
                        continue
                    }

                    delay(delayMs)

                    val nonActiveSources = config.visibleSources.filter { it != config.weatherSource }
                    for (otherSource in nonActiveSources) {
                        try {
                            Log.i(TAG, "Non-primary actuals refresh starting for $otherSource...")
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
                                otherSource,
                                config.personalStationWeight()
                            )
                            otherRepo.refreshObservations()
                            otherService.close()
                            Log.i(TAG, "Non-primary actuals refresh successful for $otherSource.")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.i(TAG, "Non-primary actuals refresh failed for $otherSource: ${e.message}")
                            val reason = if (isOfflineException(e)) "offline" else "source_error"
                            weatherDao.log("REFRESH_FAIL", "non-primary actuals $otherSource: $reason ${e.message}", "WARN")
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
                                        // Most likely the distributable was deleted out from under
                                        // this daemon (it survives on a deleted inode but can't exec
                                        // the missing launcher). Give the click immediate feedback —
                                        // the panel ⚠ only refreshes on genmon's next poll.
                                        notifyDesktop(
                                            "Weather Widget can't open",
                                            "App files are missing — rebuild and restart: scripts/buildStart.sh",
                                            urgency = "critical",
                                        )
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

    // Resume-from-suspend detection. The fetch loops sleep on coroutine delay() (monotonic clock,
    // frozen during suspend) so they do not fire on wake; without a kick, current temp stays stale
    // for up to the remaining interval (4–8h on battery). Two best-effort detectors race:

    // Primary (interrupt-driven): logind emits PrepareForSleep(false) on wake. If gdbus is missing or
    // the stream dies we log once and lean on the heartbeat fallback below.
    daemonScope.launch(Dispatchers.IO) {
        try {
            val proc = ProcessBuilder(
                "gdbus", "monitor", "--system",
                "--dest", "org.freedesktop.login1",
                "--object-path", "/org/freedesktop/login1",
            ).redirectErrorStream(true).start()
            logindMonitor = proc
            weatherDao.log("RESUME_DETECT", "gdbus logind monitor started (pid=${proc.pid()})", "INFO")
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (isResumeSignalLine(line)) kickResumeRefresh("logind")
                }
            }
            // Stream ending unexpectedly means the primary detector is dead; heartbeat still covers us.
            weatherDao.log("RESUME_DETECT", "gdbus logind monitor stream ended — heartbeat fallback only", "WARN")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            weatherDao.log("RESUME_DETECT", "gdbus logind monitor unavailable (${e.message}) — heartbeat fallback only", "WARN")
        }
    }

    // Fallback (universal): a wall-clock jump far larger than the heartbeat interval can only mean we
    // were suspended. Mirrors the time-jump heuristic in ~/bin/sys-logging.sh.
    daemonScope.launch(Dispatchers.IO) {
        var expected = System.currentTimeMillis()
        while (true) {
            delay(HEARTBEAT_INTERVAL_MS)
            val now = System.currentTimeMillis()
            val gapMs = now - expected
            if (isSuspendJump(HEARTBEAT_INTERVAL_MS, gapMs, SUSPEND_JUMP_SLACK_MS)) {
                // gap in the reason distinguishes a real multi-hour suspend from a brief scheduler /
                // GC stall that tripped the threshold (a false positive shows a small gap).
                kickResumeRefresh("heartbeat gap=${gapMs / 1000}s")
            }
            expected = now
        }
    }

    runBlocking {
        awaitCancellation()
    }
}
