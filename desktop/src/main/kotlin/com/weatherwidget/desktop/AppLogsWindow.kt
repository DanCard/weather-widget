package com.weatherwidget.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import com.weatherwidget.data.local.desktop.DesktopLogEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.desktop.theme.WeatherDarkColorScheme
import com.weatherwidget.desktop.theme.WeatherTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AppLogsWindow(
    weatherDao: DesktopWeatherDao,
    onClose: () -> Unit
) {
    val state = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        width = 600.dp,
        height = 800.dp
    )

    Window(
        onCloseRequest = onClose,
        state = state,
        title = "App Logs",
        onKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                onClose()
                true
            } else {
                false
            }
        }
    ) {
        var logs by remember { mutableStateOf<List<DesktopLogEntity>>(emptyList()) }
        var filterQuery by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            scope.launch(Dispatchers.IO) {
                val recentLogs = weatherDao.getRecentLogs(3000)
                withContext(Dispatchers.Main) {
                    logs = recentLogs
                }
            }
        }

        val filteredLogs = remember(logs, filterQuery) {
            val query = filterQuery.lowercase().trim()
            if (query.isEmpty()) {
                logs
            } else {
                logs.filter { log ->
                    log.tag.lowercase().contains(query) ||
                            log.level.lowercase().contains(query) ||
                            log.message.lowercase().contains(query)
                }
            }
        }

        MaterialTheme(colorScheme = WeatherDarkColorScheme, typography = WeatherTypography) {
            Surface(
                modifier = Modifier.fillMaxSize().testTag("app_logs_window")
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "App Logs",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Filter Input
                    OutlinedTextField(
                        value = filterQuery,
                        onValueChange = { filterQuery = it },
                        label = { Text("Filter logs") },
                        modifier = Modifier.fillMaxWidth().testTag("app_log_filter_input"),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Log list
                    Box(modifier = Modifier.weight(1f)) {
                        LogList(filteredLogs)
                    }
                }
            }
        }
    }
}
