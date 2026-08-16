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
import com.weatherwidget.shared.util.WeatherSourceDescriptions
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
    onOpenObservations: () -> Unit = {},
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
) {
    // NOT keyed on `config`. It used to be — `remember(config) { mutableStateOf(config) }` — which
    // made Compose discard the in-progress draft and re-seed from the baseline every time anything
    // else persisted the config. The popup saves constantly (window move/resize on a 1s debounce,
    // zoom scroll, pan, view switches, day clicks), so an edit made in the 5s before auto-save was
    // routinely wiped: the slider snapped back and the now-clean Save button became a silent no-op.
    // Un-keyed, the draft survives for the life of the window; `rebase` below keeps it current.
    var currentConfig by remember { mutableStateOf(config) }
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
    LaunchedEffect(config) {
        val rebased = config.withSettingsFrom(currentConfig)
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
                        SettingsCard(title = "Hourly Zoom") {
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

                        // Personal Weather Stations
                        SettingsCard(title = "Personal Weather Stations") {
                            PersonalStationDiscount(
                                discountPercent = currentConfig.settings.personalStationDiscount,
                                onChanged = { newPercent ->
                                    updateConfig(currentConfig.copy(settings = currentConfig.settings.copy(personalStationDiscount = newPercent)))
                                }
                            )
                        }

                        // Units — Android keeps this high-use display preference directly below
                        // the Personal Weather Stations discount slider.
                        SettingsCard(title = "Units") {
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
                        SettingsCard(title = "Daily View — Today Column") {
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
                            TodayOverlayToggleRow(
                                label = "Show reading age",
                                checked = currentConfig.settings.todayOverlayDominantAge,
                                testTag = "today_overlay_dominant_age_switch",
                            ) { updateConfig(currentConfig.copy(settings = currentConfig.settings.copy(todayOverlayDominantAge = it))) }
                        }

                        // Weather Data Sources -- title matches Android's
                        // R.string.api_sources_title = "Weather Data Sources".
                        SettingsCard(title = "Weather Data Sources") {
                            ApiSourcesList(
                                visibleSources = currentConfig.settings.visibleSources,
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
                            )
                        }

                        // API Keys
                        SettingsCard(title = "API Keys") {
                            ApiKeysList(
                                apiKeys = currentConfig.settings.apiKeys,
                                onChanged = { newKeys ->
                                    updateConfig(currentConfig.copy(settings = currentConfig.settings.copy(apiKeys = newKeys)))
                                }
                            )
                        }

                        // Icon Gallery
                        SettingsCard(title = "Icon Gallery") {
                            IconGallery()
                        }

                        // Location
                        // Phase 4 item 4: enrich the label with a reverse-geocoded place name from
                        // the shared resolver. The raw config.label stays as the immediate display
                        // value while the lookup runs (and as a fallback if it fails).
                        SettingsCard(title = "Location") {
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
                                    text = locationLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                )
                                PrimaryActionProminentButton(
                                    text = "Change Location",
                                    onClick = onUpdateLocation,
                                    modifier = Modifier.testTag("change_location_btn"),
                                )
                            }
                        }

                        // Diagnostics / Observations
                        SettingsCard(title = "Diagnostics") {
                            SecondaryActionButton(
                                text = "Stations / Observations",
                                onClick = onOpenObservations,
                                modifier = Modifier.testTag("open_observations_btn"),
                                prominent = true,
                            )
                        }

                        // Phase 4 item 5: Bug Report MVP. The full Android BugReportActivity is a
                        // separate activity with a description field and diagnostic checkboxes;
                        // the desktop MVP is a single button that opens a mailto: link with basic
                        // diagnostic info. Main.kt constructs the URI (so it can pull runtime
                        // details like app version / OS); SettingsWindow just fires the callback.
                        SettingsCard(title = "Feedback") {
                            AlertActionButton(
                                text = "Submit Bug Report",
                                onClick = onSubmitBugReport,
                                modifier = Modifier.testTag("submit_bug_report_btn"),
                            )
                        }

                        // Support Development (kept last, mirrors Android's SettingsActivity)
                        SettingsCard(title = "Support Development") {
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

private fun formatCoord(value: Double): String = "%.4f".format(value)

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
                    "Adds a 48-hour level — 42 hours back, 6 hours forward.",
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
    fun labelFor(percent: Int): String = when (percent) {
        0 -> "0% — no discount (counts the same as official)"
        100 -> "100% — personal stations ignored"
        else -> "$percent% discount"
    }

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
@Composable
private fun ApiSourcesList(
    visibleSources: List<String>,
    onChanged: (List<String>) -> Unit,
    onMustKeepOne: () -> Unit,
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
                            val newList = WeatherSourceOrdering.toggle(visibleSources, source, makeVisible = !isVisible)
                            if (newList == null) {
                                onMustKeepOne()
                            } else {
                                onChanged(newList)
                            }
                        },
                ) {
                    Text(source.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        WeatherSourceDescriptions.describe(source),
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
 * URLs come from the shared `:shared` ApiKeySignupUrls object so both platforms stay in sync.
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
                        onClick = { openInBrowser(ApiKeySignupUrls.signupUrl(source)) },
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
