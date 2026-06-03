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
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopDbPaths
import com.weatherwidget.data.remote.IpGeolocationApi
import com.weatherwidget.data.remote.NominatimApi
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
fun main() = application {
    MaterialTheme(colorScheme = darkColorScheme()) {
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
            val repo = repository ?: return@LaunchedEffect
            
            // 1. Instant load from cache
            val cached = repo.loadCached()
            if (cached != null) {
                forecast = cached
            }
            
            // 2. Refresh loop
            while (true) {
                try {
                    forecast = repo.refresh()
                } catch (e: Exception) {
                    // Ignore background errors for now
                }
                kotlinx.coroutines.delay(15 * 60 * 1000) // 15 min refresh
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

internal fun createTrayTextMeasurer(): TextMeasurer =
    TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultLayoutDirection = LayoutDirection.Ltr,
        defaultDensity = Density(1f),
    )

@Composable
private fun TemperatureSystemTray(
    temperature: Float?,
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

    LaunchedEffect(temperature) {
        tray.setImage(createTemperatureTrayImage(temperature))
        tray.setStatus(temperature?.let { formatTrayTemperature(it) + "°" } ?: "Weather Widget")
        tray.setTooltip(temperature?.let { "Weather Widget: ${formatTrayTemperature(it)}°" } ?: "Weather Widget")
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
    onUpdateLocation: () -> Unit,
    onUpdateConfig: (DesktopConfig) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        val snapshot = forecast
        when {
            snapshot == null -> CenteredMessage("Loading…")
            else -> Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                WidgetHeader(
                    config = config,
                    forecast = snapshot,
                    onUpdateConfig = onUpdateConfig,
                    onOpenSettings = onOpenSettings,
                    onUpdateLocation = onUpdateLocation
                )
                
                Spacer(Modifier.height(8.dp))
                
                if (config.viewMode == "HOURLY") {
                    TemperatureGraph(
                        hourly = snapshot.hourly,
                        currentTemp = snapshot.currentTemp,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    DailyForecastGraph(
                        daily = snapshot.daily,
                        actuals = snapshot.dailyActuals,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetHeader(
    config: DesktopConfig,
    forecast: ForecastResult,
    onUpdateConfig: (DesktopConfig) -> Unit,
    onOpenSettings: () -> Unit,
    onUpdateLocation: () -> Unit
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }
    val now = remember { LocalDateTime.now() }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Top row: Location + Settings Gear + Source + Date
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text(
                    text = config.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.clickable { onUpdateLocation() }
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    painter = androidx.compose.ui.res.painterResource("drawable/ic_settings_gear.xml"),
                    contentDescription = "Settings",
                    modifier = Modifier.size(16.dp).clickable { onOpenSettings() },
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = config.weatherSource,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = now.format(dateFormatter),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Bottom row: Icon + Temp + Toggles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    painter = WeatherIcon.painter(forecast.currentCondition),
                    contentDescription = null,
                    modifier = Modifier.size(42.dp).padding(end = 8.dp)
                )
                Column {
                    Text(
                        text = forecast.currentTemp?.let { formatTrayTemperature(it) + "°" } ?: "—",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(
                        text = forecast.currentCondition ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            // View Mode and Source Toggles
            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val visibleSources = config.visibleSources
                    val currentIdx = visibleSources.indexOf(config.weatherSource)
                    if (visibleSources.size > 1) {
                        SourceToggle("Cycle API", false) {
                            val nextIdx = (currentIdx + 1) % visibleSources.size
                            onUpdateConfig(config.copy(weatherSource = visibleSources[nextIdx]))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SourceToggle("Hourly", config.viewMode == "HOURLY") {
                        onUpdateConfig(config.copy(viewMode = "HOURLY"))
                    }
                    SourceToggle("Daily", config.viewMode == "DAILY") {
                        onUpdateConfig(config.copy(viewMode = "DAILY"))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceToggle(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(24.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
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
