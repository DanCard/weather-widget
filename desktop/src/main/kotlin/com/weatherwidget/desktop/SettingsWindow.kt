package com.weatherwidget.desktop

import androidx.compose.foundation.Image
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.SharedLocationResolver
import com.weatherwidget.desktop.theme.AlertActionButton
import com.weatherwidget.desktop.theme.PrimaryActionButton
import com.weatherwidget.desktop.theme.PrimaryActionProminentButton
import com.weatherwidget.desktop.theme.SecondaryActionButton
import com.weatherwidget.desktop.theme.SettingsCard
import com.weatherwidget.desktop.theme.TertiaryActionButton
import com.weatherwidget.desktop.theme.WeatherDarkColorScheme
import com.weatherwidget.desktop.theme.WeatherOutlinedButton
import com.weatherwidget.desktop.theme.WeatherTypography
import com.weatherwidget.shared.graph.HourlyZoomRules
import com.weatherwidget.shared.util.ApiKeySignupUrls
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.settings.SettingsSection
import com.weatherwidget.shared.util.WeatherSourceOrdering
import kotlin.math.roundToInt

/** Phase 5: default auto-save kicks in this many ms after the last edit if the window stays open. */
private const val TAG = "SettingsWindow"
private const val DEFAULT_AUTO_SAVE_DELAY_MS = 5_000L

@Composable
internal fun SettingsWindow(
    config: DesktopConfig,
    onClose: () -> Unit,
    onSave: (DesktopConfig) -> Unit,
    onExit: () -> Unit,
    onUpdateLocation: () -> Unit = {},
    // Android's Settings has no Observations entry — the widget's current-temperature tap opens
    // that screen, and the desktop header's thermometer icon is the same door — so there is no
    // onOpenObservations here. The icon gallery, by contrast, IS a Settings destination on Android
    // (a button to IconGalleryActivity), mirrored here as a button to its own window.
    onOpenIconGallery: () -> Unit = {},
    // Fire-and-forget: the caller owns both the coroutine and the progress flag. Fetching weather is
    // app-level work, not this window's, so it must NOT run on the local rememberCoroutineScope —
    // closing Settings during the ~5s fetch cancelled the scope and threw the completed result away
    // (ForgottenCoroutineScopeException), leaving the DB updated but the UI never repainted.
    onRefreshData: () -> Unit = {},
    // Driven by the caller now that the work outlives this window.
    isRefreshing: Boolean = false,
    // Diagnostic breadcrumb for the Refresh Data click path. The button can *look* like it worked —
    // "Refreshing…" flashes and clears — while leaving no REFRESH row in app_logs at all, so the
    // click and each downstream stage need their own persistent marker to tell "never clicked" from
    // "clicked but no-op" from "threw". Default no-op keeps preview/test call sites unchanged.
    onRefreshBreadcrumb: (String) -> Unit = {},
    onViewAppLogs: () -> Unit = {},
    // Phase 4 item 4: reverse-geocoded location label. Null on first-launch / preview paths so the
    // caller still sees config.label verbatim; non-null in Main.kt where the resolver is already
    // constructed for LocationPicker.
    locationResolver: SharedLocationResolver? = null,
    // Phase 4 item 5: Bug Report MVP. Main.kt wires this to a mailto: launcher; default no-op so
    // existing tests / preview paths keep working unchanged.
    onSubmitBugReport: () -> Unit = {},
    // Phase 5: auto-save delay in ms after the last edit. Tests pass a short value; production
    // uses the 5s default.
    autoSaveDelayMs: Long = DEFAULT_AUTO_SAVE_DELAY_MS,
    // Keeps the owning Window aware of the latest draft so title-bar close and Escape can flush
    // changes made less than [autoSaveDelayMs] ago.
    onDraftChanged: (DesktopConfig) -> Unit = {},
    // The one-shot dominant-station temperature watch, deliberately outside the config draft.
    watchStore: DominantTempWatchStore = DominantTempWatchStore(),
    // Data usage provider for querying historical network usage statistics.
    dataUsageProvider: (suspend () -> com.weatherwidget.shared.util.NetworkUsageReport?)? = null,
) {
    // NOT keyed on `config`. It used to be — `remember(config) { mutableStateOf(config) }` — which
    // made Compose discard the in-progress draft and re-seed from the baseline every time anything
    // else persisted the config. The popup saves constantly (window move/resize on a 1s debounce,
    // zoom scroll, pan, view switches, day clicks), so an edit made in the 5s before auto-save was
    // routinely wiped: the slider snapped back and the now-clean Save button became a silent no-op.
    // Un-keyed, the draft survives for the life of the window; `rebase` below keeps it current.
    var currentConfig by remember { mutableStateOf(config) }
    var dataUsageReport by remember { mutableStateOf<com.weatherwidget.shared.util.NetworkUsageReport?>(null) }
    var dataUsageLoading by remember { mutableStateOf(dataUsageProvider != null) }
    if (dataUsageProvider != null) {
        LaunchedEffect(Unit) {
            dataUsageReport = dataUsageProvider()
            dataUsageLoading = false
        }
    }
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun updateConfig(updated: DesktopConfig) {
        val changes = updated.settingsDiffFrom(currentConfig)
        if (changes.isNotEmpty()) Log.i(TAG, "SETTINGS_EDIT ${changes.joinToString(", ")}")
        currentConfig = updated
        onDraftChanged(updated)
    }

    // `config` is the latest persisted snapshot supplied by Main — and Main is not the only writer,
    // so a new baseline usually carries a POPUP change (window bounds, zoom) rather than a settings
    // one. Rebase rather than reset: take the popup fields from the new baseline and keep the user's
    // settings edits. Without this, whoever saved last clobbered the other.
    //
    // The baseline the draft was last rebased onto. Needed because a few settings fields ARE
    // written by other windows while this one is open (the location picker retiring NWS is the
    // reported case): only a three-way comparison can tell an unsaved edit from a draft that is
    // merely older than the new baseline. See DesktopConfig.rebaseSettingsDraft.
    var previousBaseline by remember { mutableStateOf(config) }
    LaunchedEffect(config) {
        val rebased = config.rebaseSettingsDraft(previous = previousBaseline, draft = currentConfig)
        previousBaseline = config
        if (rebased != currentConfig) {
            val kept = currentConfig.settingsDiffFrom(config)
            Log.i(
                TAG,
                "SETTINGS_REBASE onto new baseline; " +
                    if (kept.isEmpty()) "no unsaved edits" else "kept unsaved ${kept.joinToString(", ")}",
            )
            currentConfig = rebased
        }
    }

    // Dirty means the SETTINGS-owned fields differ. Comparing whole configs would latch dirty
    // forever the moment the popup moved its window, and saving would then write that stale
    // geometry back over the newer one.
    val isDirty = config.withSettingsFrom(currentConfig) != config

    // Re-launch on either a new draft or a newly persisted baseline. A baseline update cancels any
    // stale timer, while a flurry of edits keeps resetting the five-second idle window.
    LaunchedEffect(currentConfig, config) {
        if (!isDirty) return@LaunchedEffect
        delay(autoSaveDelayMs)
        val merged = config.withSettingsFrom(currentConfig)
        if (merged != config) {
            Log.i(TAG, "SETTINGS_AUTOSAVE ${merged.settingsDiffFrom(config).joinToString(", ")}")
            onSave(merged)
        }
    }

    // Used by both the back arrow and the Save button so an explicit click always flushes
    // before closing, regardless of the auto-save timer.
    val saveAndClose: () -> Unit = {
        val merged = config.withSettingsFrom(currentConfig)
        if (isDirty) {
            Log.i(TAG, "SETTINGS_SAVE ${merged.settingsDiffFrom(config).joinToString(", ")}")
            onSave(merged)
        } else {
            // Previously the ONLY trace of the reverting-setting bug: the user clicks Save, nothing
            // is dirty because the draft was already wiped, and the window just closes.
            Log.i(TAG, "SETTINGS_SAVE no-op (nothing dirty)")
        }
        onClose()
    }

    MaterialTheme(colorScheme = WeatherDarkColorScheme, typography = WeatherTypography) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Phase 5: back arrow flushes dirty edits before closing so users who
                        // click "back" don't lose unsaved changes.
                        IconButton(onClick = saveAndClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text(
                            "Settings",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 8.dp).weight(1f)
                        )

                        // Phase 4 item 1: color-coded header buttons matching Android's drawable
                        // palette. Refresh Data is the primary "go" action (green); View App Logs
                        // is a secondary navigation action (blue).
                        PrimaryActionButton(
                            text = if (isRefreshing) "Refreshing…" else "Refresh Data",
                            onClick = {
                                onRefreshBreadcrumb("click received (isRefreshing=$isRefreshing)")
                                onRefreshData()
                            },
                            enabled = !isRefreshing,
                            modifier = Modifier.padding(horizontal = 4.dp).testTag("refresh_data_btn"),
                        )

                        SecondaryActionButton(
                            text = "View App Logs",
                            onClick = onViewAppLogs,
                            modifier = Modifier.padding(horizontal = 4.dp).testTag("view_app_logs_btn"),
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState)
                            .padding(16.dp)
                    ) {
                        // Hourly Zoom -- matches Android's R.string.hourly_zoom_title, and sits
                        // directly above Personal Weather Stations on both platforms.
                        SettingsCard(title = SettingsSection.HOURLY_ZOOM.title) {
                            HourlyZoomSpan(
                                spanHours = currentConfig.settings.narrowZoomSpanHours,
                                onChanged = { newSpan ->
                                    updateConfig(currentConfig.copy(settings = currentConfig.settings.copy(narrowZoomSpanHours = newSpan)))
                                },
                                multiDayZoomEnabled = currentConfig.settings.multiDayZoomEnabled,
                                onMultiDayZoomChanged = { enabled ->
                                    updateConfig(currentConfig.copy(settings = currentConfig.settings.copy(multiDayZoomEnabled = enabled)))
                                },
                            )
                        }

                        // Notifications — the one-shot dominant-station temperature watch.
                        //
                        // Bound to DominantTempWatchStore, NOT to the config draft: the daemon
                        // clears this flag when the notification fires, and this process does not
                        // watch config.json for external edits, so a draft-backed toggle would
                        // re-arm the spent watch on the next auto-save. See DominantTempWatchStore.
                        SettingsCard(title = SettingsSection.NOTIFICATIONS.title) {
                            DominantTempNotifyToggle(watchStore = watchStore)
                        }

                        // Units — Android keeps this high-use display preference directly below
                        // the Notifications card.
                        SettingsCard(title = SettingsSection.UNITS.title) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Use Celsius",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Switch(
                                    checked = currentConfig.settings.useCelsius,
                                    onCheckedChange = { isChecked ->
                                        updateConfig(currentConfig.copy(settings = currentConfig.settings.copy(useCelsius = isChecked)))
                                    },
                                    modifier = Modifier.testTag("use_celsius_switch")
                                )
                            }
                        }

                        // Daily View — Today Column overlay toggles (matches Android's
                        // "Daily View — Today Column" settings section). All opt-in.
                        SettingsCard(title = SettingsSection.TODAY_COLUMN.title) {
                            Text(
                                "Appears in the Today column only on windows at least 4 rows tall.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TodayOverlayToggleRow(
                                label = "Show delta from forecast",
                                checked = currentConfig.settings.todayOverlayDelta,
                                testTag = "today_overlay_delta_switch",
                            ) { updateConfig(currentConfig.copy(settings = currentConfig.settings.copy(todayOverlayDelta = it))) }
                            TodayOverlayToggleRow(
                                label = "Show dominant station temperature",
                                checked = currentConfig.settings.todayOverlayDominantTemp,
                                testTag = "today_overlay_dominant_temp_switch",
                            ) { updateConfig(currentConfig.copy(settings = currentConfig.settings.copy(todayOverlayDominantTemp = it))) }
                        }

                        // Personal Weather Stations
                        SettingsCard(title = SettingsSection.PERSONAL_STATIONS.title) {
                            PersonalStationDiscount(
                                discountPercent = currentConfig.settings.personalStationDiscount,
                                onChanged = { newPercent ->
                                    updateConfig(currentConfig.copy(settings = currentConfig.settings.copy(personalStationDiscount = newPercent)))
                                }
                            )
                        }

                        // Weather Data Sources -- title matches Android's
                        // R.string.api_sources_title = "Weather Data Sources".
                        SettingsCard(title = SettingsSection.WEATHER_SOURCES.title) {
                            ApiSourcesList(
                                visibleSources = currentConfig.settings.visibleSources,
                                apiKeys = currentConfig.settings.apiKeys,
                                onChanged = { newSources ->
                                    updateConfig(currentConfig.copy(settings = currentConfig.settings.copy(visibleSources = newSources)))
                                },
                                onMustKeepOne = {
                                    // Phase 4 item 3: Android shows a toast; Snackbar is the
                                    // Compose equivalent.
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "At least one source must remain enabled.",
                                        )
                                    }
                                },
                                onRequiresApiKey = { source ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "${source.displayName} requires an API key before enabling.",
                                        )
                                    }
                                },
                            )
                        }

                        // Location
                        // Phase 4 item 4: enrich the label with a reverse-geocoded place name from
                        // the shared resolver. The raw config.label stays as the immediate display
                        // value while the lookup runs (and as a fallback if it fails).
                        // Title, line and button copy follow Android's R.string.default_location_title /
                        // no_location_set / set_location_button. Android appends "• Follows device" or
                        // "• Fixed"; the desktop has no location mode, so the line ends at the coordinates.
                        SettingsCard(title = SettingsSection.DEFAULT_LOCATION.title) {
                            var locationLabel by remember(currentConfig.label, currentConfig.lat, currentConfig.lon) {
                                mutableStateOf(currentConfig.label.ifEmpty { "No location set" })
                            }
                            val resolver = locationResolver
                            if (resolver != null && currentConfig.label.isNotBlank()) {
                                LaunchedEffect(currentConfig.lat, currentConfig.lon) {
                                    val friendly = runCatching {
                                        resolver.friendlyName(currentConfig.lat, currentConfig.lon)
                                    }.getOrNull()
                                    if (!friendly.isNullOrBlank()) {
                                        locationLabel = "$friendly (${formatCoord(currentConfig.lat)}, ${formatCoord(currentConfig.lon)})"
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (currentConfig.label.isEmpty()) locationLabel else "Widget Location: $locationLabel",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                )
                                PrimaryActionProminentButton(
                                    text = "Set Location…",
                                    onClick = onUpdateLocation,
                                    modifier = Modifier.testTag("set_location_btn"),
                                )
                            }
                        }

                        // Icon gallery -- Android: R.string.icon_preview_title / _description +
                        // a "View Icon Gallery" button to IconGalleryActivity. The grid used to be
                        // inline here, which made this form far longer than Android's.
                        SettingsCard(title = SettingsSection.ICON_GALLERY.title) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "Comprehensive gallery of all weather icons used in the widget.",
                                    style = WeatherTypography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                SecondaryActionButton(
                                    text = "View Icon Gallery",
                                    onClick = onOpenIconGallery,
                                    modifier = Modifier.testTag("view_icon_gallery_btn"),
                                    prominent = true,
                                )
                            }
                        }

                        // Phase 4 item 5: Bug Report MVP. The full Android BugReportActivity is a
                        // separate activity with a description field and diagnostic checkboxes;
                        // the desktop MVP is a single button that opens a mailto: link with basic
                        // diagnostic info. Main.kt constructs the URI (so it can pull runtime
                        // details like app version / OS); SettingsWindow just fires the callback.
                        SettingsCard(title = SettingsSection.FEEDBACK.title) {
                            Text(
                                text = "Encountered an issue or want to suggest an improvement? Submit a " +
                                    "detailed bug report with optional system diagnostics.",
                                style = WeatherTypography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            AlertActionButton(
                                text = "Submit Bug Report",
                                onClick = onSubmitBugReport,
                                modifier = Modifier.testTag("submit_bug_report_btn"),
                            )
                        }

                        // API Keys -- after Feedback, where Android puts it (R.string.api_keys_title /
                        // _description). It used to sit directly under Weather Data Sources here.
                        SettingsCard(title = SettingsSection.API_KEYS.title) {
                            Text(
                                text = "Enter your own API keys for restricted services. Free services " +
                                    "(NWS, Open-Meteo) do not require keys.",
                                style = WeatherTypography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            ApiKeysList(
                                apiKeys = currentConfig.settings.apiKeys,
                                onChanged = { newKeys ->
                                    updateConfig(currentConfig.copy(settings = currentConfig.settings.copy(apiKeys = newKeys)))
                                }
                            )
                        }

                        // Support Development (kept last, mirrors Android's SettingsActivity)
                        SettingsCard(title = SettingsSection.SUPPORT.title) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "If you find this app useful, tips are appreciated but never required.",
                                    style = WeatherTypography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TertiaryActionButton(
                                    text = "Tip Jar",
                                    onClick = { openInBrowser("https://paypal.me/DannyCarde") },
                                    modifier = Modifier.testTag("support_development_btn"),
                                )
                            }
                        }

                        // Data Usage (mirrors Android's SettingsActivity)
                        SettingsCard(title = SettingsSection.DATA_USAGE.title) {
                            DataUsageSectionContent(
                                report = dataUsageReport,
                                isLoading = dataUsageLoading,
                            )
                        }
                    }

                    // Footer
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Exit the whole app — the only quit affordance when running without a tray
                        // (WEATHER_DESKTOP_NO_TRAY).
                        WeatherOutlinedButton(
                            text = "Exit app",
                            onClick = onExit,
                            modifier = Modifier.testTag("exit_app"),
                        )
                        PrimaryActionButton(
                            text = if (isDirty) "Save •" else "Save",
                            onClick = saveAndClose,
                            modifier = Modifier.testTag("save_settings"),
                        )
                    }
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

private fun formatCoord(value: Double): String = com.weatherwidget.shared.util.formatCoord(value)

/**
 * The arm switch for the one-shot dominant-station temperature notification.
 *
 * Reads its state when the window composes rather than observing the file: the daemon only ever
 * clears it, and it does so at a moment the user is almost certainly not staring at this screen.
 * Reopening Settings shows the truth.
 */
@Composable
private fun DominantTempNotifyToggle(watchStore: DominantTempWatchStore) {
    var armed by remember { mutableStateOf(watchStore.isArmed()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Notify once when the dominant station reading changes",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Sends one notification the next time the dominant station behind your primary " +
                    "source reports a different temperature, or a different station takes over — " +
                    "for example \"KNUQ 69.9°, was 68°\". Clears itself after it fires.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = armed,
            onCheckedChange = {
                armed = it
                watchStore.setArmed(it)
                Log.i(TAG, "Dominant-temp change notification ${if (it) "armed" else "cleared"}.")
            },
            modifier = Modifier.testTag("notify_dominant_temp_change_switch"),
        )
    }
}

@Composable
private fun TodayOverlayToggleRow(
    label: String,
    checked: Boolean,
    testTag: String,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            modifier = Modifier.testTag(testTag),
        )
    }
}

/**
 * Span of the tight NARROW zoom stage (4–8h) plus the optional 2-day cycle stop, mirroring
 * Android's "Hourly Zoom" card (slider first, switch second — same order and copy on both
 * platforms).
 */
@Composable
private fun HourlyZoomSpan(
    spanHours: Int,
    onChanged: (Int) -> Unit,
    multiDayZoomEnabled: Boolean,
    onMultiDayZoomChanged: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Tap the hourly graph to cycle zoom levels. These set which levels the cycle includes " +
                "and how wide the tightest one is. Wider spans scroll further per arrow tap.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "$spanHours hours",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
        Slider(
            value = spanHours.toFloat(),
            onValueChange = { onChanged(HourlyZoomRules.clampNarrowSpan(it.roundToInt())) },
            valueRange = HourlyZoomRules.MIN_NARROW_SPAN_HOURS.toFloat()..
                HourlyZoomRules.MAX_NARROW_SPAN_HOURS.toFloat(),
            // 4..8 inclusive is 5 stops, i.e. 3 interior steps.
            steps = HourlyZoomRules.MAX_NARROW_SPAN_HOURS - HourlyZoomRules.MIN_NARROW_SPAN_HOURS - 1,
            modifier = Modifier.fillMaxWidth().testTag("hourly_zoom_span_slider")
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("4 hours", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("8 hours", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Include 2-day view",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Adds a 48-hour level — 36 hours back, 12 hours forward.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = multiDayZoomEnabled,
                onCheckedChange = onMultiDayZoomChanged,
                modifier = Modifier.testTag("hourly_zoom_two_day_switch"),
            )
        }
    }
}

@Composable
private fun PersonalStationDiscount(
    discountPercent: Int,
    onChanged: (Int) -> Unit
) {
    fun labelFor(percent: Int): String =
        com.weatherwidget.shared.util.formatPersonalStationDiscount(percent)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Personal (backyard) stations often over-read in the sun. Discount how much they count " +
                "toward the measured temperature. 0% = no discount; 100% = ignored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            labelFor(discountPercent),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
        Slider(
            value = discountPercent.toFloat(),
            onValueChange = { onChanged(it.toInt()) },
            valueRange = 0f..100f,
            steps = 0,
            modifier = Modifier.fillMaxWidth().testTag("personal_station_discount_slider")
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("0% · no discount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("100% · ignored", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Phase 4 item 3: rewritten to use [WeatherSourceOrdering] (the `:shared` helper) instead of
 * duplicating the toggle/move logic. Adds the three Android affordances the old desktop list was
 * missing: hidden-source dimming, click-on-text toggles the checkbox, and a Snackbar-driven
 * "must keep at least one source" message (Android's `R.string.must_keep_one_source`).
 */
/**
 * True when enabling [source] would leave it with no key at all: it needs one, the user has not
 * entered one, and this build did not bake one ([DesktopApiKeys.DEFAULTS], from
 * `local.properties`; empty on public builds). Mirrors `BuiltInApiKeys.hasEffectiveKey` on Android
 * and the fallback `DesktopWeatherService` actually fetches with, so Settings never demands a key
 * the fetch path already has — nor lets a source through that the fetch path cannot serve.
 */
internal fun needsKeyFromUser(source: WeatherSource, userKeys: Map<String, String>): Boolean =
    source.requiresApiKey &&
        userKeys[source.id].isNullOrBlank() &&
        DesktopApiKeys.DEFAULTS[source.id].isNullOrBlank()

@Composable
private fun ApiSourcesList(
    visibleSources: List<String>,
    apiKeys: Map<String, String> = emptyMap(),
    onChanged: (List<String>) -> Unit,
    onMustKeepOne: () -> Unit,
    onRequiresApiKey: (WeatherSource) -> Unit = {},
) {
    val orderedSources = remember(visibleSources) {
        WeatherSourceOrdering.ordered(visibleSources)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        orderedSources.forEach { source ->
            val isVisible = source.id in visibleSources
            val visibleIndex = visibleSources.indexOf(source.id)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (isVisible) 1f else 0.5f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = isVisible,
                    onCheckedChange = { checked ->
                        if (checked && needsKeyFromUser(source, apiKeys)) {
                            onRequiresApiKey(source)
                            return@Checkbox
                        }
                        val newList = WeatherSourceOrdering.toggle(visibleSources, source, makeVisible = checked)
                        if (newList == null) {
                            // Toggle refused: it would empty the list. Tell the user, and reassert
                            // the checkbox state so the UI doesn't lie about what persisted.
                            onMustKeepOne()
                        } else {
                            onChanged(newList)
                        }
                    },
                    modifier = Modifier.testTag("source_checkbox_${source.id}"),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            // Mirrors Android: tapping the source name toggles the checkbox.
                            val targetState = !isVisible
                            if (targetState && needsKeyFromUser(source, apiKeys)) {
                                onRequiresApiKey(source)
                                return@clickable
                            }
                            val newList = WeatherSourceOrdering.toggle(visibleSources, source, makeVisible = targetState)
                            if (newList == null) {
                                onMustKeepOne()
                            } else {
                                onChanged(newList)
                            }
                        },
                ) {
                    Text(source.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        source.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Phase 4 item 3: hide reorder arrows for hidden sources, matching Android's
                // View.INVISIBLE on the same buttons (SettingsActivity.kt:293-294).
                if (isVisible) {
                    IconButton(
                        onClick = { onChanged(WeatherSourceOrdering.moveUp(visibleSources, source)) },
                        enabled = visibleIndex > 0,
                        modifier = Modifier.testTag("move_up_${source.id}"),
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                    }
                    IconButton(
                        onClick = { onChanged(WeatherSourceOrdering.moveDown(visibleSources, source)) },
                        enabled = visibleIndex >= 0 && visibleIndex < visibleSources.size - 1,
                        modifier = Modifier.testTag("move_down_${source.id}"),
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                    }
                } else {
                    // Reserve the same horizontal space so visible rows don't shift when reordered.
                    Spacer(Modifier.width(48.dp))
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
    }
}

/**
 * Phase 4 item 2: each row gains a "Get key…" button (TertiaryActionButton — navy, matching
 * Android's rounded_button_navy.xml) that opens the source's signup page in the default browser.
 * URLs come from `WeatherSource.signupUrl` in `:shared` so both platforms stay in sync.
 */
@Composable
private fun ApiKeysList(
    apiKeys: Map<String, String>,
    onChanged: (Map<String, String>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ApiKeySignupUrls.sourcesRequiringKeys.forEach { source ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        source.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TertiaryActionButton(
                        text = "Get key…",
                        onClick = { openInBrowser(source.signupUrl) },
                        modifier = Modifier.testTag("get_key_${source.id}"),
                    )
                }
                Spacer(Modifier.height(4.dp))
                var text by remember(source.id) { mutableStateOf(apiKeys[source.id] ?: "") }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        val newKeys = apiKeys.toMutableMap()
                        if (it.isBlank()) newKeys.remove(source.id) else newKeys[source.id] = it
                        onChanged(newKeys)
                    },
                    label = { Text("${source.displayName} API key") },
                    modifier = Modifier.fillMaxWidth().testTag("api_key_${source.id}"),
                    singleLine = true,
                )
            }
        }
    }
}

/**
 * The icon grid, shown in its own window (see `IconGalleryWindowHost`) — the desktop counterpart of
 * Android's `IconGalleryActivity`. [iconSize] is larger there than the 32 dp it had when this sat
 * inline in the Settings form.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconGallery(iconSize: Dp = 32.dp, cellWidth: Dp = 80.dp) {
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
                modifier = Modifier.width(cellWidth)
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(res),
                    contentDescription = name,
                    modifier = Modifier.size(iconSize)
                )
                Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun DataUsageSectionContent(
    report: com.weatherwidget.shared.util.NetworkUsageReport?,
    isLoading: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Network data used for forecast and observation updates.",
            style = WeatherTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (isLoading) {
            Text(
                text = "Loading data usage…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val r = report ?: com.weatherwidget.shared.util.NetworkUsageReport(
                past24Hours = com.weatherwidget.shared.util.NetworkUsageWindow(),
                past7Days = com.weatherwidget.shared.util.NetworkUsageWindow(),
                past30Days = com.weatherwidget.shared.util.NetworkUsageWindow(),
                past90Days = com.weatherwidget.shared.util.NetworkUsageWindow(),
            )

            DataUsageWindowBlock(
                title = "Past 24 Hours",
                window = r.past24Hours,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            )
            DataUsageWindowBlock(
                title = "Past 7 Days (Week)",
                window = r.past7Days,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            )
            DataUsageWindowBlock(
                title = "Past 30 Days (Month)",
                window = r.past30Days,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            )
            DataUsageWindowBlock(
                title = "Past 90 Days",
                window = r.past90Days,
            )
        }
    }
}

@Composable
private fun DataUsageWindowBlock(
    title: String,
    window: com.weatherwidget.shared.util.NetworkUsageWindow,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        val cellTotal = com.weatherwidget.shared.util.formatNetworkBytes(window.cellular.totalBytes)
        val cellFg = com.weatherwidget.shared.util.formatNetworkBytes(window.cellular.foregroundBytes)
        val cellBg = com.weatherwidget.shared.util.formatNetworkBytes(window.cellular.backgroundBytes)
        Text(
            text = "Cellular: $cellTotal (FG: $cellFg • BG: $cellBg)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(1.dp))
        val wifiTotal = com.weatherwidget.shared.util.formatNetworkBytes(window.wifi.totalBytes)
        val wifiFg = com.weatherwidget.shared.util.formatNetworkBytes(window.wifi.foregroundBytes)
        val wifiBg = com.weatherwidget.shared.util.formatNetworkBytes(window.wifi.backgroundBytes)
        Text(
            text = "Wi-Fi: $wifiTotal (FG: $wifiFg • BG: $wifiBg)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
