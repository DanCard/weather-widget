package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
 * the Android home-screen widget. This is the MVP scaffold: it wires the tray/popup lifecycle.
 * Data fetch (Open-Meteo via :shared) and the Skia temperature graph land in later steps.
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

    if (popupVisible && config != null) {
        val windowState = rememberWindowState(
            position = WindowPosition(Alignment.TopEnd),
            width = 360.dp,
            height = 280.dp,
        )
        Window(
            onCloseRequest = { popupVisible = false },
            state = windowState,
            title = "Weather Widget",
        ) {
            WidgetPopup(
                config = config,
                onUpdateLocation = {
                    popupVisible = false
                    pickerVisible = true
                },
            )
        }
    }
}

@Composable
private fun WidgetPopup(
    config: DesktopConfig?,
    onUpdateLocation: () -> Unit,
) {
    var forecast by remember { mutableStateOf<ForecastResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val weatherService = remember(config) { DesktopWeatherService(config) }

    // Fetch once when the popup first composes. withContext(IO) keeps the blocking HTTP off the
    // Compose UI dispatcher.
    LaunchedEffect(config) {
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
                    // Header: current temp (top-left, large) + condition — mirrors the widget layout.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = config?.label ?: "Unknown location",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                            Text(
                                text = config?.source ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                        Button(onClick = onUpdateLocation) {
                            Text("Update")
                        }
                    }
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
