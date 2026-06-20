package com.weatherwidget.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.deriveDataStatus
import com.weatherwidget.data.model.isOfflineException
import com.weatherwidget.shared.config.ForecastHorizon
import com.weatherwidget.shared.graph.ZoomStage
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

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.math.roundToInt


/**
 * Desktop entry point. System-tray icon + a small frameless popup — the Linux-desktop analogue of
 * the Android home-screen widget.
 */
private const val APP_PACKAGE = "weather-widget-desktop"
private const val TAG = "Main"
/** 30 days × 24 hours = 720 hours. Maximum pannable depth into the past. */
private const val MIN_HOURLY_OFFSET = -720
/** 30 days × 24 hours = 720 hours. Maximum pannable depth into the future. */
private const val MAX_HOURLY_OFFSET = 720

/**
 * Whole-hour offset from now to the center of a full-day hourly window for [clickedDate]. The hourly
 * graphs render a window of `[center - backHours, center + forwardHours]` around `now + hourlyOffset`.
 * Anchoring `center = startOfDay + backHours` (with the day-view zoom whose back+forward ≈ 24h) makes
 * the window's left edge land on the day's midnight, so it frames the clicked day midnight→midnight.
 * Rounded to the nearest hour (not truncated) so the temperature graph's hour-alignment lands on the
 * intended center regardless of the current minute-of-hour.
 */
private fun offsetToDayCenter(clickedDate: LocalDate, backHours: Int): Int {
    val center = clickedDate.atStartOfDay().plusHours(backHours.toLong())
    val minutes = java.time.Duration.between(LocalDateTime.now(), center).toMinutes()
    return Math.round(minutes / 60.0).toInt()
}

/**
 * Config for opening the hourly view focused on [clickedDate]: frames the clicked day as a full
 * 24-hour window (midnight→midnight) by selecting the day-view zoom and anchoring the center so the
 * window's left edge is the day's midnight. Deliberately ignores whatever zoom the hourly graph was
 * last left at, so a clicked day always opens at a consistent full-day framing.
 */
internal fun dayClickConfig(
    config: DesktopConfig,
    clickedDate: LocalDate,
    days: List<DesktopDailyDay>,
): DesktopConfig {
    val zoom = DesktopGraphUtils.dayViewZoomFactor
    val hours = offsetToDayCenter(clickedDate, DesktopGraphUtils.backHoursFor(zoom))
    // Route on the resolved+gated icon name (matches the displayed icon), not the raw condition.
    val clickedIconName = days.find { it.date == clickedDate }?.iconName
    val targetView = clickedIconName
        ?.let { WeatherIcon.resolveIconHome("drawable/$it.xml") } ?: ViewMode.HOURLY
    return config.copy(
        viewMode = targetView,
        hourlyOffset = hours,
        zoomFactor = zoom,
    )
}

fun main(args: Array<String>) {
    // Surface shared-module diagnostics on the console (default JulSink drops DEBUG). First thing so
    // even startup logging from :shared is visible.
    Log.install(DesktopLogSink)
    val isUiMode = args.contains("--ui") || args.contains("ui") || args.contains("--show") || args.contains("show")
    if (System.getProperty("weatherwidget.desktop.startupSmoke") == "true") {
        if (isUiMode) {
            runApp()
        }
        return
    }
    if (isUiMode) {
        Thread.currentThread().name = "WeatherUI"
        Log.i("Main", "Starting WeatherUI process...")
        if (args.contains("--show")) {
            System.setProperty("weatherwidget.desktop.show", "true")
        }
        runApp()
    } else {
        runDaemon()
    }
}

/** Oldest timestamp present in a loaded forecast (oldest observation or hourly point), or null. */
private fun oldestLoadedMs(f: ForecastResult): Long? =
    listOfNotNull(
        f.rawObservations.minOfOrNull { it.timestamp },
        f.hourly.minOfOrNull { it.dateTime },
    ).minOrNull()

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

        remember(weatherDao) {
            com.weatherwidget.widget.CurrentTemperatureResolver.dbLogger = { tag, message, level ->
                weatherDao.log(tag, message, level)
            }
        }

        var popupVisible by remember { mutableStateOf(config != null) }
        // Edge-triggered show counter: a boolean can't re-fire an effect when it's already
        // true, so bump this on every show request to reliably raise an already-open window.
        var showRequestId by remember { mutableStateOf(0) }

        LaunchedEffect(config) {
            Log.i(TAG, "config loaded: config != null is ${config != null}")
        }
        var pickerVisible by remember { mutableStateOf(config == null) }
        var settingsVisible by remember { mutableStateOf(false) }
        var statsVisible by remember { mutableStateOf(false) }
        var historyVisible by remember { mutableStateOf(false) }
        var observationsVisible by remember { mutableStateOf(false) }
        var obsShowRequestId by remember { mutableStateOf(0) }
        var appLogsVisible by remember { mutableStateOf(false) }
        // Registered by WidgetPopup for whichever view is active (daily/hourly); the popup Window forwards
        // ←/→ here. Returns true when the key was consumed (so Escape/default handling stays intact).
        var arrowKeyHandler by remember { mutableStateOf<((left: Boolean) -> Boolean)?>(null) }
        val desktopClients = remember { DesktopClients() }
        val locationResolver = remember {
            val sharedLocationResolver = com.weatherwidget.data.repository.SharedLocationResolver(
                nominatimApi = NominatimApi(desktopClients.httpClient, desktopClients.json),
                ipGeolocationApi = IpGeolocationApi(desktopClients.httpClient, desktopClients.json),
            )
            LocationResolver(
                phoneLocator = PhoneLocator(),
                timezoneLocator = TimezoneLocator(),
                sharedLocationResolver = sharedLocationResolver,
            )
        }

        var forecast by remember { mutableStateOf<ForecastResult?>(null) }
        var dataStatus by remember { mutableStateOf<DataStatus>(DataStatus.Loading) }
        // Transient "Fetching older data…" banner shown while an on-demand deep-history pull runs.
        var historyFetchToast by remember { mutableStateOf<String?>(null) }
        val uiScope = rememberCoroutineScope()
        val currentConfig = config

        val weatherService = remember(currentConfig?.lat, currentConfig?.lon, currentConfig?.weatherSource, currentConfig?.apiKeys) {
            currentConfig?.let {
                DesktopWeatherService(it.lat, it.lon, it.weatherSource, it.apiKeys, weatherDao)
            } ?: DesktopWeatherService(currentConfig)
        }
        val repository = remember(weatherService, currentConfig?.lat, currentConfig?.lon, currentConfig?.weatherSource, currentConfig?.personalStationDiscount) {
            currentConfig?.let {
                DesktopWeatherRepository(weatherService, weatherDao, it.lat, it.lon, it.weatherSource, it.personalStationWeight())
            }
        }

        // Helper to save config and notify the daemon
        val saveConfigAndNotify = remember {
            { newConfig: DesktopConfig ->
                configStore.save(newConfig)
                config = newConfig
                runCatching {
                    val trigger = appDataDir().resolve(CONFIG_CHANGED_TRIGGER)
                    java.nio.file.Files.writeString(trigger, "", java.nio.charset.StandardCharsets.UTF_8)
                }
            }
        }

        // On-demand deep-history pull: fired by WidgetPopup when the hourly graph is zoomed/panned
        // past cached data. Runs in this UI process's own repository (no daemon IPC); on success it
        // reloads the cache so the graph extends. The in-flight flag + repository's own depth guard
        // keep rapid zoom ticks from stacking fetches; needsDeeperHistory avoids flashing the toast
        // when the requested span is already covered.
        var historyFetchInFlight by remember { mutableStateOf(false) }
        val onNeedHistory: (Int) -> Unit = remember(repository) {
            fn@{ neededBackHours: Int ->
                val repo = repository ?: return@fn
                if (historyFetchInFlight || !repo.needsDeeperHistory(neededBackHours)) return@fn
                historyFetchInFlight = true
                val oldestBefore = forecast?.let { oldestLoadedMs(it) }
                historyFetchToast = "Fetching older data…"
                uiScope.launch {
                    try {
                        val fetched = repo.ensureHistory(neededBackHours)
                        if (fetched) repo.loadCached()?.let { forecast = it }
                        // The DB already holds all retained history (loadCached reads the full window),
                        // and an on-demand fetch can only add RECENT obs (NWS serves ~7 days), never
                        // older. So if the oldest loaded point didn't move further back, there is
                        // genuinely no older data — tell the user that instead of implying a fetch.
                        val oldestAfter = forecast?.let { oldestLoadedMs(it) }
                        val extended = oldestAfter != null && oldestBefore != null && oldestAfter < oldestBefore
                        historyFetchToast = if (extended) null else "Reached end of stored history"
                    } catch (e: Exception) {
                        Log.e(TAG, "On-demand history fetch failed: ${e.message}")
                        historyFetchToast = "Couldn't load older data"
                    } finally {
                        historyFetchInFlight = false
                    }
                }
            }
        }
        // On-demand forecast extension: fired by WidgetPopup when the daily view's rightmost visible
        // day moves past what the baseline (8-day) fetch covers. Unlike deep history we don't widen
        // incrementally — we fetch straight to ForecastHorizon.MAX_DAYS once, which unlocks all
        // further forward navigation. The repository's own widest-horizon guard (needsWiderForecast)
        // makes this a no-op once the extended batch has landed, so repeated pans don't re-fetch.
        var forecastExtendInFlight by remember { mutableStateOf(false) }
        val onNeedForecastExtension: (LocalDate) -> Unit = remember(repository) {
            fn@{ rightmostVisible: LocalDate ->
                val repo = repository ?: return@fn
                val needed = ForecastHorizon.daysToCover(LocalDate.now(), rightmostVisible)
                if (forecastExtendInFlight || !repo.needsWiderForecast(needed)) return@fn
                forecastExtendInFlight = true
                uiScope.launch {
                    try {
                        if (repo.ensureForecastDays(ForecastHorizon.MAX_DAYS)) {
                            repo.loadCached()?.let { forecast = it }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "On-demand forecast extension failed: ${e.message}")
                    } finally {
                        forecastExtendInFlight = false
                    }
                }
            }
        }

        // Auto-dismiss the transient end-of-history / failure messages (a successful extend clears the
        // toast immediately).
        LaunchedEffect(historyFetchToast) {
            if (historyFetchToast == "Couldn't load older data" || historyFetchToast == "Reached end of stored history") {
                kotlinx.coroutines.delay(3000)
                historyFetchToast = null
            }
        }

        // Exit on close logic:
        val anyWindowOpen = popupVisible || pickerVisible || settingsVisible || statsVisible || historyVisible || observationsVisible || appLogsVisible
        LaunchedEffect(anyWindowOpen) {
            if (!anyWindowOpen) {
                Log.i(TAG, "All windows closed. Ephemeral UI process exiting...")
                // Grace period for Compose/EDT teardown before hard exit.
                kotlin.concurrent.thread(isDaemon = true, name = "quit-hard-exit") {
                    Thread.sleep(400)
                    kotlin.system.exitProcess(0)
                }
                desktopClients.close()
                exitApplication()
            }
        }

        // Load cached forecast data once and start the re-interpolation loop
        LaunchedEffect(repository) {
            val repo = repository ?: return@LaunchedEffect
            try {
                Log.i(TAG, "Loading cached data...")
                val cached = repo.loadCached()
                if (cached != null) {
                    forecast = cached
                    val lastFetch = weatherDao.getLastSuccessfulFetch(currentConfig?.weatherSource)
                    dataStatus = DataStatus.Live(lastFetch ?: System.currentTimeMillis())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load initial cache: ${e.message}")
            }

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

        // Surface the popup for any show request. Bumping showRequestId edge-triggers the
        // raise-to-front effect even when the window is already visible (just buried).
        fun requestShowPopup() {
            popupVisible = true
            showRequestId++
        }

        fun quit() {
            // Signal daemon to quit first
            runCatching {
                val quitFile = appDataDir().resolve(QUIT_TRIGGER)
                java.nio.file.Files.writeString(quitFile, "", java.nio.charset.StandardCharsets.UTF_8)
            }
            // Spawn hard-exit daemon thread first so it runs even if EDT teardown or HTTP close hangs.
            kotlin.concurrent.thread(isDaemon = true, name = "quit-hard-exit") {
                Thread.sleep(400)
                kotlin.system.exitProcess(0)
            }
            desktopClients.close()
            exitApplication()
        }

        // Watch for external show request (specifically on .ui-show)
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val dir = appDataDir()
                java.nio.file.Files.createDirectories(dir)
                runCatching { java.nio.file.Files.deleteIfExists(dir.resolve(UI_SHOW_TRIGGER)) }
                
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
                            if (name == UI_SHOW_TRIGGER) {
                                Log.i(TAG, "WatchService: .ui-show trigger detected. Bumping showRequestId.")
                                runCatching { java.nio.file.Files.deleteIfExists(dir.resolve(UI_SHOW_TRIGGER)) }
                                SwingUtilities.invokeLater { requestShowPopup() }
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

        if (statsVisible && currentConfig != null) {
            StatisticsWindow(
                weatherDao = weatherDao,
                config = currentConfig,
                onClose = { statsVisible = false },
            )
        }

        if (historyVisible && currentConfig != null) {
            ForecastHistoryWindow(
                weatherDao = weatherDao,
                config = currentConfig,
                onClose = { historyVisible = false },
                onConfigUpdate = { newConfig -> saveConfigAndNotify(newConfig) },
            )
        }

        if (observationsVisible && currentConfig != null && repository != null) {
            ObservationsWindow(
                weatherDao = weatherDao,
                repository = repository,
                config = currentConfig,
                showRequestId = obsShowRequestId,
                onClose = { observationsVisible = false },
                onConfigUpdate = { newConfig ->
                    saveConfigAndNotify(newConfig)
                }
            )
        }

        if (appLogsVisible) {
            AppLogsWindow(
                weatherDao = weatherDao,
                onClose = { appLogsVisible = false }
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
                onKeyEvent = { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                        pickerVisible = false
                        true
                    } else {
                        false
                    }
                }
            ) {
                LocationPicker(locationResolver, allowAutoSelect = config == null) { resolved ->
                    val saved = resolved.toConfig()
                    saveConfigAndNotify(saved)
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
                onKeyEvent = { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                        settingsVisible = false
                        true
                    } else {
                        false
                    }
                }
            ) {
                SettingsWindow(
                    config = config!!, // guarded by `config != null` in outer if
                    onClose = { settingsVisible = false },
                    onSave = { newConfig ->
                        saveConfigAndNotify(newConfig)
                    },
                    onExit = { quit() },
                    onUpdateLocation = {
                        pickerVisible = true
                    },
                    onOpenObservations = {
                        observationsVisible = true
                        obsShowRequestId++
                    },
                    onRefreshData = {
                        repository?.let { forecast = it.refresh() }
                    },
                    onViewAppLogs = {
                        appLogsVisible = true
                    }
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
                val latestConfig = config ?: return@LaunchedEffect
                if (pos is WindowPosition.Absolute) {
                    val newConfig = latestConfig.copy(
                        windowX = pos.x.value,
                        windowY = pos.y.value,
                        windowWidth = windowState.size.width.value,
                        windowHeight = windowState.size.height.value
                    )
                    if (newConfig != latestConfig) {
                        saveConfigAndNotify(newConfig)
                    }
                }
            }

            Window(
                onCloseRequest = { popupVisible = false },
                state = windowState,
                title = "Weather Widget",
                icon = appIcon,
                onKeyEvent = { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) {
                        false
                    } else when (keyEvent.key) {
                        Key.Escape -> { popupVisible = false; true }
                        Key.DirectionLeft -> arrowKeyHandler?.invoke(true) ?: false
                        Key.DirectionRight -> arrowKeyHandler?.invoke(false) ?: false
                        else -> false
                    }
                }
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
                        saveConfigAndNotify(newConfig)
                    },
                    onOpenSettings = {
                        settingsVisible = true
                    },
                    onOpenObservations = {
                        observationsVisible = true
                        obsShowRequestId++
                    },
                    onOpenHistory = {
                        historyVisible = true
                    },
                    onRegisterArrowKeyHandler = { arrowKeyHandler = it },
                    onNeedHistory = onNeedHistory,
                    onNeedForecastExtension = onNeedForecastExtension,
                    historyFetchToast = historyFetchToast,
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
internal fun WidgetPopup(
    config: DesktopConfig,
    forecast: ForecastResult?,
    dataStatus: DataStatus,
    onUpdateLocation: () -> Unit,
    onUpdateConfig: (DesktopConfig) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenObservations: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onRegisterArrowKeyHandler: (((left: Boolean) -> Boolean)?) -> Unit = {},
    onNeedHistory: (Int) -> Unit = {},
    onNeedForecastExtension: (LocalDate) -> Unit = {},
    historyFetchToast: String? = null,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      // One shared scale for header + graph so everything grows together with the window.
      // Density-independent (maxHeight and 320.dp both carry density). ~2x at a typical window.
      val uiScale = (maxHeight / 320.dp).coerceIn(1f, 3f)
      Surface(modifier = Modifier.fillMaxSize()) {
        when (dataStatus) {
            is DataStatus.Error -> CenteredMessage(dataStatus.message)
            is DataStatus.Loading -> CenteredMessage("Loading…")
            is DataStatus.NoData -> CenteredMessage("Tap to configure")
            is DataStatus.Live, is DataStatus.Stale -> {
                val snapshot = forecast ?: return@Surface
                Column(modifier = Modifier.fillMaxSize().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 2.dp)) {
                    WidgetHeader(
                        config = config,
                        forecast = snapshot,
                        onUpdateConfig = onUpdateConfig,
                        onOpenSettings = onOpenSettings,
                        onOpenObservations = onOpenObservations,
                        onOpenHistory = onOpenHistory,
                        onUpdateLocation = onUpdateLocation,
                        headerTime = LocalDateTime.now().plusHours(config.hourlyOffset.toLong()),
                        scale = uiScale,
                    )

                    Spacer(Modifier.height(4.dp))

                    val isHourly = config.viewMode.isHourly
                    if (isHourly) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f).testTag("hourly_temperature_surface")) {
                            // Shared scroll-zoom + drag-pan handlers for all three hourly graphs.
                            val handleZoomScroll: (Float, Int) -> Unit = { deltaZoom, cursorOffset ->
                                val newFactor = (config.zoomFactor + deltaZoom).coerceIn(0f, 1f)
                                if (newFactor != config.zoomFactor) {
                                    // Zooming in re-centers on the cursor; zooming out keeps the current center.
                                    val newOffset = if (deltaZoom < 0f) {
                                        cursorOffset.coerceIn(MIN_HOURLY_OFFSET, MAX_HOURLY_OFFSET)
                                    } else {
                                        config.hourlyOffset
                                    }
                                    onUpdateConfig(config.copy(zoomFactor = newFactor, hourlyOffset = newOffset))
                                }
                            }
                            val handlePan: (Int) -> Unit = { deltaHours ->
                                val newOffset = (config.hourlyOffset + deltaHours).coerceIn(MIN_HOURLY_OFFSET, MAX_HOURLY_OFFSET)
                                if (newOffset != config.hourlyOffset) {
                                    onUpdateConfig(config.copy(hourlyOffset = newOffset))
                                }
                            }
                            // Body-tap zoom toggle, shared by all three hourly graphs: cycle the 3 zoom
                            // stages (WIDE→NARROW→THREE_DAY→WIDE), matching Android, and re-center on the
                            // tapped hour. The wheel may have moved us off a stage, so snap to the nearest
                            // one before advancing.
                            val handleToggleZoom: (Int) -> Unit = { clickedOffset ->
                                val current = ZoomStage.nearestByTotalSpan(
                                    DesktopGraphUtils.totalSpanHoursFor(config.zoomFactor)
                                )
                                val next = current.next()
                                onUpdateConfig(
                                    config.copy(
                                        zoomFactor = DesktopGraphUtils.zoomFactorForStage(next),
                                        hourlyOffset = clickedOffset.coerceIn(MIN_HOURLY_OFFSET, MAX_HOURLY_OFFSET),
                                    )
                                )
                            }
                            // ←/→ pan the hourly window by the same nav-jump the arrow buttons use.
                            SideEffect {
                                onRegisterArrowKeyHandler { left ->
                                    val jump = DesktopGraphUtils.navJumpHours(config.zoomFactor)
                                    if (left && config.hourlyOffset > MIN_HOURLY_OFFSET) {
                                        handlePan(-jump); true
                                    } else if (!left && config.hourlyOffset < MAX_HOURLY_OFFSET) {
                                        handlePan(jump); true
                                    } else false
                                }
                            }
                            // Whenever zoom or pan changes, ask for deeper history if the left edge of the
                            // visible window now reaches further back than what's cached. The offset is
                            // negative when panned into the past, so subtracting it extends the reach.
                            LaunchedEffect(config.zoomFactor, config.hourlyOffset) {
                                val earliestVisibleHoursBack =
                                    DesktopGraphUtils.backHoursFor(config.zoomFactor) - config.hourlyOffset
                                onNeedHistory(earliestVisibleHoursBack)
                            }
                            if (config.viewMode == ViewMode.CLOUD_COVER) {
                                CloudCoverGraph(
                                    hourly = snapshot.hourly,
                                    displaySourceId = config.weatherSource,
                                    latitude = config.lat,
                                    longitude = config.lon,
                                    modifier = Modifier.fillMaxSize(),
                                    centerOffsetHours = config.hourlyOffset,
                                    zoomFactor = config.zoomFactor,
                                    scale = uiScale,
                                    onViewModeChange = { targetView ->
                                        onUpdateConfig(config.copy(viewMode = targetView))
                                    },
                                    onToggleZoom = handleToggleZoom,
                                    onZoomScroll = handleZoomScroll,
                                    onPan = handlePan,
                                )
                            } else if (config.viewMode == ViewMode.PRECIPITATION) {
                                PrecipitationGraph(
                                    hourly = snapshot.hourly,
                                    observations = snapshot.rawObservations,
                                    displaySourceId = config.weatherSource,
                                    latitude = config.lat,
                                    longitude = config.lon,
                                    modifier = Modifier.fillMaxSize(),
                                    centerOffsetHours = config.hourlyOffset,
                                    zoomFactor = config.zoomFactor,
                                    scale = uiScale,
                                    onViewModeChange = { targetView ->
                                        onUpdateConfig(config.copy(viewMode = targetView))
                                    },
                                    onToggleZoom = handleToggleZoom,
                                    onZoomScroll = handleZoomScroll,
                                    onPan = handlePan,
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
                                    zoomFactor = config.zoomFactor,
                                    scale = uiScale,
                                    personalStationWeight = config.personalStationWeight(),
                                    onViewModeChange = { targetView ->
                                        onUpdateConfig(config.copy(viewMode = targetView))
                                    },
                                    onToggleZoom = handleToggleZoom,
                                    onZoomScroll = handleZoomScroll,
                                    onPan = handlePan,
                                )
                            }
                            NavArrow(
                                alignment = Alignment.CenterStart,
                                enabled = config.hourlyOffset > MIN_HOURLY_OFFSET,
                                testTag = "hourly_nav_left",
                            ) {
                                val jump = DesktopGraphUtils.navJumpHours(config.zoomFactor)
                                val newOffset = (config.hourlyOffset - jump).coerceAtLeast(MIN_HOURLY_OFFSET)
                                Log.d(TAG, "HourlyNav: left jump=${-jump}h zoom=${config.zoomFactor} offset ${config.hourlyOffset}->$newOffset")
                                onUpdateConfig(config.copy(hourlyOffset = newOffset))
                            }
                            NavArrow(
                                alignment = Alignment.CenterEnd,
                                enabled = config.hourlyOffset < MAX_HOURLY_OFFSET,
                                testTag = "hourly_nav_right",
                            ) {
                                val jump = DesktopGraphUtils.navJumpHours(config.zoomFactor)
                                val newOffset = (config.hourlyOffset + jump).coerceAtMost(MAX_HOURLY_OFFSET)
                                Log.d(TAG, "HourlyNav: right jump=+${jump}h zoom=${config.zoomFactor} offset ${config.hourlyOffset}->$newOffset")
                                onUpdateConfig(config.copy(hourlyOffset = newOffset))
                            }
                            // Transient banner while an on-demand deep-history pull is in flight (or
                            // briefly on failure). Drawn last so it floats over the graph + arrows.
                            historyFetchToast?.let { msg ->
                                Surface(
                                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.Black.copy(alpha = 0.72f),
                                ) {
                                    Text(
                                        text = msg,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        color = Color.White,
                                        fontSize = (12f * uiScale).sp,
                                    )
                                }
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

                            // When the rightmost visible day moves past the baseline forecast horizon,
                            // ask for an on-demand extension to the full Open-Meteo window. Keyed on the
                            // date so it fires once per new edge (pan, arrow, or initial load) and the
                            // repository's widest-horizon guard no-ops it once the wider batch is in.
                            val rightmostVisibleDate = dailyState.days.lastOrNull()?.date
                            LaunchedEffect(rightmostVisibleDate) {
                                rightmostVisibleDate?.let(onNeedForecastExtension)
                            }

                            // Sync both clamped values in one write so a simultaneous offset+zoom clamp
                            // doesn't clobber each other (two separate copy() calls off the same config would).
                            LaunchedEffect(dailyState.clampedDateOffset, dailyState.clampedExtraHistory) {
                                if (dailyState.clampedDateOffset != config.dateOffset ||
                                    dailyState.clampedExtraHistory != config.dailyExtraHistory) {
                                    onUpdateConfig(config.copy(
                                        dateOffset = dailyState.clampedDateOffset,
                                        dailyExtraHistory = dailyState.clampedExtraHistory,
                                    ))
                                }
                            }

                            // Snap-step horizontal drag → day offset; direction-gated by the same bounds
                            // the nav arrows use. A fast flick may emit >1 step; over-panning a column or
                            // two is harmless (model tolerates empty edge columns) and self-heals next gesture.
                            val handleDailyPan: (Int) -> Unit = { steps ->
                                val blocked = (steps < 0 && !dailyState.canNavigateLeft) ||
                                    (steps > 0 && !dailyState.canNavigateRight)
                                if (!blocked) {
                                    val target = dailyState.clampedDateOffset + steps
                                    if (target != config.dateOffset) onUpdateConfig(config.copy(dateOffset = target))
                                }
                            }
                            // Scroll-wheel zoom → extra history days; clamped to model-computed bounds.
                            val handleDailyZoom: (Int) -> Unit = { delta ->
                                val blocked = (delta > 0 && !dailyState.canZoomOut) ||
                                    (delta < 0 && !dailyState.canZoomIn)
                                if (!blocked) {
                                    val target = (dailyState.clampedExtraHistory + delta).coerceAtLeast(0)
                                    if (target != config.dailyExtraHistory) {
                                        onUpdateConfig(config.copy(dailyExtraHistory = target))
                                    }
                                }
                            }
                            val dailyInput = Modifier.dailyPanZoomInput(
                                columnCount = dailyState.days.size,
                                onPanDays = handleDailyPan,
                                onZoomScroll = handleDailyZoom,
                            )
                            // ←/→ step one day, gated by the same data bounds as the nav arrows.
                            SideEffect {
                                onRegisterArrowKeyHandler { left ->
                                    if (left && dailyState.canNavigateLeft) {
                                        handleDailyPan(-1); true
                                    } else if (!left && dailyState.canNavigateRight) {
                                        handleDailyPan(1); true
                                    } else false
                                }
                            }

                            if (dailyState.dimensions.useGraph) {
                                DailyForecastGraph(
                                    state = dailyState,
                                    modifier = Modifier.fillMaxSize().then(dailyInput),
                                    scale = uiScale,
                                    onDayClick = { clickedDate ->
                                        onUpdateConfig(dayClickConfig(config, clickedDate, dailyState.days))
                                    }
                                )
                            } else {
                                DailyForecastTextMode(
                                    state = dailyState,
                                    modifier = Modifier.fillMaxSize().then(dailyInput),
                                    onDayClick = { clickedDate ->
                                        onUpdateConfig(dayClickConfig(config, clickedDate, dailyState.days))
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
private fun WidgetHeader(
    config: DesktopConfig,
    forecast: ForecastResult,
    onUpdateConfig: (DesktopConfig) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenObservations: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onUpdateLocation: () -> Unit,
    headerTime: LocalDateTime = LocalDateTime.now(),
    scale: Float = 1f,
) {
    val showWeatherSummary = config.viewMode.isHourly
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }
    val targetHour = remember(headerTime) { headerTime.truncatedTo(ChronoUnit.HOURS) }

    val nowEpoch = System.currentTimeMillis()
    val zoneId = remember { ZoneId.systemDefault() }
    val nowLocal = remember(nowEpoch, zoneId) {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(nowEpoch), zoneId)
    }
    val displaySource = remember(config.weatherSource) {
        WeatherSource.fromDisplaySource(config.weatherSource)
    }
    val displayTemp = forecast.currentTemp
    val deltaTemp = forecast.appliedDelta?.takeIf { kotlin.math.abs(it) >= 0.1f }

    val currentHourData = forecast.hourly.find {
        it.dateTime >= nowEpoch - 3_600_000L && it.dateTime <= nowEpoch + 3_600_000L
    }
    val precipProb = currentHourData?.precipProbability?.takeIf { it > 0 }
    val isHourly = config.viewMode.isHourly

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left cluster: current temp/icon clickable to toggle view
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        val targetMode = if (isHourly) ViewMode.DAILY else ViewMode.HOURLY
                        onUpdateConfig(config.copy(viewMode = targetMode))
                    }.testTag("current_temp_toggle")
                ) {
                    androidx.compose.foundation.Image(
                        painter = WeatherIcon.painter(forecast.currentCondition),
                        contentDescription = null,
                        modifier = Modifier.size((22 * scale).dp).padding(end = 4.dp)
                    )
                    Text(
                        text = displayTemp?.let { formatTrayTemperature(it) + "°" } ?: "—",
                        style = MaterialTheme.typography.displaySmall,
                        fontSize = (15 * scale).sp
                    )
                }
                if (deltaTemp != null) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = String.format(Locale.US, "%+.1f", deltaTemp),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = (11 * scale).sp,
                        color = Color(0xFFFF6B35),
                        modifier = Modifier.align(Alignment.CenterVertically).offset(y = 2.dp)
                    )
                }
                if (precipProb != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$precipProb%",
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = (12 * scale).sp,
                        color = Color(0xFF4FC3F7),
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .offset(y = 2.dp)
                            .clickable {
                                onUpdateConfig(config.copy(viewMode = ViewMode.PRECIPITATION))
                            }
                    )
                }
            }

            // Center cluster: view-switch icons when hourly, else date text
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isHourly) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((8 * scale).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Cycling graph selector
                        val currentView = config.viewMode
                        val (nextEmoji, nextView) = when (currentView) {
                            ViewMode.CLOUD_COVER -> "🌧️" to ViewMode.PRECIPITATION
                            ViewMode.PRECIPITATION -> "🌡️" to ViewMode.HOURLY
                            else -> "☁️" to ViewMode.CLOUD_COVER
                        }
                        Text(
                            text = nextEmoji,
                            fontSize = (13 * scale).sp,
                            color = Color.White,
                            modifier = Modifier.clickable {
                                onUpdateConfig(config.copy(viewMode = nextView))
                            }.testTag("graph_selector")
                        )
                        // Station observations button — ports Android's ic_thermometer drawable
                        // (drawable/ic_thermometer.xml, tinted dim white like TemperatureTouchTargets'
                        // 0xAAFFFFFF) instead of the 🌡️ emoji, so it no longer collides with the graph
                        // selector's HOURLY (🌡️) cycle hint.
                        Icon(
                            painter = androidx.compose.ui.res.painterResource("drawable/ic_thermometer.xml"),
                            contentDescription = "Weather station observations",
                            tint = Color.White.copy(alpha = 0.67f),
                            modifier = Modifier.size((15 * scale).dp).clickable {
                                onOpenObservations()
                            }.testTag("open_observations_header")
                        )
                        // Home/Daily view mode — ports Android's ic_home line icon
                        // (drawable/ic_home.xml) instead of the 🏠 emoji for parity.
                        Icon(
                            painter = androidx.compose.ui.res.painterResource("drawable/ic_home.xml"),
                            contentDescription = "Daily view",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size((15 * scale).dp).clickable {
                                onUpdateConfig(config.copy(viewMode = ViewMode.DAILY))
                            }.testTag("switch_to_daily")
                        )
                        // Forecast history (how each day's forecast evolved) — ports Android's
                        // rising line-chart icon (drawable/ic_forecast_history_line.xml),
                        // shown right of the home icon on the hourly graph.
                        Icon(
                            painter = androidx.compose.ui.res.painterResource("drawable/ic_forecast_history_line.xml"),
                            contentDescription = "Forecast history",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size((15 * scale).dp).clickable {
                                onOpenHistory()
                            }.testTag("open_forecast_history")
                        )
                    }
                } else {
                    Text(
                        text = targetHour.format(dateFormatter),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = (12 * scale).sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            // Right cluster: API source + Settings gear
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val visibleSources = config.visibleSources
                if (visibleSources.size > 1) {
                    Text(
                        text = config.weatherSource,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = (10 * scale).sp,
                        modifier = Modifier.clickable {
                            val nextIdx = (visibleSources.indexOf(config.weatherSource) + 1) % visibleSources.size
                            onUpdateConfig(config.copy(weatherSource = visibleSources[nextIdx]))
                        }.padding(end = 6.dp)
                    )
                } else {
                    Text(
                        text = config.weatherSource,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = (10 * scale).sp,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                Icon(
                    painter = androidx.compose.ui.res.painterResource("drawable/ic_settings_gear.xml"),
                    contentDescription = "Settings",
                    modifier = Modifier.size((14 * scale).dp).clickable { onOpenSettings() },
                    tint = Color.White.copy(alpha = 0.7f)
                )
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
                    text = com.weatherwidget.shared.util.TempUtils.formatTemp(high) ?: "--",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                )
                if (state.dimensions.cols >= 2) {
                    Text(
                        text = com.weatherwidget.shared.util.TempUtils.formatTemp(low) ?: "--",
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
private fun CenteredMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
