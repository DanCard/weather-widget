package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.weatherwidget.desktop.theme.WeatherDarkColorScheme
import com.weatherwidget.desktop.theme.WeatherTypography
import com.weatherwidget.data.remote.weathernext.*
import com.weatherwidget.shared.rainmap.RainLoopLoadError
import io.ktor.client.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * When the WeatherNext host last answered 404/410, and which ([WeatherNextAvailability.knownGone]).
 * In memory only: the desktop app runs for days, and after a restart the next click re-checks.
 */
internal object WeatherNextDesktopState {
    @Volatile var goneAtMs: Long? = null
    @Volatile var goneStatus: Int = 404
}

private val WEATHER_NEXT_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

@Composable
internal fun RainForecastWindow(
    config: DesktopConfig,
    httpClient: HttpClient,
    onClose: () -> Unit,
    onError: (RainLoopLoadError) -> Unit = {},
    onUseOpenMeteo: (RainLoopLoadError) -> Unit = {},
) {
    val state = rememberWindowState(
        width = 1040.dp,
        height = 880.dp,
    )

    Window(
        onCloseRequest = onClose,
        state = state,
        title = "WeatherNext 3 · Rain & Storm Forecast Viewer",
    ) {
        MaterialTheme(colorScheme = WeatherDarkColorScheme, typography = WeatherTypography) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF0F172A),
            ) {
                RainForecastContent(
                    config = config,
                    httpClient = httpClient,
                    onError = onError,
                    onUseOpenMeteo = onUseOpenMeteo,
                )
            }
        }
    }
}

@Composable
private fun RainForecastContent(
    config: DesktopConfig,
    httpClient: HttpClient,
    onError: (RainLoopLoadError) -> Unit,
    onUseOpenMeteo: (RainLoopLoadError) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lat = config.lat
    val lon = config.lon
    val locationLabel = config.label.split(",").firstOrNull()?.trim() ?: "Current Location"

    val domain = remember(lat, lon) { WeatherNextDomain.resolve(lat, lon) }
    var selectedLayer by remember { mutableStateOf(WeatherNextLayer.QPF1_IMERG) }

    var manifest by remember { mutableStateOf<WeatherNextManifest?>(null) }
    var framesForLayer by remember { mutableStateOf<List<WeatherNextFrame>>(emptyList()) }
    val cachedBitmaps = remember { mutableStateMapOf<Int, ImageBitmap>() }

    var currentPos by remember { mutableStateOf(0.0) }
    // AUTO-PLAY: true by default so it starts playing immediately on open!
    var isPlaying by remember { mutableStateOf(true) }
    var speedMultiplier by remember { mutableStateOf(1.0f) }
    var zoomLevel by remember { mutableStateOf(1.0f) }
    var statusText by remember { mutableStateOf("Loading forecast manifest...") }
    var loadError by remember { mutableStateOf<RainLoopLoadError?>(null) }
    var manifestAttempt by remember { mutableStateOf(0) }
    val client = remember(httpClient) { WeatherNextForecastClient(httpClient, WEATHER_NEXT_JSON) }

    val cacheDir = remember {
        File(System.getProperty("user.home"), ".cache/weather-widget/forecast_loops").apply { mkdirs() }
    }

    // Load manifest on launch (and on retry: the host is a third-party GitHub Pages site).
    LaunchedEffect(domain, manifestAttempt) {
        loadError = null
        // A 404/410 within the last day still stands: say so without asking the host again.
        if (WeatherNextAvailability.knownGone(WeatherNextDesktopState.goneAtMs, System.currentTimeMillis())) {
            RainLoopLoadError.gone(WeatherNextAvailability.HOST, WeatherNextDesktopState.goneStatus).let { loadError = it; onError(it) }
            statusText = "Model run unavailable"
            return@LaunchedEffect
        }
        statusText = "Loading forecast manifest..."
        client.fetchManifest(domain)
            .onSuccess {
                WeatherNextDesktopState.goneAtMs = null
                manifest = it
                statusText = "Model Run: ${WeatherNextForecastClient.formatUtcToLocal(it.init ?: it.run)}"
            }
            .onFailure {
                if (it is WeatherNextGoneException) {
                    WeatherNextDesktopState.goneAtMs = System.currentTimeMillis()
                    WeatherNextDesktopState.goneStatus = it.status
                }
                WeatherNextAvailability.loadError(it).let { e -> loadError = e; onError(e) }
                statusText = "Model run unavailable"
            }
    }

    // Filter frames when layer or manifest changes
    LaunchedEffect(manifest, selectedLayer) {
        val m = manifest ?: return@LaunchedEffect
        val layerId = selectedLayer.id
        val filtered = m.frames.filter { it.files.containsKey(layerId) }
        framesForLayer = filtered
        cachedBitmaps.clear()
        currentPos = 0.0

        // Background prefetch frames for this layer; frames are cached per model run.
        loadError = null
        if (filtered.isEmpty()) {
            RainLoopLoadError.noFrames(WeatherNextAvailability.HOST, 0).let { loadError = it; onError(it) }
            return@LaunchedEffect
        }
        scope.launch(Dispatchers.IO) {
            WeatherNextFrameCache.pruneOtherRuns(cacheDir, domain, layerId, m)
            var loaded = 0
            filtered.forEachIndexed { idx, frame ->
                val file = client.cachedFrame(cacheDir, domain, layerId, m, frame).getOrNull() ?: return@forEachIndexed
                try {
                    cachedBitmaps[idx] = Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
                    loaded++
                } catch (_: Exception) {}
            }
            // Some frames missing: the loop plays the rest. None at all: say so.
            if (loaded == 0 && selectedLayer.id == layerId) {
                RainLoopLoadError.noFrames(WeatherNextAvailability.HOST, filtered.size).let { withContext(Dispatchers.Main) { loadError = it; onError(it) } }
            }
        }
    }

    // Auto-play loop ticker
    LaunchedEffect(isPlaying, speedMultiplier, framesForLayer.size) {
        val tickIntervalMs = 35L
        val baseStepMs = 140f // ~7 FPS at 1.0x
        while (isActive && isPlaying && framesForLayer.isNotEmpty()) {
            delay(tickIntervalMs)
            val advance = (tickIntervalMs * speedMultiplier) / baseStepMs
            val maxPos = (framesForLayer.size - 1).toDouble()
            var nextPos = currentPos + advance
            if (nextPos > maxPos) {
                nextPos = 0.0
            }
            currentPos = nextPos
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 1. Top Header Banner
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E293B))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "📍 $locationLabel",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "· ${domain.title}",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                    )
                }
                Text(
                    text = statusText,
                    color = if (loadError != null) Color(0xFFF59E0B) else Color(0xFF64748B),
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Layer Selector Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "CHART: ",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                WeatherNextLayer.entries.forEach { layer ->
                    val isSelected = layer == selectedLayer
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .background(
                                color = if (isSelected) Color(0xFF2563EB) else Color(0xFF334155),
                                shape = RoundedCornerShape(6.dp),
                            )
                            .clickable { selectedLayer = layer }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = when (layer) {
                                WeatherNextLayer.QPF1_IMERG -> "⏱ 1-Hour Rain"
                                WeatherNextLayer.PCP6 -> "🌧 6-Hour Fronts"
                                WeatherNextLayer.QPF_ACC -> "💧 Total Rain"
                                WeatherNextLayer.IVT -> "🌊 Water Vapor"
                            },
                            color = if (isSelected) Color.White else Color(0xFFE2E8F0),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = selectedLayer.subtitle,
                color = Color(0xFF38BDF8),
                fontSize = 11.sp,
            )
        }

        // 2. Main Map Area (Compose Canvas with smooth sub-frame rendering)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF0F172A))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (delta < 0) {
                                    zoomLevel = min(8.0f, zoomLevel * 1.25f)
                                } else if (delta > 0) {
                                    zoomLevel = max(1.0f, zoomLevel / 1.25f)
                                }
                            }
                        }
                    }
                }
        ) {
            val idx1 = currentPos.toInt()
            val idx2 = min(framesForLayer.size - 1, idx1 + 1)
            val subAlpha = (currentPos - idx1).toFloat().coerceIn(0f, 1f)

            val bitmap1 = cachedBitmaps[idx1]
            val bitmap2 = cachedBitmaps[idx2]

            Canvas(modifier = Modifier.fillMaxSize()) {
                val cw = size.width
                val ch = size.height
                if (bitmap1 == null) return@Canvas

                val figW = bitmap1.width.toFloat()
                val figH = bitmap1.height.toFloat()

                val (px, py) = WeatherNextCoordinateProjector.projectToPixel(
                    lat = lat,
                    lon = lon,
                    domain = domain,
                    figWidth = figW,
                    figHeight = figH,
                )

                val crop = WeatherNextCoordinateProjector.calculateCropBox(
                    px = px,
                    py = py,
                    figWidth = figW,
                    figHeight = figH,
                    zoom = zoomLevel,
                )

                val cropAspect = crop.width / crop.height
                val canvasAspect = cw / ch
                val dispW: Float
                val dispH: Float
                val offsetX: Float
                val offsetY: Float

                if (canvasAspect > cropAspect) {
                    dispH = ch
                    dispW = ch * cropAspect
                    offsetX = (cw - dispW) / 2f
                    offsetY = 0f
                } else {
                    dispW = cw
                    dispH = cw / cropAspect
                    offsetX = 0f
                    offsetY = (ch - dispH) / 2f
                }

                val srcOffset = IntOffset(crop.x1.toInt(), crop.y1.toInt())
                val srcSize = IntSize(crop.width.toInt(), crop.height.toInt())
                val dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt())
                val dstSize = IntSize(dispW.toInt(), dispH.toInt())

                // Draw base frame
                drawImage(
                    image = bitmap1,
                    srcOffset = srcOffset,
                    srcSize = srcSize,
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                    alpha = 1f,
                )

                // Cross-fade frame 2 on top
                if (bitmap2 != null && subAlpha > 0.01f && idx1 != idx2) {
                    drawImage(
                        image = bitmap2,
                        srcOffset = srcOffset,
                        srcSize = srcSize,
                        dstOffset = dstOffset,
                        dstSize = dstSize,
                        alpha = subAlpha,
                    )
                }

                // Location marker pin
                val pinX = offsetX + (px - crop.x1) * (dispW / crop.width)
                val pinY = offsetY + (py - crop.y1) * (dispH / crop.height)

                if (pinX in offsetX..(offsetX + dispW) && pinY in offsetY..(offsetY + dispH)) {
                    drawCircle(
                        color = Color.White,
                        radius = 8f,
                        center = Offset(pinX, pinY),
                    )
                    drawCircle(
                        color = Color(0xFFEF4444),
                        radius = 6f,
                        center = Offset(pinX, pinY),
                    )
                }
            }

            loadError?.let { error ->
                SimLoopErrorPanel(
                    error = error,
                    onRetry = { manifestAttempt++ },
                    onUseOpenMeteo = { onUseOpenMeteo(error) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Top-left HUD badge on map
            val lead = framesForLayer.getOrNull(idx1)?.lead ?: 0
            val validIso = framesForLayer.getOrNull(idx1)?.valid
            val validLocal = WeatherNextForecastClient.formatUtcToLocal(validIso)

            Box(
                modifier = Modifier
                    .padding(12.dp)
                    .background(Color(0xDD0F172A), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Column {
                    Text(
                        text = "${selectedLayer.shortTitle} · F+${String.format("%03d", lead)}h",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Valid: $validLocal",
                        color = Color.White,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        // 3. Legend / Scale Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E293B))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "SCALE: ",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            selectedLayer.swatches.forEach { swatch ->
                val clean = swatch.hexColor.removePrefix("#")
                val bgCol = Color(clean.toLong(16) or 0xFF000000L)
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .background(bgCol, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = swatch.label,
                        color = if (swatch.hexColor in listOf("#1f6fbf", "#e41800")) Color.White else Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "• ${selectedLayer.legendNote}",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
            )
        }

        // 4. Playback Controls Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Scrub slider
            if (framesForLayer.isNotEmpty()) {
                Slider(
                    value = currentPos.toFloat(),
                    onValueChange = { currentPos = it.toDouble() },
                    valueRange = 0f..(framesForLayer.size - 1).toFloat(),
                    modifier = Modifier.fillMaxWidth().height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF38BDF8),
                        activeTrackColor = Color(0xFF0284C7),
                        inactiveTrackColor = Color(0xFF334155),
                    ),
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Play / Pause & Steps
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { isPlaying = !isPlaying },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPlaying) Color(0xFFE11D48) else Color(0xFF0284C7)
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(if (isPlaying) "⏸ Pause" else "▶ Play")
                    }

                    Spacer(Modifier.width(6.dp))

                    OutlinedButton(
                        onClick = {
                            currentPos = max(0.0, currentPos.toInt() - 1.0)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("⏮")
                    }

                    Spacer(Modifier.width(4.dp))

                    OutlinedButton(
                        onClick = {
                            currentPos = min(framesForLayer.size - 1.0, currentPos.toInt() + 1.0)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("⏭")
                    }
                }

                // Speed Selector
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speed: ", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    listOf(0.5f, 1.0f, 1.5f, 2.5f).forEach { spd ->
                        val active = speedMultiplier == spd
                        Text(
                            text = "${spd}x",
                            color = if (active) Color(0xFF38BDF8) else Color(0xFF64748B),
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clickable { speedMultiplier = spd }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }

                // Zoom Selector
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Zoom: ", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    listOf(1.0f to "1x", 2.5f to "2.5x", 5.0f to "5x").forEach { (z, lbl) ->
                        val active = (zoomLevel - z) in -0.2f..0.2f
                        Text(
                            text = lbl,
                            color = if (active) Color(0xFF38BDF8) else Color(0xFF64748B),
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clickable { zoomLevel = z }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimLoopErrorPanel(
    error: RainLoopLoadError,
    onRetry: () -> Unit,
    onUseOpenMeteo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 520.dp)
            .background(Color(0xEE1E293B), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = error.englishText(),
            color = Color(0xFFFDE68A),
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (error.retryable) {
                OutlinedButton(onClick = onRetry) { Text("Retry") }
            }
            Button(onClick = onUseOpenMeteo) { Text("Use Open-Meteo rain map") }
        }
    }
}
