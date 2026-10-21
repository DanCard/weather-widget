package com.weatherwidget.shared.radar

import com.weatherwidget.shared.rainmap.RainLoopHttpException
import com.weatherwidget.shared.rainmap.RainLoopLoadError
import com.weatherwidget.shared.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.readBytes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

private const val TAG = "Radar"

/** One frame ready to draw: [image] spans the loop's [RadarBox] exactly. */
class RadarFrame(val timeMs: Long, val image: ArgbImage)

class RadarLoop(
    val feed: RadarFeed,
    val box: RadarBox,
    /** Oldest first. */
    val frames: List<RadarFrame>,
    val fetchedAtMs: Long,
) {
    /**
     * Any echo worth drawing in any frame. RainViewer paints sub-10 dBZ noise at low alpha, so a
     * pixel counts from half opacity (10 dBZ is 0x96 in Universal Blue).
     */
    val hasEchoes: Boolean by lazy { frames.any { f -> f.image.pixels.any { (it ushr 24) >= 0x80 } } }
}

/**
 * The platform's PNG decoder — the only part of the radar load that differs (Android
 * `BitmapFactory`, desktop `ImageIO`). Null = unreadable bytes.
 */
fun interface RadarImageDecoder {
    fun decode(bytes: ByteArray): ArgbImage?
}

/**
 * Loads the radar loop around a site: the feed's frame list, then each frame (NWS: one WMS
 * `GetMap` per frame; RainViewer: the 1–4 zoom-6 512-px tiles under the box per frame, resampled).
 * On demand only — the viewer is the one caller; nothing fetches radar in the background.
 *
 * Frames are immutable once published, so every downloaded image is kept on disk under its URL and
 * reopening the view fetches only frames that are new; the list itself is reused for
 * [LIST_MAX_AGE_MS]. Files older than [CACHE_MAX_AGE_MS] are dropped on each load.
 *
 * RainViewer allows 100 requests per IP per minute (2026 terms), which a 13-frame × 4-tile loop
 * fits only with the cache and no retries; a refused frame is skipped, not retried.
 */
class RadarRepository(
    private val httpClient: HttpClient,
    private val cacheDir: File,
    private val decoder: RadarImageDecoder,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun load(lat: Double, lon: Double): Result<RadarLoop> = runCatching {
        val started = nowMs()
        val feed = RadarFeed.forLocation(lat, lon)
        val box = RadarBox.around(lat, lon)
        pruneCache(started)
        val stats = Stats()
        val frames = when (feed) {
            is RadarFeed.Nws -> loadNws(feed, box, stats)
            RadarFeed.RainViewer -> loadRainViewer(box, stats)
        }
        Log.i(
            TAG,
            "RADAR_FETCH feed=${feed.logName} frames=${frames.size} downloaded=${stats.downloaded} cached=${stats.cached} " +
                "failed=${stats.failed} ms=${nowMs() - started}",
        )
        if (frames.isEmpty()) throw stats.firstError ?: NoRadarFramesException(stats.asked)
        RadarLoop(feed, box, frames, started)
    }.onFailure {
        Log.w(TAG, "RADAR_FETCH outcome=failed error=${it::class.simpleName}: ${it.message?.substringBefore(" [url=")?.take(160)}")
    }

    private class Stats {
        var asked = 0
        var downloaded = 0
        var cached = 0
        var failed = 0
        var firstError: Throwable? = null
    }

    private suspend fun loadNws(feed: RadarFeed.Nws, box: RadarBox, stats: Stats): List<RadarFrame> {
        val caps = listText(
            "${feed.owsUrl}?service=WMS&version=1.3.0&request=GetCapabilities",
            "nws_${feed.workspace}_capabilities.xml",
        )
        val times = RadarFrameList.thin(RadarFrameList.parseNwsCapabilities(caps))
        stats.asked = times.size
        val bbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", box.minLat, box.minLon, box.maxLat, box.maxLon)
        return forEachFrame(times) { t ->
            val url = "${feed.owsUrl}?service=WMS&version=1.3.0&request=GetMap&layers=${feed.layer}&styles=" +
                "&crs=EPSG:4326&bbox=$bbox&width=$SIZE_PX&height=$SIZE_PX&format=image/png&transparent=true" +
                "&time=${Instant.ofEpochMilli(t)}"
            val img = image(url, stats) ?: return@forEachFrame null
            RadarFrame(t, img)
        }
    }

    private suspend fun loadRainViewer(box: RadarBox, stats: Stats): List<RadarFrame> {
        val (host, refs) = RadarFrameList.parseRainViewer(listText(RadarFeed.RAINVIEWER_LIST_URL, "rainviewer_maps.json"))
        stats.asked = refs.size
        val tiles = MercatorTiles.tilesFor(box, RAINVIEWER_ZOOM, RAINVIEWER_TILE_PX)
        return forEachFrame(refs.map { it.timeMs }) { t ->
            val path = refs.first { it.timeMs == t }.rainViewerPath
            val images = tiles.mapNotNull { tile ->
                val url = "$host$path/$RAINVIEWER_TILE_PX/$RAINVIEWER_ZOOM/${tile.x}/${tile.y}/$RAINVIEWER_COLOR/$RAINVIEWER_OPTIONS.png"
                image(url, stats)?.let { tile to it }
            }.toMap()
            // A frame missing a tile would show a hole that looks like clear sky.
            if (images.size < tiles.size) return@forEachFrame null
            RadarFrame(t, MercatorTiles.resample(box, RAINVIEWER_ZOOM, RAINVIEWER_TILE_PX, images, SIZE_PX, SIZE_PX))
        }
    }

    /** Runs [load] for each time, [PARALLEL] at a time; failed frames drop out, order is kept. */
    private suspend fun forEachFrame(times: List<Long>, load: suspend (Long) -> RadarFrame?): List<RadarFrame> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        times.map { t -> async { gate.withPermit { load(t) } } }.awaitAll().filterNotNull()
    }

    private suspend fun image(url: String, stats: Stats): ArgbImage? {
        val file = File(cacheDir, CACHE_PREFIX + sha1(url) + ".png")
        val bytes = if (file.exists()) {
            synchronized(stats) { stats.cached++ }
            file.readBytes()
        } else {
            try {
                get(url).also {
                    cacheDir.mkdirs()
                    file.writeBytes(it)
                    synchronized(stats) { stats.downloaded++ }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                synchronized(stats) {
                    stats.failed++
                    if (stats.firstError == null) stats.firstError = e
                }
                return null
            }
        }
        return decoder.decode(bytes) ?: run {
            file.delete()
            synchronized(stats) {
                stats.failed++
                if (stats.firstError == null) stats.firstError = kotlinx.serialization.SerializationException("undecodable image")
            }
            null
        }
    }

    /** The feed's frame list, reused for [LIST_MAX_AGE_MS]. */
    private suspend fun listText(url: String, name: String): String {
        val file = File(cacheDir, CACHE_PREFIX + name)
        if (file.exists() && nowMs() - file.lastModified() in 0 until LIST_MAX_AGE_MS) return file.readText()
        val text = get(url).decodeToString()
        cacheDir.mkdirs()
        file.writeText(text)
        file.setLastModified(nowMs())
        return text
    }

    private suspend fun get(url: String): ByteArray {
        val response = httpClient.get(url) {
            if (httpClient.pluginOrNull(HttpTimeout) != null) {
                timeout {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MS
                }
            }
            retry { noRetry() }
        }
        val status = response.status.value
        if (status !in 200..299) throw RainLoopHttpException(status, "radar")
        return response.readBytes()
    }

    private fun pruneCache(now: Long) {
        cacheDir.listFiles { f -> f.name.startsWith(CACHE_PREFIX) && now - f.lastModified() > CACHE_MAX_AGE_MS }?.forEach { it.delete() }
    }

    companion object {
        /** Each frame's pixels (square); 13 frames ≈ 8 MB of ARGB. */
        const val SIZE_PX = 400
        const val PARALLEL = 4
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val LIST_MAX_AGE_MS = 2 * 60 * 1000L
        const val CACHE_MAX_AGE_MS = 3 * 60 * 60 * 1000L

        /** Zoom 6 at 512 px = zoom 7's detail (the free maximum) in a quarter of the requests. */
        const val RAINVIEWER_ZOOM = 6
        const val RAINVIEWER_TILE_PX = 512

        /** Universal Blue, the only scheme the free API still serves. */
        const val RAINVIEWER_COLOR = 2

        /** Smoothed, snow in its own colours. */
        const val RAINVIEWER_OPTIONS = "1_1"
        private const val CACHE_PREFIX = "radar_"

        /** For the viewer's error text. */
        fun host(feed: RadarFeed): String = when (feed) {
            is RadarFeed.Nws -> RainLoopLoadError.hostOf(RadarFeed.NWS_HOST)
            RadarFeed.RainViewer -> "rainviewer.com"
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Every frame failed without an exception to blame (none listed). */
class NoRadarFramesException(val asked: Int) : java.io.IOException("no radar frames ($asked listed)")
