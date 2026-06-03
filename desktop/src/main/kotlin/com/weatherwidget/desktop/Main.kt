package com.weatherwidget.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.weatherwidget.shared.util.DesktopTemperatureInterpolator
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import java.awt.Color as AwtColor
import dorkbox.systemTray.SystemTray
import dorkbox.systemTray.MenuItem as TrayMenuItem

/**
 * Desktop entry point. System-tray icon + a small frameless popup — the Linux-desktop analogue of
 * the Android home-screen widget.
 */
private const val APP_PACKAGE = "weather-widget-desktop"

/** Held for the process lifetime to enforce a single running instance; never released. */
private var instanceLockChannel: java.nio.channels.FileChannel? = null

private fun appDataDir(): java.nio.file.Path = DesktopDbPaths.defaultDbPath().parent

private fun isPackaged(): Boolean = System.getProperty("jpackage.app-path") != null

/**
 * Prevents duplicate trays (Dorkbox SystemTray is a process-level singleton) when login-autostart
 * and a manual launch race. Returns false when another instance already holds the lock.
 */
private fun acquireSingleInstanceLock(): Boolean = try {
    val dir = appDataDir()
    java.nio.file.Files.createDirectories(dir)
    val channel = java.nio.channels.FileChannel.open(
        dir.resolve(".lock"),
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.WRITE,
    )
    val lock = channel.tryLock()
    if (lock == null) {
        channel.close()
        false
    } else {
        instanceLockChannel = channel
        true
    }
} catch (e: java.nio.channels.OverlappingFileLockException) {
    true // already locked by this same JVM (e.g. repeated in-process launches) — allow.
} catch (e: Exception) {
    System.err.println("single-instance lock failed, continuing: $e")
    true
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

fun main() {
    if (System.getProperty("weatherwidget.desktop.startupSmoke") == "true") {
        return
    }
    val lockAcquired = acquireSingleInstanceLock()
    if (lockAcquired) {
        maybePackagedSetup()
    }
    runApp(lockAcquired)
}

private fun runApp(lockAcquired: Boolean) = application {
    MaterialTheme(colorScheme = darkColorScheme()) {
        if (!lockAcquired) {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Weather Widget",
                state = rememberWindowState(width = 300.dp, height = 200.dp, position = WindowPosition(Alignment.Center))
            ) {
                CenteredMessage("Weather Widget is already running.\nCheck your system tray.")
            }
        } else {
            val startupSmoke = remember { System.getProperty("weatherwidget.desktop.startupSmoke") == "true" }
            val configStore = remember { DesktopConfigStore() }
            var config by remember { mutableStateOf(configStore.load()) }

            // Persistence layer
            val weatherDb = remember { DesktopWeatherDatabase(DesktopDbPaths.defaultDbPath()).apply { initialize() } }
            val weatherDao = remember { DesktopWeatherDao(weatherDb) }

        var popupVisible by remember { mutableStateOf(config != null) }
        var pickerVisible by remember { mutableStateOf(config == null) }
        var settingsVisible by remember { mutableStateOf(false) }
        var statsVisible by remember { mutableStateOf(false) }
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

        // Background fetch logic with persistence
        LaunchedEffect(repository) {
            println("LaunchedEffect(repository) started. Repository null? ${repository == null}")
            val repo = repository ?: return@LaunchedEffect

            try {
                // 1. Instant load from cache
                println("Loading cached data...")
                val cached = repo.loadCached()
                println("Cached data loaded. Null? ${cached == null}")
                if (cached != null) {
                    forecast = cached
                    val lastFetch = weatherDao.getLastSuccessfulFetch()
                    dataStatus = DataStatus.Live(lastFetch ?: System.currentTimeMillis())
                    println("DataStatus updated to Live (cached). lastFetch: $lastFetch")
                }

                // 2. Staleness-gated launch fetch: skip if cache is fresh (< 30 min)
                val lastFetch = weatherDao.getLastSuccessfulFetch()
                val cacheIsFresh = lastFetch != null &&
                    (System.currentTimeMillis() - lastFetch) < FRESHNESS_THRESHOLD_MS

                println("Cache fresh? $cacheIsFresh. lastFetch: $lastFetch")

                if (!cacheIsFresh) {
                    try {
                        println("Refreshing from network...")
                        forecast = repo.refresh()
                        val now = System.currentTimeMillis()
                        dataStatus = DataStatus.Live(now)
                        println("Refresh successful. DataStatus updated to Live.")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        println("Refresh cancelled.")
                        throw e
                    } catch (e: Exception) {
                        println("Refresh failed: ${e.message}")
                        e.printStackTrace()
                        val isOffline = isOfflineException(e)
                        val reason = if (isOffline) "offline" else "source_error"
                        weatherDao.log("REFRESH_FAIL", "launch fetch: $reason ${e.message}", "WARN")
                        val lastSuccess = weatherDao.getLastSuccessfulFetch()
                        dataStatus = deriveDataStatus(
                            cachePresent = forecast != null,
                            lastFetchMs = lastSuccess,
                            refreshFailed = true,
                            failureIsOffline = isOffline,
                        )
                        println("DataStatus updated to: $dataStatus")
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                println("Initialization failure: ${e.message}")
                e.printStackTrace()
                dataStatus = DataStatus.Error("Initialization failed: ${e.message}")
                return@LaunchedEffect
            }

            // 3. Adaptive refresh loop
            while (true) {
                val delayMs = computeRefreshDelayMs(forecast?.hourly)
                println("Next refresh in ${delayMs / 1000}s")
                kotlinx.coroutines.delay(delayMs)
                try {
                    println("Loop refresh starting...")
                    forecast = repo.refresh()
                    dataStatus = DataStatus.Live(System.currentTimeMillis())
                    println("Loop refresh successful.")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    println("Loop refresh cancelled.")
                    throw e
                } catch (e: Exception) {
                    println("Loop refresh failed: ${e.message}")
                    val isOffline = isOfflineException(e)
                    val reason = if (isOffline) "offline" else "source_error"
                    weatherDao.log("REFRESH_FAIL", "$reason ${e.message}", "WARN")
                    val lastSuccess = weatherDao.getLastSuccessfulFetch()
                    dataStatus = deriveDataStatus(
                        cachePresent = forecast != null,
                        lastFetchMs = lastSuccess,
                        refreshFailed = true,
                        failureIsOffline = isOffline,
                    )
                }
            }
        }

        // External show request: the genmon panel click (and any other caller) touches the .show
        // trigger file; poll its mtime and open the popup. Initialized to the current mtime so a
        // stale trigger from a previous session doesn't pop the window on launch.
        LaunchedEffect(Unit) {
            val triggerFile = appDataDir().resolve(".show").toFile()
            var lastSeen = triggerFile.lastModified()
            while (true) {
                kotlinx.coroutines.delay(1000)
                val modified = triggerFile.lastModified()
                if (modified != 0L && modified != lastSeen) {
                    lastSeen = modified
                    popupVisible = true
                }
            }
        }

        fun quit() {
            desktopClients.close()
            exitApplication()
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

        TemperatureSystemTray(
            temperature = forecast?.currentTemp,
            dataStatus = dataStatus,
            onShow = { popupVisible = true },
            onSettings = { settingsVisible = true },
            onStatistics = { statsVisible = true },
            onUpdateLocation = {
                popupVisible = false
                pickerVisible = true
            },
            onQuit = ::quit,
        )

        if (statsVisible && currentConfig != null) {
            StatisticsWindow(
                weatherDao = weatherDao,
                config = currentConfig,
                onClose = { statsVisible = false },
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
                    }
                )
            }
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
    val tray = remember { SystemTray.get() }
    if (tray == null) {
        println("SystemTray is NOT supported on this system.")
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
            println("TrayIcon removed from SystemTray.")
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
                        onUpdateLocation = onUpdateLocation,
                        showWeatherSummary = config.viewMode == "HOURLY",
                    )

                    Spacer(Modifier.height(8.dp))

                    if (config.viewMode == "HOURLY") {
                        TemperatureGraph(
                            hourly = snapshot.hourly,
                            currentTemp = snapshot.currentTemp,
                            observations = snapshot.rawObservations,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            startOffsetHours = config.hourlyOffset,
                        )
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
                                        onUpdateConfig(config.copy(viewMode = "HOURLY", hourlyOffset = hours))
                                    }
                                )
                            } else {
                                DailyForecastTextMode(
                                    state = dailyState,
                                    modifier = Modifier.fillMaxSize(),
                                    onDayClick = { clickedDate ->
                                        val now = LocalDateTime.now()
                                        val hours = java.time.Duration.between(now, clickedDate.atStartOfDay()).toHours().toInt()
                                        onUpdateConfig(config.copy(viewMode = "HOURLY", hourlyOffset = hours))
                                    }
                                )
                            }

                            NavArrow(Alignment.CenterStart, "<", dailyState.canNavigateLeft) {
                                onUpdateConfig(config.copy(dateOffset = dailyState.clampedDateOffset - 1))
                            }
                            NavArrow(Alignment.CenterEnd, ">", dailyState.canNavigateRight) {
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
private fun NavArrow(alignment: Alignment, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = alignment) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.width(28.dp).fillMaxHeight(),
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = if (enabled) 0.75f else 0.18f),
                style = MaterialTheme.typography.labelLarge
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
    onUpdateLocation: () -> Unit,
    showWeatherSummary: Boolean = true,
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (showWeatherSummary) {
            // Top row: dominant current temp (left) + API source / date (right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    androidx.compose.foundation.Image(
                        painter = WeatherIcon.painter(forecast.currentCondition),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp).padding(end = 6.dp)
                    )
                    Text(
                        text = forecast.currentTemp?.let { formatTrayTemperature(it) + "°" } ?: "—",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = forecast.currentCondition ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    val visibleSources = config.visibleSources
                    if (visibleSources.size > 1) {
                        Text(
                            text = config.weatherSource,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.clickable {
                                val nextIdx = (visibleSources.indexOf(config.weatherSource) + 1) % visibleSources.size
                                onUpdateConfig(config.copy(weatherSource = visibleSources[nextIdx]))
                            }
                        )
                    } else {
                        Text(
                            text = config.weatherSource,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                    Text(
                        text = LocalDateTime.now().format(dateFormatter),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
        }

        // Bottom row: location + gear (left) | H / D mode chips (right)
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
                    painter = androidx.compose.ui.res.painterResource("drawable/ic_settings_gear.xml"),
                    contentDescription = "Settings",
                    modifier = Modifier.size(13.dp).clickable { onOpenSettings() },
                    tint = Color.White.copy(alpha = 0.5f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ViewModeChip("H", config.viewMode == "HOURLY") {
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

private const val FRESHNESS_THRESHOLD_MS = 30 * 60 * 1000L
private const val MIN_REFRESH_DELAY_MS = 10 * 60 * 1000L
private const val DEFAULT_REFRESH_DELAY_MS = 15 * 60 * 1000L

internal fun computeRefreshDelayMs(hourly: List<com.weatherwidget.data.model.HourlyForecast>?): Long {
    if (hourly.isNullOrEmpty()) return DEFAULT_REFRESH_DELAY_MS
    val updatesPerHour = DesktopTemperatureInterpolator.getUpdatesPerHour(hourly)
    val intervalMs = (3600_000L / updatesPerHour).coerceAtLeast(MIN_REFRESH_DELAY_MS)
    return intervalMs
}
