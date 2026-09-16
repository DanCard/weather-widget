package com.weatherwidget.desktop

import com.weatherwidget.data.model.RecentLocation
import com.weatherwidget.data.model.ResolvedLocation
import com.weatherwidget.shared.util.RecentLocationsHelper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.desktop.theme.WeatherDarkColorScheme
import com.weatherwidget.desktop.theme.WeatherTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities

@Composable
fun LocationPicker(
    resolver: LocationResolver,
    allowAutoSelect: Boolean = true,
    recentLocations: List<RecentLocation> = emptyList(),
    onLocationSelected: (ResolvedLocation) -> Unit,
) {
    var suggested by remember { mutableStateOf<ResolvedLocation?>(null) }
    var phoneLocation by remember { mutableStateOf<ResolvedLocation?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ResolvedLocation>>(emptyList()) }
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Choose a location") }
    var selectionFinalized by remember { mutableStateOf(false) }
    val acquisitionLog = remember { mutableStateListOf<String>() }

    fun appendLog(message: String) {
        acquisitionLog.add(message)
    }

    fun appendLogFromBackground(message: String) {
        SwingUtilities.invokeLater {
            acquisitionLog.add(message)
        }
    }

    fun selectLocation(location: ResolvedLocation) {
        if (selectionFinalized) return
        selectionFinalized = true
        onLocationSelected(location)
    }

    LaunchedEffect(Unit) {
        acquisitionLog.clear()
        appendLog("Starting location acquisition.")

        launch {
            val prefill = withContext(Dispatchers.IO) {
                resolver.suggestPrefill(::appendLogFromBackground)
            }
            if (!selectionFinalized) {
                suggested = prefill
                prefill?.let {
                    latText = it.lat.toString()
                    lonText = it.lon.toString()
                } ?: appendLog("No IP or timezone prefill is available.")
            }
        }

        launch {
            val phone = withContext(Dispatchers.IO) {
                resolver.fromPhone(::appendLogFromBackground)
            }
            if (!selectionFinalized) {
                phoneLocation = phone
                when {
                    phone == null -> appendLog("Phone GPS did not return a usable location.")
                    phone.isFresh && allowAutoSelect -> {
                        appendLog("Phone GPS returned a fresh location; saving it.")
                        selectLocation(phone)
                    }
                    phone.isFresh -> appendLog("Phone GPS returned a fresh location; waiting for selection.")
                    else -> appendLog("Phone GPS location is stale; leaving picker open.")
                }
            }
        }
    }

    MaterialTheme(colorScheme = WeatherDarkColorScheme, typography = WeatherTypography) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(3f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Set weather location", style = MaterialTheme.typography.titleLarge)

                    suggested?.let { location ->
                        LocationSummary(location)
                        Button(onClick = { selectLocation(location) }) {
                            Text("Use this")
                        }
                        HorizontalDivider()
                    }

                    var searchFocused by remember { mutableStateOf(false) }
                    val matchingRecents = remember(recentLocations, query) {
                        RecentLocationsHelper.filterMatching(recentLocations, query)
                    }

                    fun triggerSearch() {
                        if (query.isNotBlank()) {
                            searchFocused = false
                            status = "Searching..."
                            results = emptyList()
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = {
                                    query = it
                                    searchFocused = true
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { searchFocused = it.isFocused }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                            triggerSearch()
                                            true
                                        } else {
                                            false
                                        }
                                    },
                                label = { Text("Address, ZIP, or city") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { triggerSearch() }),
                            )

                            DropdownMenu(
                                expanded = searchFocused && matchingRecents.isNotEmpty(),
                                onDismissRequest = { searchFocused = false },
                                modifier = Modifier.fillMaxWidth(0.85f),
                            ) {
                                matchingRecents.forEach { recent ->
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Text("🕒", fontSize = 14.sp)
                                        },
                                        text = {
                                            Column {
                                                Text(recent.label, style = MaterialTheme.typography.bodyMedium)
                                                Text(
                                                    "${recent.lat}, ${recent.lon}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            searchFocused = false
                                            selectLocation(recent.toResolvedLocation())
                                        },
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = ::triggerSearch,
                            enabled = query.isNotBlank(),
                        ) {
                            Text("Search")
                        }
                    }

                    LaunchedEffect(status) {
                        if (status == "Searching...") {
                            results = runCatching { withContext(Dispatchers.IO) { resolver.searchText(query) } }
                                .getOrElse { e ->
                                    status = "Search failed: ${e.message ?: e.javaClass.simpleName}"
                                    return@LaunchedEffect
                                }
                            status = if (results.isEmpty()) "No results" else ""
                        }
                    }

                    if (results.isNotEmpty()) {
                        // Plain text rows read as output, not as a choice: say so, and give each match
                        // a visible button so the required next step is unmistakable.
                        Text(
                            if (results.size == 1) "One match — confirm it:" else "Choose one of these ${results.size} matches:",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            results.forEach { result ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                                        .clickable { selectLocation(result) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(result.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${result.lat}, ${result.lon}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Button(onClick = { selectLocation(result) }) {
                                        Text("Use")
                                    }
                                }
                            }
                        }
                    } else {
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }

                    HorizontalDivider()
                    Text("Coordinates", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = latText,
                            onValueChange = { latText = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Latitude") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = lonText,
                            onValueChange = { lonText = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Longitude") },
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                val lat = latText.toDoubleOrNull()
                                val lon = lonText.toDoubleOrNull()
                                if (lat == null || lon == null) {
                                    status = "Enter valid coordinates"
                                } else {
                                    status = "Resolving coordinates..."
                                }
                            },
                        ) {
                            Text("Use")
                        }
                    }

                    LaunchedEffect(status) {
                        if (status == "Resolving coordinates...") {
                            val lat = latText.toDoubleOrNull()
                            val lon = lonText.toDoubleOrNull()
                            if (lat != null && lon != null) {
                                selectLocation(withContext(Dispatchers.IO) { resolver.fromCoordinates(lat, lon) })
                            }
                        }
                    }

                    HorizontalDivider()
                    Button(
                        onClick = {
                            status = "Reading phone GPS..."
                            phoneLocation = null
                            appendLog("Manual phone GPS retry started.")
                        },
                    ) {
                        Text("Use connected phone (GPS)")
                    }
                    LaunchedEffect(status) {
                        if (status == "Reading phone GPS...") {
                            val phone = withContext(Dispatchers.IO) { resolver.fromPhone(::appendLogFromBackground) }
                            if (!selectionFinalized) {
                                phoneLocation = phone
                                status = if (phone == null) {
                                    "No phone location found"
                                } else {
                                    "Phone location ready"
                                }
                                if (phone?.isFresh == true) {
                                    appendLog("Manual phone GPS retry returned a fresh location; saving it.")
                                    selectLocation(phone)
                                } else if (phone != null) {
                                    appendLog("Manual phone GPS retry returned a stale location.")
                                }
                            }
                        }
                    }
                    phoneLocation?.let { phone ->
                        LocationSummary(phone)
                        Button(onClick = { selectLocation(phone) }) {
                            Text("Use phone location")
                        }
                    }
                }

                HorizontalDivider()
                Text("Location log", style = MaterialTheme.typography.titleSmall)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = 90.dp),
                ) {
                    items(acquisitionLog) { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationSummary(location: ResolvedLocation) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(location.label, style = MaterialTheme.typography.bodyMedium)
        Text(
            buildString {
                append("${location.lat}, ${location.lon} - ${location.source}")
                location.detail?.let { append(" - $it") }
                if (!location.isFresh) append(" - stale")
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
