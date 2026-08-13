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
import androidx.compose.ui.text.TextMeasurer
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
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.deriveDataStatus
import com.weatherwidget.data.model.isOfflineException
import com.weatherwidget.data.model.isOfflineExceptionName
import com.weatherwidget.shared.util.PrecipProbabilityCalculator
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


/**
 * Desktop entry point. System-tray icon + a small frameless popup — the Linux-desktop analogue of
 * the Android home-screen widget.
 */
private const val APP_PACKAGE = "weather-widget-desktop"
private const val TAG = "Main"
/** 30 days × 24 hours = 720 hours. Maximum pannable depth into the past. */
private const val MIN_HOURLY_OFFSET = -720
/** 30 days × 24 hours = 720 hours. Maximum pannable depth into the future. */
private const val MAX_HOURLY_OFFSET = 720

/**
 * Config for opening the hourly view focused on [clickedDate], matching Android day-click behavior:
 * shared [DayClickResolver] routing/offset and [ZoomStage.WIDE] when entering from daily view.
 */
internal fun dayClickConfig(
    config: DesktopConfig,
    clickedDate: LocalDate,
    days: List<DesktopDailyDay>,
    zone: DayClickResolver.DayTapZone = DayClickResolver.DayTapZone.MAIN_COLUMN,
    now: LocalDateTime = LocalDateTime.now(),
): DesktopConfig {
    val clickedDay = days.find { it.date == clickedDate }
    val precipProb = clickedDay?.forecast?.precipProbability ?: clickedDay?.snapshot?.precipProbability
    val targetView = when (
        DayClickResolver.resolveView(zone, clickedDay?.iconName, precipProb)
    ) {
        DayClickResolver.DayClickView.PRECIPITATION -> ViewMode.PRECIPITATION
        DayClickResolver.DayClickView.CLOUD_COVER -> ViewMode.CLOUD_COVER
        DayClickResolver.DayClickView.TEMPERATURE -> ViewMode.HOURLY
    }
    val wideZoom = DesktopGraphUtils.zoomFactorForStage(ZoomStage.WIDE)
    return config.copy(
        viewMode = targetView,
        hourlyOffset = DayClickResolver.calculateHourlyOffset(now, clickedDate),
        zoomFactor = if (config.viewMode == ViewMode.DAILY) wideZoom else config.zoomFactor,
    )
}

/**
 * Persists the latest Settings draft when a close request beats the idle auto-save timer.
 * Keeping this decision pure makes title-bar and Escape handling independently testable.
 *
 * Saves the draft's SETTINGS fields merged onto the current persisted config, never the draft
 * verbatim: the draft carries a snapshot of every popup-owned field (window bounds, zoom, pan, view
 * mode) taken when the window opened, so writing it whole rewinds whatever the popup did in the
 * meantime. Mirrors the merge the Save button and auto-save timer perform.
 */
internal fun flushSettingsDraft(
    persistedConfig: DesktopConfig?,
    draft: DesktopConfig?,
    onSave: (DesktopConfig) -> Unit,
) {
    if (draft == null) return
    if (persistedConfig == null) {
        onSave(draft)
        return
    }
    val merged = persistedConfig.withSettingsFrom(draft)
    if (merged != persistedConfig) onSave(merged)
}

/**
 * Re-snaps the continuous [DesktopConfig.zoomFactor] when the user changes the NARROW span while
 * already viewing the NARROW stage.
 *
 * The desktop hourly view draws its visible window purely from [DesktopConfig.zoomFactor];
 * [DesktopConfig.narrowZoomSpanHours] only influences the factor a *click* stores when it cycles
 * onto NARROW. So changing the setting while sitting in NARROW used to leave the graph on the old
 * span until the next click — the view looked dead. This mirrors the click path: resolve the stage
 * the current factor is nearest to (against the OLD span, exactly as the click handler does) and,
 * if that stage is NARROW, store the factor for the NEW span so the already-open graph re-renders
 * at the configured width. WIDE / THREE_DAY are independent of the setting and left untouched, as
 * is any wheel-zoom position whose nearest stage is not NARROW.
 */
internal fun resnapNarrowZoomAfterSpanChange(prev: DesktopConfig, next: DesktopConfig): DesktopConfig {
    if (prev.narrowZoomSpanHours == next.narrowZoomSpanHours) return next
    val currentStage = ZoomStage.nearestByTotalSpan(
        DesktopGraphUtils.totalSpanHoursFor(prev.zoomFactor),
        prev.narrowZoomSpanHours,
    )
    if (currentStage != ZoomStage.NARROW) return next
    val newFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, next.narrowZoomSpanHours)
    return if (newFactor == next.zoomFactor) next else next.copy(zoomFactor = newFactor)
}

fun main(args: Array<String>) {
    // Surface shared-module diagnostics on the console (default JulSink drops DEBUG). First thing so
    // even startup logging from :shared is visible.
    Log.install(DesktopLogSink)

    // On Linux, prefer the system truststore over the bundled JRE's truststore if present.
    // The bundled JRE's cacerts is often outdated or incomplete compared to the system-wide store.
    val systemTrustStore = java.io.File("/etc/ssl/certs/java/cacerts")
    if (systemTrustStore.exists() && System.getProperty("javax.net.ssl.trustStore") == null) {
        System.setProperty("javax.net.ssl.trustStore", systemTrustStore.absolutePath)
        Log.i("Main", "Configured system SSL truststore: ${systemTrustStore.absolutePath}")
    }

    val isUiMode = args.contains("--ui") || args.contains("ui") || args.contains("--show") || args.contains("show")
    if (System.getProperty("weatherwidget.desktop.startupSmoke") == "true") {
        if (isUiMode) {
            runApp()
        }
        return
    }
    if (isUiMode) {
        Thread.currentThread().name = "WeatherUI"
        Log.i("Main", "Starting WeatherUI process...")
        if (args.contains("--show")) {
            System.setProperty("weatherwidget.desktop.show", "true")
        }
        runApp()
    } else {
        runDaemon()
    }
}

/** Oldest timestamp present in a loaded forecast (oldest observation or hourly point), or null. */
private fun oldestLoadedMs(f: ForecastResult): Long? =
    listOfNotNull(
        f.rawObservations.minOfOrNull { it.timestamp },
        f.hourly.minOfOrNull { it.dateTime },
    ).minOrNull()

private fun runApp() = application {
    // Rename the AWT Event Dispatch Thread (which handles Compose UI) to be equally descriptive.
    SwingUtilities.invokeLater {
        Thread.currentThread().name = "WeatherUI"
    }

    MaterialTheme(colorScheme = WeatherDarkColorScheme, typography = WeatherTypography) {
        val startupSmoke = remember { System.getProperty("weatherwidget.desktop.startupSmoke") == "true" }
        val configStore = remember { DesktopConfigStore() }
        var config by remember { mutableStateOf(configStore.load()) }

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
        var settingsDraft by remember { mutableStateOf<DesktopConfig?>(null) }
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
        // Registered by WidgetPopup for whichever view is active (daily/hourly); the popup Window forwards
        // ←/→ here. Returns true when the key was consumed (so Escape/default handling stays intact).
        var arrowKeyHandler by remember { mutableStateOf<((left: Boolean) -> Boolean)?>(null) }
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

        var forecast by remember { mutableStateOf<ForecastResult?>(null) }
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

        val weatherService = remember(currentConfig?.lat, currentConfig?.lon, currentConfig?.weatherSource, currentConfig?.apiKeys) {
            currentConfig?.let {
                DesktopWeatherService(it.lat, it.lon, it.weatherSource, it.apiKeys, weatherDao)
            }
        }
        val repository = remember(weatherService, currentConfig?.lat, currentConfig?.lon, currentConfig?.weatherSource, currentConfig?.personalStationDiscount) {
            val service = weatherService
            currentConfig?.let { cfg ->
                service?.let {
                    DesktopWeatherRepository(it, weatherDao, cfg.lat, cfg.lon, cfg.weatherSource, cfg.personalStationWeight())
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
                // Every writer is tagged because DesktopConfig has several of them and they all write
                // the WHOLE object: when a settings value silently reverts, the only thing that
                // identifies the culprit is which save carried the regression.
                val prev = config

                // Non-settings writers (popup, observations/history windows, location picker) compute
                // their updates from their own config snapshot, which can lag the persisted config, and
                // write the whole object back. Merge them onto the latest persisted config so they can't
                // clobber settings-owned fields (the reported "Hourly Zoom reverted to 6h" bug).
                // weatherSource is the one settings-owned field the popup header and location picker
                // legitimately change, so it is allowed through for those two sources.
                var effective = if (prev != null && source != "settings" && source != "settings-close") {
                    mergeNonSettingsSave(
                        persisted = prev,
                        draft = newConfig,
                        allowWeatherSourceChange = source == "popup" || source == "location-picker",
                    )
                } else {
                    newConfig
                }

                // Changing "Hourly Zoom" hours while the popup is already sitting in the NARROW
                // stage must re-render that view at the new span, not wait for the next click: the
                // graph draws purely from zoomFactor, which the setting otherwise never touches.
                if (prev != null) {
                    val resnapped = resnapNarrowZoomAfterSpanChange(prev, effective)
                    if (resnapped != effective) {
                        Log.i(
                            TAG,
                            "CONFIG_SAVE source=$source re-snapped NARROW zoom " +
                                "zoomFactor ${effective.zoomFactor} -> ${resnapped.zoomFactor} " +
                                "for new span ${resnapped.narrowZoomSpanHours}h",
                        )
                    }
                    effective = resnapped
                }

                if (prev != null) {
                    val settingsChanges = effective.settingsDiffFrom(prev)
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
                    val mergedAway = newConfig.settingsDiffFrom(effective)
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

        fun closeSettings() {
            flushSettingsDraft(config, settingsDraft) { saveConfigAndNotify(it, "settings-close") }
            settingsDraft = null
            settingsVisible = false
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
                onComplete(forecast?.hourly ?: emptyList())
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
                    val lastFetch = weatherDao.getLastSuccessfulFetch(currentConfig?.weatherSource)
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
                
                val src = WeatherSource.fromDisplaySource(activeConfig.weatherSource).id
                val status = weatherDao.getLatestCurrentTempStatus(src)
                if (status != null && !status.ok && status.timestamp > dismissedErrorTimestamp) {
                    val timeFmt = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
                    val attemptFmt = DateTimeFormatter.ofPattern("H:mm:ss").withZone(ZoneId.systemDefault())
                    val now = System.currentTimeMillis()
                    
                    val msg = status.message
                    val className = CurrentTempStatusLog.parseFailureClassName(msg)
                    val detail = CurrentTempStatusLog.parseFailureDetail(msg)
                    val displayName = WeatherSource.fromId(src).displayName.uppercase().replace("-", "_")

                    graceJob?.cancel()

                    // An offline-classified failure shortly after a wake/network event is the
                    // network stack still warming up, not a source problem — the resume hold-off,
                    // offline retries, and network-restored kick are all still in flight. Show a
                    // calm notice; escalate to the full error only once the grace window passes.
                    val wakeEventMs = weatherDao.getLatestWakeEventMs()
                    if (isOfflineExceptionName(className) &&
                        isNetworkWarmupWindow(wakeEventMs, now)
                    ) {
                        currentTempFetchError = "$displayName current temp\nWaiting for network to warm up…"
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

                    val host = when {
                        detail.contains("open-meteo.com") -> "api.open-meteo.com"
                        detail.contains("weather.gov") -> "api.weather.gov"
                        detail.contains("tomorrow.io") -> "api.tomorrow.io"
                        detail.contains("weatherapi.com") -> "api.weatherapi.com"
                        detail.contains("visualcrossing.com") -> "weather.visualcrossing.com"
                        detail.contains("openweathermap.org") -> "api.openweathermap.org"
                        detail.contains("silurian") -> "silurian API"
                        else -> ""
                    }
                    
                    val friendlyError = when (className) {
                        "ConnectTimeoutException" -> "Connect timeout (10s)"
                        "SocketTimeoutException" -> "Socket timeout"
                        "UnknownHostException" -> "Unknown host (DNS lookup failed)"
                        else -> detail.substringBefore(" [").take(40)
                    }
                    val errorLine = if (host.isNotEmpty()) "$friendlyError · $host" else friendlyError
                    
                    val lastGoodObsMs = forecast?.currentObservedAt
                    val lastGoodLine = if (lastGoodObsMs != null) {
                        val timeStr = timeFmt.format(Instant.ofEpochMilli(lastGoodObsMs))
                        val ageStr = formatAge(now - lastGoodObsMs)
                        "Last good obs: $timeStr ($ageStr ago)"
                    } else {
                        "Last good obs: None"
                    }
                    
                    val attemptTimeStr = attemptFmt.format(Instant.ofEpochMilli(status.timestamp))
                    val attemptLine = "Last attempt: $attemptTimeStr · 2 retries failed"
                    
                    currentTempFetchError = """
                        $displayName current temp not updating
                        $errorLine
                        $lastGoodLine
                        $attemptLine
                    """.trimIndent()
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

        // Time ticker for in-memory interpolation of current temperature
        var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(60_000L - (System.currentTimeMillis() % 60_000L))
                nowMs = System.currentTimeMillis()
            }
        }

        val resolvedTempAndDelta = remember(forecast, repository, nowMs) {
            val f = forecast
            val repo = repository
            if (f != null && repo != null) {
                repo.resolveCurrentTempInMemory(f, nowMs)
            } else {
                DesktopWeatherRepository.ResolvedCurrentTemp(
                    f?.currentTemp, f?.appliedDelta, f?.deltaFromYesterday,
                )
            }
        }
        val resolvedCurrentTemp = resolvedTempAndDelta.displayTemp
        val resolvedDeltaFromYesterday = resolvedTempAndDelta.deltaFromYesterday

        // Dynamic icon showing the current temperature.
        val textMeasurer = remember { createTrayTextMeasurer() }
        val appIcon = remember(resolvedCurrentTemp, currentConfig?.useCelsius) {
            val useCelsius = currentConfig?.useCelsius
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
                    (currentConfig?.let { "lat=${it.lat} lon=${it.lon} src=${it.weatherSource}" }
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
            val pickerState = rememberWindowState(
                position = WindowPosition(Alignment.Center),
                width = 560.dp,
                height = 680.dp,
            )
            Window(
                onCloseRequest = { pickerVisible = false },
                state = pickerState,
                title = "Set Weather Location",
                icon = appIcon,
                onKeyEvent = { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                        pickerVisible = false
                        true
                    } else {
                        false
                    }
                }
            ) {
                LocationPicker(locationResolver, allowAutoSelect = config == null) { resolved ->
                    val saved = resolved.toConfig()
                    saveConfigAndNotify(saved, "location-picker")
                    pickerVisible = false
                    popupVisible = true
                }
            }
        }

        if (settingsVisible && config != null) {
            val settingsConfig = config!!
            val settingsState = rememberWindowState(
                position = if (settingsConfig.settingsWindowX != null && settingsConfig.settingsWindowY != null) {
                    WindowPosition(settingsConfig.settingsWindowX.dp, settingsConfig.settingsWindowY.dp)
                } else {
                    WindowPosition(Alignment.Center)
                },
                width = settingsConfig.settingsWindowWidth?.dp ?: 500.dp,
                height = settingsConfig.settingsWindowHeight?.dp ?: 700.dp,
            )

            // Persist size/position on a debounce, the same way the popup and observations windows do.
            // Tagged as its own source so it is distinguishable in CONFIG_SAVE lines from the edits
            // made *inside* the window.
            LaunchedEffect(settingsState.position, settingsState.size) {
                kotlinx.coroutines.delay(1000)
                val pos = settingsState.position
                val latestConfig = config ?: return@LaunchedEffect
                if (pos is WindowPosition.Absolute) {
                    val newConfig = latestConfig.copy(
                        settingsWindowX = pos.x.value,
                        settingsWindowY = pos.y.value,
                        settingsWindowWidth = settingsState.size.width.value,
                        settingsWindowHeight = settingsState.size.height.value,
                    )
                    if (newConfig != latestConfig) {
                        saveConfigAndNotify(newConfig, "settings-window-geometry")
                    }
                }
            }
            Window(
                onCloseRequest = { closeSettings() },
                state = settingsState,
                title = "Weather Settings",
                icon = appIcon,
                onKeyEvent = { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                        closeSettings()
                        true
                    } else {
                        false
                    }
                }
            ) {
                SettingsWindow(
                    config = config!!, // guarded by `config != null` in outer if
                    onClose = { closeSettings() },
                    onSave = { newConfig ->
                        saveConfigAndNotify(newConfig, "settings")
                        settingsDraft = null
                    },
                    onDraftChanged = { draft ->
                        settingsDraft = draft.takeIf { it != config }
                    },
                    onExit = { quit() },
                    onUpdateLocation = {
                        pickerVisible = true
                    },
                    onOpenObservations = {
                        observationsVisible = true
                        obsShowRequestId++
                    },
                    isRefreshing = refreshInFlight,
                    onRefreshBreadcrumb = { msg -> weatherDao.log("REFRESH_CLICK", msg, "INFO") },
                    onRefreshData = { requestFullRefresh("settings") },
                    onViewAppLogs = {
                        appLogsVisible = true
                    },
                    locationResolver = sharedLocationResolver,
                    onSubmitBugReport = {
                        openInBrowser(buildBugReportMailto(config!!))
                    },
                )
            }
        }

        if (popupVisible && currentConfig != null) {
            val windowState = rememberWindowState(
                position = if (currentConfig.windowX != null && currentConfig.windowY != null) {
                    WindowPosition(currentConfig.windowX.dp, currentConfig.windowY.dp)
                } else {
                    WindowPosition(Alignment.TopEnd)
                },
                width = currentConfig.windowWidth?.dp ?: 380.dp,
                height = currentConfig.windowHeight?.dp ?: 320.dp,
            )

            // Persist window position and size changes with a debounce to avoid excessive disk writes.
            LaunchedEffect(windowState.position, windowState.size) {
                kotlinx.coroutines.delay(1000)
                val pos = windowState.position
                val latestConfig = config ?: return@LaunchedEffect
                if (pos is WindowPosition.Absolute) {
                    val newConfig = latestConfig.copy(
                        windowX = pos.x.value,
                        windowY = pos.y.value,
                        windowWidth = windowState.size.width.value,
                        windowHeight = windowState.size.height.value
                    )
                    if (newConfig != latestConfig) {
                        saveConfigAndNotify(newConfig, "popup-window-geometry")
                    }
                }
            }

            Window(
                onCloseRequest = { popupVisible = false },
                state = windowState,
                title = "Weather Widget",
                icon = appIcon,
                onKeyEvent = { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) {
                        false
                    } else when (keyEvent.key) {
                        Key.Escape -> { popupVisible = false; true }
                        Key.DirectionLeft -> arrowKeyHandler?.invoke(true) ?: false
                        Key.DirectionRight -> arrowKeyHandler?.invoke(false) ?: false
                        else -> false
                    }
                }
            ) {
                LaunchedEffect(Unit) {
                    Log.i(TAG, "Window composed/visible now")
                }
                // Raise an already-open (possibly buried) window on every show request.
                // FrameWindowScope exposes the underlying AWT ComposeWindow as `window`.
                LaunchedEffect(showRequestId) {
                    Log.i(TAG, "Window show request received: showRequestId=$showRequestId")
                    if (windowState.isMinimized) {
                        windowState.isMinimized = false
                    }
                    if (window is java.awt.Frame) {
                        val state = window.extendedState
                        if ((state and java.awt.Frame.ICONIFIED) != 0) {
                            window.extendedState = java.awt.Frame.NORMAL
                        }
                    }
                    window.toFront()
                    window.requestFocus()
                }
                WidgetPopup(
                    config = currentConfig,
                    forecast = forecast,
                    dataStatus = dataStatus,
                    resolvedCurrentTemp = resolvedCurrentTemp,
                    resolvedDeltaFromYesterday = resolvedDeltaFromYesterday,
                    onUpdateLocation = {
                        popupVisible = false
                        pickerVisible = true
                    },
                    onUpdateConfig = { newConfig ->
                        saveConfigAndNotify(newConfig, "popup")
                    },
                    onOpenSettings = {
                        settingsDraft = null
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
                    onRegisterArrowKeyHandler = { arrowKeyHandler = it },
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
}

internal fun createTrayTextMeasurer(): TextMeasurer =
    TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultLayoutDirection = LayoutDirection.Ltr,
        defaultDensity = Density(1f),
    )



@Composable
internal fun WidgetPopup(
    config: DesktopConfig,
    forecast: ForecastResult?,
    dataStatus: DataStatus,
    resolvedCurrentTemp: Float? = null,
    resolvedDeltaFromYesterday: Float? = null,
    onUpdateLocation: () -> Unit,
    onUpdateConfig: (DesktopConfig) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenObservations: () -> Unit,
    onOpenHistory: (viewedDate: LocalDate) -> Unit = {},
    onRegisterArrowKeyHandler: (((left: Boolean) -> Boolean)?) -> Unit = {},
    onNeedHistory: (Int) -> Unit = {},
    onNeedHourlyRefresh: (onComplete: (List<HourlyForecast>) -> Unit) -> Unit = { _ -> },
    onDayClickAudit: (String) -> Unit = {},
    historyFetchToast: String? = null,
    currentTempFetchError: String? = null,
    currentTempFetchIsWarmup: Boolean = false,
    onDismissCurrentTempError: () -> Unit = {},
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      // One shared scale for header + graph so everything grows together with the window.
      // Density-independent (maxHeight and 320.dp both carry density). ~2x at a typical window.
      val uiScale = (maxHeight / 320.dp).coerceIn(1f, 3f)
      Surface(modifier = Modifier.fillMaxSize()) {
        when (dataStatus) {
            is DataStatus.Error -> CenteredMessage(dataStatus.message)
            is DataStatus.Loading -> CenteredMessage("Loading…")
            is DataStatus.NoData -> CenteredMessage("Tap to configure")
            is DataStatus.Live, is DataStatus.Stale -> {
                val snapshot = forecast ?: return@Surface
                // Published by the daily branch below from the days it actually renders. The header
                // is composed before the daily surface measures itself, so this is reported upward
                // rather than recomputed here: the daily column count depends on the graph area's
                // width, and zoom-out prepends history columns that `getVisibleDateRange` knows
                // nothing about (see DesktopDailyForecastModel.build). Defaults to true so the
                // first frame shows both buttons rather than flashing one in.
                var dailyTodayInView by remember { mutableStateOf(true) }
                var dailyObservationsInView by remember { mutableStateOf(true) }
                Column(modifier = Modifier.fillMaxSize().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 2.dp)) {
                    WidgetHeader(
                        config = config,
                        forecast = snapshot,
                        resolvedCurrentTemp = resolvedCurrentTemp,
                        resolvedDeltaFromYesterday = resolvedDeltaFromYesterday,
                        onUpdateConfig = onUpdateConfig,
                        onOpenSettings = onOpenSettings,
                        onOpenObservations = onOpenObservations,
                        onOpenHistory = onOpenHistory,
                        onUpdateLocation = onUpdateLocation,
                        headerTime = LocalDateTime.now().plusHours(config.hourlyOffset.toLong()),
                        scale = uiScale,
                        todayInView = dailyTodayInView,
                        observationsInView = dailyObservationsInView,
                    )

                    Spacer(Modifier.height(4.dp))

                    // Transient banner for day-taps that have no hourly data (e.g. NWS horizon ends
                    // mid-week). Declared unconditionally (Compose hook ordering) and consumed only
                    // inside the daily-view branch below.
                    var noHourlyMessage by remember { mutableStateOf<String?>(null) }

                    val isHourly = config.viewMode.isHourly
                    if (isHourly) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f).testTag("hourly_temperature_surface")) {
                            // Shared scroll-zoom + drag-pan handlers for all three hourly graphs.
                            val handleZoomScroll: (Float, Int) -> Unit = { deltaZoom, cursorOffset ->
                                val newFactor = (config.zoomFactor + deltaZoom).coerceIn(0f, 1f)
                                if (newFactor != config.zoomFactor) {
                                    // Zooming in re-centers on the cursor; zooming out keeps the current center.
                                    val newOffset = if (deltaZoom < 0f) {
                                        cursorOffset.coerceIn(MIN_HOURLY_OFFSET, MAX_HOURLY_OFFSET)
                                    } else {
                                        config.hourlyOffset
                                    }
                                    onUpdateConfig(config.copy(zoomFactor = newFactor, hourlyOffset = newOffset))
                                }
                            }
                            val handlePan: (Int) -> Unit = { deltaHours ->
                                val newOffset = (config.hourlyOffset + deltaHours).coerceIn(MIN_HOURLY_OFFSET, MAX_HOURLY_OFFSET)
                                if (newOffset != config.hourlyOffset) {
                                    onUpdateConfig(config.copy(hourlyOffset = newOffset))
                                }
                            }
                            // Body-tap zoom toggle, shared by all three hourly graphs: cycle the 3 zoom
                            // stages (WIDE→NARROW→THREE_DAY→WIDE), matching Android, and re-center on the
                            // tapped hour. The wheel may have moved us off a stage, so snap to the nearest
                            // one before advancing.
                            val handleToggleZoom: (Int) -> Unit = { clickedOffset ->
                                val current = ZoomStage.nearestByTotalSpan(
                                    DesktopGraphUtils.totalSpanHoursFor(config.zoomFactor),
                                    config.narrowZoomSpanHours,
                                )
                                val next = current.next()
                                onUpdateConfig(
                                    config.copy(
                                        zoomFactor = DesktopGraphUtils.zoomFactorForStage(
                                            next,
                                            config.narrowZoomSpanHours,
                                        ),
                                        hourlyOffset = clickedOffset.coerceIn(MIN_HOURLY_OFFSET, MAX_HOURLY_OFFSET),
                                    )
                                )
                            }
                            // ←/→ pan the hourly window by the same nav-jump the arrow buttons use.
                            SideEffect {
                                onRegisterArrowKeyHandler { left ->
                                    val jump = DesktopGraphUtils.navJumpHours(config.zoomFactor)
                                    if (left && config.hourlyOffset > MIN_HOURLY_OFFSET) {
                                        handlePan(-jump); true
                                    } else if (!left && config.hourlyOffset < MAX_HOURLY_OFFSET) {
                                        handlePan(jump); true
                                    } else false
                                }
                            }
                            // Whenever zoom or pan changes, ask for deeper history if the left edge of the
                            // visible window now reaches further back than what's cached. The offset is
                            // negative when panned into the past, so subtracting it extends the reach.
                            LaunchedEffect(config.zoomFactor, config.hourlyOffset) {
                                val earliestVisibleHoursBack =
                                    DesktopGraphUtils.backHoursFor(config.zoomFactor) - config.hourlyOffset
                                onNeedHistory(earliestVisibleHoursBack)
                            }
                            if (config.viewMode == ViewMode.CLOUD_COVER) {
                                CloudCoverGraph(
                                    hourly = snapshot.hourly,
                                    displaySourceId = config.weatherSource,
                                    latitude = config.lat,
                                    longitude = config.lon,
                                    modifier = Modifier.fillMaxSize(),
                                    centerOffsetHours = config.hourlyOffset,
                                    zoomFactor = config.zoomFactor,
                                    scale = uiScale,
                                    onViewModeChange = { targetView ->
                                        onUpdateConfig(config.copy(viewMode = targetView))
                                    },
                                    onToggleZoom = handleToggleZoom,
                                    onZoomScroll = handleZoomScroll,
                                    onPan = handlePan,
                                )
                            } else if (config.viewMode == ViewMode.PRECIPITATION) {
                                PrecipitationGraph(
                                    hourly = snapshot.hourly,
                                    observations = snapshot.rawObservations,
                                    displaySourceId = config.weatherSource,
                                    latitude = config.lat,
                                    longitude = config.lon,
                                    modifier = Modifier.fillMaxSize(),
                                    centerOffsetHours = config.hourlyOffset,
                                    zoomFactor = config.zoomFactor,
                                    scale = uiScale,
                                    onViewModeChange = { targetView ->
                                        onUpdateConfig(config.copy(viewMode = targetView))
                                    },
                                    onToggleZoom = handleToggleZoom,
                                    onZoomScroll = handleZoomScroll,
                                    onPan = handlePan,
                                )
                            } else {
                                TemperatureGraph(
                                    hourly = snapshot.hourly,
                                    currentTemp = snapshot.currentTemp,
                                    currentObservedAt = snapshot.currentObservedAt,
                                    observations = snapshot.rawObservations,
                                    displaySourceId = config.weatherSource,
                                    latitude = config.lat,
                                    longitude = config.lon,
                                    modifier = Modifier.fillMaxSize(),
                                    centerOffsetHours = config.hourlyOffset,
                                    zoomFactor = config.zoomFactor,
                                    scale = uiScale,
                                    personalStationWeight = config.personalStationWeight(),
                                    onViewModeChange = { targetView ->
                                        onUpdateConfig(config.copy(viewMode = targetView))
                                    },
                                    onToggleZoom = handleToggleZoom,
                                    onZoomScroll = handleZoomScroll,
                                    onPan = handlePan,
                                    useCelsius = config.useCelsius,
                                )
                            }
                            NavArrow(
                                alignment = Alignment.CenterStart,
                                enabled = config.hourlyOffset > MIN_HOURLY_OFFSET,
                                testTag = "hourly_nav_left",
                            ) {
                                val jump = DesktopGraphUtils.navJumpHours(config.zoomFactor)
                                val newOffset = (config.hourlyOffset - jump).coerceAtLeast(MIN_HOURLY_OFFSET)
                                Log.d(TAG, "HourlyNav: left jump=${-jump}h zoom=${config.zoomFactor} offset ${config.hourlyOffset}->$newOffset")
                                onUpdateConfig(config.copy(hourlyOffset = newOffset))
                            }
                            NavArrow(
                                alignment = Alignment.CenterEnd,
                                enabled = config.hourlyOffset < MAX_HOURLY_OFFSET,
                                testTag = "hourly_nav_right",
                            ) {
                                val jump = DesktopGraphUtils.navJumpHours(config.zoomFactor)
                                val newOffset = (config.hourlyOffset + jump).coerceAtMost(MAX_HOURLY_OFFSET)
                                Log.d(TAG, "HourlyNav: right jump=+${jump}h zoom=${config.zoomFactor} offset ${config.hourlyOffset}->$newOffset")
                                onUpdateConfig(config.copy(hourlyOffset = newOffset))
                            }
                            // Transient banner while an on-demand deep-history pull is in flight (or
                            // briefly on failure). Drawn last so it floats over the graph + arrows.
                            historyFetchToast?.let { msg ->
                                Surface(
                                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.Black.copy(alpha = 0.72f),
                                ) {
                                    Text(
                                        text = msg,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        color = Color.White,
                                        fontSize = (12f * uiScale).sp,
                                    )
                                }
                            }

                            // Persistent current temp fetch failure warning label. Warm-up
                            // (post-wake offline grace window) renders informational blue; a real
                            // failure renders the red error treatment.
                            currentTempFetchError?.let { msg ->
                                val surfaceColor = if (currentTempFetchIsWarmup) Color(0xFF1B2A3A) else Color(0xFF3E1C1C)
                                val borderColor = if (currentTempFetchIsWarmup) Color(0xFF64B5F6) else Color(0xFFE57373)
                                val titleColor = if (currentTempFetchIsWarmup) Color(0xFFBBDEFB) else Color(0xFFFFCDD2)
                                val bodyColor = if (currentTempFetchIsWarmup) Color(0xFF90CAF9) else Color(0xFFEF9A9A)
                                Surface(
                                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = surfaceColor.copy(alpha = 0.95f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            val lines = msg.split("\n")
                                            if (lines.isNotEmpty()) {
                                                Text(
                                                    text = lines[0],
                                                    color = titleColor,
                                                    fontSize = (13f * uiScale).sp,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                                )
                                                lines.drop(1).forEach { line ->
                                                    Text(
                                                        text = line,
                                                        color = bodyColor,
                                                        fontSize = (11f * uiScale).sp,
                                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                        modifier = Modifier.padding(top = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.width(16.dp))
                                        Box(
                                            modifier = Modifier
                                                .size((24f * uiScale).dp)
                                                .clickable { onDismissCurrentTempError() },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "×",
                                                color = bodyColor,
                                                fontSize = (18f * uiScale).sp,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f).testTag("daily_forecast_surface")) {
                            val dimensions = DesktopDailyForecastModel.dimensions(
                                widthDp = maxWidth.value.roundToInt(),
                                heightDp = maxHeight.value.roundToInt(),
                            )
                            val dailyState = DesktopDailyForecastModel.build(
                                config = config,
                                forecast = snapshot,
                                dimensions = dimensions,
                            )

                            // Report today's on-screen status up to the header: it decides whether
                            // to show the current-observations button and which date the
                            // forecast-history button opens. Read off the rendered days, the only
                            // source that accounts for clamping and zoom-out history columns.
                            val todayOnScreen = dailyState.days.any { it.isToday }
                            val observationsOnScreen =
                                dailyState.days.any { it.isToday || it.daysFromToday == -1 }
                            SideEffect {
                                dailyTodayInView = todayOnScreen
                                dailyObservationsInView = observationsOnScreen
                            }

                            // Sync both clamped values in one write so a simultaneous offset+zoom clamp
                            // doesn't clobber each other (two separate copy() calls off the same config would).
                            LaunchedEffect(dailyState.clampedDateOffset, dailyState.clampedExtraHistory) {
                                if (dailyState.clampedDateOffset != config.dateOffset ||
                                    dailyState.clampedExtraHistory != config.dailyExtraHistory) {
                                    onUpdateConfig(config.copy(
                                        dateOffset = dailyState.clampedDateOffset,
                                        dailyExtraHistory = dailyState.clampedExtraHistory,
                                    ))
                                }
                            }

                            // Snap-step horizontal drag → day offset; direction-gated by the same bounds
                            // the nav arrows use. A fast flick may emit >1 step; over-panning a column or
                            // two is harmless (model tolerates empty edge columns) and self-heals next gesture.
                            val handleDailyPan: (Int) -> Unit = { steps ->
                                val blocked = (steps < 0 && !dailyState.canNavigateLeft) ||
                                    (steps > 0 && !dailyState.canNavigateRight)
                                if (!blocked) {
                                    val target = dailyState.clampedDateOffset + steps
                                    if (target != config.dateOffset) onUpdateConfig(config.copy(dateOffset = target))
                                }
                            }
                            // Scroll-wheel zoom → extra history days; clamped to model-computed bounds.
                            val handleDailyZoom: (Int) -> Unit = { delta ->
                                val blocked = (delta > 0 && !dailyState.canZoomOut) ||
                                    (delta < 0 && !dailyState.canZoomIn)
                                if (!blocked) {
                                    val target = (dailyState.clampedExtraHistory + delta).coerceAtLeast(0)
                                    if (target != config.dailyExtraHistory) {
                                        onUpdateConfig(config.copy(dailyExtraHistory = target))
                                    }
                                }
                            }
                            val dailyInput = Modifier.dailyPanZoomInput(
                                columnCount = dailyState.days.size,
                                onPanDays = handleDailyPan,
                                onZoomScroll = handleDailyZoom,
                            )
                            // ←/→ step one day, gated by the same data bounds as the nav arrows.
                            SideEffect {
                                onRegisterArrowKeyHandler { left ->
                                    if (left && dailyState.canNavigateLeft) {
                                        handleDailyPan(-1); true
                                    } else if (!left && dailyState.canNavigateRight) {
                                        handleDailyPan(1); true
                                    } else false
                                }
                            }

                            val handleDayClick: (LocalDate, DayClickResolver.DayTapZone) -> Unit = { clickedDate, zone ->
                                val visibleSourceIds = config.visibleSources.toSet()
                                val clickedDay = dailyState.days.find { it.date == clickedDate }
                                val precipProb = clickedDay?.forecast?.precipProbability
                                    ?: clickedDay?.snapshot?.precipProbability
                                val targetView = DayClickResolver.resolveView(zone, clickedDay?.iconName, precipProb)
                                val newOffset = DayClickResolver.calculateHourlyOffset(LocalDateTime.now(), clickedDate)
                                val clickSource = when (zone) {
                                    DayClickResolver.DayTapZone.MAIN_COLUMN -> "graph_day"
                                    DayClickResolver.DayTapZone.BOTTOM_ICON -> "graph_bottom_day"
                                }
                                onDayClickAudit(
                                    "date=$clickedDate zone=$zone targetView=$targetView offset=$newOffset " +
                                        "icon=${clickedDay?.iconName} precip=$precipProb clickSource=$clickSource",
                                )
                                if (NoHourlyChecker.hasHourlyForDay(snapshot.hourly, clickedDate, visibleSourceIds)) {
                                    noHourlyMessage = null
                                    onUpdateConfig(dayClickConfig(config, clickedDate, dailyState.days, zone))
                                } else {
                                    // Two-phase flow mirroring Android: show a pending banner, resolve
                                    // against the freshest in-memory hourly data, then replace it with a
                                    // result banner (data present, or genuinely missing — fetches already
                                    // request the maximum horizon, so there is nothing wider to pull).
                                    val dayLabel = NoHourlyChecker.formatDayLabel(clickedDate)
                                    noHourlyMessage = NoHourlyChecker.buildPendingMessage(dayLabel)
                                    onNeedHourlyRefresh { newHourly ->
                                        val hasData = NoHourlyChecker.hasHourlyForDay(newHourly, clickedDate, visibleSourceIds)
                                        val endLabel =
                                            if (!hasData) NoHourlyChecker.lastHourlyEndLabel(newHourly, visibleSourceIds)
                                            else null
                                        noHourlyMessage = NoHourlyChecker.buildResultMessage(dayLabel, hasData, endLabel)
                                    }
                                }
                            }

                            if (dailyState.dimensions.useGraph) {
                                DailyForecastGraph(
                                    state = dailyState,
                                    modifier = Modifier.fillMaxSize().then(dailyInput),
                                    scale = uiScale,
                                    onDayClick = handleDayClick,
                                    useCelsius = config.useCelsius,
                                )
                            } else {
                                DailyForecastTextMode(
                                    state = dailyState,
                                    modifier = Modifier.fillMaxSize().then(dailyInput),
                                    onDayClick = handleDayClick,
                                    useCelsius = config.useCelsius,
                                )
                            }

                            NavArrow(
                                alignment = Alignment.CenterStart,
                                enabled = dailyState.canNavigateLeft,
                                testTag = "daily_nav_left",
                            ) {
                                onUpdateConfig(config.copy(dateOffset = dailyState.clampedDateOffset - 1))
                            }
                            NavArrow(
                                alignment = Alignment.CenterEnd,
                                enabled = dailyState.canNavigateRight,
                                testTag = "daily_nav_right",
                            ) {
                                onUpdateConfig(config.copy(dateOffset = dailyState.clampedDateOffset + 1))
                            }

                            noHourlyMessage?.let { msg ->
                                LaunchedEffect(msg) {
                                    kotlinx.coroutines.delay(NoHourlyChecker.MESSAGE_DURATION_MS)
                                    noHourlyMessage = null
                                }
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = msg,
                                        textAlign = TextAlign.Center,
                                        color = Color.White.copy(alpha = 0.88f),
                                        fontSize = (13f * uiScale).sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.testTag("no_hourly_message"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun NavArrow(
    alignment: Alignment,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = alignment) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.width(28.dp).fillMaxHeight().testTag(testTag),
        ) {
            Icon(
                imageVector = if (alignment == Alignment.CenterStart) {
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = null,
                tint = Color.White.copy(alpha = if (enabled) 0.75f else 0.18f),
            )
        }
    }
}



@Composable
private fun WidgetHeader(
    config: DesktopConfig,
    forecast: ForecastResult,
    resolvedCurrentTemp: Float? = null,
    resolvedDeltaFromYesterday: Float? = null,
    onUpdateConfig: (DesktopConfig) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenObservations: () -> Unit,
    onOpenHistory: (viewedDate: LocalDate) -> Unit = {},
    onUpdateLocation: () -> Unit,
    headerTime: LocalDateTime = LocalDateTime.now(),
    scale: Float = 1f,
    /** Whether today is among the days the daily view is rendering; drives the history target date. */
    todayInView: Boolean = true,
    /** Whether today or yesterday is on screen; drives the current-observations button. */
    observationsInView: Boolean = true,
) {
    val showWeatherSummary = config.viewMode.isHourly
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }
    val targetHour = remember(headerTime) { headerTime.truncatedTo(ChronoUnit.HOURS) }

    val nowEpoch = System.currentTimeMillis()
    val zoneId = remember { ZoneId.systemDefault() }
    val nowLocal = remember(nowEpoch, zoneId) {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(nowEpoch), zoneId)
    }
    val displaySource = remember(config.weatherSource) {
        WeatherSource.fromDisplaySource(config.weatherSource)
    }
    val displayTemp = resolvedCurrentTemp ?: forecast.currentTemp
    // The header shows the DELTA FROM YESTERDAY (observed vs blended actual 24h earlier). It is
    // pan-independent, so it always shows when it exists and clears the noise threshold — no
    // graph-window gate, matching Android's post-swap header.
    val deltaVal = resolvedDeltaFromYesterday ?: forecast.deltaFromYesterday
    val deltaTemp = deltaVal?.takeIf { kotlin.math.abs(it) >= 0.1f }

    val todayForecast = remember(forecast.daily, nowLocal) {
        forecast.daily.firstOrNull { it.date == nowLocal.toLocalDate().toString() }
    }
    val precipProb = remember(forecast.hourly, displaySource, todayForecast, nowLocal) {
        PrecipProbabilityCalculator.getNext8HourPrecipProbability(
            hourlyForecasts = forecast.hourly,
            displaySourceId = displaySource.id,
            fallbackSourceId = WeatherSource.GENERIC_GAP.id,
            fallbackDailyProbability = todayForecast?.precipProbability,
            referenceTime = nowLocal
        )?.takeIf { it > 0 }
    }
    val isHourly = config.viewMode.isHourly
    // Header rain-chance sizing, matching Android: probability-scaled (shared step table), plus
    // the NIGHT_SCALE shrink only in the daily view when the next-8h rain is predominantly
    // overnight. Base size is the desktop header temp size (Android's precip base == its temp base).
    val precipFontScale = remember(precipProb, forecast.hourly, displaySource, nowLocal, isHourly, config.lat, config.lon) {
        precipProb?.let { prob ->
            val isNightPrecip = !isHourly && run {
                val sunTimes = com.weatherwidget.util.SunPositionUtils.getSunTimes(nowLocal, config.lat, config.lon)
                PrecipProbabilityCalculator.isNext8HourPrecipPredominantlyNight(
                    hourlyForecasts = forecast.hourly,
                    displaySourceId = displaySource.id,
                    fallbackSourceId = WeatherSource.GENERIC_GAP.id,
                    referenceTime = nowLocal,
                    sunriseHour = sunTimes.sunriseHour,
                    sunsetHour = sunTimes.sunsetHour,
                )
            }
            HeaderPrecipSizing.headerPrecipFontScale(prob, isDailyView = !isHourly, isNightPrecip = isNightPrecip)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left cluster: current temp/icon clickable to toggle view
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        val targetMode = if (isHourly) ViewMode.DAILY else ViewMode.HOURLY
                        onUpdateConfig(config.copy(viewMode = targetMode))
                    }.testTag("current_temp_toggle")
                ) {
                    androidx.compose.foundation.Image(
                        painter = WeatherIcon.painter(forecast.currentCondition),
                        contentDescription = null,
                        modifier = Modifier.size((22 * scale).dp).padding(end = 4.dp)
                    )
                    Text(
                        text = displayTemp?.let { formatTrayTemperature(it, config.useCelsius) + "°" } ?: "—",
                        style = MaterialTheme.typography.displaySmall,
                        fontSize = (15 * scale).sp
                    )
                }
                if (deltaTemp != null) {
                    Spacer(Modifier.width(2.dp))
                    val displayDelta = if (config.useCelsius) deltaTemp / 1.8f else deltaTemp
                    Text(
                        text = String.format(Locale.US, "%+.1f", displayDelta),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = (11 * scale).sp,
                        color = Color(0xFFFF6B35),
                        modifier = Modifier.align(Alignment.CenterVertically).offset(y = 2.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    // Caption clarifying the delta's meaning; smaller/dimmer than the delta itself.
                    Text(
                        text = "from yest",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = (9 * scale).sp,
                        color = Color(0xFFFF6B35).copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.CenterVertically).offset(y = 2.dp)
                    )
                }
                if (precipProb != null && precipFontScale != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$precipProb%",
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = (HeaderPrecipSizing.HEADER_TEMP_BASE_SP * precipFontScale * scale).sp,
                        color = Color(0xFF4FC3F7),
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .offset(y = 2.dp)
                            .clickable {
                                onUpdateConfig(config.copy(viewMode = ViewMode.PRECIPITATION))
                            }
                    )
                }
            }

            // Center cluster: view-switch icons when hourly, else date text
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isHourly) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((8 * scale).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Cycling graph selector
                        val currentView = config.viewMode
                        val (nextEmoji, nextView) = when (currentView) {
                            ViewMode.CLOUD_COVER -> "🌧️" to ViewMode.PRECIPITATION
                            ViewMode.PRECIPITATION -> "🌡️" to ViewMode.HOURLY
                            else -> "☁️" to ViewMode.CLOUD_COVER
                        }
                        Text(
                            text = nextEmoji,
                            fontSize = (13 * scale).sp,
                            color = Color.White,
                            modifier = Modifier.clickable {
                                onUpdateConfig(config.copy(viewMode = nextView))
                            }.testTag("graph_selector")
                        )
                        // Station observations button — ports Android's ic_thermometer drawable
                        // (drawable/ic_thermometer.xml, tinted dim white like TemperatureTouchTargets'
                        // 0xAAFFFFFF) instead of the 🌡️ emoji, so it no longer collides with the graph
                        // selector's HOURLY (🌡️) cycle hint.
                        Icon(
                            painter = androidx.compose.ui.res.painterResource("drawable/ic_thermometer.xml"),
                            contentDescription = "Weather station observations",
                            tint = Color.White.copy(alpha = 0.67f),
                            modifier = Modifier.size((15 * scale).dp).clickable {
                                onOpenObservations()
                            }.testTag("open_observations_header")
                        )
                        // Home/Daily view mode — ports Android's ic_home line icon
                        // (drawable/ic_home.xml) instead of the 🏠 emoji for parity.
                        Icon(
                            painter = androidx.compose.ui.res.painterResource("drawable/ic_home.xml"),
                            contentDescription = "Daily view",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size((15 * scale).dp).clickable {
                                onUpdateConfig(config.copy(viewMode = ViewMode.DAILY))
                            }.testTag("switch_to_daily")
                        )
                        // Forecast history (how each day's forecast evolved) — ports Android's
                        // rising line-chart icon (drawable/ic_forecast_history_line.xml),
                        // shown right of the home icon on the hourly graph. Opens on the viewed
                        // window's center date (Android: centerTime.toLocalDate()), so panning
                        // back to Wednesday and tapping opens Wednesday, not today.
                        Icon(
                            painter = androidx.compose.ui.res.painterResource("drawable/ic_forecast_history_line.xml"),
                            contentDescription = "Forecast history",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size((15 * scale).dp).clickable {
                                onOpenHistory(targetHour.toLocalDate())
                            }.testTag("open_forecast_history")
                        )
                    }
                } else {
                    // Daily view: date, then the same two buttons the hourly header carries.
                    // Sizes/tints copied from the hourly branch so the two headers match.
                    // Buttons then date, matching Android's daily header. Android cannot reliably
                    // fit the date to the LEFT of its centred buttons (the left cluster reaches
                    // past it on real widgets), so both platforms settle on this order rather than
                    // disagreeing.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((10 * scale).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Current observations are inherently now-ish, so this drops when today AND
                        // yesterday are panned off screen — matching Android's daily header.
                        if (observationsInView) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource("drawable/ic_thermometer.xml"),
                                contentDescription = "Weather station observations",
                                tint = Color.White.copy(alpha = 0.67f),
                                modifier = Modifier.size((15 * scale).dp).clickable {
                                    onOpenObservations()
                                }.testTag("open_observations_header_daily")
                            )
                        }
                        // Opens today while today is on screen, otherwise the viewed date.
                        Icon(
                            painter = androidx.compose.ui.res.painterResource("drawable/ic_forecast_history_line.xml"),
                            contentDescription = "Forecast history",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size((15 * scale).dp).clickable {
                                onOpenHistory(if (todayInView) LocalDate.now() else targetHour.toLocalDate())
                            }.testTag("open_forecast_history_daily")
                        )
                        Text(
                            text = targetHour.format(dateFormatter),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = (12 * scale).sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Right cluster: API source + Settings gear
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val visibleSources = config.visibleSources
                // Show the shared short label (e.g. "Meteo"), matching Android's API indicator, rather
                // than the raw stored id ("OPEN_METEO"). One source of truth: WeatherSource.shortDisplayName.
                val sourceLabel = WeatherSource.fromDisplaySource(config.weatherSource).shortDisplayName
                if (visibleSources.size > 1) {
                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = (10 * scale).sp,
                        modifier = Modifier.clickable {
                            val nextIdx = (visibleSources.indexOf(config.weatherSource) + 1) % visibleSources.size
                            onUpdateConfig(config.copy(weatherSource = visibleSources[nextIdx]))
                        }.padding(end = 6.dp)
                    )
                } else {
                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = (10 * scale).sp,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                Icon(
                    painter = androidx.compose.ui.res.painterResource("drawable/ic_settings_gear.xml"),
                    contentDescription = "Settings",
                    modifier = Modifier.size((14 * scale).dp).clickable { onOpenSettings() },
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun DailyForecastTextMode(
    state: DesktopDailyViewState,
    modifier: Modifier = Modifier,
    onDayClick: (LocalDate, DayClickResolver.DayTapZone) -> Unit = { _, _ -> },
    useCelsius: Boolean,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        state.days.forEach { day ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("day_tab_${day.date}")
                    .clickable {
                        onDayClick(day.date, DayClickResolver.DayTapZone.MAIN_COLUMN)
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = day.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = (MaterialTheme.typography.labelSmall.fontSize.value * 1.3f).sp),
                    color = if (day.isToday) Color.Yellow else Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                )
                val high = listOfNotNull(day.solidHigh, day.forecastHigh, day.snapshotHigh).maxOrNull()
                val low = listOfNotNull(day.solidLow, day.forecastLow, day.snapshotLow).minOrNull()
                Text(
                    text = com.weatherwidget.shared.util.TempUtils.formatTemp(high, useCelsius) ?: "--",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = (MaterialTheme.typography.titleMedium.fontSize.value * 1.3f).sp),
                    color = Color.White,
                    maxLines = 1,
                )
                if (state.dimensions.cols >= 2) {
                    Text(
                        text = com.weatherwidget.shared.util.TempUtils.formatTemp(low, useCelsius) ?: "--",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = (MaterialTheme.typography.labelSmall.fontSize.value * 1.3f).sp),
                        color = Color.White.copy(alpha = 0.62f),
                        maxLines = 1,
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

private fun formatAge(ageMillis: Long): String {
    val minutes = ageMillis / 60_000
    val hours = minutes / 60
    return when {
        hours >= 24 -> "${hours / 24}d ${hours % 24}h old"
        hours > 0 -> "${hours}h ${minutes % 60}m old"
        else -> "${minutes}m old"
    }
}
