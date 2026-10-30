package com.weatherwidget.shared.radar

import com.weatherwidget.data.remote.ApiUsageClassifier
import com.weatherwidget.shared.rainmap.RainLoopLoadError
import com.weatherwidget.shared.util.NwsCoverage
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cos

/** Radar button on the precipitation graph — `plans/261021-radar-button-on-precip-graph.md`. */
@Category(ShortDuration::class)
class RadarTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mtvLat = 37.4166
    private val mtvLon = -122.0888

    // ---- feed choice ----

    @Test
    fun `NWS mosaic inside each NWS region, RainViewer elsewhere`() {
        assertEquals(RadarFeed.Nws(NwsCoverage.Region.CONUS), RadarFeed.forLocation(mtvLat, mtvLon))
        assertEquals("conus/conus_bref_qcd", (RadarFeed.forLocation(mtvLat, mtvLon) as RadarFeed.Nws).let { "${it.workspace}/${it.layer}" })
        assertEquals("alaska", (RadarFeed.forLocation(61.2181, -149.9003) as RadarFeed.Nws).workspace) // Anchorage
        assertEquals("hawaii", (RadarFeed.forLocation(21.3069, -157.8583) as RadarFeed.Nws).workspace) // Honolulu
        assertEquals("carib", (RadarFeed.forLocation(18.4655, -66.1057) as RadarFeed.Nws).workspace) // San Juan
        assertEquals(RadarFeed.RainViewer, RadarFeed.forLocation(50.45, 30.52)) // Kyiv
        assertEquals(RadarFeed.RainViewer, RadarFeed.forLocation(52.52, 13.405)) // Berlin
    }

    // ---- box ----

    @Test
    fun `box is centred on the site, lon span widened by 1 over cos(lat)`() {
        val box = RadarBox.around(mtvLat, mtvLon)
        assertEquals(mtvLat, box.centerLat, 1e-9)
        assertEquals(mtvLon, box.centerLon, 1e-9)
        assertEquals(2 * RadarBox.HALF_SPAN_LAT_DEG, box.maxLat - box.minLat, 1e-9)
        assertEquals(2 * RadarBox.HALF_SPAN_LAT_DEG / cos(Math.toRadians(mtvLat)), box.maxLon - box.minLon, 1e-9)
        assertEquals(0f, box.x(box.minLon.toFloat(), 400f), 0.01f)
        assertEquals(0f, box.y(box.maxLat.toFloat(), 400f), 0.01f)
        assertEquals(200f, box.x(mtvLon.toFloat(), 400f), 0.05f)
    }

    // ---- frame lists ----

    private val t0 = Instant.parse("2026-10-21T16:00:00Z").toEpochMilli()

    /** A capabilities reply listing frames every 2 min (with jitter) for [minutes]. */
    private fun capabilities(minutes: Int = 130): String {
        val times = (0..minutes step 2).map { m -> Instant.ofEpochMilli(t0 + m * 60_000L + (m % 3) * 7_000L) }
        return """<WMS_Capabilities><Layer><Name>conus_bref_qcd</Name>
            <Dimension name="time" default="x" units="ISO8601" nearestValue="1">${times.joinToString(",")}</Dimension>
            </Layer></WMS_Capabilities>"""
    }

    @Test
    fun `NWS capabilities - every listed time parsed`() {
        val times = RadarFrameList.parseNwsCapabilities(capabilities(10))
        assertEquals(6, times.size)
        assertEquals(t0, times.first())
    }

    @Test
    fun `thinning keeps the newest frame and one per 10 min back over 2 h`() {
        val all = RadarFrameList.parseNwsCapabilities(capabilities())
        val kept = RadarFrameList.thin(all)
        assertEquals(all.max(), kept.last())
        assertTrue("oldest within 2 h: ${kept.size}", all.max() - kept.first() <= RadarFrameList.WINDOW_MS)
        assertEquals(13, kept.size) // newest + 12 steps of ~10 min
        kept.zipWithNext().forEach { (a, b) -> assertTrue("step ${(b - a) / 60_000.0} min", b - a in 9 * 60_000L..11 * 60_000L) }
    }

    @Test
    fun `RainViewer listing - past frames oldest first, nowcast ignored`() {
        val (host, frames) = RadarFrameList.parseRainViewer(
            """{"version":"2.0","host":"https://tilecache.rainviewer.com","radar":{
               "past":[{"time":1792600200,"path":"/v2/radar/b"},{"time":1792599600,"path":"/v2/radar/a"}],
               "nowcast":[{"time":1792610000,"path":"/v2/radar/n"}]}}""",
        )
        assertEquals("https://tilecache.rainviewer.com", host)
        assertEquals(listOf("/v2/radar/a", "/v2/radar/b"), frames.map { it.rainViewerPath })
        assertEquals(1_792_599_600_000L, frames.first().timeMs)
    }

    // ---- Mercator ----

    @Test
    fun `tiles cover the box and a tile pixel lands at its own latitude`() {
        val box = RadarBox.around(50.45, 30.52)
        val z = RadarRepository.RAINVIEWER_ZOOM
        val size = RadarRepository.RAINVIEWER_TILE_PX
        val tiles = MercatorTiles.tilesFor(box, z, size)
        assertTrue("1..4 tiles at zoom 6 (rate limit): ${tiles.size}", tiles.size in 1..4)

        // Each tile is filled with a colour naming its row of pixels: pixel (x, y) = 0xFF000000 | y.
        val images = tiles.associateWith { ArgbImage(size, size, IntArray(size * size) { i -> (0xFF000000.toInt()) or (i / size) }) }
        val out = MercatorTiles.resample(box, z, size, images, 400, 400)
        for (row in listOf(0, 137, 399)) {
            val wy = MercatorTiles.worldY(box.latAt(row, 400), z, size)
            val expectRow = (wy - Math.floor(wy / size) * size).toInt()
            assertEquals("row $row", expectRow, out.pixels[row * 400 + 200] and 0xFFFFFF)
        }
    }

    @Test
    fun `a missing tile reads as transparent, not a crash`() {
        val box = RadarBox.around(50.45, 30.52)
        val out = MercatorTiles.resample(box, 6, 512, emptyMap(), 10, 10)
        assertTrue(out.pixels.all { it == 0 })
    }

    // ---- repository ----

    private val opaque = ArgbImage(2, 2, IntArray(4) { 0xFF00A3E0.toInt() })
    private val clear = ArgbImage(2, 2, IntArray(4))

    private fun repo(
        requests: MutableList<String>,
        image: ArgbImage = opaque,
        now: () -> Long = { t0 + 3 * 60 * 60_000L },
        status: (String) -> HttpStatusCode = { HttpStatusCode.OK },
    ) = RadarRepository(
        HttpClient(
            MockEngine { req ->
                val url = req.url.toString()
                requests += url
                val body = when {
                    "GetCapabilities" in url -> capabilities()
                    "weather-maps.json" in url ->
                        """{"host":"https://tilecache.rainviewer.com","radar":{"past":[{"time":1792599600,"path":"/v2/radar/a"},{"time":1792600200,"path":"/v2/radar/b"}]}}"""
                    else -> "png"
                }
                respond(body, status(url))
            },
        ),
        tmp.root,
        { if (it.decodeToString() == "png") image else null },
        now,
    )

    @Test
    fun `NWS - capabilities then one GetMap per kept frame over the site's box`() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val loop = repo(requests).load(mtvLat, mtvLon).getOrThrow()
        assertEquals(13, loop.frames.size)
        assertTrue(loop.hasEchoes)
        val maps = requests.filter { "GetMap" in it }
        assertEquals(13, maps.size)
        assertTrue(maps.first(), "layers=conus_bref_qcd" in maps.first() && "crs=EPSG:4326" in maps.first() && "time=" in maps.first())
        assertTrue(maps.first(), "bbox=35.9166,-123.9774,38.9166,-120.2002" in maps.first())
        assertEquals(loop.frames.map { it.timeMs }, loop.frames.map { it.timeMs }.sorted())
    }

    @Test
    fun `reopening reuses the list for 2 min and every frame from disk`() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        var now = t0 + 3 * 60 * 60_000L
        val r = repo(requests, now = { now })
        r.load(mtvLat, mtvLon).getOrThrow()
        val first = requests.size
        now += 60_000
        r.load(mtvLat, mtvLon).getOrThrow()
        assertEquals("nothing new within 2 min", first, requests.size)
        now += 2 * 60_000
        r.load(mtvLat, mtvLon).getOrThrow()
        assertEquals("only the list again: frames are immutable", first + 1, requests.size)
    }

    @Test
    fun `RainViewer - zoom 6 512-px Universal Blue tiles, every tile of every frame`() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val loop = repo(requests).load(50.45, 30.52).getOrThrow()
        assertEquals(RadarFeed.RainViewer, loop.feed)
        assertEquals(2, loop.frames.size)
        val tiles = requests.filter { "tilecache" in it }
        val perFrame = MercatorTiles.tilesFor(loop.box, 6, 512).size
        assertEquals(2 * perFrame, tiles.size)
        // Frames download in parallel, so request order is not frame order: check the set.
        val tileUrl = Regex("""/v2/radar/(a|b)/512/6/\d+/\d+/2/1_1\.png$""")
        tiles.forEach { assertTrue(it, tileUrl.containsMatchIn(it)) }
        assertEquals(setOf("a", "b"), tiles.map { tileUrl.find(it)!!.groupValues[1] }.toSet())
        assertEquals(RadarRepository.SIZE_PX, loop.frames.first().image.width)
    }

    @Test
    fun `a dry sky is a loop with no echoes, not an error`() = runBlocking {
        val loop = repo(mutableListOf(), image = clear).load(mtvLat, mtvLon).getOrThrow()
        assertFalse(loop.hasEchoes)
    }

    @Test
    fun `refused frames drop out, the rest still play`() = runBlocking {
        var n = 0
        val loop = repo(mutableListOf(), status = { if ("GetMap" in it && n++ % 2 == 0) HttpStatusCode.TooManyRequests else HttpStatusCode.OK })
            .load(mtvLat, mtvLon).getOrThrow()
        assertTrue("some frames: ${loop.frames.size}", loop.frames.size in 1..12)
    }

    @Test
    fun `every frame refused - the 429 reaches the viewer's error text`() = runBlocking {
        val result = repo(mutableListOf(), status = { if ("GetMap" in it) HttpStatusCode.TooManyRequests else HttpStatusCode.OK })
            .load(mtvLat, mtvLon)
        assertTrue(result.isFailure)
        assertEquals(RainLoopLoadError.Kind.RATE_LIMITED, RainLoopLoadError.of(result.exceptionOrNull()!!, "x").kind)
    }

    @Test
    fun `list unreachable - load fails with the HTTP status`() = runBlocking {
        val result = repo(mutableListOf(), status = { HttpStatusCode.ServiceUnavailable }).load(mtvLat, mtvLon)
        assertEquals(RainLoopLoadError.Kind.HTTP, RainLoopLoadError.of(result.exceptionOrNull()!!, "x").kind)
    }

    // ---- playback ----

    @Test
    fun `playback - half a second a frame, newest held, then back to the oldest`() {
        assertEquals(0, RadarPlayback.frameAt(0, 13))
        assertEquals(1, RadarPlayback.frameAt(500, 13))
        assertEquals(12, RadarPlayback.frameAt(12 * 500L, 13))
        assertEquals(12, RadarPlayback.frameAt(12 * 500L + 1_999, 13))
        assertEquals(0, RadarPlayback.frameAt(RadarPlayback.loopMs(13), 13))
        assertEquals(5, RadarPlayback.frameAt(RadarPlayback.startOf(5), 13))
        assertEquals(0, RadarPlayback.frameAt(12_345, 1))
        assertEquals(0L, RadarPlayback.minutesAgo(t0 + 1_000, t0))
        assertEquals(25L, RadarPlayback.minutesAgo(t0, t0 + 25 * 60_000L + 59_000))
    }

    @Test
    fun `legend - RainViewer's published Universal Blue, none invented for NWS`() {
        assertTrue(RadarLegend.stops(RadarFeed.Nws(NwsCoverage.Region.CONUS)).isEmpty())
        val stops = RadarLegend.stops(RadarFeed.RainViewer)
        assertEquals(0xFF00A3E0.toInt(), stops.first { it.dbz == 20 }.argb)
        assertEquals(stops.map { it.dbz }.sorted(), stops.map { it.dbz })
    }

    // ---- usage rows ----

    @Test
    fun `radar requests count as their own usage rows, no coordinates in the endpoint`() {
        assertEquals(
            ApiUsageClassifier.Key("NWS", "radar/list"),
            ApiUsageClassifier.classify("opengeo.ncep.noaa.gov", "/geoserver/conus/conus_bref_qcd/ows", "service=WMS&request=GetCapabilities"),
        )
        assertEquals(
            ApiUsageClassifier.Key("NWS", "radar/frame"),
            ApiUsageClassifier.classify("opengeo.ncep.noaa.gov", "/geoserver/conus/conus_bref_qcd/ows", "request=GetMap&bbox=35.9,-123.9"),
        )
        assertEquals(ApiUsageClassifier.Key("RAINVIEWER", "radar/list"), ApiUsageClassifier.classify("api.rainviewer.com", "/public/weather-maps.json"))
        assertEquals(
            ApiUsageClassifier.Key("RAINVIEWER", "radar/frame"),
            ApiUsageClassifier.classify("tilecache.rainviewer.com", "/v2/radar/abc/512/6/36/21/2/1_1.png"),
        )
    }
}
