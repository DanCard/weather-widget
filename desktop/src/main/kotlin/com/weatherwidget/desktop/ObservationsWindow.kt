package com.weatherwidget.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import com.weatherwidget.data.local.desktop.DesktopLogEntity
import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.StationHistoryUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Shared palette for the Observations & Logs window (pure-black "OLED" look). */
internal object ObsStyle {
    val background = Color.Black
    val cardFill = Color(0xFF121214)
    val cardBorder = Color(0xFF2A2A2E)
    val textSecondary = Color(0xFFAAAAAA)
    val accent = Color(0xFF4FC3F7)
    val typeOfficial = Color(0xFF2BFF88) // bright green — distinct from the blue accent
    val typePersonal = Color(0xFFB0B0B8)
    val divider = Color(0xFF222226)
    val timeReported = Color(0xFFE8A24E) // amber — matches the mild band of the temp gradient
    val timeFetched = accent
}

/**
 * Which fetch-related log tags the "Fetch Logs" tab shows. Filtering happens in SQL
 * (see [DesktopWeatherDao.getRecentLogsByTags]) so the row cap counts displayed rows, not the
 * verbose current-temp tags that dominate app_logs.
 */
private enum class LogFilter(val label: String, val tags: List<String>) {
    FETCHES("Fetches", listOf("OBS_REFRESH", "REFRESH", "REFRESH_FAIL")),
    OBSERVATIONS("Observations", listOf("OBS_REFRESH", "REFRESH_FAIL")),
    ALL(
        "All activity",
        listOf("OBS_REFRESH", "REFRESH", "REFRESH_FAIL", "LAUNCH_REFRESH_CHECK", "RESUME_DETECT"),
    ),
}

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
        onKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                onClose()
                true
            } else {
                false
            }
        }
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
        var selectedTab by remember { mutableStateOf(0) }
        var logFilter by remember { mutableStateOf(LogFilter.FETCHES) }
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

                // Filter by tag in SQL so the cap counts fetch rows, not the verbose current-temp
                // tags (CurrentTempResolver etc.) that otherwise swamp app_logs.
                val recentLogs = weatherDao.getRecentLogsByTags(logFilter.tags, 100)

                withContext(Dispatchers.Main) {
                    observations = obs
                    logs = recentLogs
                }
            }
        }

        LaunchedEffect(currentSource, logFilter) {
            loadData()
        }

        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = ObsStyle.background,
                contentColor = Color.White
            ) {
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
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF0D2B45),
                                    contentColor = ObsStyle.accent
                                ),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Text(currentSource.shortDisplayName)
                            }
                        }

                        // Fetch Logs tag filter — a small icon that opens a menu of LogFilter sets.
                        if (selectedTab == 1) {
                            var filterMenuOpen by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { filterMenuOpen = true }) {
                                    Icon(Icons.Default.FilterList, contentDescription = "Filter logs")
                                }
                                DropdownMenu(
                                    expanded = filterMenuOpen,
                                    onDismissRequest = { filterMenuOpen = false }
                                ) {
                                    LogFilter.entries.forEach { filter ->
                                        DropdownMenuItem(
                                            text = { Text(filter.label) },
                                            leadingIcon = {
                                                if (filter == logFilter) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = ObsStyle.accent
                                                    )
                                                } else {
                                                    Spacer(Modifier.width(24.dp))
                                                }
                                            },
                                            onClick = {
                                                logFilter = filter
                                                filterMenuOpen = false
                                            }
                                        )
                                    }
                                }
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
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = ObsStyle.background,
                        contentColor = Color.White,
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = ObsStyle.accent
                            )
                        }
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            selectedContentColor = ObsStyle.accent,
                            unselectedContentColor = ObsStyle.textSecondary
                        ) {
                            Text("Observations", modifier = Modifier.padding(12.dp))
                        }
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            selectedContentColor = ObsStyle.accent,
                            unselectedContentColor = ObsStyle.textSecondary
                        ) {
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
    
    LazyColumn(modifier = Modifier.fillMaxSize().padding(6.dp)) {
        items(observations) { obs ->
            // NWS stations link to their public time-series history page; other sources have none.
            val historyUrl = StationHistoryUrl.forStation(obs.api, obs.stationId)
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clickable(enabled = historyUrl != null) {
                        historyUrl?.let(::openInBrowser)
                    },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = ObsStyle.cardFill),
                border = BorderStroke(1.dp, ObsStyle.cardBorder)
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            obs.stationName,
                            fontSize = 16.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (obs.condition.isNotBlank()) {
                            Text(
                                obs.condition,
                                fontSize = 13.sp,
                                color = ObsStyle.textSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                        Text(
                            String.format("%.1f°", obs.temperature),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = trayTempToColor(obs.temperature)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val distanceStr = if (obs.distanceKm > 0) String.format("%.1f mi", obs.distanceKm * 0.621371f) else "Local"
                        Text("${obs.stationId} • $distanceStr • ", fontSize = 14.sp, color = ObsStyle.textSecondary)
                        Text(
                            obs.stationType,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (obs.stationType == "OFFICIAL") ObsStyle.typeOfficial else ObsStyle.typePersonal
                        )
                    }
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = ObsStyle.textSecondary)) { append("Reported ") }
                            withStyle(SpanStyle(color = ObsStyle.timeReported, fontSize = 21.sp)) {
                                append(timeFormatter.format(Instant.ofEpochMilli(obs.timestamp)))
                            }
                            withStyle(SpanStyle(color = ObsStyle.textSecondary)) { append(" • Fetched ") }
                            withStyle(SpanStyle(color = ObsStyle.timeFetched, fontSize = 21.sp)) {
                                append(timeFormatter.format(Instant.ofEpochMilli(obs.fetchedAt)))
                            }
                        },
                        fontSize = 14.sp
                    )
                }
            }
        }

        if (observations.isEmpty()) {
            item {
                Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No recent observations found", color = ObsStyle.textSecondary)
                }
            }
        }
    }
}

