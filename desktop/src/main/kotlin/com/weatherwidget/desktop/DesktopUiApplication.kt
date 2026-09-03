package com.weatherwidget.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.deriveDataStatus
import com.weatherwidget.data.model.isOfflineException
import com.weatherwidget.data.model.isOfflineExceptionName
import com.weatherwidget.shared.util.PreferredSourceHome
import com.weatherwidget.shared.graph.ZoomStage
import com.weatherwidget.shared.util.DayClickResolver
import com.weatherwidget.shared.util.NoHourlyChecker
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.WeatherConditionResolver
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopDbPaths
import com.weatherwidget.data.local.desktop.CurrentTempStatusLog
import com.weatherwidget.data.remote.IpGeolocationApi
import com.weatherwidget.data.remote.NominatimApi
import com.weatherwidget.desktop.theme.WeatherDarkColorScheme
import com.weatherwidget.desktop.theme.WeatherTypography
import com.weatherwidget.util.NavigationUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

private const val TAG = "Main"

/** Oldest timestamp present in a loaded forecast (oldest observation or hourly point), or null. */
private fun oldestLoadedMs(f: ForecastSnapshot): Long? =
    listOfNotNull(
        f.raw.rawObservations.minOfOrNull { it.timestamp },
        f.raw.hourly.minOfOrNull { it.dateTime },
    ).minOrNull()

internal fun runDesktopUiApplication() = application {
    // Rename the AWT Event Dispatch Thread (which handles Compose UI) to be equally descriptive.
    SwingUtilities.invokeLater {
        Thread.currentThread().name = "WeatherUI"
    }

    MaterialTheme(colorScheme = WeatherDarkColorScheme, typography = WeatherTypography) {
        val startupSmoke = remember { System.getProperty("weatherwidget.desktop.startupSmoke") == "true" }
        val configStore = remember { DesktopConfigStore() }
        var config by remember { mutableStateOf(configStore.load()) }

        // Publish settings to the ActualsProviderResolver seam on load and on every change, rather
        // than having the blend re-read the config file nine times a paint.
        DisposableEffect(Unit) {
            DesktopActualsPreference.install()
            onDispose { }
        }
        LaunchedEffect(config?.settings) {
            DesktopActualsPreference.update(config?.settings)
        }

        // Persistence layer
        val weatherDb = remember { DesktopWeatherDatabase(DesktopDbPaths.defaultDbPath()).apply { initialize() } }
        val weatherDao = remember { DesktopWeatherDao(weatherDb) }

        remember(weatherDao) {
            com.weatherwidget.widget.CurrentTemperatureResolver.dbLogger = { tag, message, level ->
                // Persistence boundary: VERBOSE (high-frequency render/poll trace) stays ephemeral
                // (console/autostart log) and is never persisted, keeping the DB log sparse. DEBUG+ persist.
                if (level != "VERBOSE") weatherDao.log(tag, message, level)
            }
        }

        var popupVisible by remember { mutableStateOf(config != null) }
        // Edge-triggered show counter: a boolean can't re-fire an effect when it's already
        // true, so bump this on every show request to reliably raise an already-open window.
        var showRequestId by remember { mutableStateOf(0) }
        var dataUpdateCount by remember { mutableStateOf(0) }

        LaunchedEffect(config) {
            Log.i(TAG, "config loaded: config != null is ${config != null}")
        }
        var pickerVisible by remember { mutableStateOf(config == null) }
        var settingsVisible by remember { mutableStateOf(false) }
        var statsVisible by remember { mutableStateOf(false) }
        var historyVisible by remember { mutableStateOf(false) }
        var historyShowRequestId by remember { mutableStateOf(0) }
        // Day the forecast-history window opens on. Seeded from the hourly graph's viewed center
        // date (Android parity: TemperatureTouchTargets passes centerTime.toLocalDate()), so
        // opening history while viewing a past day lands on THAT day, not today.
        var historyInitialDate by remember { mutableStateOf(LocalDate.now()) }
        var observationsVisible by remember { mutableStateOf(false) }
        var obsShowRequestId by remember { mutableStateOf(0) }
        var appLogsVisible by remember { mutableStateOf(false) }
        // Owned here rather than in either child window: full refresh work runs on uiScope and
        // survives closing Settings or Stations/Observations.
        var refreshInFlight by remember { mutableStateOf(false) }
        val desktopClients = remember { DesktopClients() }
        // Held as a top-level remember so SettingsWindow can call friendlyName() directly for the
        // reverse-geocoded location label, without going through the higher-level LocationResolver
        // wrapper that LocationPicker uses.
        val sharedLocationResolver = remember {
            com.weatherwidget.data.repository.SharedLocationResolver(
                nominatimApi = NominatimApi(desktopClients.httpClient, desktopClients.json),
                ipGeolocationApi = IpGeolocationApi(desktopClients.httpClient, desktopClients.json),
            )
        }
        val locationResolver = remember {
            LocationResolver(
                phoneLocator = PhoneLocator(),
                timezoneLocator = TimezoneLocator(),
                sharedLocationResolver = sharedLocationResolver,
            )
        }

        var forecast by remember { mutableStateOf<ForecastSnapshot?>(null) }
        var dataStatus by remember { mutableStateOf<DataStatus>(DataStatus.Loading) }
        // Transient "Fetching older data…" banner shown while an on-demand deep-history pull runs.
        var historyFetchToast by remember { mutableStateOf<String?>(null) }
        var currentTempFetchError by remember { mutableStateOf<String?>(null) }
        // True when the failure is offline-classified during the post-wake grace window: the banner
        // renders as a calm "waiting for network" notice instead of a hard error.
        var currentTempFetchIsWarmup by remember { mutableStateOf(false) }
        var currentTempFetchTimestamp by remember { mutableStateOf(0L) }
        var dismissedErrorTimestamp by remember { mutableStateOf(0L) }
        // Banner state lives only in this UI process; without a durable transition row the
        // "did a banner appear during that resume?" question is unanswerable after the fact.
        var lastLoggedBannerState by remember { mutableStateOf("none") }
        val uiScope = rememberCoroutineScope()
        val currentConfig = config

        val weatherService = remember(currentConfig?.lat, currentConfig?.lon, currentConfig?.settings?.weatherSource, currentConfig?.settings?.apiKeys) {
            currentConfig?.let {
                DesktopWeatherService(it.lat, it.lon, it.settings.weatherSource, it.settings.apiKeys, weatherDao)
            }
        }
        val repository = remember(weatherService, currentConfig?.lat, currentConfig?.lon, currentConfig?.settings?.weatherSource, currentConfig?.settings?.personalStationDiscount) {
            val service = weatherService
            currentConfig?.let { cfg ->
                service?.let {
                    DesktopWeatherRepository(it, weatherDao, cfg.lat, cfg.lon, cfg.settings.weatherSource, cfg.personalStationWeight())
                }
            }
        }

        // Single reload path shared by every "data changed" trigger — socket push, file watch, resume
        // heartbeat, popup show. Re-reads the DB cache into Compose state; loadCached() reflects the
        // live DB, so this is always current. rememberUpdatedState keeps the repository reference fresh
        // so a lambda captured once (e.g. in a LaunchedEffect(Unit) watcher) still sees the latest repo.
        val currentRepository = rememberUpdatedState(repository)
        val reloadCachedForecast: (String) -> Unit = remember {
            fn@{ reason: String ->
                val repo = currentRepository.value ?: return@fn
                uiScope.launch {
                    try {
                        repo.loadCached()?.let { forecast = it }
                        dataUpdateCount++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Reload cache ($reason) failed: ${e.message}")
                    }
                }
            }
        }

        // Non-lossy daemon → UI push: reload the instant the daemon signals a change, and on every
        // (re)connect (which catches a change that landed while disconnected — daemon restart, resume).
        // Complements the lossy `.data-updated` file watcher below. Keyed on the reload lambda so a
        // repository change rebinds the client with a fresh reload target.
        DisposableEffect(reloadCachedForecast) {
            val client = UiNotifyClient(appDataDir()) { reason -> reloadCachedForecast("socket:$reason") }
            client.start()
            onDispose { client.close() }
        }

        // Reload whenever the popup is (re)shown, so looking at it always yields current data even if a
        // push/watch event was somehow missed. showRequestId starts at 0 (no show yet) and bumps per show.
        LaunchedEffect(showRequestId) {
            if (showRequestId > 0) reloadCachedForecast("show")
        }

        // Helper to save config and notify the daemon
        val saveConfigAndNotify = remember {
            { newConfig: DesktopConfig, source: String ->
                val prev = config
                val resolution = resolveDesktopConfigSave(prev, newConfig, source)
                val effective = resolution.config
                resolution.zoomFactorBeforeResnap?.let { previousFactor ->
                    Log.i(
                        TAG,
                        "CONFIG_SAVE source=$source re-snapped NARROW zoom " +
                            "zoomFactor $previousFactor -> ${effective.zoomFactor} " +
                            "for new span ${effective.settings.narrowZoomSpanHours}h",
                    )
                }

                if (prev != null) {
                    val settingsChanges = resolution.settingsChanges
                    if (settingsChanges.isNotEmpty()) {
                        val line = "CONFIG_SAVE source=$source settings-fields-changed: " +
                            settingsChanges.joinToString(", ")
                        val level = if (source == "settings" || source == "settings-close") "INFO" else "WARN"
                        if (source == "settings" || source == "settings-close") Log.i(TAG, line) else Log.w(TAG, line)
                        // Persist the same breadcrumb to the queryable app_logs DB. This bug was invisible
                        // there because CONFIG_SAVE went only to the console/autostart file.
                        weatherDao.log("CONFIG_SAVE", "source=$source ${settingsChanges.joinToString(", ")}", level)
                    }

                    // Positive proof the merge is working: a non-settings writer carried stale settings
                    // values that were corrected before persisting.
                    val mergedAway = resolution.mergedAwaySettings
                    if (mergedAway.isNotEmpty()) {
                        val line = "CONFIG_SAVE source=$source merged-away-stale-settings: " +
                            mergedAway.joinToString(", ")
                        Log.i(TAG, line)
                        weatherDao.log("CONFIG_SAVE", "source=$source ${mergedAway.joinToString(", ")}", "INFO")
                    }
                }

                configStore.save(effective)
                config = effective
                runCatching {
                    val trigger = appDataDir().resolve(CONFIG_CHANGED_TRIGGER)
                    java.nio.file.Files.writeString(trigger, "", java.nio.charset.StandardCharsets.UTF_8)
                }
                Unit
            }
        }


        // On-demand deep-history pull: fired by WidgetPopup when the hourly graph is zoomed/panned
        // past cached data. Runs in this UI process's own repository (no daemon IPC); on success it
        // reloads the cache so the graph extends. The in-flight flag + repository's own depth guard
        // keep rapid zoom ticks from stacking fetches; needsDeeperHistory avoids flashing the toast
        // when the requested span is already covered.
        var historyFetchInFlight by remember { mutableStateOf(false) }
        val onNeedHistory: (Int) -> Unit = remember(repository) {
            fn@{ neededBackHours: Int ->
                val repo = repository ?: return@fn
                if (historyFetchInFlight || !repo.needsDeeperHistory(neededBackHours)) return@fn
                historyFetchInFlight = true
                val oldestBefore = forecast?.let { oldestLoadedMs(it) }
                historyFetchToast = "Fetching older data…"
                uiScope.launch {
                    try {
                        val fetched = repo.ensureHistory(neededBackHours)
                        if (fetched) repo.loadCached()?.let { forecast = it }
                        // The DB already holds all retained history (loadCached reads the full window),
                        // and an on-demand fetch can only add RECENT obs (NWS serves ~7 days), never
                        // older. So if the oldest loaded point didn't move further back, there is
                        // genuinely no older data — tell the user that instead of implying a fetch.
                        val oldestAfter = forecast?.let { oldestLoadedMs(it) }
                        val extended = oldestAfter != null && oldestBefore != null && oldestAfter < oldestBefore
                        historyFetchToast = if (extended) null else "Reached end of stored history"
                    } catch (e: Exception) {
                        Log.e(TAG, "On-demand history fetch failed: ${e.message}")
                        historyFetchToast = "Couldn't load older data"
                    } finally {
                        historyFetchInFlight = false
                    }
                }
            }
        }
        // Daily-view tap on a day that has no hourly data. Every fetch already requests the maximum
        // forecast horizon, so there is nothing wider to fetch on tap — the two-phase pending→result
        // banner resolves immediately from the in-memory forecast. The completion callback always
        // fires so the UI never strands on the pending banner.
        val onNeedHourlyRefresh: ((List<HourlyForecast>) -> Unit) -> Unit = remember(repository) {
            { onComplete: (List<HourlyForecast>) -> Unit ->
                onComplete(forecast?.raw?.hourly ?: emptyList())
            }
        }

        // Auto-dismiss the transient end-of-history / failure messages (a successful extend clears the
        // toast immediately).
        LaunchedEffect(historyFetchToast) {
            if (historyFetchToast == "Couldn't load older data" || historyFetchToast == "Reached end of stored history") {
                kotlinx.coroutines.delay(3000)
                historyFetchToast = null
            }
        }

        // Exit on close logic:
        val anyWindowOpen = popupVisible || pickerVisible || settingsVisible || statsVisible || historyVisible || observationsVisible || appLogsVisible
        LaunchedEffect(anyWindowOpen) {
            if (!anyWindowOpen) {
                Log.i(TAG, "All windows closed. Ephemeral UI process exiting...")
                // Grace period for Compose/EDT teardown before hard exit.
                kotlin.concurrent.thread(isDaemon = true, name = "quit-hard-exit") {
                    Thread.sleep(400)
                    kotlin.system.exitProcess(0)
                }
                desktopClients.close()
                exitApplication()
            }
        }

        // Load cached forecast once, then run a resume-aware safety-net reload. The socket push and
        // the `.data-updated` watcher are the primary update paths; this loop bounds the damage of a
        // missed event (or a dead watcher) instead of leaving the UI stale forever. Two hazards it
        // must survive: a missed notification, and suspend/resume. It ticks at a SHORT cadence rather
        // than one long delay() because delay() runs on the monotonic clock and freezes during
        // suspend — a single long sleep would not fire promptly on wake. Each tick reloads when either
        // the fallback interval elapsed OR a suspend-sized wall-clock jump reveals we just resumed
        // (isSuspendJump, mirroring the daemon's heartbeat). That closes the hole where a laptop woke,
        // the daemon re-fetched, but its notification was dropped and the UI's timer was frozen.
        LaunchedEffect(repository) {
            val repo = repository ?: return@LaunchedEffect
            try {
                Log.i(TAG, "Loading cached data...")
                val cached = repo.loadCached()
                if (cached != null) {
                    forecast = cached
                    val lastFetch = weatherDao.getLastSuccessfulFetch(currentConfig?.settings?.weatherSource)
                    dataStatus = DataStatus.Live(lastFetch ?: System.currentTimeMillis())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load initial cache: ${e.message}")
            }

            var lastReloadMs = System.currentTimeMillis()
            var expectedMs = System.currentTimeMillis()
            while (true) {
                delay(UI_FALLBACK_TICK_MS)
                val now = System.currentTimeMillis()
                val gapMs = now - expectedMs
                val resumed = isSuspendJump(UI_FALLBACK_TICK_MS, gapMs, SUSPEND_JUMP_SLACK_MS)
                expectedMs = now
                if (!resumed && now - lastReloadMs < UI_FALLBACK_RELOAD_MS) continue
                try {
                    repo.loadCached()?.let { forecast = it }
                    // Also re-evaluates the status banner (see the dataUpdateCount-keyed effect).
                    dataUpdateCount++
                    lastReloadMs = now
                    if (resumed) Log.i(TAG, "UI resume detected (gap=${gapMs}ms) — reloaded cache.")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Fallback cache reload failed: ${e.message}")
                }
            }
        }

        LaunchedEffect(repository, config, forecast, dataUpdateCount) {
            val repo = repository ?: return@LaunchedEffect
            val activeConfig = config ?: return@LaunchedEffect
            var graceJob: Job? = null

            fun logBannerTransition() {
                val state = when {
                    currentTempFetchError == null -> "none"
                    currentTempFetchIsWarmup -> "warmup"
                    else -> "error"
                }
                if (state != lastLoggedBannerState) {
                    weatherDao.log("CURR_TEMP_BANNER", "state=$state (was $lastLoggedBannerState)", "INFO")
                    lastLoggedBannerState = state
                }
            }
            
            fun updateStatus() {
                val isHourly = activeConfig.viewMode.isHourly
                if (!isHourly) {
                    currentTempFetchError = null
                    currentTempFetchIsWarmup = false
                    return
                }
                
                val src = WeatherSource.fromDisplaySource(activeConfig.settings.weatherSource).id
                val status = weatherDao.getLatestCurrentTempStatus(src)
                if (status != null && !status.ok && status.timestamp > dismissedErrorTimestamp) {
                    val timeFmt = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
                    val attemptFmt = DateTimeFormatter.ofPattern("H:mm:ss").withZone(ZoneId.systemDefault())
                    val now = System.currentTimeMillis()
                    
                    val msg = status.message
                    val className = CurrentTempStatusLog.parseFailureClassName(msg)
                    val detail = CurrentTempStatusLog.parseFailureDetail(msg)
                    val displayName = WeatherSource.fromId(src).displayName

                    graceJob?.cancel()

                    // An offline-classified failure shortly after a wake/network event is the
                    // network stack still warming up, not a source problem — the resume hold-off,
                    // offline retries, and network-restored kick are all still in flight. Show a
                    // calm notice; escalate to the full error only once the grace window passes.
                    val wakeEventMs = weatherDao.getLatestWakeEventMs()
                    if (isOfflineExceptionName(className) &&
                        isNetworkWarmupWindow(wakeEventMs, now)
                    ) {
                        currentTempFetchError = "${displayName.uppercase()} WEATHER UPDATE\nWaiting for network to warm up…"
                        currentTempFetchIsWarmup = true
                        currentTempFetchTimestamp = status.timestamp
                        
                        val timeRemaining = (wakeEventMs ?: 0L) + NETWORK_WARMUP_GRACE_MS - now
                        if (timeRemaining > 0) {
                            graceJob = this.launch {
                                delay(timeRemaining)
                                updateStatus()
                                logBannerTransition()
                            }
                        }
                        return
                    }
                    currentTempFetchIsWarmup = false

                    val presentation = desktopFetchErrorPresentation(displayName, className, detail)
                    val lastSuccessfulUpdateMs = weatherDao.getLastSuccessfulFetch(src)
                    val lastSuccessfulLine = if (lastSuccessfulUpdateMs != null) {
                        val timeStr = timeFmt.format(Instant.ofEpochMilli(lastSuccessfulUpdateMs))
                        val ageStr = formatAge(now - lastSuccessfulUpdateMs)
                        "Last successful update: $timeStr ($ageStr ago)"
                    } else {
                        "Last successful update: None"
                    }

                    val attemptTimeStr = attemptFmt.format(Instant.ofEpochMilli(status.timestamp))
                    currentTempFetchError = buildList {
                        add(presentation.title)
                        addAll(presentation.bodyLines)
                        add("")
                        add(lastSuccessfulLine)
                        add("Last attempt: $attemptTimeStr")
                        add(presentation.retryLine)
                    }.joinToString("\n")
                    currentTempFetchTimestamp = status.timestamp
                } else {
                    currentTempFetchError = null
                    currentTempFetchIsWarmup = false
                }
            }

            updateStatus()
            logBannerTransition()
        }

        // Surface the popup for any show request. Bumping showRequestId edge-triggers the
        // raise-to-front effect even when the window is already visible (just buried).
        fun requestShowPopup() {
            popupVisible = true
            showRequestId++
        }

        fun quit() {
            // Signal daemon to quit first
            runCatching {
                val quitFile = appDataDir().resolve(QUIT_TRIGGER)
                java.nio.file.Files.writeString(quitFile, "", java.nio.charset.StandardCharsets.UTF_8)
            }
            // Spawn hard-exit daemon thread first so it runs even if EDT teardown or HTTP close hangs.
            kotlin.concurrent.thread(isDaemon = true, name = "quit-hard-exit") {
                Thread.sleep(400)
                kotlin.system.exitProcess(0)
            }
            desktopClients.close()
            exitApplication()
        }

        // Watch for external show request (`.ui-show`) and data updates (`.data-updated`). This is the
        // fallback signal path alongside the socket push (UiNotifyClient above): Java's WatchService
        // drops/coalesces events, so it can't be the only signal. It must also SELF-HEAL — the old
        // code did `if (!key.reset()) break`, so a single reset failure (or a closed service) killed
        // the watcher permanently, after which only the slow poll remained. Here a dead watch re-arms:
        // the inner loop exits, the outer loop rebuilds the WatchService and re-registers, after a
        // short pause to avoid a tight spin if the directory is persistently unwatchable.
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val dir = appDataDir()
                java.nio.file.Files.createDirectories(dir)
                while (true) {
                    runCatching { java.nio.file.Files.deleteIfExists(dir.resolve(UI_SHOW_TRIGGER)) }
                    runCatching { java.nio.file.Files.deleteIfExists(dir.resolve(DATA_UPDATED_TRIGGER)) }

                    val watchService = java.nio.file.FileSystems.getDefault().newWatchService()
                    try {
                        dir.register(
                            watchService,
                            java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                            java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
                        )
                        var watchValid = true
                        while (watchValid) {
                            val key = watchService.take() // Blocks until an event occurs
                            for (event in key.pollEvents()) {
                                val name = (event.context() as? java.nio.file.Path)?.toString()
                                if (name == UI_SHOW_TRIGGER) {
                                    Log.i(TAG, "WatchService: .ui-show trigger detected. Bumping showRequestId.")
                                    runCatching { java.nio.file.Files.deleteIfExists(dir.resolve(UI_SHOW_TRIGGER)) }
                                    SwingUtilities.invokeLater { requestShowPopup() }
                                } else if (name == DATA_UPDATED_TRIGGER) {
                                    Log.i(TAG, "WatchService: .data-updated trigger detected. Reloading cache...")
                                    runCatching { java.nio.file.Files.deleteIfExists(dir.resolve(DATA_UPDATED_TRIGGER)) }
                                    // Deliberately no dataStatus write: the daemon touches this trigger
                                    // on fetch *failures* too, and a bare trigger carries no outcome —
                                    // assuming Live here erased the offline/stale indication. Fetch
                                    // outcome reaches the UI through the CURRENT_TEMP_STATUS log
                                    // contract, re-read when dataUpdateCount bumps (reloadCachedForecast).
                                    reloadCachedForecast("watch")
                                }
                            }
                            if (!key.reset()) watchValid = false // watch invalid → rebuild below
                        }
                        Log.w(TAG, "WatchService key invalidated — re-arming watcher.")
                    } catch (e: java.nio.file.ClosedWatchServiceException) {
                        Log.w(TAG, "WatchService closed — re-arming watcher.")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        runCatching { watchService.close() }
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "WatchService loop error: ${e.message} — re-arming watcher.")
                    } finally {
                        runCatching { watchService.close() }
                    }
                    delay(1000L) // pause before re-arming so a persistent failure can't tight-spin
                }
            }
        }

        // Time ticker: re-reads the daemon-published current_status each STATUS_TICK_MS. The daemon
        // owns the resolution (and re-persists it on the same cadence), so this process only
        // re-reads a single row instead of re-running the IDW blend. Boundary-aligned and
        // phase-locked with the daemon loop in DaemonProcess.kt — see STATUS_TICK_MS.
        var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(STATUS_TICK_MS - (System.currentTimeMillis() % STATUS_TICK_MS))
                nowMs = System.currentTimeMillis()
            }
        }

        // Phase 3: consume the daemon-published snapshot; fall back to the values loadCached()
        // already resolved when the daemon hasn't published yet (e.g. startup/preview paths).
        val publishedStatus = remember(forecast, currentConfig, nowMs) {
            val cfg = currentConfig
            if (cfg != null) weatherDao.getCurrentStatus(cfg.lat, cfg.lon, cfg.settings.weatherSource) else null
        }
        val resolvedCurrentTemp = publishedStatus?.displayTempF ?: forecast?.resolved?.currentTemp
        val resolvedDeltaFromYesterday = publishedStatus?.deltaFromYesterdayF ?: forecast?.resolved?.deltaFromYesterday

        // Dynamic icon showing the current temperature.
        val textMeasurer = remember { createTrayTextMeasurer() }
        val appIcon = remember(resolvedCurrentTemp, currentConfig?.settings?.useCelsius) {
            val useCelsius = currentConfig?.settings?.useCelsius
                ?: com.weatherwidget.shared.util.UnitDefaults.defaultUseCelsius(java.util.Locale.getDefault())
            TemperatureTrayPainter(resolvedCurrentTemp, textMeasurer, useCelsius)
        }

        LaunchedEffect(startupSmoke) {
            if (startupSmoke) {
                exitApplication()
            }
        }

        fun requestFullRefresh(origin: String) {
            val repo = repository
            weatherDao.log(
                "REFRESH_CLICK",
                "origin=$origin repository=" +
                    (if (repo == null) "NULL (no-op)" else "present") +
                    " config=" +
                    (currentConfig?.let { "lat=${it.lat} lon=${it.lon} src=${it.settings.weatherSource}" }
                        ?: "null"),
                "INFO",
            )
            if (repo == null || refreshInFlight) {
                if (refreshInFlight) {
                    weatherDao.log("REFRESH_CLICK", "origin=$origin suppressed=in_flight", "INFO")
                }
                return
            }

            refreshInFlight = true
            // Application-owned scope: removing either child window from composition cannot cancel
            // the fetch or discard its result.
            uiScope.launch {
                try {
                    forecast = repo.refresh()
                    dataUpdateCount++
                    weatherDao.log(
                        "REFRESH_CLICK",
                        "origin=$origin repository.refresh() completed",
                        "INFO",
                    )
                    // UI -> daemon direction: only notify after the refreshed rows are durable.
                    notifyRefreshRequested()
                    weatherDao.log(
                        "REFRESH_CLICK",
                        "origin=$origin notifyRefreshRequested() sent",
                        "INFO",
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    weatherDao.log("REFRESH_CLICK", "origin=$origin refresh cancelled", "WARN")
                    throw e
                } catch (e: Exception) {
                    // A launched child must not take down uiScope and the unrelated UI features it owns.
                    weatherDao.log(
                        "REFRESH_CLICK",
                        "origin=$origin refresh failed ${e::class.simpleName}: ${e.message}",
                        "WARN",
                    )
                } finally {
                    refreshInFlight = false
                }
            }
        }

        if (statsVisible && currentConfig != null) {
            StatisticsWindow(
                weatherDao = weatherDao,
                config = currentConfig,
                onClose = { statsVisible = false },
            )
        }

        if (historyVisible && currentConfig != null) {
            ForecastHistoryWindow(
                weatherDao = weatherDao,
                config = currentConfig,
                showRequestId = historyShowRequestId,
                initialDate = historyInitialDate,
                onClose = { historyVisible = false },
                onConfigUpdate = { newConfig -> saveConfigAndNotify(newConfig, "observations") },
            )
        }

        if (observationsVisible && currentConfig != null && repository != null) {
            ObservationsWindow(
                weatherDao = weatherDao,
                config = currentConfig,
                showRequestId = obsShowRequestId,
                // Same "DB changed" signal the popup reloads on, so the stations list tracks the
                // live DB instead of freezing at the snapshot taken when the window was opened.
                dataUpdateCount = dataUpdateCount,
                isRefreshing = refreshInFlight,
                onRefreshData = { requestFullRefresh("observations") },
                onClose = { observationsVisible = false },
                onConfigUpdate = { newConfig ->
                    saveConfigAndNotify(newConfig, "observations-window")
                }
            )
        }

        if (appLogsVisible) {
            AppLogsWindow(
                weatherDao = weatherDao,
                onClose = { appLogsVisible = false }
            )
        }

        if (pickerVisible) {
            LocationPickerWindowHost(
                locationResolver = locationResolver,
                isFirstLaunch = config == null,
                icon = appIcon,
                onClose = { pickerVisible = false },
                onResolved = { saved ->
                    saveConfigAndNotify(saved, "location-picker")
                    pickerVisible = false
                    popupVisible = true
                },
            )
        }

        if (settingsVisible && config != null) {
            SettingsWindowHost(
                config = config!!,
                icon = appIcon,
                isRefreshing = refreshInFlight,
                weatherDao = weatherDao,
                locationResolver = sharedLocationResolver,
                onSaveConfig = saveConfigAndNotify,
                onClose = { settingsVisible = false },
                onExit = { quit() },
                onUpdateLocation = { pickerVisible = true },
                onOpenObservations = {
                    observationsVisible = true
                    obsShowRequestId++
                },
                onRefreshData = { requestFullRefresh("settings") },
                onViewAppLogs = { appLogsVisible = true },
            )
        }

        if (popupVisible && currentConfig != null) {
            PopupWindowHost(
                config = currentConfig,
                forecast = forecast,
                dataStatus = dataStatus,
                resolvedCurrentTemp = resolvedCurrentTemp,
                resolvedDeltaFromYesterday = resolvedDeltaFromYesterday,
                showRequestId = showRequestId,
                icon = appIcon,
                onClose = { popupVisible = false },
                onUpdateLocation = {
                    popupVisible = false
                    pickerVisible = true
                },
                onUpdateConfig = { newConfig ->
                    saveConfigAndNotify(newConfig, "popup")
                },
                onOpenSettings = {
                    settingsVisible = true
                },
                onOpenObservations = {
                    observationsVisible = true
                    obsShowRequestId++
                },
                onOpenHistory = { viewedDate ->
                    Log.d(TAG, "OpenHistory: viewedDate=$viewedDate (hourlyOffset=${currentConfig.hourlyOffset})")
                    historyInitialDate = viewedDate
                    historyVisible = true
                    historyShowRequestId++
                },
                onNeedHistory = onNeedHistory,
                onNeedHourlyRefresh = onNeedHourlyRefresh,
                onDayClickAudit = { message ->
                    Log.d("CLICK_DAILY", message)
                    weatherDao.log("CLICK_DAILY", message, "DEBUG")
                },
                historyFetchToast = historyFetchToast,
                currentTempFetchError = currentTempFetchError,
                currentTempFetchIsWarmup = currentTempFetchIsWarmup,
                onDismissCurrentTempError = {
                    dismissedErrorTimestamp = currentTempFetchTimestamp
                    currentTempFetchError = null
                },
            )
        }
    }
}

private fun formatAge(ageMillis: Long): String {
    val minutes = ageMillis / 60_000
    val hours = minutes / 60
    return when {
        hours >= 24 -> "${hours / 24}d ${hours % 24}h old"
        hours > 0 -> "${hours}h ${minutes % 60}m old"
        else -> "${minutes}m old"
    }
}
