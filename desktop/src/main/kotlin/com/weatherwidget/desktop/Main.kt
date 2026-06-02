package com.weatherwidget.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.WeatherSource
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Desktop entry point. System-tray icon + a small frameless popup — the Linux-desktop analogue of
 * the Android home-screen widget.
 */
fun main() = application {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val configStore = remember { DesktopConfigStore() }
        var config by remember { mutableStateOf(configStore.load()) }
        var popupVisible by remember { mutableStateOf(config != null) }
        var pickerVisible by remember { mutableStateOf(config == null) }
        var settingsVisible by remember { mutableStateOf(false) }
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
            DesktopWeatherService(currentConfig)
        }

        // Background fetch logic lifted to application scope so Tray can see it.
        LaunchedEffect(currentConfig?.lat, currentConfig?.lon, currentConfig?.weatherSource, currentConfig?.apiKeys) {
            if (currentConfig == null) return@LaunchedEffect
            while (true) {
                try {
                    forecast = withContext(Dispatchers.IO) { weatherService.fetchForecast() }
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
        val textMeasurer = remember {
            androidx.compose.ui.text.TextMeasurer(
                defaultFontFamilyResolver = androidx.compose.ui.text.font.createFontFamilyResolver(),
                defaultLayoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
                defaultDensity = androidx.compose.ui.unit.Density(1f)
            )
        }
        val appIcon = remember(forecast?.currentTemp) {
            TemperatureTrayPainter(forecast?.currentTemp, textMeasurer)
        }

        Tray(
            icon = appIcon,
            tooltip = forecast?.currentTemp?.let { "Weather Widget: ${it.toInt()}°" } ?: "Weather Widget",
            onAction = { popupVisible = true },
            menu = {
                Item("Show", onClick = { popupVisible = true })
                Item("Settings", onClick = { settingsVisible = true })
                Item("Update location...", onClick = {
                    popupVisible = false
                    pickerVisible = true
                })
                Separator()
                Item("Quit", onClick = ::quit)
            },
        )

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
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    DailyForecastGraph(
                        daily = snapshot.daily,
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
                        text = forecast.currentTemp?.let { "${it.toInt()}°" } ?: "—",
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
