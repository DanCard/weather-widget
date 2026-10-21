package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.weatherwidget.data.remote.weathernext.WeatherNextForecastClient
import com.weatherwidget.desktop.theme.WeatherDarkColorScheme
import com.weatherwidget.desktop.theme.WeatherTypography
import com.weatherwidget.shared.rainmap.BaseMap
import com.weatherwidget.shared.rainmap.RainColorScale
import com.weatherwidget.shared.rainmap.RainGrid
import com.weatherwidget.shared.rainmap.RainGridRaster
import com.weatherwidget.shared.rainmap.RainGridRepository
import com.weatherwidget.shared.rainmap.RainGridSpec
import com.weatherwidget.shared.rainmap.RainLayer
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The precipitation pill's loop when the popup shows **Open-Meteo** — desktop twin of Android's
 * `OpenMeteoRainLoopActivity`. Other sources keep [RainForecastWindow] (WeatherNext).
 * See `plans/261020-open-meteo-rain-map-loop.md`.
 */
@Composable
internal fun OpenMeteoRainLoopWindow(
    config: DesktopConfig,
    httpClient: HttpClient,
    onClose: () -> Unit,
) {
    Window(
        onCloseRequest = onClose,
        state = rememberWindowState(width = 760.dp, height = 900.dp),
        title = "Rain Map · Open-Meteo",
    ) {
        MaterialTheme(colorScheme = WeatherDarkColorScheme, typography = WeatherTypography) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
                OpenMeteoRainLoopContent(config, httpClient)
            }
        }
    }
}

private const val TICK_MS = 35L
private const val FRAME_MS = 400f
private val SPEEDS = listOf(0.5f, 1.0f, 1.5f, 2.5f)

@Composable
private fun OpenMeteoRainLoopContent(config: DesktopConfig, httpClient: HttpClient) {
    val place = shortPlace(config.label) ?: "Current location"
    val useInches = Locale.getDefault().country.uppercase(Locale.US) in setOf("US", "GB")
    val repo = remember(httpClient) {
        RainGridRepository(httpClient, File(System.getProperty("user.home"), ".cache/weather-widget/rain_grid"))
    }
    var grid by remember { mutableStateOf<RainGrid?>(null) }
    var status by remember { mutableStateOf("Loading forecast…") }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var layer by remember { mutableStateOf(RainLayer.AMOUNT) }
    var pos by remember { mutableStateOf(0.0) }
    var nowPos by remember { mutableStateOf(0.0) }
    var playing by remember { mutableStateOf(true) }
    var speedIndex by remember { mutableStateOf(1) }
    // Wide (±8°) start ↔ detail (±2°) around the pin; each zoom is its own cached grid.
    var detail by remember { mutableStateOf(false) }

    LaunchedEffect(config.lat, config.lon, attempt, detail) {
        failed = false
        status = "Loading forecast…"
        val firstLoad = grid == null
        repo.load(config.lat, config.lon, if (detail) RainGridSpec.DETAIL else RainGridSpec.WIDE)
            .onSuccess {
                grid = it
                nowPos = it.hourPosition(System.currentTimeMillis())
                // A zoom switch keeps the moment being watched; only the first load starts at now.
                pos = if (firstLoad) nowPos else pos.coerceIn(0.0, (it.times.size - 1).toDouble())
                status = "Map: Open-Meteo · fetched " +
                    Instant.ofEpochMilli(it.fetchedAtMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
            }
            .onFailure {
                failed = true
                status = "Couldn't load the rain map. Click to retry."
            }
    }

    LaunchedEffect(playing, speedIndex, grid) {
        val g = grid ?: return@LaunchedEffect
        while (isActive && playing && g.times.size > 1) {
            delay(TICK_MS)
            var next = pos + TICK_MS * SPEEDS[speedIndex] / FRAME_MS
            // Loop from now to the end, then back to now.
            if (next > g.times.size - 1) next = nowPos
            pos = next
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(Color(0xFF1E293B)).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("📍 $place", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                status,
                color = if (failed) Color(0xFFF59E0B) else Color(0xFF94A3B8),
                fontSize = 12.sp,
                modifier = if (failed) Modifier.clickable { attempt++ } else Modifier,
            )
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((l, label) in listOf(RainLayer.AMOUNT to "Amount", RainLayer.CHANCE to "Chance")) {
                Button(
                    onClick = { layer = l },
                    colors = ButtonDefaults.buttonColors(containerColor = if (layer == l) Color(0xFF2563EB) else Color(0xFF334155)),
                ) { Text(label, color = Color.White) }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { detail = !detail },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
            ) { Text(if (detail) "Zoom out" else "Zoom in", color = Color.White) }
        }

        val g = grid
        if (g != null) {
            val hourMs = g.times[0] + (pos * RainGrid.HOUR_MS).toLong()
            Text(
                "Valid: " + WeatherNextForecastClient.formatUtcToLocal(Instant.ofEpochMilli(hourMs).toString()),
                color = Color(0xFF38BDF8), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val mm = g.valueAt(RainLayer.AMOUNT, g.centerRow, g.centerCol, pos)
            val pct = g.valueAt(RainLayer.CHANCE, g.centerRow, g.centerCol, pos)
            Text(
                "Here: ${rate(mm, useInches)} · ${pct.roundToInt()}%",
                color = Color.White, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            if (g == null) {
                if (!failed) CircularProgressIndicator()
            } else {
                RainMapCanvas(g, layer, pos, place, Modifier.fillMaxSize().aspectRatio(1f, matchHeightConstraintsFirst = true))
            }
        }

        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            for (stop in RainColorScale.stops(layer)) {
                val label = if (layer == RainLayer.AMOUNT) RainColorScale.amountLabel(stop.value, useInches) else "${stop.value.roundToInt()}%"
                Text(
                    " $label ",
                    color = Color(0xFF0F172A), fontSize = 11.sp,
                    modifier = Modifier.background(Color(stop.argb or 0xFF000000.toInt())),
                )
                Spacer(Modifier.width(4.dp))
            }
            if (layer == RainLayer.AMOUNT) Text(if (useInches) "in/h" else "mm/h", color = Color(0xFF94A3B8), fontSize = 11.sp)
        }

        Column(Modifier.fillMaxWidth().background(Color(0xFF1E293B)).padding(12.dp)) {
            val last = ((g?.times?.size ?: 1) - 1).coerceAtLeast(1).toFloat()
            Slider(value = pos.toFloat().coerceIn(0f, last), onValueChange = { pos = it.toDouble() }, valueRange = 0f..last)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { playing = !playing }) { Text(if (playing) "⏸ Pause" else "▶ Play") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { pos = nowPos }) { Text("Now") }
                Spacer(Modifier.weight(1f))
                Text(
                    listOf("0.5× (Smooth)", "1.0× (Snappy)", "1.5× (Fast)", "2.5× (Blazing)")[speedIndex],
                    color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { speedIndex = (speedIndex + 1) % SPEEDS.size }.padding(8.dp),
                )
            }
        }
    }
}

/**
 * "860, Avery Drive, Mountain View, …" → "860 Avery Drive": Nominatim puts the house number in its
 * own comma part, so the first part alone printed just "860" beside the pin.
 */
internal fun shortPlace(label: String): String? {
    val parts = label.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val first = parts.firstOrNull() ?: return null
    return if (first.none { it.isLetter() } && parts.size > 1) "$first ${parts[1]}" else first
}

private fun rate(mm: Float, useInches: Boolean): String =
    if (useInches) {
        String.format(Locale.US, "%.2f", mm / 25.4f).trimEnd('0').trimEnd('.').removePrefix("0").ifEmpty { "0" } + " in/h"
    } else {
        String.format(Locale.US, "%.1f", mm).trimEnd('0').trimEnd('.') + " mm/h"
    }

/** Base map, the rain raster scaled up with bilinear filtering, and the pin at the centre. */
@Composable
private fun RainMapCanvas(grid: RainGrid, layer: RainLayer, pos: Double, place: String, modifier: Modifier) {
    val lines = remember(grid) { BaseMap.linesFor(grid) }
    val size = RainGridRaster.DEFAULT_SIZE_PX
    val pixels = remember { IntArray(size * size) }
    val image = rasterImage(RainGridRaster.render(grid, layer, pos, size, size, pixels), size)
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    // Compose does not clip a Canvas to its bounds: without this, coastlines ran past the map square.
    Canvas(modifier.clipToBounds()) {
        drawRect(Color(0xFF1E293B))
        for (line in lines) {
            val path = Path()
            for (k in line.lats.indices) {
                val x = BaseMap.x(grid, line.lons[k], this.size.width)
                val y = BaseMap.y(grid, line.lats[k], this.size.height)
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val (color, width) = when (line.kind) {
                BaseMap.Kind.COAST, BaseMap.Kind.LAKE -> Color(0xFFCBD5E1) to 1.2f
                BaseMap.Kind.COUNTRY -> Color(0xFF94A3B8) to 1f
                BaseMap.Kind.STATE -> Color(0x8094A3B8) to 0.7f
            }
            drawPath(path, color, style = Stroke(width = width.dp.toPx()))
        }
        drawImage(
            image,
            dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
            dstOffset = IntOffset.Zero,
            filterQuality = FilterQuality.Low,
        )
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(Color.White, 7.dp.toPx(), c)
        drawCircle(Color(0xFFEF4444), 5.dp.toPx(), c)
        drawText(
            textMeasurer, place, topLeft = Offset(c.x + 10.dp.toPx(), c.y - 9.dp.toPx()),
            style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
    }
}

/** ARGB ints → a Skia image (BGRA bytes on little-endian N32). */
private fun rasterImage(argb: IntArray, side: Int): ImageBitmap {
    val bytes = ByteArray(argb.size * 4)
    for (i in argb.indices) {
        val p = argb[i]
        bytes[i * 4] = (p and 0xFF).toByte()
        bytes[i * 4 + 1] = (p ushr 8 and 0xFF).toByte()
        bytes[i * 4 + 2] = (p ushr 16 and 0xFF).toByte()
        bytes[i * 4 + 3] = (p ushr 24 and 0xFF).toByte()
    }
    return Image.makeRaster(ImageInfo(side, side, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, side * 4)
        .toComposeImageBitmap()
}
