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
import kotlinx.coroutines.withContext

@Composable
internal fun AppLogsWindow(
    weatherDao: DesktopWeatherDao,
    showRequestId: Int = 0,
    onClose: () -> Unit
) {
    val state = rememberSanitizedWindowState(
        savedX = null,
        savedY = null,
        savedWidth = null,
        savedHeight = null,
        defaultWidth = 600.dp,
        defaultHeight = 800.dp,
        defaultAlignment = Alignment.Center,
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
        BringToFrontOnShow(
            window = window,
            windowState = state,
            showRequestId = showRequestId,
        )
        AppLogsContent(weatherDao, onClose)
    }
}

/**
 * The window's content, apart from the [Window] so a test composes it in its own composition: a
 * real Window recomposes on its own frame clock, which the test rule's idle waits do not cover —
 * typed text had not reached the filter field when the next line looked (2026-10-23).
 */
@Composable
internal fun AppLogsContent(weatherDao: DesktopWeatherDao, onClose: () -> Unit) {
    var logs by remember { mutableStateOf<List<DesktopLogEntity>>(emptyList()) }
    var filterQuery by remember { mutableStateOf("") }

    // Read on IO, applied in the effect's own (composition) context. It used to launch from a
    // separate scope and hop to Dispatchers.Main (the Swing thread), outside what the test's
    // waits observe — DesktopUiTest.testAppLogsWindowShowsLogsAndFilters failed ~1 in 50 runs
    // under full CPU load (2026-10-23).
    LaunchedEffect(weatherDao) {
        logs = withContext(Dispatchers.IO) { weatherDao.getRecentLogs(3000) }
    }

    val filteredLogs = remember(logs, filterQuery) { filterLogs(logs, filterQuery) }

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

/** The rows whose tag, level or message contains [query], ignoring case; all of them for a blank query. */
internal fun filterLogs(logs: List<DesktopLogEntity>, query: String): List<DesktopLogEntity> {
    val q = query.lowercase().trim()
    if (q.isEmpty()) return logs
    return logs.filter { log ->
        log.tag.lowercase().contains(q) || log.level.lowercase().contains(q) || log.message.lowercase().contains(q)
    }
}
