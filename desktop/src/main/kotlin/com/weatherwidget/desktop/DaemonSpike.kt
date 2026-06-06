package com.weatherwidget.desktop

import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.deriveDataStatus
import com.weatherwidget.data.model.isOfflineException
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopDbPaths
import com.weatherwidget.shared.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import kotlin.system.exitProcess

private const val TAG = "DaemonSpike"

private const val QUIT_TRIGGER = ".quit"
private const val QUIT_PREFIX = ".quit-"

private val appLaunchId = java.util.UUID.randomUUID().toString()

private fun appDataDir(): Path = DesktopDbPaths.defaultDbPath().parent

private fun signalIncumbentToQuit(dir: Path, launchId: String) {
    Files.createDirectories(dir)
    if (Files.exists(dir)) {
        Files.list(dir).use { paths ->
            paths.forEach { path ->
                val name = path.fileName.toString()
                if (name == QUIT_TRIGGER || name.startsWith(QUIT_PREFIX)) {
                    runCatching { Files.deleteIfExists(path) }
                }
            }
        }
    }
    val trigger = dir.resolve("$QUIT_PREFIX$launchId")
    Files.writeString(
        trigger,
        "",
        java.nio.charset.StandardCharsets.UTF_8
    )
}

private class SpikeDesktopClients {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    fun close() {
        httpClient.close()
    }
}

private const val CURRENT_TEMP_UI_INTERVAL_MS = 2 * 60 * 1000L
private const val SUSPEND_RECHECK_INTERVAL_MS = 5 * 60 * 1000L

fun main() {
    // As the very first statement: java.awt.headless = true
    System.setProperty("java.awt.headless", "true")

    // Set thread name to WeatherDaemon
    Thread.currentThread().name = "WeatherDaemon"

    Log.i(TAG, "Starting headless WeatherDaemon spike...")
    Log.i(TAG, "Java: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")}) @ ${System.getProperty("java.home")}")

    val appDir = appDataDir()
    runCatching { signalIncumbentToQuit(appDir, appLaunchId) }

    val configStore = DesktopConfigStore()
    val config = configStore.load()
    if (config == null) {
        Log.e(TAG, "No configuration found! Run the main UI app first to configure location.")
        exitProcess(1)
    }

    Log.i(TAG, "Loaded config: lat=${config.lat}, lon=${config.lon}, source=${config.weatherSource}")

    // Persistence layer
    val weatherDb = DesktopWeatherDatabase(DesktopDbPaths.defaultDbPath()).apply { initialize() }
    val weatherDao = DesktopWeatherDao(weatherDb)

    val desktopClients = SpikeDesktopClients()

    val weatherService = DesktopWeatherService(config.lat, config.lon, config.weatherSource, config.apiKeys, weatherDao)
    val repo = DesktopWeatherRepository(weatherService, weatherDao, config.lat, config.lon, config.weatherSource)

    // IPC server
    val ipcServer = PanelIpcServer(appDir).apply { start() }

    val forecastState = MutableStateFlow<ForecastResult?>(null)
    val dataStatusState = MutableStateFlow<DataStatus>(DataStatus.Loading)

    fun quit() {
        Log.i(TAG, "Quitting daemon spike...")
        kotlin.concurrent.thread(isDaemon = true, name = "quit-hard-exit") {
            Thread.sleep(400)
            exitProcess(0)
        }
        desktopClients.close()
        runCatching {
            val myQuitFile = appDir.resolve("$QUIT_PREFIX$appLaunchId")
            Files.deleteIfExists(myQuitFile)
        }
        exitProcess(0)
    }

    runBlocking {
        // Sync the state flows with IPC server updates
        launch {
            combine(forecastState, dataStatusState) { forecast, dataStatus ->
                ipcServer.update(forecast, dataStatus, config)
            }.collect {}
        }

        coroutineScope {
            // 1. Startup refresh
            launch {
                try {
                    Log.i(TAG, "Loading cached data...")
                    val cached = repo.loadCached()
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
                                    repo.refresh()
                                }
                                LaunchRefreshAction.OBSERVATIONS -> {
                                    Log.i(TAG, "Refreshing current observations from network...")
                                    repo.refreshObservations()
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
                        repo.loadCached()?.let { forecastState.value = it }
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
                        val result = repo.refreshObservations()
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
                        val result = repo.refresh()
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

            // WatchService loop
            launch(Dispatchers.IO) {
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
                                when {
                                    name == ".show" -> {
                                        Log.i(TAG, "WatchService: .show trigger detected. Would spawn UI.")
                                        // Optional delete of .show so it doesn't linger
                                        runCatching { Files.deleteIfExists(appDir.resolve(".show")) }
                                    }
                                    name == QUIT_TRIGGER -> {
                                        Log.i(TAG, "Script or manual quit trigger detected. Exiting.")
                                        quit()
                                    }
                                    name.startsWith(QUIT_PREFIX) -> {
                                        val suffix = name.substring(QUIT_PREFIX.length)
                                        if (suffix != appLaunchId) {
                                            Log.i(TAG, "Newer instance detected (launchId=$suffix, mine=$appLaunchId). Exiting.")
                                            quit()
                                        } else {
                                            Log.i(TAG, "Ignored quit trigger (launchId=$suffix, mine=$appLaunchId).")
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

            // Await cancellation to keep the blocking scope alive
            awaitCancellation()
        }
    }
}
