package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.weatherwidget.data.model.ForecastResult
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

/**
 * Desktop entry point. System-tray icon + a small frameless popup — the Linux-desktop analogue of
 * the Android home-screen widget. This MVP wires the tray/popup lifecycle and supports both
 * Open-Meteo and NWS data sources.
 */
fun main() = application {
    val configStore = remember { DesktopConfigStore() }
    var config by remember { mutableStateOf(configStore.load()) }
    var popupVisible by remember { mutableStateOf(config != null) }
    var pickerVisible by remember { mutableStateOf(config == null) }
    val desktopClients = remember { DesktopClients() }
    val locationResolver = remember {
        LocationResolver(
            phoneLocator = PhoneLocator(),
            timezoneLocator = TimezoneLocator(),
            ipGeolocationApi = IpGeolocationApi(desktopClients.httpClient, desktopClients.json),
            nominatimApi = NominatimApi(desktopClients.httpClient, desktopClients.json),
        )
    }

    fun quit() {
        desktopClients.close()
        exitApplication()
    }

    // Placeholder tray icon (solid swatch) until we render the current temperature into it.
    Tray(
        icon = ColorPainter(Color(0xFF5AC8FA)),
        tooltip = "Weather Widget",
        onAction = { popupVisible = true },
        menu = {
            Item("Show", onClick = { popupVisible = true })
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

    val currentConfig = config
    if (popupVisible && currentConfig != null) {
        val windowState = rememberWindowState(
            position = WindowPosition(Alignment.TopEnd),
            width = 380.dp,
            height = 320.dp,
        )
        Window(
            onCloseRequest = { popupVisible = false },
            state = windowState,
            title = "Weather Widget",
        ) {
            WidgetPopup(
                config = currentConfig,
                onUpdateLocation = {
                    popupVisible = false
                    pickerVisible = true
                },
                onUpdateConfig = { newConfig ->
                    configStore.save(newConfig)
                    config = newConfig
                }
            )
        }
    }
}

@Composable
private fun WidgetPopup(
    config: DesktopConfig,
    onUpdateLocation: () -> Unit,
    onUpdateConfig: (DesktopConfig) -> Unit,
) {
    var forecast by remember { mutableStateOf<ForecastResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val weatherService = remember(config.lat, config.lon, config.weatherSource) { 
        DesktopWeatherService(config) 
    }

    // Fetch when config changes. withContext(IO) keeps the blocking HTTP off the
    // Compose UI dispatcher.
    LaunchedEffect(config.lat, config.lon, config.weatherSource) {
        forecast = null
        error = null
        try {
            forecast = withContext(Dispatchers.IO) { weatherService.fetchForecast() }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val snapshot = forecast
            when {
                error != null -> CenteredMessage("Error: $error")
                snapshot == null -> CenteredMessage("Loading…")
                else -> Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    // Header: location label + source toggles — mirrors the widget's source-aware UI.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = config.label,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                            Row(
                                modifier = Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                SourceToggle("Meteo", config.weatherSource == "OPEN_METEO") {
                                    onUpdateConfig(config.copy(weatherSource = "OPEN_METEO"))
                                }
                                SourceToggle("NWS", config.weatherSource == "NWS") {
                                    onUpdateConfig(config.copy(weatherSource = "NWS"))
                                }
                            }
                        }
                        Button(onClick = onUpdateLocation) {
                            Text("Location")
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text(
                        text = snapshot.currentTemp?.let { "${it.toInt()}°" } ?: "—",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(
                        text = snapshot.currentCondition ?: "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    TemperatureGraph(
                        hourly = snapshot.hourly,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
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
