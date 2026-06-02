package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop entry point. System-tray icon + a small frameless popup — the Linux-desktop analogue of
 * the Android home-screen widget. This is the MVP scaffold: it wires the tray/popup lifecycle.
 * Data fetch (Open-Meteo via :shared) and the Skia temperature graph land in later steps.
 */
fun main() = application {
    var popupVisible by remember { mutableStateOf(true) }
    val weatherService = remember { DesktopWeatherService() }

    // Placeholder tray icon (solid swatch) until we render the current temperature into it.
    Tray(
        icon = ColorPainter(Color(0xFF5AC8FA)),
        tooltip = "Weather Widget",
        onAction = { popupVisible = true },
        menu = {
            Item("Show", onClick = { popupVisible = true })
            Item("Quit", onClick = ::exitApplication)
        },
    )

    if (popupVisible) {
        val windowState = rememberWindowState(
            position = WindowPosition(Alignment.TopEnd),
            width = 360.dp,
            height = 240.dp,
        )
        Window(
            onCloseRequest = { popupVisible = false },
            state = windowState,
            title = "Weather Widget",
            undecorated = true,
            alwaysOnTop = true,
        ) {
            WidgetPopup(weatherService)
        }
    }
}

@Composable
private fun WidgetPopup(weatherService: DesktopWeatherService) {
    var forecast by remember { mutableStateOf<ForecastResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Fetch once when the popup first composes. withContext(IO) keeps the blocking HTTP off the
    // Compose UI dispatcher.
    LaunchedEffect(Unit) {
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
private fun CenteredMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
