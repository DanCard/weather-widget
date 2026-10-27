package com.weatherwidget.desktop

import com.weatherwidget.data.remote.weathernext.WeatherNextDomain
import com.weatherwidget.shared.rainmap.WeatherNextLoop
import com.weatherwidget.shared.util.DayTap
import com.weatherwidget.shared.util.SourceSwitchFetchRule
import com.weatherwidget.shared.util.WeatherSourceOrdering
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
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.deriveDataStatus
import com.weatherwidget.data.model.isOfflineException
import com.weatherwidget.data.model.isOfflineExceptionName
import com.weatherwidget.shared.util.LocationChangePaintPolicy
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
import com.weatherwidget.data.local.desktop.DesktopQuotaState
import com.weatherwidget.data.remote.RateLimitNotice
import com.weatherwidget.data.remote.QuotaNotice
import com.weatherwidget.data.remote.FetchErrorCode
import com.weatherwidget.data.remote.GoogleQuota
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
import androidx.compose.runtime.CompositionLocalProvider
import com.weatherwidget.shared.util.RefreshNotice
import com.weatherwidget.shared.util.RefreshOutcome
import com.weatherwidget.shared.observations.ActualsProviderResolver
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
        LaunchedEffect(config) {
            DesktopActualsPreference.publish(config)
        }

        // Persistence layer
        val weatherDb = remember { DesktopWeatherDatabase(DesktopDbPaths.defaultDbPath()).apply { initialize() } }
        val weatherDao = remember { DesktopWeatherDao(weatherDb) }
        remember(weatherDao) { DesktopSourceViews.install(weatherDao) }
        // The four-second refresh toast every window shows (plans/261015-refresh-queued-toast-all-surfaces.md).
        val refreshNotice = remember { RefreshNoticeState(log = { weatherDao.log(RefreshNotice.TAG, it, "INFO") }) }
        RefreshNoticeExpiry(refreshNotice)

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
        // Once a local day, what the shared estimator makes of source_view_days (checked on each
        // data update; the helper skips when today's line is already written).
        LaunchedEffect(dataUpdateCount, config) {
            config?.let { DesktopSourceViews.logDailyIfDue(it.effectiveSources) }
        }

        LaunchedEffect(config) {
            Log.i(TAG, "config loaded: config != null is ${config != null}")
        }
        var pickerVisible by remember { mutableStateOf(config == null) }
        var pickerShowRequestId by remember { mutableStateOf(0) }
        var settingsVisible by remember { mutableStateOf(false) }
        var settingsShowRequestId by remember { mutableStateOf(0) }
        var statsVisible by remember { mutableStateOf(false) }
        var statsShowRequestId by remember { mutableStateOf(0) }
        var historyVisible by remember { mutableStateOf(false) }
        var historyShowRequestId by remember { mutableStateOf(0) }
        // Day the forecast-history window opens on. Seeded from the hourly graph's viewed center
        // date (Android parity: TemperatureTouchTargets passes centerTime.toLocalDate()), so
        // opening history while viewing a past day lands on THAT day, not today.
        var historyInitialDate by remember { mutableStateOf(LocalDate.now()) }
        // The popup's viewed day when Observations was opened: its refresh refills a past day's
        // stale Google hours (GoogleHistoryRefill), as the History window's refresh does.
        var observationsViewedDate by remember { mutableStateOf(LocalDate.now()) }
        var observationsVisible by remember { mutableStateOf(false) }
        var obsShowRequestId by remember { mutableStateOf(0) }
        var appLogsVisible by remember { mutableStateOf(false) }
        var appLogsShowRequestId by remember { mutableStateOf(0) }
        var iconGalleryVisible by remember { mutableStateOf(false) }
        var iconGalleryShowRequestId by remember { mutableStateOf(0) }
        var usageStatsVisible by remember { mutableStateOf(false) }
        var usageStatsShowRequestId by remember { mutableStateOf(0) }
        var simLoopVisible by remember { mutableStateOf(false) }
        var radarVisible by remember { mutableStateOf(false) }
        // Set by the WeatherNext viewer's "Use Open-Meteo rain map"; cleared when the loop closes.
        var simLoopForceOpenMeteo by remember { mutableStateOf(false) }
        // Owned here rather than in either child window: full refresh work runs on uiScope and
        // survives closing Settings or Stations/Observations.
        var refreshInFlight by remember { mutableStateOf(false) }
        val desktopClients = remember { DesktopClients(weatherDao) }
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
        // Place name of a location-picker save whose new site had nothing cached; drives the
        // "Getting weather for {place}…" interstitial until the first fetch lands (or fails).
        // Android parity: WidgetStateManager.getPendingLocationFetch.
        var pendingLocationLabel by remember { mutableStateOf<String?>(null) }
        // Transient "Fetching older data…" banner shown while an on-demand deep-history pull runs.
        var historyFetchToast by remember { mutableStateOf<String?>(null) }
        // "Getting weather for {place}…" over the cached graph while a picker save's fetch runs;
        // takes precedence over historyFetchToast in the popup's single banner slot.
        var locationBanner by remember { mutableStateOf<LocationBanner?>(null) }
        // A Settings save that made a source primary (WeatherSourceOrdering.newlyPrimary); its fetch
        // and banner run in the effect keyed on it below. Android: SourceSwitchFetch.
        var pendingSourceSwitch by remember { mutableStateOf<WeatherSource?>(null) }
        // While a picker save's fetch runs, a cache reload for the new site that can't draw today
        // (nothing cached, or two-week-old rows) must not replace the previous site's graph under
        // the banner with an empty one. Every reload path goes through this.
        fun holdForLocationChange(snapshot: ForecastSnapshot): Boolean =
            (pendingLocationLabel != null || locationBanner != null) &&
                !LocationChangePaintPolicy.hasTodayRow(snapshot.raw.daily, LocalDate.now())
        var currentTempFetchError by remember { mutableStateOf<String?>(null) }
        // The shared pill drawn over the graph for [currentTempFetchError] (its text is the details
        // card the pill opens). Null for the warm-up notice, which has no pill.
        var currentTempFetchPill by remember { mutableStateOf<DesktopFailurePill?>(null) }
        // True when the failure is offline-classified during the post-wake grace window: the banner
        // renders as a calm "waiting for network" notice instead of a hard error.
        var currentTempFetchIsWarmup by remember { mutableStateOf(false) }
        var currentTempFetchTimestamp by remember { mutableStateOf(0L) }
        var dismissedErrorTimestamp by remember { mutableStateOf(0L) }
        // Banner state lives only in this UI process; without a durable transition row the
        // "did a banner appear during that resume?" question is unanswerable after the fact.
        var lastLoggedBannerState by remember { mutableStateOf("none") }
        val uiScope = rememberCoroutineScope()
        // User refreshes start here (the rule Android shares): a fetch outlives whatever window asked.
        val refreshRunner = remember(uiScope) {
            com.weatherwidget.shared.util.UserRefreshRunner(uiScope) { tag, message, level -> weatherDao.log(tag, message, level) }
        }
        val currentConfig = config

        val weatherService = remember(currentConfig?.lat, currentConfig?.lon, currentConfig?.settings?.weatherSource, currentConfig?.settings?.apiKeys, currentConfig?.personalStationWeight()) {
            currentConfig?.let {
                DesktopWeatherService(it.lat, it.lon, it.displaySource, it.settings.apiKeys, weatherDao, isForeground = true, synopticBackoffStore = DesktopSynopticBackoffStore.default(), nowcastStore = DesktopNowcast.store, personalStationWeight = it.personalStationWeight())
            }
        }
        val repository = remember(weatherService, currentConfig?.lat, currentConfig?.lon, currentConfig?.settings?.weatherSource, currentConfig?.settings?.personalStationDiscount) {
            val service = weatherService
            currentConfig?.let { cfg ->
                service?.let {
                    DesktopWeatherRepository(it, weatherDao, cfg.lat, cfg.lon, cfg.displaySource, cfg.personalStationWeight(), snapshotConfig = { DesktopConfigStore().load() })
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
                        repo.loadCached()?.takeUnless { holdForLocationChange(it) }?.let {
                            forecast = it
                            // The daemon's own fetch for a just-picked site can land before the
                            // UI-side one: rows are here, so the interstitial (or the error it
                            // became) is over.
                            if (dataStatus is DataStatus.FetchingLocation || dataStatus is DataStatus.Error) {
                                dataStatus = DataStatus.Live(System.currentTimeMillis())
                            }
                        }
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
                DesktopLocationChangeFeedback.pendingPlaceName(source, prev, effective)?.let { place ->
                    pendingLocationLabel = place
                }
                if (prev != null) {
                    WeatherSourceOrdering.newlyPrimary(prev.settings.visibleSources, effective.settings.visibleSources)
                        ?.let { pendingSourceSwitch = it }
                }
                runCatching {
                    val trigger = appDataDir().resolve(CONFIG_CHANGED_TRIGGER)
                    java.nio.file.Files.writeString(trigger, "", java.nio.charset.StandardCharsets.UTF_8)
                }
                // A source cycle shows no notice, whatever the daemon fetches for it (user, 2026-10-15).
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
                val shownAt = System.currentTimeMillis()
                weatherDao.log(
                    "HISTORY_FETCH_TOAST",
                    "action=shown neededBackHours=$neededBackHours source=${currentConfig?.displaySource}",
                    "INFO",
                )
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
                        weatherDao.log(
                            "HISTORY_FETCH_TOAST",
                            "action=${if (extended) "cleared" else "end_of_history"} fetched=$fetched " +
                                "neededBackHours=$neededBackHours shownMs=${System.currentTimeMillis() - shownAt}",
                            "INFO",
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "On-demand history fetch failed: ${e.message}")
                        historyFetchToast = "Couldn't load older data"
                        weatherDao.log("HISTORY_FETCH_TOAST", "action=failed neededBackHours=$neededBackHours ${e.message}", "WARN")
                    } finally {
                        historyFetchInFlight = false
                    }
                }
            }
        }
        // Daily-view tap on a day whose hourly the displayed source has not stored. Google keeps
        // 72 h and fetches a later day here (HourlyOnDemand); every other source already holds its
        // whole horizon, so there is nothing to fetch and this resolves from memory. The completion
        // callback always fires so the popup never strands on its "Fetching…" banner.
        val onNeedHourlyRefresh: (LocalDate, (List<HourlyForecast>) -> Unit) -> Unit = remember(repository) {
            { date: LocalDate, onComplete: (List<HourlyForecast>) -> Unit ->
                val repo = repository
                if (repo == null) {
                    onComplete(forecast?.raw?.hourly ?: emptyList())
                } else {
                    uiScope.launch {
                        try {
                            if (repo.extendHourlyFor(date)) repo.loadCached()?.let { forecast = it }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "On-demand hourly fetch failed: ${e.message}")
                        } finally {
                            onComplete(forecast?.raw?.hourly ?: emptyList())
                        }
                    }
                }
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
        val anyWindowOpen = popupVisible || pickerVisible || settingsVisible || statsVisible || historyVisible || observationsVisible || appLogsVisible || iconGalleryVisible || simLoopVisible || radarVisible
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
                if (cached != null && !holdForLocationChange(cached)) {
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
                    repo.loadCached()?.takeUnless { holdForLocationChange(it) }?.let { forecast = it }
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

        // Setup-driven location change (Android parity: LocationUpdater.paintInterstitialIfUncached).
        // Keyed on the repository so it runs after the new site's repository exists. Every picker
        // move is confirmed and fetched here, in the UI process, so its outcome is known:
        //  - the new site has a forecast row for today → show its cached graph at once, no banner
        //    (switching home ↔ work must not blank it, nor claim to be loading what is shown);
        //  - it does not → drop the snapshot (the previous city, or a stale one with nothing in the
        //    visible window — that painted an empty graph for 26 s on 2026-09-28) and show the
        //    full-screen interstitial naming the place.
        // The fetch runs in both cases: the daemon's `.config-changed` refresh is gated on the
        // SOURCE's last fetch, not the site's, so it may skip a site that is days stale. Success →
        // Live; failure → an Error naming the place, or a brief failure banner over the cache.
        LaunchedEffect(repository, pendingLocationLabel) {
            val label = pendingLocationLabel ?: return@LaunchedEffect
            val repo = repository ?: return@LaunchedEffect
            val cfg = currentConfig ?: return@LaunchedEffect
            if (!LocationChangePaintPolicy.isSameSite(cfg.lat to cfg.lon, repo.latitude, repo.longitude)) {
                // Stale repository from before recomposition; the keyed rerun handles the new one.
                return@LaunchedEffect
            }
            // Named from the label when it leads with the place; otherwise "the new location" until
            // the reverse lookup (the same compact name Android's save toast uses) fills it in.
            var place = LocationChangePaintPolicy.shortPlaceNameOrNull(label)
            val token = Any()
            fun ownsBanner() = locationBanner?.token === token
            try {
                val cached = runCatching { repo.loadCached() }.getOrNull()
                val decision = DesktopLocationChangeFeedback.decide(cached, hasRenderOnScreen = forecast != null, LocalDate.now())
                if (decision.feedback == LocationChangePaintPolicy.Feedback.INTERSTITIAL) {
                    LocationChangePaintPolicy.log(LocationChangePaintPolicy.Action.RENDER_INTERSTITIAL, label, write = weatherDao::log)
                    forecast = null
                    dataStatus = DataStatus.FetchingLocation(DesktopLocationChangeFeedback.placePhrase(place))
                } else if (decision.adoptCached) {
                    // The new site's own graph is the feedback; a "Getting weather for…" banner over
                    // it read as still loading (2026-09-29). The refresh below runs silently.
                    LocationChangePaintPolicy.log(LocationChangePaintPolicy.Action.CACHE_ADOPTED, label, write = weatherDao::log)
                    forecast = cached
                    dataStatus = DataStatus.Live(System.currentTimeMillis())
                } else {
                    LocationChangePaintPolicy.log(LocationChangePaintPolicy.Action.BANNER_SHOWN, label, "under=previous_site", write = weatherDao::log)
                    locationBanner = LocationBanner(token, DesktopLocationChangeFeedback.fetchingMessage(place))
                }
                val naming = if (place != null) null else launch {
                    val friendly = runCatching {
                        sharedLocationResolver.friendlyName(repo.latitude, repo.longitude)
                    }.getOrNull() ?: return@launch
                    place = friendly
                    if (ownsBanner() && locationBanner?.failed == false) {
                        locationBanner = LocationBanner(token, DesktopLocationChangeFeedback.fetchingMessage(friendly))
                    }
                    if (dataStatus is DataStatus.FetchingLocation) {
                        dataStatus = DataStatus.FetchingLocation(friendly)
                    }
                }
                refreshInFlight = true
                try {
                    // A Synoptic backoff earned at the previous site must not blank this one's actuals.
                    forecast = repo.refresh(userLocationChange = true, reason = "location_change")
                    dataStatus = DataStatus.Live(System.currentTimeMillis())
                    dataUpdateCount++
                    LocationChangePaintPolicy.log(LocationChangePaintPolicy.Action.CLEARED, label, write = weatherDao::log)
                    if (ownsBanner()) locationBanner = null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LocationChangePaintPolicy.log(LocationChangePaintPolicy.Action.RENDER_ERROR, label, "${e::class.simpleName}: ${e.message}", write = weatherDao::log)
                    val phrase = DesktopLocationChangeFeedback.placePhrase(place)
                    if (dataStatus is DataStatus.FetchingLocation) {
                        dataStatus = DataStatus.Error("Couldn\u2019t get weather for $phrase \u2014 check the network, then Refresh")
                    }
                    if (ownsBanner()) {
                        locationBanner = LocationBanner(token, DesktopLocationChangeFeedback.failedMessage(place), failed = true)
                    }
                } finally {
                    refreshInFlight = false
                    naming?.cancel()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Superseded (another pick, or a repository rebuild): don't strand our banner.
                if (ownsBanner()) locationBanner = null
                throw e
            } finally {
                if (pendingLocationLabel == label) pendingLocationLabel = null
            }
        }

        // A source just became primary (Settings enable). Without its own data the popup would show
        // its empty graph until the daemon's gated refresh ran; fetch it now, and say so while it
        // loads unless its cache is already drawable (shared rule with Android's SourceSwitchFetch).
        LaunchedEffect(repository, pendingSourceSwitch) {
            val switched = pendingSourceSwitch ?: return@LaunchedEffect
            val repo = repository ?: return@LaunchedEffect
            // Stale repository from before the rebuild for the new source; the keyed rerun handles it.
            if (currentConfig?.displaySource != switched.id) return@LaunchedEffect
            val token = Any()
            fun ownsBanner() = locationBanner?.token === token
            try {
                val cached = runCatching { repo.loadCached() }.getOrNull()
                val plan = SourceSwitchFetchRule.start(
                    hasCache = DesktopSourceSwitchFeedback.hasDrawableCache(
                        switched.id, weatherDao.getLastSuccessfulFetch(switched.id), cached, LocalDate.now(),
                    ),
                    hasLocation = true,
                    hasSurface = true,
                )
                weatherDao.log(SourceSwitchFetchRule.TAG, SourceSwitchFetchRule.startLine(switched.id, "settings_enable", plan), "INFO")
                // A drawable cache needs no wait; the forecast loop's cadence refreshes it when due.
                if (!plan.forced) return@LaunchedEffect
                locationBanner = LocationBanner(token, DesktopSourceSwitchFeedback.fetchingMessage(switched))
                refreshInFlight = true
                try {
                    forecast = repo.refresh(reason = "source_enabled")
                    dataStatus = DataStatus.Live(System.currentTimeMillis())
                    dataUpdateCount++
                    weatherDao.log(
                        SourceSwitchFetchRule.TAG,
                        SourceSwitchFetchRule.finishLine(switched.id, succeeded = true, reason = "fetched"),
                        SourceSwitchFetchRule.finishLevel(true),
                    )
                    if (ownsBanner()) locationBanner = null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    weatherDao.log(
                        SourceSwitchFetchRule.TAG,
                        SourceSwitchFetchRule.finishLine(
                            switched.id, succeeded = false, reason = "error", "error=${e::class.simpleName}: ${e.message}",
                        ),
                        SourceSwitchFetchRule.finishLevel(false),
                    )
                    if (ownsBanner()) {
                        locationBanner = LocationBanner(token, DesktopSourceSwitchFeedback.failedMessage(switched), failed = true)
                    }
                } finally {
                    refreshInFlight = false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (ownsBanner()) locationBanner = null
                throw e
            } finally {
                if (pendingSourceSwitch == switched) pendingSourceSwitch = null
            }
        }

        // A failure banner is informational: show it briefly, then get out of the way.
        LaunchedEffect(locationBanner) {
            val banner = locationBanner ?: return@LaunchedEffect
            if (banner.failed) {
                kotlinx.coroutines.delay(4000)
                if (locationBanner === banner) locationBanner = null
            }
        }

        LaunchedEffect(repository, config, forecast, dataUpdateCount) {
            val repo = repository ?: return@LaunchedEffect
            val activeConfig = config ?: return@LaunchedEffect
            var graceJob: Job? = null
            // Takes a rate-limit banner down when its minute is up (RateLimitNotice.SHOW_MS).
            var rateLimitExpiryJob: Job? = null

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
            
            // A forecast product's quota block, on every view ([QuotaNotice.bannerBlock], the rule the
            // widget uses): the viewed product's block first, else the other's.
            data class ProductQuotaBanner(val product: ForecastProduct, val loggedAt: Long, val text: String)
            fun productQuotaBanner(src: String): ProductQuotaBanner? {
                val quotas = DesktopQuotaState(weatherDao)
                val nowMs = System.currentTimeMillis()
                val (product, quota) = QuotaNotice.bannerBlock(QuotaNotice.productOf(activeConfig.viewMode)) { product ->
                    quotas.productQuota(src, product, nowMs)?.takeIf { it.sinceMs > dismissedErrorTimestamp }
                } ?: return null
                val notice = QuotaNotice.forProductBlock(product, quota.untilMs, quota.detail)
                val presentation = desktopQuotaPresentation(WeatherSource.fromId(src).displayName, notice)
                return ProductQuotaBanner(
                    product,
                    quota.sinceMs,
                    (listOf(presentation.title) + presentation.bodyLines + listOf("", presentation.retryLine)).joinToString("\n"),
                )
            }

            fun updateStatus() {
                val isHourly = activeConfig.viewMode.isHourly
                val src = WeatherSource.fromDisplaySource(activeConfig.displaySource).id
                val status = weatherDao.getLatestCurrentTempStatus(src)
                val statusNow = System.currentTimeMillis()
                rateLimitExpiryJob?.cancel()
                fun expireRateLimitBannerAfter(atMs: Long) {
                    val remaining = RateLimitNotice.remainingMs(atMs, statusNow) ?: return
                    rateLimitExpiryJob = this@LaunchedEffect.launch {
                        delay(remaining)
                        updateStatus()
                        logBannerTransition()
                    }
                }
                // The daily view shows a source-wide failure only when it is a quota (it says when
                // updates resume); other current-temp failures stay on the hourly graphs. A per-minute
                // 429 shows on every view, for a minute (plans/261016-quota-refusals-always-show-a-banner.md).
                val sourceFailure = status?.takeIf { !it.ok && it.timestamp > dismissedErrorTimestamp }?.takeIf {
                    val presentation = desktopFetchErrorPresentation(
                        "", CurrentTempStatusLog.parseFailureClassName(it.message),
                        CurrentTempStatusLog.parseFailureDetail(it.message), it.timestamp,
                    )
                    when {
                        presentation.rateLimit -> RateLimitNotice.isShowing(it.timestamp, statusNow)
                        else -> isHourly || presentation.quota
                    }
                }
                if (status != null && sourceFailure != null) {
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
                        currentTempFetchPill = null
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

                    val presentation = desktopFetchErrorPresentation(displayName, className, detail, status.timestamp)
                    if (presentation.rateLimit) expireRateLimitBannerAfter(status.timestamp)
                    val lastSuccessfulUpdateMs = weatherDao.getLastSuccessfulFetch(src)
                    val lastSuccessfulLine = if (lastSuccessfulUpdateMs != null) {
                        val timeStr = timeFmt.format(Instant.ofEpochMilli(lastSuccessfulUpdateMs))
                        val ageStr = formatAge(now - lastSuccessfulUpdateMs)
                        "Last successful update: $timeStr ($ageStr ago)"
                    } else {
                        "Last successful update: None"
                    }

                    val attemptTimeStr = attemptFmt.format(Instant.ofEpochMilli(status.timestamp))
                    currentTempFetchPill = DesktopFailurePill(
                        sourceLabel = displayName,
                        errorCode = if (presentation.rateLimit) RateLimitNotice.ERROR_CODE else FetchErrorCode.fromLogged(className, detail),
                        failureTimeMs = status.timestamp,
                    )
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
                    currentTempFetchIsWarmup = false
                    // No source-wide failure: a forecast product may still be refused while the other
                    // keeps updating, so the refresh succeeded but those days/hours are not (user,
                    // 2026-10-07) — bannered on every view (user, 2026-10-21).
                    val productQuota = productQuotaBanner(src)
                    // Else hourly pages a per-minute limit cut short, on every view for a minute.
                    val partialRateLimit = if (productQuota != null) null else DesktopQuotaState(weatherDao).partialRateLimit(src, statusNow)
                        ?.takeIf { it.atMs > dismissedErrorTimestamp }
                    val displayName = WeatherSource.fromId(src).displayName
                    when {
                        productQuota != null -> {
                            currentTempFetchError = productQuota.text
                            currentTempFetchPill = DesktopFailurePill(
                                sourceLabel = displayName,
                                errorCode = GoogleQuota.errorCodeFor(productQuota.product),
                                failureTimeMs = productQuota.loggedAt,
                            )
                            currentTempFetchTimestamp = productQuota.loggedAt
                        }
                        partialRateLimit != null -> {
                            val loggedAt = partialRateLimit.atMs
                            val presentation = desktopPartialRateLimitPresentation(displayName, partialRateLimit.detail.orEmpty())
                            currentTempFetchError = (listOf(presentation.title) + presentation.bodyLines + listOf("", presentation.retryLine))
                                .joinToString("\n")
                            currentTempFetchPill = DesktopFailurePill(
                                sourceLabel = displayName,
                                errorCode = RateLimitNotice.ERROR_CODE_PARTIAL,
                                failureTimeMs = loggedAt,
                            )
                            currentTempFetchTimestamp = loggedAt
                            expireRateLimitBannerAfter(loggedAt)
                        }
                        else -> {
                            currentTempFetchError = null
                            currentTempFetchPill = null
                        }
                    }
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

        // Fallback signal path alongside the socket push: see [DataUpdateWatcher].
        DataUpdateWatcher(
            onShowRequested = ::requestShowPopup,
            onDataUpdated = { reloadCachedForecast("watch") },
        )


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
            if (cfg != null) weatherDao.getCurrentStatus(cfg.lat, cfg.lon, cfg.displaySource) else null
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

        /** [refillDay]: see [DesktopWeatherRepository.refresh]; set only by a past day's History refresh. */
        /**
         * A Forecast History Refresh's ending, on the History window: "Refreshed Google · Hourly
         * forecast quota used · resets 12 AM" — the shared rule Android's History screen uses
         * (plans/261016-history-refresh-outcome-shared-desktop-parity.md). Other origins announce nothing.
         */
        fun announceHistoryRefreshDone(origin: String, sourceId: String, ok: Boolean, startedAtMs: Long) {
            if (origin != "history") return
            val outcome = historyRefreshOutcome(weatherDao, sourceId, ok, startedAtMs)
            weatherDao.log(
                RefreshOutcome.DONE_TAG,
                RefreshOutcome.doneLine(ok, "source=$sourceId", System.currentTimeMillis() - startedAtMs, outcome),
                RefreshOutcome.doneLevel(ok),
            )
            refreshNotice.showOutcome(outcome, WeatherSource.fromId(sourceId).shortDisplayName, refreshNoticeSurface(origin), origin)
        }

        fun requestFullRefresh(origin: String, refillDay: java.time.LocalDate? = null) {
            val repo = repository
            weatherDao.log(
                "REFRESH_CLICK",
                "origin=$origin repository=" +
                    (if (repo == null) "NULL (no-op)" else "present") +
                    " config=" +
                    (currentConfig?.let { "lat=${it.lat} lon=${it.lon} src=${it.displaySource}" }
                        ?: "null"),
                "INFO",
            )
            val surface = refreshNoticeSurface(origin)
            if (repo == null || refreshInFlight) {
                if (refreshInFlight) {
                    weatherDao.log("REFRESH_CLICK", "origin=$origin suppressed=in_flight", "INFO")
                }
                refreshNotice.show(
                    RefreshNotice.NotQueued(if (refreshInFlight) RefreshNotice.Reason.IN_PROGRESS else RefreshNotice.Reason.NO_LOCATION),
                    surface,
                    origin,
                )
                return
            }

            refreshInFlight = true
            refreshNotice.show(RefreshNotice.Queued(RefreshNotice.Kind.FORECAST), surface, origin)
            val startedAtMs = System.currentTimeMillis()
            val refreshedSourceId = WeatherSource.fromDisplaySource(currentConfig?.displaySource ?: "").id
            // Through the shared runner on the application-owned scope: removing either child window
            // from composition cannot cancel the fetch or discard its result.
            val refresh = refreshRunner.start("user_refresh:$origin") {
                val snapshot = repo.refresh(reason = "user_refresh:$origin", refillDay = refillDay, userRefresh = true)
                // The daily NWS gap fill, as Android's Refresh runs it: need-gated, so a complete
                // 3 days costs no request. Only a wake ran it before, so a fill that wake judged
                // unneeded had no manual way out (2026-10-27: cloud actuals stopped at 01:00).
                val served = currentConfig?.effectiveSources?.map(WeatherSource::fromId).orEmpty()
                if (repo.refreshObservationWindow(served, reason = "user_refresh:$origin") > 0) repo.loadCached() ?: snapshot else snapshot
            }
            uiScope.launch {
                try {
                    forecast = refresh.await()
                    // A Refresh is the way out the location-change error message points at.
                    if (dataStatus is DataStatus.FetchingLocation || dataStatus is DataStatus.Error) {
                        dataStatus = DataStatus.Live(System.currentTimeMillis())
                    }
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
                    announceHistoryRefreshDone(origin, refreshedSourceId, ok = true, startedAtMs)
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
                    announceHistoryRefreshDone(origin, refreshedSourceId, ok = false, startedAtMs)
                } finally {
                    refreshInFlight = false
                }
            }
        }

        /**
         * The Observations window's Refresh: current observations only — the displayed source's
         * forced recent-only fetch the current-temp loop makes, never the forecast (user, 2026-10-14).
         * On a previous day ([refillDay]) also that day's Google `history/hours` refill, alone
         * (plans/261014-observations-refresh-fetches-observations-only.md).
         */
        fun requestObservationsRefresh(refillDay: java.time.LocalDate?) {
            val origin = "observations"
            val repo = repository
            weatherDao.log(
                "REFRESH_CLICK",
                "origin=$origin scope=observations refillDay=$refillDay repository=" +
                    (if (repo == null) "NULL (no-op)" else "present"),
                "INFO",
            )
            if (repo == null || refreshInFlight) {
                if (refreshInFlight) {
                    weatherDao.log("REFRESH_CLICK", "origin=$origin suppressed=in_flight", "INFO")
                }
                refreshNotice.show(
                    RefreshNotice.NotQueued(if (refreshInFlight) RefreshNotice.Reason.IN_PROGRESS else RefreshNotice.Reason.NO_LOCATION),
                    RefreshNoticeSurface.OBSERVATIONS,
                    origin,
                )
                return
            }
            refreshInFlight = true
            refreshNotice.show(RefreshNotice.Queued(RefreshNotice.Kind.OBSERVATIONS), RefreshNoticeSurface.OBSERVATIONS, origin)
            // The shared runner, as in requestFullRefresh: closing the window cannot cancel it.
            val refresh = refreshRunner.start("user_refresh:$origin") {
                val snapshot = repo.refreshObservations(reason = "user_refresh:$origin")
                if (refillDay == null) {
                    snapshot
                } else {
                    // Reload so the refilled hours are in what the popup draws.
                    repo.refillGoogleHistory(refillDay)
                    repo.loadCached() ?: snapshot
                }
            }
            uiScope.launch {
                try {
                    forecast = refresh.await()
                    dataUpdateCount++
                    notifyRefreshRequested()
                    weatherDao.log("REFRESH_CLICK", "origin=$origin completed", "INFO")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    weatherDao.log("REFRESH_CLICK", "origin=$origin refresh cancelled", "WARN")
                    throw e
                } catch (e: Exception) {
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

        /**
         * History of Forecasts' refresh: the viewed source only (user, 2026-10-09). The displayed
         * source goes through [requestFullRefresh] — this window's repository is built for it — and
         * any other source through a repository of its own, as the daemon's non-active loop does.
         */
        fun requestSourceRefresh(source: WeatherSource, origin: String, refillDay: java.time.LocalDate? = null) {
            val cfg = currentConfig ?: return
            if (source.id == cfg.displaySource) {
                requestFullRefresh(origin, refillDay)
                return
            }
            weatherDao.log("REFRESH_CLICK", "origin=$origin source=${source.id}", "INFO")
            if (refreshInFlight) {
                weatherDao.log("REFRESH_CLICK", "origin=$origin suppressed=in_flight", "INFO")
                refreshNotice.show(RefreshNotice.NotQueued(RefreshNotice.Reason.IN_PROGRESS), refreshNoticeSurface(origin), origin)
                return
            }
            refreshInFlight = true
            refreshNotice.show(RefreshNotice.Queued(RefreshNotice.Kind.FORECAST), refreshNoticeSurface(origin), origin)
            val startedAtMs = System.currentTimeMillis()
            // The shared runner, as in requestFullRefresh: closing the window cannot cancel it.
            val refresh = refreshRunner.start("user_refresh:$origin:${source.id}") {
                val service = DesktopWeatherService(
                    cfg.lat, cfg.lon, source.id, cfg.settings.apiKeys, weatherDao,
                    isForeground = true, synopticBackoffStore = DesktopSynopticBackoffStore.default(), nowcastStore = DesktopNowcast.store,
                    personalStationWeight = cfg.personalStationWeight(),
                )
                try {
                    DesktopWeatherRepository(service, weatherDao, cfg.lat, cfg.lon, source.id, cfg.personalStationWeight(), snapshotConfig = { DesktopConfigStore().load() })
                        .refresh(reason = "user_refresh:$origin", refillDay = refillDay, userRefresh = true)
                } finally {
                    service.close()
                }
            }
            uiScope.launch {
                try {
                    refresh.await()
                    dataUpdateCount++
                    notifyRefreshRequested()
                    weatherDao.log("REFRESH_CLICK", "origin=$origin source=${source.id} completed", "INFO")
                    announceHistoryRefreshDone(origin, source.id, ok = true, startedAtMs)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    weatherDao.log(
                        "REFRESH_CLICK",
                        "origin=$origin source=${source.id} failed ${e::class.simpleName}: ${e.message}",
                        "WARN",
                    )
                    announceHistoryRefreshDone(origin, source.id, ok = false, startedAtMs)
                } finally {
                    refreshInFlight = false
                }
            }
        }

        if (statsVisible && currentConfig != null) {
            StatisticsWindow(
                weatherDao = weatherDao,
                config = currentConfig,
                showRequestId = statsShowRequestId,
                onClose = { statsVisible = false },
            )
        }

        if (historyVisible && currentConfig != null) {
            CompositionLocalProvider(LocalRefreshNotice provides refreshNotice) { ForecastHistoryWindow(
                weatherDao = weatherDao,
                config = currentConfig,
                showRequestId = historyShowRequestId,
                initialDate = historyInitialDate,
                onClose = { historyVisible = false },
                onConfigUpdate = { newConfig -> saveConfigAndNotify(newConfig, "observations") },
                dataUpdateCount = dataUpdateCount,
                isRefreshing = refreshInFlight,
                onRefreshSource = { source, viewedDate ->
                    requestSourceRefresh(
                        source,
                        "history",
                        com.weatherwidget.data.remote.GoogleHistoryRefill.refillDay(viewedDate, java.time.LocalDate.now()),
                    )
                },
                onOpenSettings = {
                    settingsVisible = true
                    settingsShowRequestId++
                },
            ) }
        }

        if (observationsVisible && currentConfig != null && repository != null) {
            CompositionLocalProvider(LocalRefreshNotice provides refreshNotice) { ObservationsWindow(
                weatherDao = weatherDao,
                config = currentConfig,
                showRequestId = obsShowRequestId,
                // Same "DB changed" signal the popup reloads on, so the stations list tracks the
                // live DB instead of freezing at the snapshot taken when the window was opened.
                dataUpdateCount = dataUpdateCount,
                isRefreshing = refreshInFlight,
                onRefreshData = {
                    requestObservationsRefresh(
                        com.weatherwidget.data.remote.GoogleHistoryRefill.refillDay(observationsViewedDate, LocalDate.now()),
                    )
                },
                onClose = { observationsVisible = false },
                onConfigUpdate = { newConfig ->
                    saveConfigAndNotify(newConfig, "observations-window")
                }
            ) }
        }

        if (iconGalleryVisible) {
            IconGalleryWindowHost(
                icon = appIcon,
                showRequestId = iconGalleryShowRequestId,
                onClose = { iconGalleryVisible = false },
            )
        }

        if (usageStatsVisible) {
            UsageStatsWindowHost(
                icon = appIcon,
                weatherDao = weatherDao,
                showRequestId = usageStatsShowRequestId,
                onClose = { usageStatsVisible = false },
            )
        }

        if (appLogsVisible) {
            AppLogsWindow(
                weatherDao = weatherDao,
                showRequestId = appLogsShowRequestId,
                onClose = { appLogsVisible = false },
            )
        }

        if (pickerVisible) {
            LocationPickerWindowHost(
                locationResolver = locationResolver,
                isFirstLaunch = config == null,
                recentLocations = config?.recentLocations ?: emptyList(),
                icon = appIcon,
                showRequestId = pickerShowRequestId,
                onClose = { pickerVisible = false },
                onResolved = { saved ->
                    saveConfigAndNotify(saved, "location-picker")
                    pickerVisible = false
                    popupVisible = true
                    showRequestId++
                },
            )
        }

        if (settingsVisible && config != null) {
            CompositionLocalProvider(LocalRefreshNotice provides refreshNotice) { SettingsWindowHost(
                config = config!!,
                icon = appIcon,
                isRefreshing = refreshInFlight,
                weatherDao = weatherDao,
                locationResolver = sharedLocationResolver,
                showRequestId = settingsShowRequestId,
                onSaveConfig = saveConfigAndNotify,
                onClose = { settingsVisible = false },
                onExit = { quit() },
                onUpdateLocation = {
                    pickerVisible = true
                    pickerShowRequestId++
                },
                onOpenIconGallery = {
                    iconGalleryVisible = true
                    iconGalleryShowRequestId++
                },
                onOpenUsageStats = {
                    usageStatsVisible = true
                    usageStatsShowRequestId++
                },
                onRefreshData = { requestFullRefresh("settings") },
                onViewAppLogs = {
                    appLogsVisible = true
                    appLogsShowRequestId++
                },
            ) }
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
                    pickerShowRequestId++
                },
                onUpdateConfig = { newConfig ->
                    saveConfigAndNotify(newConfig, "popup")
                },
                onOpenSettings = {
                    settingsVisible = true
                    settingsShowRequestId++
                },
                onOpenObservations = { viewedDate ->
                    observationsViewedDate = viewedDate
                    observationsVisible = true
                    obsShowRequestId++
                },
                onOpenHistory = { viewedDate ->
                    Log.d(TAG, "OpenHistory: viewedDate=$viewedDate (hourlyOffset=${currentConfig.hourlyOffset})")
                    historyInitialDate = viewedDate
                    historyVisible = true
                    historyShowRequestId++
                },
                onOpenSimLoop = {
                    simLoopVisible = true
                },
                onOpenRadar = {
                    radarVisible = true
                },
                onNeedHistory = onNeedHistory,
                onNeedHourlyRefresh = onNeedHourlyRefresh,
                onDayClickAudit = { message ->
                    Log.d(DayTap.TAG, message)
                    weatherDao.log(DayTap.TAG, message, "DEBUG")
                },
                onHourlyAudit = { tag, message -> weatherDao.log(tag, message, "INFO") },
                transientMessage = locationBanner?.text
                    ?: refreshNotice.textFor(RefreshNoticeSurface.POPUP)
                    ?: historyFetchToast,
                currentTempFetchError = currentTempFetchError,
                currentTempFetchPill = currentTempFetchPill,
                currentTempFetchIsWarmup = currentTempFetchIsWarmup,
                onDismissCurrentTempError = {
                    dismissedErrorTimestamp = currentTempFetchTimestamp
                    currentTempFetchError = null
                    currentTempFetchPill = null
                },
            )
        }

        if (radarVisible && currentConfig != null) {
            RadarWindow(
                config = currentConfig,
                httpClient = desktopClients.httpClient,
                onClose = { radarVisible = false },
            )
        }

        if (simLoopVisible && currentConfig != null) {
            val closeSimLoop = {
                simLoopVisible = false
                simLoopForceOpenMeteo = false
            }
            if (simLoopOpensOpenMeteo(currentConfig.displaySource, simLoopForceOpenMeteo)) {
                OpenMeteoRainLoopWindow(
                    config = currentConfig,
                    httpClient = desktopClients.httpClient,
                    onClose = closeSimLoop,
                    onSpeedChosen = { seconds ->
                        if (currentConfig.rainMapSecondsPerHour != seconds) {
                            saveConfigAndNotify(currentConfig.copy(rainMapSecondsPerHour = seconds), "rain-map-window")
                        }
                    },
                )
            } else {
                RainForecastWindow(
                    config = currentConfig,
                    httpClient = desktopClients.httpClient,
                    onClose = closeSimLoop,
                    onError = { error ->
                        val message = WeatherNextLoop.errorLine(error, WeatherNextDomain.resolve(currentConfig.lat, currentConfig.lon))
                        Log.w(WeatherNextLoop.ERROR_TAG, message)
                        weatherDao.log(WeatherNextLoop.ERROR_TAG, message, "WARN")
                    },
                    onUseOpenMeteo = { error ->
                        val message = WeatherNextLoop.fallbackLine(error)
                        Log.i(WeatherNextLoop.FALLBACK_TAG, message)
                        weatherDao.log(WeatherNextLoop.FALLBACK_TAG, message, "INFO")
                        simLoopForceOpenMeteo = true
                    },
                )
            }
        }
    }
}

/**
 * Fallback signal path alongside the socket push (UiNotifyClient): watches `.ui-show` and
 * `.data-updated`, and self-heals by rebuilding the WatchService when a key is invalidated.
 * Extracted from `runDesktopUiApplication` (Phase 2 of the duplication/complexity review).
 */
@Composable
private fun DataUpdateWatcher(
    onShowRequested: () -> Unit,
    onDataUpdated: () -> Unit,
) {
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
                                SwingUtilities.invokeLater { onShowRequested() }
                            } else if (name == DATA_UPDATED_TRIGGER) {
                                Log.i(TAG, "WatchService: .data-updated trigger detected. Reloading cache...")
                                runCatching { java.nio.file.Files.deleteIfExists(dir.resolve(DATA_UPDATED_TRIGGER)) }
                                // Deliberately no dataStatus write: the daemon touches this trigger
                                // on fetch *failures* too, and a bare trigger carries no outcome —
                                // assuming Live here erased the offline/stale indication. Fetch
                                // outcome reaches the UI through the CURRENT_TEMP_STATUS log
                                // contract, re-read when dataUpdateCount bumps (reloadCachedForecast).
                                onDataUpdated()
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
}


/** Which window's Refresh asked, for its notice. */
internal fun refreshNoticeSurface(origin: String): RefreshNoticeSurface = when (origin) {
    "history" -> RefreshNoticeSurface.HISTORY
    "observations" -> RefreshNoticeSurface.OBSERVATIONS
    else -> RefreshNoticeSurface.SETTINGS
}

private fun formatAge(ageMillis: Long): String =
    com.weatherwidget.shared.util.AgeFormatter.formatAgeOld(ageMillis)

/**
 * Which rain loop the pill opens: Open-Meteo's map when Open-Meteo is shown, or when the WeatherNext
 * viewer's error offered it and the user took it ([forcedOpenMeteo]); WeatherNext otherwise.
 */
internal fun simLoopOpensOpenMeteo(displaySourceId: String, forcedOpenMeteo: Boolean): Boolean =
    forcedOpenMeteo || displaySourceId == com.weatherwidget.data.model.WeatherSource.OPEN_METEO.id
