package com.weatherwidget.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.deriveDataStatus
import com.weatherwidget.data.model.isOfflineException
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.shared.util.Log
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopDbPaths
import com.weatherwidget.data.remote.IpGeolocationApi
import com.weatherwidget.data.remote.NominatimApi
import com.weatherwidget.util.NavigationUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import java.awt.Color as AwtColor
import dorkbox.systemTray.SystemTray
import dorkbox.systemTray.MenuItem as TrayMenuItem

/**
 * Desktop entry point. System-tray icon + a small frameless popup — the Linux-desktop analogue of
 * the Android home-screen widget.
 */
private const val APP_PACKAGE = "weather-widget-desktop"
private const val TAG = "Main"
private const val HOURLY_NAV_JUMP = 6
private const val MIN_HOURLY_OFFSET = -720
private const val MAX_HOURLY_OFFSET = 720

private fun appDataDir(): java.nio.file.Path = DesktopDbPaths.defaultDbPath().parent

private fun isPackaged(): Boolean = System.getProperty("jpackage.app-path") != null

private const val QUIT_TRIGGER = ".quit"
private const val QUIT_PREFIX = ".quit-"

/**
 * Best-effort, last-launch-wins single-instance handoff. Dorkbox SystemTray is a process-level
 * singleton, so two instances would fight over the tray (and double up on background fetches + DB
 * writers). Rather than the new launch giving up, it touches a [.quit-<launchId>] file that any
 * running instance's WatchService is watching, so the incumbent exits itself. This mirrors the
 * `.show` trigger and makes dev iteration painless — a fresh launch simply replaces whatever's
 * running.
 *
 * Fire-and-forget: we do NOT wait for the incumbent to exit (a brief tray/socket overlap is fine —
 * the new PanelIpcServer rebinds weather.sock anyway). Crucially this runs in main() *before* the
 * Compose composition registers this instance's own WatchService — inotify never replays a
 * pre-existing file, so the new instance never quits itself; only the already-watching incumbent
 * reacts.
 */
private val appLaunchId = java.util.UUID.randomUUID().toString()

private fun signalIncumbentToQuit(dir: java.nio.file.Path, launchId: String) {
    java.nio.file.Files.createDirectories(dir)
    if (java.nio.file.Files.exists(dir)) {
        java.nio.file.Files.list(dir).use { paths ->
            paths.forEach { path ->
                val name = path.fileName.toString()
                if (name == QUIT_TRIGGER || name.startsWith(QUIT_PREFIX)) {
                    runCatching { java.nio.file.Files.deleteIfExists(path) }
                }
            }
        }
    }
    val trigger = dir.resolve("$QUIT_PREFIX$launchId")
    java.nio.file.Files.writeString(
        trigger,
        "",
        java.nio.charset.StandardCharsets.UTF_8
    )
}

/** Packaged-only first-run setup: extract the genmon script. */
private fun maybePackagedSetup() {
    if (!isPackaged()) return
    runCatching { extractGenmonScript() }.onFailure { System.err.println("genmon extract failed: $it") }
}

/** Copies the bundled genmon script to a stable XDG path so the panel command survives repo removal. */
private fun extractGenmonScript() {
    val target = appDataDir().resolve("genmon-weather.py")
    if (java.nio.file.Files.exists(target)) return
    val stream = object {}.javaClass.getResourceAsStream("/scripts/genmon-weather.py") ?: return
    java.nio.file.Files.createDirectories(target.parent)
    stream.use { java.nio.file.Files.copy(it, target) }
    target.toFile().setExecutable(true)
}

fun main(args: Array<String>) {
    // On Linux, jpackage names the main JVM thread "MainThread" by default. Override to something
    // descriptive for system monitors like 'top'.
    Thread.currentThread().name = "WeatherWidget"

    Log.i("Main", "Starting WeatherWidget...")
    Log.i("Main", "Environment: DISPLAY=${System.getenv("DISPLAY")}, XAUTHORITY=${System.getenv("XAUTHORITY")}")
    Log.i("Main", "Java: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")}) @ ${System.getProperty("java.home")}")

    if (System.getProperty("weatherwidget.desktop.startupSmoke") == "true") {
        return
    }
    // Minimized (popup hidden) is the default for a panel/tray app — the window shouldn't pop open
    // on every launch/restart. Pass --show to open the popup on launch instead. (--minimized is
    // still accepted as a harmless no-op for backward compatibility.)
    if (args.contains("--show")) {
        System.setProperty("weatherwidget.desktop.show", "true")
    }
    if (args.contains("--no-tray")) {
        System.setProperty("weatherwidget.desktop.noTray", "true")
    }
    runCatching { signalIncumbentToQuit(appDataDir(), appLaunchId) } // ask any running instance to exit (best-effort)
    maybePackagedSetup()
    runApp()
}

private fun runApp() = application {
    // Rename the AWT Event Dispatch Thread (which handles Compose UI) to be equally descriptive.
    SwingUtilities.invokeLater {
        Thread.currentThread().name = "WeatherUI"
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
            val startupSmoke = remember { System.getProperty("weatherwidget.desktop.startupSmoke") == "true" }
            val configStore = remember { DesktopConfigStore() }
            var config by remember { mutableStateOf(configStore.load()) }

            // Persistence layer
            val weatherDb = remember { DesktopWeatherDatabase(DesktopDbPaths.defaultDbPath()).apply { initialize() } }
            val weatherDao = remember { DesktopWeatherDao(weatherDb) }

        var popupVisible by remember { mutableStateOf(config != null && System.getProperty("weatherwidget.desktop.show") == "true") }
        // Edge-triggered show counter: a boolean can't re-fire an effect when it's already
        // true, so bump this on every show request to reliably raise an already-open window.
        var showRequestId by remember { mutableStateOf(0) }
        LaunchedEffect(popupVisible) {
            Log.i(TAG, "popupVisible changed to $popupVisible (show property = ${System.getProperty("weatherwidget.desktop.show")})")
        }
        LaunchedEffect(config) {
            Log.i(TAG, "config loaded: config != null is ${config != null}")
        }
        var pickerVisible by remember { mutableStateOf(config == null) }
        var settingsVisible by remember { mutableStateOf(false) }
        var statsVisible by remember { mutableStateOf(false) }
        var observationsVisible by remember { mutableStateOf(false) }
        val desktopClients = remember { DesktopClients() }
        val locationResolver = remember {
            LocationResolver(
                phoneLocator = PhoneLocator(),
                timezoneLocator = TimezoneLocator(),
                ipGeolocationApi = IpGeolocationApi(desktopClients.httpClient, desktopClients.json),
                nominatimApi = NominatimApi(desktopClients.httpClient, desktopClients.json),
            )
        }

        var forecast by remember { mutableStateOf<ForecastResult?>(null) }
        var dataStatus by remember { mutableStateOf<DataStatus>(DataStatus.Loading) }
        val currentConfig = config

        // IPC server for the XFCE panel plugin (genmon)
        val ipcServer = remember { PanelIpcServer(appDataDir()).apply { start() } }
        LaunchedEffect(forecast, dataStatus, currentConfig) {
            currentConfig?.let { ipcServer.update(forecast, dataStatus, it) }
        }

        val weatherService = remember(currentConfig?.lat, currentConfig?.lon, currentConfig?.weatherSource, currentConfig?.apiKeys) {
            currentConfig?.let {
                DesktopWeatherService(it.lat, it.lon, it.weatherSource, it.apiKeys, weatherDao)
            } ?: DesktopWeatherService(currentConfig)
        }
        val repository = remember(weatherService, currentConfig?.lat, currentConfig?.lon, currentConfig?.weatherSource) {
            currentConfig?.let {
                DesktopWeatherRepository(weatherService, weatherDao, it.lat, it.lon, it.weatherSource)
            }
        }

        // Background fetch logic with persistence
        LaunchedEffect(repository) {
            Log.i(TAG, "LaunchedEffect(repository) started. Repository null? ${repository == null}")
            val repo = repository ?: return@LaunchedEffect

            try {
                // 1. Instant load from cache
                Log.i("Main", "Loading cached data...")
                val cached = repo.loadCached()
                Log.i("Main", "Cached data loaded. Null? ${cached == null}")
                if (cached != null) {
                    forecast = cached
                    val lastFetch = weatherDao.getLastSuccessfulFetch(currentConfig?.weatherSource)
                    dataStatus = DataStatus.Live(lastFetch ?: System.currentTimeMillis())
                    Log.i(TAG, "DataStatus updated to Live (cached). lastFetch: $lastFetch")
                }

                // 2. Launch network refresh: missing forecast data needs a full fetch; stale current
                // observations use the observations-only path so daily/hourly forecasts stay on the
                // 60-minute cadence.
                val now = System.currentTimeMillis()
                val lastForecastFetch = weatherDao.getLastSuccessfulFetch(currentConfig?.weatherSource)
                val lastObservationFetch = weatherDao.getLastSuccessfulObservationFetch(currentConfig?.weatherSource)
                val launchRefreshAction = determineLaunchRefreshAction(
                    cachePresent = cached != null,
                    lastObservationFetchMs = lastObservationFetch,
                    nowMs = now,
                )

                Log.i(TAG, "Launch refresh action: $launchRefreshAction. lastForecastFetch: $lastForecastFetch lastObservationFetch: $lastObservationFetch")
                
                weatherDao.log(
                    tag = "LAUNCH_REFRESH_CHECK",
                    message = "source=${currentConfig?.weatherSource} cachePresent=${cached != null} action=$launchRefreshAction " +
                        "lastForecastFetch=$lastForecastFetch forecastAgeMs=${lastForecastFetch?.let { now - it }} " +
                        "lastObservationFetch=$lastObservationFetch observationAgeMs=${lastObservationFetch?.let { now - it }}",
                    level = "INFO"
                )

                if (launchRefreshAction != LaunchRefreshAction.NONE) {
                    try {
                        forecast = when (launchRefreshAction) {
                            LaunchRefreshAction.FULL_FORECAST -> {
                                Log.i("Main", "Refreshing full forecast from network...")
                                repo.refresh()
                            }
                            LaunchRefreshAction.OBSERVATIONS -> {
                                Log.i("Main", "Refreshing current observations from network...")
                                repo.refreshObservations()
                            }
                            LaunchRefreshAction.NONE -> forecast
                        }
                        dataStatus = DataStatus.Live(System.currentTimeMillis())
                        Log.i("Main", "Launch refresh successful. DataStatus updated to Live.")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        Log.i(TAG, "Refresh cancelled.")
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Refresh failed: ${e.message}")
                        e.printStackTrace()
                        val isOffline = isOfflineException(e)
                        val reason = if (isOffline) "offline" else "source_error"
                        weatherDao.log("REFRESH_FAIL", "launch fetch: $reason ${e.message}", "WARN")
                        val lastSuccess = weatherDao.getLastSuccessfulFetch(currentConfig?.weatherSource)
                        dataStatus = deriveDataStatus(
                            cachePresent = forecast != null,
                            lastFetchMs = lastSuccess,
                            refreshFailed = true,
                            failureIsOffline = isOffline,
                        )
                        Log.i(TAG, "DataStatus updated to: $dataStatus")
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Initialization failure: ${e.message}")
                e.printStackTrace()
                dataStatus = DataStatus.Error("Initialization failed: ${e.message}")
                return@LaunchedEffect
            }

            // Two-tier background updates (mirrors the Android design): a cheap UI-temp loop
            // that never touches the network, and a battery-aware data-fetch loop. Previously
            // these were collapsed into one loop that ran the full fetch every 2 min — ~15 HTTP
            // calls + ~1,600 observation upserts + extremes recompute + cleanup, 720×/day — which
            // is what showed up as steady background CPU in `top`.

            // 3a. Current-temp UI loop: re-interpolate currentTemp from cached hourly data as the
            //     wall clock advances. No network, no DB writes — this is the "wake every two
            //     minutes just to update the temp" tier.
            launch {
                while (true) {
                    kotlinx.coroutines.delay(CURRENT_TEMP_UI_INTERVAL_MS)
                    try {
                        repo.loadCached()?.let { forecast = it }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Current-temp UI update failed: ${e.message}")
                    }
                }
            }

            // 3b. Temp actuals (observations) fetch loop: dynamic battery-aware interval.
            launch {
                while (true) {
                    val (isCharging, level) = PowerDetector.getPowerState()
                    val delayMs = DesktopFetchStrategy.getObservationRefreshDelayMs(isCharging, level)

                    if (delayMs == null) {
                        Log.i(TAG, "Observation loop: background fetch suspended due to low battery ($level%). Re-checking in 5 min.")
                        kotlinx.coroutines.delay(SUSPEND_RECHECK_INTERVAL_MS)
                        continue
                    }

                    kotlinx.coroutines.delay(delayMs)

                    try {
                        Log.i(TAG, "Temp actuals loop refresh starting for ${currentConfig?.weatherSource} (charging=$isCharging, level=$level%)...")
                        val result = repo.refreshObservations()
                        forecast = result
                        dataStatus = DataStatus.Live(weatherDao.getLastSuccessfulFetch(currentConfig?.weatherSource) ?: System.currentTimeMillis())
                        Log.i(TAG, "Temp actuals loop refresh successful.")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        Log.i(TAG, "Temp actuals loop refresh cancelled.")
                        throw e
                    } catch (e: Exception) {
                        Log.i(TAG, "Temp actuals loop refresh failed: ${e.message}")
                        val isOffline = isOfflineException(e)
                        val reason = if (isOffline) "offline" else "source_error"
                        weatherDao.log("REFRESH_FAIL", "temp actuals: $reason ${e.message}", "WARN")
                        val lastSuccess = weatherDao.getLastSuccessfulFetch(currentConfig?.weatherSource)
                        dataStatus = deriveDataStatus(
                            cachePresent = forecast != null,
                            lastFetchMs = lastSuccess,
                            refreshFailed = true,
                            failureIsOffline = isOffline,
                        )
                    }
                }
            }

            // 3c. Forecast fetch loop: dynamic battery-aware interval for active and non-active sources.
            while (true) {
                val (isCharging, level) = PowerDetector.getPowerState()
                val delayMs = DesktopFetchStrategy.getForecastRefreshDelayMs(isCharging, level, isActiveSource = true)

                if (delayMs == null) {
                    Log.i(TAG, "Forecast loop: background fetch suspended due to low battery ($level%). Re-checking in 5 min.")
                    kotlinx.coroutines.delay(SUSPEND_RECHECK_INTERVAL_MS)
                    continue
                }

                kotlinx.coroutines.delay(delayMs)

                val activeSource = currentConfig?.weatherSource ?: "NWS"
                val allVisible = currentConfig?.visibleSources ?: listOf(activeSource)
                
                try {
                    Log.i(TAG, "Loop forecast refresh starting for active source: $activeSource (charging=$isCharging, level=$level%)...")
                    forecast = repo.refresh()
                    dataStatus = DataStatus.Live(System.currentTimeMillis())
                    Log.i(TAG, "Active source forecast refresh successful.")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.i(TAG, "Loop refresh cancelled.")
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "Active source forecast refresh failed: ${e.message}")
                    val isOffline = isOfflineException(e)
                    val reason = if (isOffline) "offline" else "source_error"
                    weatherDao.log("REFRESH_FAIL", "$reason ${e.message}", "WARN")
                    val lastSuccess = weatherDao.getLastSuccessfulFetch(currentConfig?.weatherSource)
                    dataStatus = deriveDataStatus(
                        cachePresent = forecast != null,
                        lastFetchMs = lastSuccess,
                        refreshFailed = true,
                        failureIsOffline = isOffline,
                    )
                }

                // Slower forecast fetch for other APIs (interval also scales with battery)
                val nonActiveSources = allVisible.filter { it != activeSource }
                for (otherSource in nonActiveSources) {
                    try {
                        val lastOtherFetch = weatherDao.getLastSuccessfulFetch(otherSource)
                        val otherDelayMs = DesktopFetchStrategy.getForecastRefreshDelayMs(isCharging, level, isActiveSource = false)
                            ?: continue // Should not happen if primary delay was non-null, but safe.

                        val isDue = lastOtherFetch == null || 
                            (System.currentTimeMillis() - lastOtherFetch) >= otherDelayMs
                        
                        if (isDue) {
                            Log.i(TAG, "Refreshing forecast for non-active source: $otherSource...")
                            val otherService = DesktopWeatherService(
                                currentConfig?.lat ?: DesktopWeatherService.FALLBACK_LATITUDE,
                                currentConfig?.lon ?: DesktopWeatherService.FALLBACK_LONGITUDE,
                                otherSource,
                                currentConfig?.apiKeys ?: emptyMap(),
                                weatherDao
                            )
                            val otherRepo = DesktopWeatherRepository(
                                otherService,
                                weatherDao,
                                currentConfig?.lat ?: DesktopWeatherService.FALLBACK_LATITUDE,
                                currentConfig?.lon ?: DesktopWeatherService.FALLBACK_LONGITUDE,
                                otherSource
                            )
                            otherRepo.refresh()
                            Log.i(TAG, "Non-active source $otherSource forecast refresh successful.")
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
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

        // Surface the popup for any show request. Bumping showRequestId edge-triggers the
        // raise-to-front effect even when the window is already visible (just buried).
        fun requestShowPopup() {
            popupVisible = true
            showRequestId++
        }

        fun quit() {
            // Spawn hard-exit daemon thread first so it runs even if EDT teardown or HTTP close hangs.
            kotlin.concurrent.thread(isDaemon = true, name = "quit-hard-exit") {
                Thread.sleep(400)
                kotlin.system.exitProcess(0)
            }
            desktopClients.close()
            runCatching {
                val myQuitFile = appDataDir().resolve("$QUIT_PREFIX$appLaunchId")
                java.nio.file.Files.deleteIfExists(myQuitFile)
            }
            exitApplication()
        }

        // External show request: the genmon panel click (and any other caller) touches the .show
        // trigger file. We use WatchService (inotify on Linux) to avoid polling every second,
        // allowing the CPU to stay in a lower power state until a click actually happens.
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val dir = appDataDir()
                // Clean up any old .quit files (except our own signature file)
                if (java.nio.file.Files.exists(dir)) {
                    java.nio.file.Files.list(dir).use { paths ->
                        paths.forEach { path ->
                            val name = path.fileName.toString()
                            if (name == QUIT_TRIGGER || (name.startsWith(QUIT_PREFIX) && name != "$QUIT_PREFIX$appLaunchId")) {
                                runCatching { java.nio.file.Files.deleteIfExists(path) }
                            }
                        }
                    }
                }
                val watchService = java.nio.file.FileSystems.getDefault().newWatchService()
                dir.register(
                    watchService,
                    java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                    java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
                )

                try {
                    while (true) {
                        val key = watchService.take() // Blocks until an event occurs
                        for (event in key.pollEvents()) {
                            val name = (event.context() as? java.nio.file.Path)?.toString()
                            if (name != null) {
                                when {
                                    name == ".show" -> requestShowPopup()
                                    name == QUIT_TRIGGER -> {
                                        Log.i(TAG, "Script or manual quit trigger detected. Exiting.")
                                        SwingUtilities.invokeLater { quit() }
                                    }
                                    name.startsWith(QUIT_PREFIX) -> {
                                        val suffix = name.substring(QUIT_PREFIX.length)
                                        if (suffix != appLaunchId) {
                                            Log.i(TAG, "Newer instance detected (launchId=$suffix, mine=$appLaunchId). Exiting.")
                                            SwingUtilities.invokeLater { quit() }
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
        }

        // Dynamic icon showing the current temperature.
        val textMeasurer = remember { createTrayTextMeasurer() }
        val appIcon = remember(forecast?.currentTemp) {
            TemperatureTrayPainter(forecast?.currentTemp, textMeasurer)
        }

        LaunchedEffect(startupSmoke) {
            if (startupSmoke) {
                exitApplication()
            }
        }

        // The Dorkbox SystemTray runs a continuous GTK/X11 event loop (the AWT-XAWT + "GTK Native
        // Event Loop" threads) — the last regular CPU waker once the JVM idle flags are applied.
        // Disable it with EITHER the `--no-tray` launch flag OR WEATHER_DESKTOP_NO_TRAY=1, and rely
        // on the genmon panel (which already shows the temperature and opens the popup via the .show
        // trigger) for display + interaction.
        val trayEnabled = remember {
            System.getenv("WEATHER_DESKTOP_NO_TRAY") != "1" &&
                System.getProperty("weatherwidget.desktop.noTray") != "true"
        }
        if (trayEnabled) {
            TemperatureSystemTray(
                temperature = forecast?.currentTemp,
                dataStatus = dataStatus,
                onShow = { requestShowPopup() },
                onSettings = { settingsVisible = true },
                onStatistics = { statsVisible = true },
                onUpdateLocation = {
                    popupVisible = false
                    pickerVisible = true
                },
                onQuit = ::quit,
            )
        }

        if (statsVisible && currentConfig != null) {
            StatisticsWindow(
                weatherDao = weatherDao,
                config = currentConfig,
                onClose = { statsVisible = false },
            )
        }

        if (observationsVisible && currentConfig != null && repository != null) {
            ObservationsWindow(
                weatherDao = weatherDao,
                repository = repository,
                config = currentConfig,
                onClose = { observationsVisible = false },
                onConfigUpdate = { newConfig ->
                    configStore.save(newConfig)
                    config = newConfig
                }
            )
        }

        if (pickerVisible) {
            val pickerState = rememberWindowState(
                position = WindowPosition(Alignment.Center),
                width = 560.dp,
                height = 680.dp,
            )
            Window(
                onCloseRequest = { pickerVisible = false },
                state = pickerState,
                title = "Set Weather Location",
                icon = appIcon,
            ) {
                LocationPicker(locationResolver) { resolved ->
                    val saved = resolved.toConfig()
                    configStore.save(saved)
                    config = saved
                    pickerVisible = false
                    popupVisible = true
                }
            }
        }

        if (settingsVisible && config != null) {
            val settingsState = rememberWindowState(
                position = WindowPosition(Alignment.Center),
                width = 500.dp,
                height = 700.dp,
            )
            Window(
                onCloseRequest = { settingsVisible = false },
                state = settingsState,
                title = "Weather Settings",
                icon = appIcon,
            ) {
                SettingsWindow(
                    config = config!!,
                    onClose = { settingsVisible = false },
                    onSave = { newConfig ->
                        configStore.save(newConfig)
                        config = newConfig
                    },
                    onExit = { quit() }
                )
            }
        }

        if (popupVisible && currentConfig != null) {
            val windowState = rememberWindowState(
                position = if (currentConfig.windowX != null && currentConfig.windowY != null) {
                    WindowPosition(currentConfig.windowX.dp, currentConfig.windowY.dp)
                } else {
                    WindowPosition(Alignment.TopEnd)
                },
                width = currentConfig.windowWidth?.dp ?: 380.dp,
                height = currentConfig.windowHeight?.dp ?: 320.dp,
            )

            // Persist window position and size changes with a debounce to avoid excessive disk writes.
            LaunchedEffect(windowState.position, windowState.size) {
                kotlinx.coroutines.delay(1000)
                val pos = windowState.position
                if (pos is WindowPosition.Absolute) {
                    val newConfig = currentConfig.copy(
                        windowX = pos.x.value,
                        windowY = pos.y.value,
                        windowWidth = windowState.size.width.value,
                        windowHeight = windowState.size.height.value
                    )
                    if (newConfig != currentConfig) {
                        configStore.save(newConfig)
                        config = newConfig
                    }
                }
            }

            Window(
                onCloseRequest = { popupVisible = false },
                state = windowState,
                title = "Weather Widget",
                icon = appIcon,
            ) {
                LaunchedEffect(Unit) {
                    Log.i(TAG, "Window composed/visible now")
                }
                // Raise an already-open (possibly buried) window on every show request.
                // FrameWindowScope exposes the underlying AWT ComposeWindow as `window`.
                LaunchedEffect(showRequestId) {
                    Log.i(TAG, "Window show request received: showRequestId=$showRequestId")
                    if (windowState.isMinimized) {
                        windowState.isMinimized = false
                    }
                    if (window is java.awt.Frame) {
                        val state = window.extendedState
                        if ((state and java.awt.Frame.ICONIFIED) != 0) {
                            window.extendedState = java.awt.Frame.NORMAL
                        }
                    }
                    window.toFront()
                    window.requestFocus()
                }
                WidgetPopup(
                    config = currentConfig,
                    forecast = forecast,
                    dataStatus = dataStatus,
                    onUpdateLocation = {
                        popupVisible = false
                        pickerVisible = true
                    },
                    onUpdateConfig = { newConfig ->
                        configStore.save(newConfig)
                        config = newConfig
                    },
                    onOpenSettings = {
                        settingsVisible = true
                    },
                    onOpenObservations = {
                        observationsVisible = true
                    }
                )
            }
        }
    }
}

internal fun createTrayTextMeasurer(): TextMeasurer =
    TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultLayoutDirection = LayoutDirection.Ltr,
        defaultDensity = Density(1f),
    )

@Composable
private fun TemperatureSystemTray(
    temperature: Float?,
    dataStatus: DataStatus,
    onShow: () -> Unit,
    onSettings: () -> Unit,
    onStatistics: () -> Unit,
    onUpdateLocation: () -> Unit,
    onQuit: () -> Unit,
) {
    Log.i("Main", "Initializing SystemTray...")
    val tray = remember {
        try {
            SystemTray.get()
        } catch (e: Throwable) {
            // Note: GTK errors often cause a hard abort that try-catch cannot stop,
            // but logging before/after helps isolate the cause.
            Log.e("Main", "Failed to initialize SystemTray: $e")
            null
        }
    }
    if (tray == null) {
        Log.w("Main", "SystemTray is NOT supported or failed to initialize on this system.")
        return
    }

    DisposableEffect(Unit) {
        tray.setImage(createTemperatureTrayImage(temperature))
        tray.setStatus(temperature?.let { formatTrayTemperature(it) + "°" } ?: "Weather Widget")
        
        tray.menu.apply {
            add(TrayMenuItem("Show") { onShow() })
            add(TrayMenuItem("Forecast Accuracy") { onStatistics() })
            add(TrayMenuItem("Settings") { onSettings() })
            add(TrayMenuItem("Update location...") { onUpdateLocation() })
            add(TrayMenuItem("Quit") { onQuit() })
        }

        onDispose {
            tray.shutdown()
            Log.i("Main", "TrayIcon removed from SystemTray.")
        }
    }

    LaunchedEffect(temperature, dataStatus) {
        tray.setImage(createTemperatureTrayImage(temperature))
        tray.setStatus(temperature?.let { formatTrayTemperature(it) + "°" } ?: "Weather Widget")
        val suffix = if (dataStatus is DataStatus.Stale) " (offline)" else ""
        tray.setTooltip(temperature?.let { "Weather Widget: ${formatTrayTemperature(it)}°$suffix" } ?: "Weather Widget")
    }
}

private fun createTemperatureTrayImage(temperature: Float?): BufferedImage {
    val size = 64
    // TYPE_INT_RGB (opaque) so the background is guaranteed not to be white-on-white on tray
    // hosts that drop the alpha channel (some Linux AppIndicator implementations, older
    // Windows shells). A solid black background with yellow text stays legible everywhere.
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

        graphics.color = AwtColor.BLACK
        graphics.fillRect(0, 0, size, size)

        val text = temperature?.let { formatTrayTemperature(it) } ?: "--"
        graphics.color = AwtColor.YELLOW // High contrast yellow

        // We use a large base font size and then scale it to fit the square perfectly.
        // This makes the text "fat" or "squashed" to use every available pixel.
        val baseFontSize = 64
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, baseFontSize)
        val metrics = graphics.fontMetrics
        val textWidth = metrics.stringWidth(text)
        val textHeight = metrics.ascent + metrics.descent

        graphics.translate(size / 2.0, size / 2.0)
        graphics.rotate(Math.toRadians(90.0))

        // Scale the text so it fills the 64x64 square exactly.
        // sideways width (size) / textWidth
        // sideways height (size) / textHeight
        val scaleX = size.toDouble() / textWidth
        val scaleY = size.toDouble() / textHeight
        
        // Apply scaling (limiting to a reasonable max to avoid extreme distortion if text is very short)
        graphics.scale(scaleX.coerceAtMost(2.0), scaleY.coerceAtMost(2.0))

        graphics.drawString(text, -textWidth / 2, (metrics.ascent - metrics.descent) / 2)
    } finally {
        graphics.dispose()
    }
    return image
}

@Composable
internal fun WidgetPopup(
    config: DesktopConfig,
    forecast: ForecastResult?,
    dataStatus: DataStatus,
    onUpdateLocation: () -> Unit,
    onUpdateConfig: (DesktopConfig) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenObservations: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        when (dataStatus) {
            is DataStatus.Error -> CenteredMessage(dataStatus.message)
            is DataStatus.Loading -> CenteredMessage("Loading…")
            is DataStatus.NoData -> CenteredMessage("Tap to configure")
            is DataStatus.Live, is DataStatus.Stale -> {
                val snapshot = forecast ?: return@Surface
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    StatusBar(dataStatus)
                    Spacer(Modifier.height(4.dp))
                    WidgetHeader(
                        config = config,
                        forecast = snapshot,
                        onUpdateConfig = onUpdateConfig,
                        onOpenSettings = onOpenSettings,
                        onOpenObservations = onOpenObservations,
                        onUpdateLocation = onUpdateLocation,
                        showWeatherSummary = config.viewMode == "HOURLY" || config.viewMode == "TEMPERATURE" || config.viewMode == "CLOUD_COVER" || config.viewMode == "PRECIPITATION",
                        headerTime = LocalDateTime.now().plusHours(config.hourlyOffset.toLong()),
                    )

                    Spacer(Modifier.height(8.dp))

                    val isHourly = config.viewMode == "HOURLY" || config.viewMode == "TEMPERATURE" || config.viewMode == "CLOUD_COVER" || config.viewMode == "PRECIPITATION"
                    if (isHourly) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f).testTag("hourly_temperature_surface")) {
                            if (config.viewMode == "CLOUD_COVER") {
                                CloudCoverGraph(
                                    hourly = snapshot.hourly,
                                    displaySourceId = config.weatherSource,
                                    latitude = config.lat,
                                    longitude = config.lon,
                                    modifier = Modifier.fillMaxSize(),
                                    centerOffsetHours = config.hourlyOffset,
                                    zoomLevel = config.zoomLevel,
                                    onViewModeChange = { targetView ->
                                        onUpdateConfig(config.copy(viewMode = targetView))
                                    }
                                )
                            } else if (config.viewMode == "PRECIPITATION") {
                                PrecipitationGraph(
                                    hourly = snapshot.hourly,
                                    observations = snapshot.rawObservations,
                                    displaySourceId = config.weatherSource,
                                    latitude = config.lat,
                                    longitude = config.lon,
                                    modifier = Modifier.fillMaxSize(),
                                    centerOffsetHours = config.hourlyOffset,
                                    zoomLevel = config.zoomLevel,
                                    onViewModeChange = { targetView ->
                                        onUpdateConfig(config.copy(viewMode = targetView))
                                    }
                                )
                            } else {
                                TemperatureGraph(
                                    hourly = snapshot.hourly,
                                    currentTemp = snapshot.currentTemp,
                                    currentObservedAt = snapshot.currentObservedAt,
                                    observations = snapshot.rawObservations,
                                    displaySourceId = config.weatherSource,
                                    latitude = config.lat,
                                    longitude = config.lon,
                                    modifier = Modifier.fillMaxSize(),
                                    centerOffsetHours = config.hourlyOffset,
                                    zoomLevel = config.zoomLevel,
                                    onViewModeChange = { targetView ->
                                        onUpdateConfig(config.copy(viewMode = targetView))
                                    }
                                )
                            }
                            NavArrow(
                                alignment = Alignment.CenterStart,
                                enabled = config.hourlyOffset > MIN_HOURLY_OFFSET,
                                testTag = "hourly_nav_left",
                            ) {
                                onUpdateConfig(config.copy(hourlyOffset = (config.hourlyOffset - HOURLY_NAV_JUMP).coerceAtLeast(MIN_HOURLY_OFFSET)))
                            }
                            NavArrow(
                                alignment = Alignment.CenterEnd,
                                enabled = config.hourlyOffset < MAX_HOURLY_OFFSET,
                                testTag = "hourly_nav_right",
                            ) {
                                onUpdateConfig(config.copy(hourlyOffset = (config.hourlyOffset + HOURLY_NAV_JUMP).coerceAtMost(MAX_HOURLY_OFFSET)))
                            }
                        }
                    } else {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f).testTag("daily_forecast_surface")) {
                            val dimensions = DesktopDailyForecastModel.dimensions(
                                widthDp = maxWidth.value.roundToInt(),
                                heightDp = maxHeight.value.roundToInt(),
                            )
                            val dailyState = DesktopDailyForecastModel.build(
                                config = config,
                                forecast = snapshot,
                                dimensions = dimensions,
                            )

                            LaunchedEffect(dailyState.clampedDateOffset) {
                                if (dailyState.clampedDateOffset != config.dateOffset) {
                                    onUpdateConfig(config.copy(dateOffset = dailyState.clampedDateOffset))
                                }
                            }

                            if (dailyState.dimensions.useGraph) {
                                DailyForecastGraph(
                                    state = dailyState,
                                    modifier = Modifier.fillMaxSize(),
                                    onDayClick = { clickedDate ->
                                        val now = LocalDateTime.now()
                                        val hours = java.time.Duration.between(now, clickedDate.atStartOfDay()).toHours().toInt()
                                        val clickedDay = dailyState.days.find { it.date == clickedDate }
                                        val clickedIcon = clickedDay?.iconCondition
                                        val targetView = clickedIcon?.let { WeatherIcon.resolveIconHome(WeatherIcon.getIconResource(it)) } ?: "HOURLY"
                                        onUpdateConfig(config.copy(viewMode = targetView, hourlyOffset = hours))
                                    }
                                )
                            } else {
                                DailyForecastTextMode(
                                    state = dailyState,
                                    modifier = Modifier.fillMaxSize(),
                                    onDayClick = { clickedDate ->
                                        val now = LocalDateTime.now()
                                        val hours = java.time.Duration.between(now, clickedDate.atStartOfDay()).toHours().toInt()
                                        val clickedDay = dailyState.days.find { it.date == clickedDate }
                                        val clickedIcon = clickedDay?.iconCondition
                                        val targetView = clickedIcon?.let { WeatherIcon.resolveIconHome(WeatherIcon.getIconResource(it)) } ?: "HOURLY"
                                        onUpdateConfig(config.copy(viewMode = targetView, hourlyOffset = hours))
                                    }
                                )
                            }

                            NavArrow(
                                alignment = Alignment.CenterStart,
                                enabled = dailyState.canNavigateLeft,
                                testTag = "daily_nav_left",
                            ) {
                                onUpdateConfig(config.copy(dateOffset = dailyState.clampedDateOffset - 1))
                            }
                            NavArrow(
                                alignment = Alignment.CenterEnd,
                                enabled = dailyState.canNavigateRight,
                                testTag = "daily_nav_right",
                            ) {
                                onUpdateConfig(config.copy(dateOffset = dailyState.clampedDateOffset + 1))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavArrow(
    alignment: Alignment,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = alignment) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.width(28.dp).fillMaxHeight().testTag(testTag),
        ) {
            Icon(
                imageVector = if (alignment == Alignment.CenterStart) {
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = null,
                tint = Color.White.copy(alpha = if (enabled) 0.75f else 0.18f),
            )
        }
    }
}

@Composable
private fun StatusBar(dataStatus: DataStatus) {
    val relativeTime = when (dataStatus) {
        is DataStatus.Live -> formatRelativeTime(dataStatus.updatedAt)
        is DataStatus.Stale -> formatRelativeTime(dataStatus.updatedAt)
        else -> return
    }
    val text = when (dataStatus) {
        is DataStatus.Live -> "Updated $relativeTime"
        is DataStatus.Stale -> when (dataStatus.reason) {
            com.weatherwidget.data.model.StaleReason.OFFLINE -> "Offline — last updated $relativeTime"
            com.weatherwidget.data.model.StaleReason.SOURCE_ERROR -> "Source error — last updated $relativeTime"
        }
        else -> return
    }
    val color = if (dataStatus is DataStatus.Stale) {
        Color(0xFFFFA726) // muted orange/amber
    } else {
        Color.White.copy(alpha = 0.5f)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatRelativeTime(epochMs: Long): String {
    val elapsed = System.currentTimeMillis() - epochMs
    val minutes = elapsed / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}

@Composable
private fun WidgetHeader(
    config: DesktopConfig,
    forecast: ForecastResult,
    onUpdateConfig: (DesktopConfig) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenObservations: () -> Unit,
    onUpdateLocation: () -> Unit,
    showWeatherSummary: Boolean = true,
    headerTime: LocalDateTime = LocalDateTime.now(),
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }
    val targetHour = remember(headerTime) { headerTime.truncatedTo(ChronoUnit.HOURS) }

    val nowEpoch = System.currentTimeMillis()
    val interpolatedForecastTemp = com.weatherwidget.shared.util.TemperatureInterpolator.getInterpolatedTemperature(forecast.hourly, nowEpoch)
    val currentForecastTemp = forecast.currentTemp
    val deltaTemp = if (currentForecastTemp != null && interpolatedForecastTemp != null) {
        val diff = currentForecastTemp - interpolatedForecastTemp
        if (kotlin.math.abs(diff) >= 0.1f) diff else null
    } else null

    val currentHourData = forecast.hourly.find {
        it.dateTime >= nowEpoch - 3_600_000L && it.dateTime <= nowEpoch + 3_600_000L
    }
    val precipProb = currentHourData?.precipProbability?.takeIf { it > 0 }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Top row: current temp/icon (left) | API source / date (right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).clickable { onOpenObservations() }
            ) {
                androidx.compose.foundation.Image(
                    painter = WeatherIcon.painter(forecast.currentCondition),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp).padding(end = 4.dp)
                )
                Text(
                    text = forecast.currentTemp?.let { formatTrayTemperature(it) + "°" } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontSize = 22.sp
                )
                if (deltaTemp != null) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = String.format(Locale.US, "%+.1f", deltaTemp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF6B35),
                        modifier = Modifier.align(Alignment.CenterVertically).offset(y = 2.dp)
                    )
                }
                if (precipProb != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$precipProb%",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF4FC3F7),
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .offset(y = 2.dp)
                            .clickable {
                                onUpdateConfig(config.copy(viewMode = "PRECIPITATION"))
                            }
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                val visibleSources = config.visibleSources
                if (visibleSources.size > 1) {
                    Text(
                        text = config.weatherSource,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 9.sp,
                        modifier = Modifier.clickable {
                            val nextIdx = (visibleSources.indexOf(config.weatherSource) + 1) % visibleSources.size
                            onUpdateConfig(config.copy(weatherSource = visibleSources[nextIdx]))
                        }
                    )
                } else {
                    Text(
                        text = config.weatherSource,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
                Text(
                    text = targetHour.format(dateFormatter),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Bottom row: location + icons (left) | mode chips (right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = config.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    modifier = Modifier.clickable { onUpdateLocation() }
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    painter = androidx.compose.ui.res.painterResource("drawable/ic_thermometer.xml"),
                    contentDescription = "Stations",
                    modifier = Modifier.size(13.dp).clickable { onOpenObservations() },
                    tint = Color.White.copy(alpha = 0.7f)
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    painter = androidx.compose.ui.res.painterResource("drawable/ic_settings_gear.xml"),
                    contentDescription = "Settings",
                    modifier = Modifier.size(13.dp).clickable { onOpenSettings() },
                    tint = Color.White.copy(alpha = 0.7f)
                )
                val isHourly = config.viewMode == "HOURLY" || config.viewMode == "TEMPERATURE" || config.viewMode == "CLOUD_COVER" || config.viewMode == "PRECIPITATION"
                if (isHourly) {
                    Spacer(Modifier.width(6.dp))
                    val isCloud = config.viewMode == "CLOUD_COVER"
                    val isPrecip = config.viewMode == "PRECIPITATION"
                    val emoji = if (isCloud || isPrecip) "🌡️" else "☁️"
                    Text(
                        text = emoji,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable {
                            val nextMode = if (isCloud || isPrecip) "HOURLY" else "CLOUD_COVER"
                            onUpdateConfig(config.copy(viewMode = nextMode))
                        }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val isHourly = config.viewMode == "HOURLY" || config.viewMode == "TEMPERATURE" || config.viewMode == "CLOUD_COVER" || config.viewMode == "PRECIPITATION"
                if (isHourly) {
                    ViewModeChip(config.zoomLevel.take(1), true) {
                        val nextZoom = if (config.zoomLevel == "WIDE") "NARROW" else "WIDE"
                        onUpdateConfig(config.copy(zoomLevel = nextZoom))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                ViewModeChip("H", isHourly) {
                    onUpdateConfig(config.copy(viewMode = "HOURLY"))
                }
                ViewModeChip("D", config.viewMode == "DAILY") {
                    onUpdateConfig(config.copy(viewMode = "DAILY"))
                }
            }
        }
    }
}

@Composable
private fun DailyForecastTextMode(
    state: DesktopDailyViewState,
    modifier: Modifier = Modifier,
    onDayClick: (LocalDate) -> Unit = {},
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        state.days.forEach { day ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onDayClick(day.date) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = day.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (day.isToday) Color.Yellow else Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                )
                val high = listOfNotNull(day.solidHigh, day.forecastHigh, day.snapshotHigh).maxOrNull()
                val low = listOfNotNull(day.solidLow, day.forecastLow, day.snapshotLow).minOrNull()
                Text(
                    text = high?.roundToInt()?.let { "$it°" } ?: "--",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                )
                if (state.dimensions.cols >= 2) {
                    Text(
                        text = low?.roundToInt()?.let { "$it°" } ?: "--",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.62f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.35f),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

private class DesktopClients {
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

@Composable
private fun CenteredMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val FRESHNESS_THRESHOLD_MS = 10 * 60 * 1000L
private const val MIN_REFRESH_DELAY_MS = 10 * 60 * 1000L
private const val DEFAULT_REFRESH_DELAY_MS = 15 * 60 * 1000L

/** Cheap, network-free re-interpolation of the displayed current temp. */
private const val CURRENT_TEMP_UI_INTERVAL_MS = 2 * 60 * 1000L

/** How often to re-check the power state when fetching is suspended due to low battery. */
private const val SUSPEND_RECHECK_INTERVAL_MS = 5 * 60 * 1000L

internal enum class LaunchRefreshAction {
    FULL_FORECAST,
    OBSERVATIONS,
    NONE,
}

internal fun determineLaunchRefreshAction(
    cachePresent: Boolean,
    lastObservationFetchMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
): LaunchRefreshAction {
    if (!cachePresent) return LaunchRefreshAction.FULL_FORECAST
    val observationsAreFresh = lastObservationFetchMs != null &&
        (nowMs - lastObservationFetchMs) < FRESHNESS_THRESHOLD_MS
    return if (observationsAreFresh) LaunchRefreshAction.NONE else LaunchRefreshAction.OBSERVATIONS
}

internal fun computeRefreshDelayMs(hourly: List<com.weatherwidget.data.model.HourlyForecast>?): Long {
    if (hourly.isNullOrEmpty()) return DEFAULT_REFRESH_DELAY_MS
    val updatesPerHour = TemperatureInterpolator.getUpdatesPerHour(hourly)
    val intervalMs = (3600_000L / updatesPerHour).coerceAtLeast(MIN_REFRESH_DELAY_MS)
    return intervalMs
}
