package com.weatherwidget.desktop

import androidx.compose.foundation.Image
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
import com.weatherwidget.shared.util.ApiKeySignupUrls
import com.weatherwidget.shared.util.WeatherSourceDescriptions
import com.weatherwidget.shared.util.WeatherSourceOrdering

@Composable
internal fun SettingsWindow(
    config: DesktopConfig,
    onClose: () -> Unit,
    onSave: (DesktopConfig) -> Unit,
    onExit: () -> Unit,
    onUpdateLocation: () -> Unit = {},
    onOpenObservations: () -> Unit = {},
    onRefreshData: suspend () -> Unit = {},
    onViewAppLogs: () -> Unit = {},
    // Phase 4 item 4: reverse-geocoded location label. Null on first-launch / preview paths so the
    // caller still sees config.label verbatim; non-null in Main.kt where the resolver is already
    // constructed for LocationPicker.
    locationResolver: SharedLocationResolver? = null,
    // Phase 4 item 5: Bug Report MVP. Main.kt wires this to a mailto: launcher; default no-op so
    // existing tests / preview paths keep working unchanged.
    onSubmitBugReport: () -> Unit = {},
) {
    var currentConfig by remember { mutableStateOf(config) }
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = WeatherDarkColorScheme, typography = WeatherTypography) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose) {
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
                        var isRefreshing by remember { mutableStateOf(false) }
                        PrimaryActionButton(
                            text = if (isRefreshing) "Refreshing…" else "Refresh Data",
                            onClick = {
                                scope.launch {
                                    isRefreshing = true
                                    try {
                                        onRefreshData()
                                    } finally {
                                        isRefreshing = false
                                    }
                                }
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
                        // API Sources
                        SettingsCard(title = "API Sources") {
                            ApiSourcesList(
                                visibleSources = currentConfig.visibleSources,
                                onChanged = { newSources ->
                                    currentConfig = currentConfig.copy(visibleSources = newSources)
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

                        // Personal Weather Stations
                        SettingsCard(title = "Personal Weather Stations") {
                            PersonalStationDiscount(
                                discountPercent = currentConfig.personalStationDiscount,
                                onChanged = { newPercent ->
                                    currentConfig = currentConfig.copy(personalStationDiscount = newPercent)
                                }
                            )
                        }

                        // API Keys
                        SettingsCard(title = "API Keys") {
                            ApiKeysList(
                                apiKeys = currentConfig.apiKeys,
                                onChanged = { newKeys ->
                                    currentConfig = currentConfig.copy(apiKeys = newKeys)
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

                        // Units
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
                                    checked = currentConfig.useCelsius,
                                    onCheckedChange = { isChecked ->
                                        currentConfig = currentConfig.copy(useCelsius = isChecked)
                                    },
                                    modifier = Modifier.testTag("use_celsius_switch")
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
                            text = "Save",
                            onClick = {
                                onSave(currentConfig)
                                onClose()
                            },
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
