package com.weatherwidget.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.WeatherSource

@Composable
internal fun SettingsWindow(
    config: DesktopConfig,
    onClose: () -> Unit,
    onSave: (DesktopConfig) -> Unit,
    onExit: () -> Unit
) {
    var currentConfig by remember { mutableStateOf(config) }
    val scrollState = rememberScrollState()

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(16.dp)
                ) {
                    // API Sources
                    Text("API Sources", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    ApiSourcesList(
                        visibleSources = currentConfig.visibleSources,
                        onChanged = { newSources ->
                            currentConfig = currentConfig.copy(visibleSources = newSources)
                        }
                    )

                    Spacer(Modifier.height(24.dp))

                    // API Keys
                    Text("API Keys", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    ApiKeysList(
                        apiKeys = currentConfig.apiKeys,
                        onChanged = { newKeys ->
                            currentConfig = currentConfig.copy(apiKeys = newKeys)
                        }
                    )

                    Spacer(Modifier.height(24.dp))

                    // Icon Gallery
                    Text("Icon Gallery", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    IconGallery()
                }

                // Footer
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Exit the whole app — the only quit affordance when running without a tray
                    // (WEATHER_DESKTOP_NO_TRAY).
                    OutlinedButton(onClick = onExit, modifier = Modifier.testTag("exit_app")) {
                        Text("Exit app")
                    }
                    Button(onClick = {
                        onSave(currentConfig)
                        onClose()
                    }, modifier = Modifier.testTag("save_settings")) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiSourcesList(
    visibleSources: List<String>,
    onChanged: (List<String>) -> Unit
) {
    val allSources = listOf(
        WeatherSource.NWS,
        WeatherSource.TOMORROW_IO,
        WeatherSource.OPEN_METEO,
        WeatherSource.SILURIAN,
        WeatherSource.WEATHER_API,
        WeatherSource.VISUAL_CROSSING,
    )

    val orderedSources = remember(visibleSources) {
        val visible = visibleSources.mapNotNull { id -> allSources.find { it.id == id } }
        val hidden = allSources.filter { it.id !in visibleSources }
        visible + hidden
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        orderedSources.forEachIndexed { index, source ->
            val isVisible = source.id in visibleSources
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isVisible,
                    onCheckedChange = { checked ->
                        val newList = visibleSources.toMutableList()
                        if (checked) {
                            if (source.id !in newList) newList.add(source.id)
                        } else {
                            if (newList.size > 1) newList.remove(source.id)
                        }
                        onChanged(newList)
                    },
                    modifier = Modifier.testTag("source_checkbox_${source.id}")
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        sourceDescription(source),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                if (isVisible) {
                    IconButton(
                        onClick = {
                            val newList = visibleSources.toMutableList()
                            val pos = newList.indexOf(source.id)
                            if (pos > 0) {
                                val tmp = newList[pos]
                                newList[pos] = newList[pos - 1]
                                newList[pos - 1] = tmp
                                onChanged(newList)
                            }
                        },
                        enabled = visibleSources.indexOf(source.id) > 0,
                        modifier = Modifier.testTag("move_up_${source.id}")
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                    }
                    IconButton(
                        onClick = {
                            val newList = visibleSources.toMutableList()
                            val pos = newList.indexOf(source.id)
                            if (pos < newList.size - 1) {
                                val tmp = newList[pos]
                                newList[pos] = newList[pos + 1]
                                newList[pos + 1] = tmp
                                onChanged(newList)
                            }
                        },
                        enabled = visibleSources.indexOf(source.id) < visibleSources.size - 1,
                        modifier = Modifier.testTag("move_down_${source.id}")
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiKeysList(
    apiKeys: Map<String, String>,
    onChanged: (Map<String, String>) -> Unit
) {
    val sourcesRequiringKeys = listOf(
        WeatherSource.TOMORROW_IO,
        WeatherSource.SILURIAN,
        WeatherSource.WEATHER_API,
        WeatherSource.VISUAL_CROSSING,
        WeatherSource.OPEN_WEATHER_MAP,
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sourcesRequiringKeys.forEach { source ->
            var text by remember(source.id) { mutableStateOf(apiKeys[source.id] ?: "") }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    val newKeys = apiKeys.toMutableMap()
                    if (it.isBlank()) newKeys.remove(source.id) else newKeys[source.id] = it
                    onChanged(newKeys)
                },
                label = { Text(source.displayName) },
                modifier = Modifier.fillMaxWidth().testTag("api_key_${source.id}"),
                singleLine = true
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconGallery() {
    val icons = listOf(
        "drawable/ic_weather_clear.xml" to "Clear",
        "drawable/ic_weather_mostly_clear.xml" to "Mostly Clear",
        "drawable/ic_weather_partly_cloudy.xml" to "Partly Cloudy",
        "drawable/ic_weather_mostly_cloudy.xml" to "Mostly Cloudy",
        "drawable/ic_weather_cloudy.xml" to "Cloudy",
        "drawable/ic_weather_night.xml" to "Night",
        "drawable/ic_weather_rain.xml" to "Rain",
        "drawable/ic_weather_storm.xml" to "Storm",
        "drawable/ic_weather_snow.xml" to "Snow",
        "drawable/ic_weather_fog.xml" to "Fog",
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        icons.forEach { (res, name) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(80.dp)
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(res),
                    contentDescription = name,
                    modifier = Modifier.size(32.dp)
                )
                Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

private fun sourceDescription(source: WeatherSource): String = when (source) {
    WeatherSource.SILURIAN -> "High-resolution AI-driven local weather."
    WeatherSource.NWS -> "Official US government forecasts (US only)."
    WeatherSource.TOMORROW_IO -> "High-precision global weather API."
    WeatherSource.VISUAL_CROSSING -> "Comprehensive global historical and forecast data."
    WeatherSource.OPEN_METEO -> "Free, global open-source weather data."
    WeatherSource.WEATHER_API -> "Real-time and local weather information."
    else -> ""
}
