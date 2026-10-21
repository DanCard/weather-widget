package com.weatherwidget.shared.radar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/** One radar frame to download: its valid time and, for RainViewer, the tile path prefix. */
data class RadarFrameRef(val timeMs: Long, val rainViewerPath: String? = null)

/**
 * Which frames the view plays, from each feed's listing.
 *
 * NWS publishes a frame every ~2 min for ~2 h (60 frames); playing all of them is 60 downloads
 * for motion a 10-min step already shows, so [thin] keeps one per [STEP_MS] counted back from the
 * newest. RainViewer already lists 10-min frames.
 */
object RadarFrameList {
    const val WINDOW_MS = 2 * 60 * 60 * 1000L
    const val STEP_MS = 10 * 60 * 1000L

    /** NWS frames are ~2 min apart but irregular; a frame this close to a 10-min step counts. */
    private const val STEP_SLACK_MS = 60 * 1000L

    private val TIME_DIMENSION = Regex("""<Dimension\s+name="time"[^>]*>([^<]*)</Dimension>""")

    /** The `time` dimension of a WMS 1.3.0 GetCapabilities reply, as epoch ms (any order). */
    fun parseNwsCapabilities(xml: String): List<Long> {
        val list = TIME_DIMENSION.find(xml)?.groupValues?.get(1)
            ?: throw kotlinx.serialization.SerializationException("no time dimension")
        return list.split(',').mapNotNull { s -> s.trim().takeIf { it.isNotEmpty() && '/' !in it }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } }
    }

    /** RainViewer's `radar.past` (oldest first). `nowcast` is ignored — the free API serves none. */
    fun parseRainViewer(text: String): Pair<String, List<RadarFrameRef>> {
        val root = Json.parseToJsonElement(text).jsonObject
        val host = root["host"]?.jsonPrimitive?.content ?: throw kotlinx.serialization.SerializationException("no host")
        val past = root["radar"]?.jsonObject?.get("past")?.jsonArray ?: throw kotlinx.serialization.SerializationException("no radar.past")
        val frames = past.mapNotNull { f ->
            val o = f.jsonObject
            val t = o["time"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val path = o["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
            RadarFrameRef(t * 1000, path)
        }
        return host to frames.sortedBy { it.timeMs }
    }

    /**
     * The newest frame, then one per [stepMs] back over [windowMs]; oldest first. A step takes the
     * newest frame at least `stepMs - 1 min` older than the last one kept.
     */
    fun thin(timesMs: List<Long>, windowMs: Long = WINDOW_MS, stepMs: Long = STEP_MS): List<Long> {
        val desc = timesMs.distinct().sortedDescending()
        val newest = desc.firstOrNull() ?: return emptyList()
        val kept = mutableListOf(newest)
        for (t in desc) {
            if (newest - t > windowMs) break
            if (kept.last() - t >= stepMs - STEP_SLACK_MS) kept.add(t)
        }
        return kept.reversed()
    }
}
