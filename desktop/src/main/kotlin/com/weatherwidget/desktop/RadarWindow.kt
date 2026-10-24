package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.weatherwidget.desktop.theme.WeatherDarkColorScheme
import com.weatherwidget.desktop.theme.WeatherTypography
import com.weatherwidget.shared.radar.ArgbImage
import com.weatherwidget.shared.radar.NoRadarFramesException
import com.weatherwidget.shared.radar.RadarBox
import com.weatherwidget.shared.radar.RadarFeed
import com.weatherwidget.shared.radar.RadarImageDecoder
import com.weatherwidget.shared.radar.RadarLegend
import com.weatherwidget.shared.radar.RadarPlayback
import com.weatherwidget.shared.radar.RadarRepository
import com.weatherwidget.shared.rainmap.BaseMap
import com.weatherwidget.shared.rainmap.RainLoopLoadError
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * The precipitation pill "Radar" — desktop twin of Android's `RadarActivity`: the last ~2 h of
 * observed radar around the popup's location over the offline base map.
 * See `plans/261021-radar-button-on-precip-graph.md`.
 */
@Composable
internal fun RadarWindow(
    config: DesktopConfig,
    httpClient: HttpClient,
    onClose: () -> Unit,
) {
    Window(
        onCloseRequest = onClose,
        state = rememberWindowState(width = 760.dp, height = 860.dp),
        title = "Radar",
    ) {
        MaterialTheme(colorScheme = WeatherDarkColorScheme, typography = WeatherTypography) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
                RadarContent(config, httpClient)
            }
        }
    }
}

private const val TICK_MS = 100L

/** `ImageIO` behind the shared repository's decoder seam. */
internal object ImageIoRadarDecoder : RadarImageDecoder {
    override fun decode(bytes: ByteArray): ArgbImage? {
        val img = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val pixels = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        return ArgbImage(img.width, img.height, pixels)
    }
}

@Composable
private fun RadarContent(config: DesktopConfig, httpClient: HttpClient) {
    val place = shortPlace(config.label) ?: "Current location"
    val feed = remember(config.lat, config.lon) { RadarFeed.forLocation(config.lat, config.lon) }
    val repo = remember(httpClient) {
        RadarRepository(httpClient, File(System.getProperty("user.home"), ".cache/weather-widget/radar"), ImageIoRadarDecoder)
    }
    var box by remember { mutableStateOf(RadarBox.around(config.lat, config.lon)) }
    var frames by remember { mutableStateOf<List<Pair<Long, ImageBitmap>>>(emptyList()) }
    var hasEchoes by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Loading radar…") }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var index by remember { mutableStateOf(0) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(true) }

    LaunchedEffect(config.lat, config.lon, attempt) {
        failed = false
        status = "Loading radar…"
        repo.load(config.lat, config.lon)
            .onSuccess { loop ->
                box = loop.box
                frames = loop.frames.map { it.timeMs to argbImageBitmap(it.image.pixels, it.image.width, it.image.height) }
                hasEchoes = loop.hasEchoes
                status = "Radar: ${loop.feed.attribution} · observed, past 2 h"
                // Open on the newest frame, then loop from the oldest.
                index = frames.size - 1
                elapsedMs = RadarPlayback.startOf(index)
            }
            .onFailure {
                failed = true
                val host = RadarRepository.host(feed)
                val error = if (it is NoRadarFramesException) RainLoopLoadError.noFrames(host, it.asked) else RainLoopLoadError.of(it, host)
                status = error.englishText() + " Click to retry."
            }
    }

    LaunchedEffect(playing, frames) {
        while (isActive && playing && frames.size > 1) {
            delay(TICK_MS)
            elapsedMs += TICK_MS
            index = RadarPlayback.frameAt(elapsedMs, frames.size)
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

        frames.getOrNull(index)?.let { (timeMs, _) ->
            val clock = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
            Text(
                "$clock · ${RadarPlayback.minutesAgo(timeMs, System.currentTimeMillis())} min ago",
                color = Color(0xFF38BDF8), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            RadarMapCanvas(box, frames.getOrNull(index)?.second, place, Modifier.fillMaxSize().aspectRatio(1f, matchHeightConstraintsFirst = true))
            if (frames.isEmpty() && !failed) CircularProgressIndicator()
            // A dry sky is the common case, not an error: said over the map, which stays.
            if (frames.isNotEmpty() && !hasEchoes) {
                Text(
                    "No rain on radar nearby right now.",
                    color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                        .background(Color(0xCC1E293B)).padding(8.dp),
                )
            }
        }

        val stops = RadarLegend.stops(feed)
        if (stops.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                for (stop in stops) {
                    Text(" ${stop.dbz} ", color = Color(0xFF0F172A), fontSize = 11.sp, modifier = Modifier.background(Color(stop.argb)))
                    Spacer(Modifier.width(2.dp))
                }
                Text("dBZ", color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
        }

        Column(Modifier.fillMaxWidth().background(Color(0xFF1E293B)).padding(12.dp)) {
            val last = (frames.size - 1).coerceAtLeast(1).toFloat()
            Slider(
                value = index.toFloat().coerceIn(0f, last),
                onValueChange = {
                    // Scrubbing pauses on the chosen frame; Play resumes from it.
                    playing = false
                    index = it.roundToInt().coerceIn(0, (frames.size - 1).coerceAtLeast(0))
                    elapsedMs = RadarPlayback.startOf(index)
                },
                valueRange = 0f..last,
                steps = (frames.size - 2).coerceAtLeast(0),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { playing = !playing }) { Text(if (playing) "⏸ Pause" else "▶ Play") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (frames.isNotEmpty()) {
                        playing = false
                        index = frames.size - 1
                        elapsedMs = RadarPlayback.startOf(index)
                    }
                }) { Text("Latest") }
            }
        }
    }
}

/** The radar frame scaled to the square, base map lines over it, and the pin at the site. */
@Composable
private fun RadarMapCanvas(box: RadarBox, frame: ImageBitmap?, place: String, modifier: Modifier) {
    val lines = remember(box) { BaseMap.linesIn(box.minLat, box.maxLat, box.minLon, box.maxLon) }
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    // Compose does not clip a Canvas to its bounds: without this, coastlines ran past the map square.
    Canvas(modifier.clipToBounds()) {
        drawRect(Color(0xFF1E293B))
        // Lines over the radar, as on radar.weather.gov: heavy rain must not erase the coast.
        if (frame != null) {
            drawImage(
                frame,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                dstOffset = IntOffset.Zero,
                filterQuality = FilterQuality.Low,
            )
        }
        for (line in lines) {
            val path = Path()
            for (k in line.lats.indices) {
                val x = box.x(line.lons[k], size.width)
                val y = box.y(line.lats[k], size.height)
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val (color, width) = when (line.kind) {
                BaseMap.Kind.COAST, BaseMap.Kind.LAKE -> Color(0xFFCBD5E1) to 1.2f
                BaseMap.Kind.COUNTRY -> Color(0xFF94A3B8) to 1f
                BaseMap.Kind.STATE -> Color(0x8094A3B8) to 0.7f
            }
            drawPath(path, color, style = Stroke(width = width.dp.toPx()))
        }
        val c = Offset(box.x(box.centerLon.toFloat(), size.width), box.y(box.centerLat.toFloat(), size.height))
        drawCircle(Color.White, 7.dp.toPx(), c)
        drawCircle(Color(0xFFEF4444), 5.dp.toPx(), c)
        drawText(
            textMeasurer, place, topLeft = Offset(c.x + 10.dp.toPx(), c.y - 9.dp.toPx()),
            style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
    }
}
