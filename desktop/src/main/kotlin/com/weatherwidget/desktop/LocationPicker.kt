package com.weatherwidget.desktop

import com.weatherwidget.data.model.ResolvedLocation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
                Text("Set weather location", style = MaterialTheme.typography.titleLarge)

                suggested?.let { location ->
                    LocationSummary(location)
                    Button(onClick = { selectLocation(location) }) {
                        Text("Use this")
                    }
                    HorizontalDivider()
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Address, ZIP, or city") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            status = "Searching..."
                            results = emptyList()
                        },
                        enabled = query.isNotBlank(),
                    ) {
                        Text("Search")
                    }
                }

                LaunchedEffect(status) {
                    if (status == "Searching...") {
                        results = withContext(Dispatchers.IO) { resolver.searchText(query) }
                        status = if (results.isEmpty()) "No results" else "Choose a result"
                    }
                }

                if (results.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(results) { result ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectLocation(result) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(result.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${result.lat}, ${result.lon}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
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

                HorizontalDivider()
                Text("Location log", style = MaterialTheme.typography.titleSmall)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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
