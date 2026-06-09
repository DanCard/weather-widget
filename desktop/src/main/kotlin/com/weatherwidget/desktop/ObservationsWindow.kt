package com.weatherwidget.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.weatherwidget.data.local.desktop.DesktopLogEntity
import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.WeatherSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ObservationsWindow(
    weatherDao: DesktopWeatherDao,
    repository: DesktopWeatherRepository,
    config: DesktopConfig,
    showRequestId: Int = 0,
    onClose: () -> Unit,
    onConfigUpdate: (DesktopConfig) -> Unit,
) {
    val state = rememberWindowState(
        position = if (config.obsWindowX != null && config.obsWindowY != null) {
            WindowPosition(config.obsWindowX.dp, config.obsWindowY.dp)
        } else {
            WindowPosition(Alignment.Center)
        },
        width = config.obsWindowWidth?.dp ?: 500.dp,
        height = config.obsWindowHeight?.dp ?: 700.dp,
    )

    // Sync window state back to config
    LaunchedEffect(state.position, state.size) {
        val position = state.position
        val size = state.size
        if (position is WindowPosition.Absolute) {
            onConfigUpdate(
                config.copy(
                    obsWindowX = position.x.value,
                    obsWindowY = position.y.value,
                    obsWindowWidth = size.width.value,
                    obsWindowHeight = size.height.value
                )
            )
        }
    }

    Window(
        onCloseRequest = onClose,
        state = state,
        title = "Weather Observations & Logs",
    ) {
        // Bring to front on every new show request (incremented showRequestId)
        LaunchedEffect(showRequestId) {
            if (state.isMinimized) {
                state.isMinimized = false
            }
            if (window is java.awt.Frame) {
                val frameState = window.extendedState
                if ((frameState and java.awt.Frame.ICONIFIED) != 0) {
                    window.extendedState = java.awt.Frame.NORMAL
                }
            }
            window.toFront()
            window.requestFocus()
        }
        var currentSource by remember { mutableStateOf(WeatherSource.valueOf(config.weatherSource)) }
        var observations by remember { mutableStateOf<List<DesktopObservationEntity>>(emptyList()) }
        var logs by remember { mutableStateOf<List<DesktopLogEntity>>(emptyList()) }
        var isRefreshing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val loadData = {
            scope.launch(Dispatchers.IO) {
                val sinceMs = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                val obs = weatherDao.getRecentObservations(sinceMs)
                    .filter { it.api == currentSource.id }
                    // Hide synthetic aggregates (the internal IDW blend) — same guard the Android
                    // widget applies via WeatherObservationsActivity.matchesObservationSource().
                    .filter {
                        it.stationId != DesktopObservationEntity.NWS_BLEND_STATION_ID &&
                            it.stationType != "BLENDED"
                    }
                    .groupBy { it.stationId }
                    .map { it.value.first() }
                    .sortedBy { it.distanceKm }
                
                val recentLogs = weatherDao.getRecentLogs(100)
                    .filter { it.tag == "REFRESH" || it.tag == "REFRESH_FAIL" }

                withContext(Dispatchers.Main) {
                    observations = obs
                    logs = recentLogs
                }
            }
        }

        LaunchedEffect(currentSource) {
            loadData()
        }

        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Stations",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )

                        // Source Cycler
                        val visibleSources = config.visibleSources.map { WeatherSource.valueOf(it) }
                        if (visibleSources.isNotEmpty()) {
                            Button(
                                onClick = {
                                    val currentIndex = visibleSources.indexOf(currentSource)
                                    val nextIndex = (currentIndex + 1) % visibleSources.size
                                    currentSource = visibleSources[nextIndex]
                                },
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Text(currentSource.shortDisplayName)
                            }
                        }

                        IconButton(
                            onClick = {
                                scope.launch {
                                    isRefreshing = true
                                    try {
                                        repository.refresh()
                                        loadData()
                                    } finally {
                                        isRefreshing = false
                                    }
                                }
                            },
                            enabled = !isRefreshing
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }

                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    // Content
                    var selectedTab by remember { mutableStateOf(0) }
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                            Text("Observations", modifier = Modifier.padding(12.dp))
                        }
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                            Text("Fetch Logs", modifier = Modifier.padding(12.dp))
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        if (selectedTab == 0) {
                            ObservationList(observations)
                        } else {
                            LogList(logs)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ObservationList(observations: List<DesktopObservationEntity>) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault()) }
    
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(observations) { obs ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(obs.stationName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            val distanceStr = if (obs.distanceKm > 0) String.format("%.1f mi", obs.distanceKm * 0.621371f) else "Local"
                            Text("${obs.stationId} • $distanceStr", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            String.format("%.1f°", obs.temperature),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(obs.condition, style = MaterialTheme.typography.bodyMedium)
                        Badge(
                            containerColor = if (obs.stationType == "OFFICIAL") Color(0xFF2196F3) else Color.Gray,
                            contentColor = Color.White
                        ) {
                            Text(obs.stationType, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 10.sp)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        "Reported: ${timeFormatter.format(Instant.ofEpochMilli(obs.timestamp))} • Fetched: ${timeFormatter.format(Instant.ofEpochMilli(obs.fetchedAt))}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
        
        if (observations.isEmpty()) {
            item {
                Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No recent observations found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

