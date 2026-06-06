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

fun main(args: Array<String>) {
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

        // Exit on close logic:
        val anyWindowOpen = popupVisible || pickerVisible || settingsVisible || statsVisible || observationsVisible
        LaunchedEffect(anyWindowOpen) {
            if (!anyWindowOpen) {
                Log.i(TAG, "All windows closed. Ephemeral UI process exiting...")
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

        if (observationsVisible && currentConfig != null && repository != null) {
            ObservationsWindow(
                weatherDao = weatherDao,
                repository = repository,
                config = currentConfig,
                onClose = { observationsVisible = false },
                onConfigUpdate = { newConfig ->
                    saveConfigAndNotify(newConfig)
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
            ) {
                SettingsWindow(
                    config = config!!,
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
                if (pos is WindowPosition.Absolute) {
                    val newConfig = currentConfig.copy(
                        windowX = pos.x.value,
                        windowY = pos.y.value,
                        windowWidth = windowState.size.width.value,
                        windowHeight = windowState.size.height.value
                    )
                    if (newConfig != currentConfig) {
                        saveConfigAndNotify(newConfig)
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
                        saveConfigAndNotify(newConfig)
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
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    WidgetHeader(
                        config = config,
                        forecast = snapshot,
                        onUpdateConfig = onUpdateConfig,
                        onOpenSettings = onOpenSettings,
                        onOpenObservations = onOpenObservations,
                        onUpdateLocation = onUpdateLocation,
                        showWeatherSummary = config.viewMode == "HOURLY" || config.viewMode == "TEMPERATURE" || config.viewMode == "CLOUD_COVER" || config.viewMode == "PRECIPITATION",
                        headerTime = LocalDateTime.now().plusHours(config.hourlyOffset.toLong()),
                        scale = uiScale,
                    )

                    Spacer(Modifier.height(4.dp))

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
                                    scale = uiScale,
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
    onUpdateLocation: () -> Unit,
    showWeatherSummary: Boolean = true,
    headerTime: LocalDateTime = LocalDateTime.now(),
    scale: Float = 1f,
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
    val isHourly = config.viewMode == "HOURLY" || config.viewMode == "TEMPERATURE" || config.viewMode == "CLOUD_COVER" || config.viewMode == "PRECIPITATION"

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
                        val targetMode = if (isHourly) "DAILY" else "HOURLY"
                        onUpdateConfig(config.copy(viewMode = targetMode))
                    }.testTag("current_temp_toggle")
                ) {
                    androidx.compose.foundation.Image(
                        painter = WeatherIcon.painter(forecast.currentCondition),
                        contentDescription = null,
                        modifier = Modifier.size((22 * scale).dp).padding(end = 4.dp)
                    )
                    Text(
                        text = forecast.currentTemp?.let { formatTrayTemperature(it) + "°" } ?: "—",
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
                                onUpdateConfig(config.copy(viewMode = "PRECIPITATION"))
                            }
                    )
                }
            }

            // Center cluster: date text
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = targetHour.format(dateFormatter),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = (12 * scale).sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
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

        if (isHourly) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = config.label,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = (12 * scale).sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        modifier = Modifier.clickable { onUpdateLocation() }
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        painter = androidx.compose.ui.res.painterResource("drawable/ic_thermometer.xml"),
                        contentDescription = "Stations",
                        modifier = Modifier.size((13 * scale).dp).clickable { onOpenObservations() },
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(6.dp))
                    val isCloud = config.viewMode == "CLOUD_COVER"
                    val isPrecip = config.viewMode == "PRECIPITATION"
                    val emoji = if (isCloud || isPrecip) "🌡️" else "☁️"
                    Text(
                        text = emoji,
                        fontSize = (11 * scale).sp,
                        modifier = Modifier.clickable {
                            val nextMode = if (isCloud || isPrecip) "HOURLY" else "CLOUD_COVER"
                            onUpdateConfig(config.copy(viewMode = nextMode))
                        }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ViewModeChip(config.zoomLevel.take(1), true) {
                        val nextZoom = if (config.zoomLevel == "WIDE") "NARROW" else "WIDE"
                        onUpdateConfig(config.copy(zoomLevel = nextZoom))
                    }
                    Spacer(Modifier.width(4.dp))
                    ViewModeChip("H", true) {
                        onUpdateConfig(config.copy(viewMode = "HOURLY"))
                    }
                    ViewModeChip("D", false) {
                        onUpdateConfig(config.copy(viewMode = "DAILY"))
                    }
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
