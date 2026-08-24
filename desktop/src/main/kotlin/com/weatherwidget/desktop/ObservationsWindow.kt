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
import androidx.compose.ui.platform.testTag
import com.weatherwidget.data.local.desktop.DesktopLogEntity
import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.desktop.theme.WeatherDarkColorScheme
import com.weatherwidget.desktop.theme.WeatherTypography
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.shared.actuals.BlendTable
import com.weatherwidget.shared.actuals.BlendTableFormatter
import com.weatherwidget.shared.observations.ObservationOrigin
import com.weatherwidget.shared.observations.ObservationSourceMatcher
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
    val error = Color(0xFFFF3366) // readings excluded from the blend: QC-rejected or stale
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

/** How often the open window re-evaluates reading ages (see [nowMs] use in [ObservationList]). */
private const val AGE_TICK_MS = 60_000L

// Blend leads: it is the tab that explains the graph's observed dot, which is what the window is
// usually opened to investigate.
internal const val TAB_BLEND = 0
internal const val TAB_OBSERVATIONS = 1
internal const val TAB_FETCH_LOGS = 2

/**
 * The Blend tab shows only the CURRENT blended point. History was dropped deliberately: the question
 * the tab answers is "why does the dot read what it reads right now", and a scrolling backlog of past
 * timestamps buried it. One table also buys the room for a much larger, readable font.
 */
private const val BLEND_TABLE_POINTS = 1

/**
 * Runs the blend for the Blend tab.
 *
 * **Must mirror the graph's inputs exactly.** A table computed from different observations, forecasts,
 * location, source or personal-station weight than [TemperatureGraph] uses would show numbers the dot
 * never had — worse than no table at all, since the whole point is to explain the dot. The queries and
 * arguments below are deliberately the same ones [DesktopWeatherRepository] feeds the graph
 * (`getObservationsInRange` / `getHourlyWithHistory` over `MAX_BACK_HOURS`, and
 * `config.personalStationWeight()`).
 *
 * The emitted values are window-independent by construction (see `BlendWindowIndependenceTest`), so
 * this reproduces the same per-timestamp results the render path computes.
 */
private fun loadBlendTables(
    weatherDao: DesktopWeatherDao,
    config: DesktopConfig,
    source: WeatherSource,
): List<BlendTable> {
    val now = System.currentTimeMillis()
    val backMs = DesktopGraphUtils.MAX_BACK_HOURS * 3600 * 1000L
    val observations = weatherDao
        .getObservationsInRange(now - backMs, now + (2 * 3600 * 1000L), config.lat, config.lon)
        .map { it.toReading() }
    val hourly = weatherDao.getHourlyWithHistory(
        config.lat,
        config.lon,
        source.id,
        now - backMs,
        now + (168 * 3600 * 1000L),
        24 * 60 * 60 * 1000L,
    )

    val result = ActualTemperatureSeriesBuilder.blendObservationSeries(
        observations = observations,
        hourlyForecasts = hourly,
        displaySourceId = source.id,
        userLat = config.lat,
        userLon = config.lon,
        startMs = now - backMs,
        endMs = now + (2 * 3600 * 1000L),
        personalStationWeight = config.personalStationWeight(),
        captureBreakdowns = BLEND_TABLE_POINTS,
    )
    return BlendTableFormatter.format(result.breakdowns, config.settings.useCelsius)
}

/**
 * The station rows the list shows for [source], newest reading per station, nearest first.
 *
 * Extracted from the composable so it is testable: the one-shot-snapshot bug this window had went
 * unnoticed partly because every transform lived inside `@Composable` code with no seam.
 *
 * **Order-dependent:** picking `first()` per station is only "newest" because
 * [DesktopWeatherDao.getRecentObservations] returns `ORDER BY timestamp DESC`. Sorting defensively
 * here keeps the result correct even if that contract changes.
 */
internal fun visibleStationRows(
    all: List<DesktopObservationEntity>,
    source: WeatherSource,
): List<DesktopObservationEntity> =
    all.asSequence()
        // Hides synthetic rows (IDW blend + NWS history backfill) and matches the stored `api`
        // against the feed that actually supplies this source's actuals — the same shared matcher
        // the Android stations list uses. There used to be a separate `api == source.id` pre-filter
        // here; that is exactly the comparison a borrowing source must NOT make, since Open-Meteo's
        // actuals arrive filed under METAR or NWS. The stationType guard stays as a
        // belt-and-suspenders catch for the desktop-only "BLENDED" marker.
        .filter {
            ObservationSourceMatcher.matchesStationsList(it.stationId, it.api, source) &&
                it.stationType != "BLENDED"
        }
        .groupBy { it.stationId }
        .map { (_, rows) -> rows.maxByOrNull { it.timestamp }!! }
        .sortedBy { it.distanceKm }

@Composable
internal fun ObservationsWindow(
    weatherDao: DesktopWeatherDao,
    config: DesktopConfig,
    showRequestId: Int = 0,
    dataUpdateCount: Int = 0,
    isRefreshing: Boolean = false,
    onRefreshData: () -> Unit = {},
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
        var currentSource by remember { mutableStateOf(WeatherSource.valueOf(config.settings.weatherSource)) }
        var observations by remember { mutableStateOf<List<DesktopObservationEntity>>(emptyList()) }
        var logs by remember { mutableStateOf<List<DesktopLogEntity>>(emptyList()) }
        var blendTables by remember { mutableStateOf<List<BlendTable>>(emptyList()) }
        var selectedTab by remember { mutableStateOf(config.obsSelectedTab) }
        val selectTab: (Int) -> Unit = { tab ->
            selectedTab = tab
            if (config.obsSelectedTab != tab) {
                onConfigUpdate(config.copy(obsSelectedTab = tab))
            }
        }
        var logFilter by remember { mutableStateOf(LogFilter.FETCHES) }
        val scope = rememberCoroutineScope()

        val loadData = {
            scope.launch(Dispatchers.IO) {
                val sinceMs = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                val obs = visibleStationRows(weatherDao.getRecentObservations(sinceMs), currentSource)

                // Filter by tag in SQL so the cap counts fetch rows, not the verbose current-temp
                // tags (CurrentTempResolver etc.) that otherwise swamp app_logs.
                val recentLogs = weatherDao.getRecentLogsByTags(logFilter.tags, 100)

                // Only when the tab is actually showing: this re-runs the blend, which the two other
                // tabs have no use for.
                val tables = if (selectedTab == TAB_BLEND) {
                    loadBlendTables(weatherDao, config, currentSource)
                } else {
                    emptyList()
                }

                withContext(Dispatchers.Main) {
                    observations = obs
                    logs = recentLogs
                    if (selectedTab == TAB_BLEND) blendTables = tables
                }
            }
        }

        // Reload on every "data changed" signal, not just on user input. dataUpdateCount is the
        // popup's consolidated counter — socket push, `.data-updated` watch, and the resume-aware
        // fallback tick all terminate in it (Main.kt) — so keying on it inherits all three paths.
        // showRequestId covers raising an ALREADY-OPEN window from the tray, which previously only
        // called toFront(); without it the user is shown the snapshot from whenever they first
        // opened the window. (A closed window drops out of composition, so reopening always reloads.)
        // selectedTab is a key so switching to Blend loads its table on first open, not on the next
        // data-changed signal.
        LaunchedEffect(currentSource, logFilter, dataUpdateCount, showRequestId, selectedTab) {
            loadData()
        }

        // Reading ages must advance on their own: ObservationOrigin.of() is evaluated during
        // composition, so with a frozen clock a station that falls past BLEND_MAX_AGE_MS (3h) while
        // the window sits open keeps rendering as a live "(API)" contributor with a temperature —
        // even though the blend has already decayed its weight to zero. Ticking on the minute
        // boundary mirrors the popup's interpolation ticker and keeps the window idle-cheap.
        var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(AGE_TICK_MS - (System.currentTimeMillis() % AGE_TICK_MS))
                nowMs = System.currentTimeMillis()
            }
        }

        MaterialTheme(colorScheme = WeatherDarkColorScheme, typography = WeatherTypography) {
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
                            fontSize = 24.sp,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )

                        // Source Cycler
                        val visibleSources = config.settings.visibleSources.map { WeatherSource.valueOf(it) }
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
                                Text(currentSource.shortDisplayName, fontSize = 18.sp)
                            }
                        }

                        // Fetch Logs tag filter — a small icon that opens a menu of LogFilter sets.
                        if (selectedTab == TAB_FETCH_LOGS) {
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
                                            text = { Text(filter.label, fontSize = 18.sp) },
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

                        ObservationRefreshButton(
                            isRefreshing = isRefreshing,
                            onRefreshData = onRefreshData,
                        )

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
                            selected = selectedTab == TAB_BLEND,
                            onClick = { selectTab(TAB_BLEND) },
                            selectedContentColor = ObsStyle.accent,
                            unselectedContentColor = ObsStyle.textSecondary
                        ) {
                            Text("Blend", fontSize = 18.sp, modifier = Modifier.padding(12.dp))
                        }
                        Tab(
                            selected = selectedTab == TAB_OBSERVATIONS,
                            onClick = { selectTab(TAB_OBSERVATIONS) },
                            selectedContentColor = ObsStyle.accent,
                            unselectedContentColor = ObsStyle.textSecondary
                        ) {
                            Text("Observations", fontSize = 18.sp, modifier = Modifier.padding(12.dp))
                        }
                        Tab(
                            selected = selectedTab == TAB_FETCH_LOGS,
                            onClick = { selectTab(TAB_FETCH_LOGS) },
                            selectedContentColor = ObsStyle.accent,
                            unselectedContentColor = ObsStyle.textSecondary
                        ) {
                            Text("Fetch Logs", fontSize = 18.sp, modifier = Modifier.padding(12.dp))
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        when (selectedTab) {
                            TAB_BLEND -> BlendTableView(blendTables, currentSource.id)
                            TAB_OBSERVATIONS -> ObservationList(observations, config.settings.useCelsius, nowMs)
                            else -> LogList(logs)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Caller-owned Stations refresh control.
 *
 * The network operation must not be launched from [ObservationsWindow]'s composition scope: closing
 * that window disposes the scope and cancels the fetch. Keeping this control callback-only makes the
 * ownership boundary explicit and lets [Main] run the work in its application-level UI scope.
 */
@Composable
internal fun ObservationRefreshButton(
    isRefreshing: Boolean,
    onRefreshData: () -> Unit,
) {
    IconButton(
        onClick = onRefreshData,
        enabled = !isRefreshing,
        modifier = Modifier.testTag("observations_refresh_btn"),
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
        }
    }
}

@Composable
private fun ObservationList(
    observations: List<DesktopObservationEntity>,
    useCelsius: Boolean,
    nowMs: Long,
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault()) }
    
    LazyColumn(modifier = Modifier.fillMaxSize().padding(6.dp)) {
        items(observations) { obs ->
            // NWS stations link to their public time-series history page; other sources have none.
            val historyUrl = StationHistoryUrl.forStation(obs.api, obs.stationId)
            val origin = ObservationOrigin.of(
                timestampMs = obs.timestamp,
                qcFailed = obs.qcFailed,
                isWebFallback = obs.isWebFallback,
                nowMs = nowMs,
            )
            // QC-rejected and stale readings are both absent from the blend, so neither shows a value.
            val excludedFromBlend = origin == ObservationOrigin.Kind.QC_FAILED ||
                origin == ObservationOrigin.Kind.STALE
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
                            fontSize = 21.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (obs.condition.isNotBlank()) {
                            Text(
                                obs.condition,
                                fontSize = 17.sp,
                                color = ObsStyle.textSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                        if (excludedFromBlend) {
                            Text(
                                "—",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = ObsStyle.textSecondary
                            )
                        } else {
                            val displayTemp = if (useCelsius) com.weatherwidget.shared.util.TempUtils.fahrenheitToCelsius(obs.temperature) else obs.temperature
                            Text(
                                String.format("%.1f°", displayTemp),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = trayTempToColor(obs.temperature)
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val distanceStr = if (obs.distanceKm > 0) String.format("%.1f mi", obs.distanceKm * 0.621371f) else "Local"
                        val originStr = when (origin) {
                            ObservationOrigin.Kind.QC_FAILED -> "failed QC check"
                            ObservationOrigin.Kind.STALE -> "Stale"
                            ObservationOrigin.Kind.WEB -> "Web"
                            ObservationOrigin.Kind.API -> "API"
                        }
                        Text("${obs.stationId} • $distanceStr • ", fontSize = 18.sp, color = ObsStyle.textSecondary)
                        Text(
                            "${obs.stationType} ($originStr)",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                excludedFromBlend -> ObsStyle.error
                                obs.stationType == "OFFICIAL" -> ObsStyle.typeOfficial
                                else -> ObsStyle.typePersonal
                            }
                        )
                    }
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = ObsStyle.textSecondary)) { append("Reported ") }
                            withStyle(SpanStyle(color = ObsStyle.timeReported, fontSize = 32.sp)) {
                                append(timeFormatter.format(Instant.ofEpochMilli(obs.timestamp)))
                            }
                            withStyle(SpanStyle(color = ObsStyle.textSecondary)) { append(" • Fetched ") }
                            withStyle(SpanStyle(color = ObsStyle.timeFetched, fontSize = 32.sp)) {
                                append(timeFormatter.format(Instant.ofEpochMilli(obs.fetchedAt)))
                            }
                        },
                        fontSize = 18.sp
                    )
                    val rawMetar = obs.rawMetar
                    if (!rawMetar.isNullOrBlank()) {
                        Text(
                            rawMetar,
                            fontSize = 13.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = ObsStyle.textSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        if (observations.isEmpty()) {
            item {
                Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No recent observations found", fontSize = 18.sp, color = ObsStyle.textSecondary)
                }
            }
        }
    }
}
