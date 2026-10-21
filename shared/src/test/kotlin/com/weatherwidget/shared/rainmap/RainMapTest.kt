package com.weatherwidget.shared.rainmap

import com.weatherwidget.data.remote.ApiUsageClassifier
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import kotlin.math.cos
import kotlin.math.roundToInt

/** Open-Meteo rain map loop — `plans/261020-open-meteo-rain-map-loop.md`. */
@Category(ShortDuration::class)
class RainMapTest {
    @get:Rule val tmp = TemporaryFolder()

    private val spec = RainGridSpec(size = 3, halfSpanLatDeg = 1.0, forecastHours = 2)
    private val lat = 37.4166
    private val lon = -122.0888

    /** A 3×3 × 2 h reply: mm = point index (0..8) in hour 0, 10× that in hour 1; chance = 5 × index. */
    private fun reply(): String =
        (0 until 9).joinToString(",", "[", "]") { i ->
            """{"latitude":0,"longitude":0,"hourly":{"time":[1800000000,1800003600],""" +
                """"precipitation":[$i.0,${i * 10}.0],"precipitation_probability":[${i * 5},${if (i == 4) "null" else (i * 5).toString()}]}}"""
        }

    private fun repo(onRequest: (String) -> Unit = {}, now: () -> Long = { 1_800_000_000_000L }) =
        RainGridRepository(
            HttpClient(MockEngine { req -> onRequest(req.url.toString()); respond(reply(), HttpStatusCode.OK) }),
            tmp.root, spec, now,
        )

    @Test
    fun `grid is centred exactly on the widget coordinate, lon span widened by cos(lat)`() {
        val big = RainGridSpec.DEFAULT
        assertEquals(lat, big.latitudes(lat)[big.size / 2], 0.0)
        assertEquals(lon, big.longitudes(lat, lon)[big.size / 2], 0.0)
        assertEquals(big.size, big.latitudes(lat).size)
        assertTrue("under Open-Meteo's 1000 locations", big.size * big.size <= 1000)
        val lonSpan = big.longitudes(lat, lon).last() - big.longitudes(lat, lon).first()
        assertEquals(2 * big.halfSpanLatDeg / cos(Math.toRadians(lat)), lonSpan, 1e-9)
    }

    @Test
    fun `one multi-location request, row-major south to north, parsed into the grid`() = runBlocking {
        var url = ""
        val grid = repo(onRequest = { url = it }).load(lat, lon).getOrThrow()
        assertTrue(url, "forecast_hours=2" in url && "timeformat=unixtime" in url && "precipitation_probability" in url)
        val sentLats = Regex("latitude=([^&]+)").find(url)!!.groupValues[1].replace("%2C", ",").split(",")
        assertEquals(9, sentLats.size)
        assertEquals("37.4166", sentLats[4]) // centre point = widget coordinate (pin matches graph)
        assertTrue(sentLats[0].toDouble() < sentLats[8].toDouble()) // south first
        assertEquals(listOf(1_800_000_000_000L, 1_800_003_600_000L), grid.times.toList())
        assertEquals(4f, grid.mm[0][4])
        assertEquals(80f, grid.mm[1][8])
        assertEquals(0f, grid.chance[1][4]) // null → 0
    }

    @Test
    fun `centre readout equals the grid's centre values, blended between hours`() = runBlocking {
        val grid = repo().load(lat, lon).getOrThrow()
        assertEquals(4f, grid.valueAt(RainLayer.AMOUNT, grid.centerRow, grid.centerCol, 0.0))
        assertEquals(22f, grid.valueAt(RainLayer.AMOUNT, grid.centerRow, grid.centerCol, 0.5), 1e-4f) // (4 + 40) / 2
        assertEquals(0.5, grid.hourPosition(1_800_001_800_000L), 1e-9)
    }

    @Test
    fun `second open within the hour is served from disk, after an hour it fetches again`() = runBlocking {
        var requests = 0
        var now = 1_800_000_000_000L
        val r = repo(onRequest = { requests++ }, now = { now })
        r.load(lat, lon).getOrThrow()
        // File mtime is the fetch time; move the clock, not the file.
        java.io.File(tmp.root, RainGridRepository.cacheName(lat, lon, spec)).setLastModified(now)
        now += 30 * 60_000
        r.load(lat, lon).getOrThrow()
        assertEquals(1, requests)
        now += 31 * 60_000
        r.load(lat, lon).getOrThrow()
        assertEquals(2, requests)
    }

    @Test
    fun `start view is wide, zoom-in is a separate cached grid of the same size`() = runBlocking {
        assertEquals(8.0, RainGridSpec.DEFAULT.halfSpanLatDeg, 0.0)
        assertEquals(RainGridSpec.WIDE.size, RainGridSpec.DETAIL.size)
        assertTrue(RainGridSpec.DETAIL.halfSpanLatDeg < RainGridSpec.WIDE.halfSpanLatDeg)
        var requests = 0
        val r = repo(onRequest = { requests++ })
        val detail = spec.copy(halfSpanLatDeg = 0.5)
        r.load(lat, lon)
        r.load(lat, lon, detail)
        r.load(lat, lon) // back to wide: still cached
        r.load(lat, lon, detail)
        assertEquals(2, requests)
    }

    @Test
    fun `latitudes stay on the globe near the poles`() {
        val lats = RainGridSpec.WIDE.latitudes(85.0)
        assertTrue(lats.all { it in -90.0..90.0 })
    }

    @Test
    fun `a grid cached at another zoom is not reused`() {
        val wide = RainGridSpec(size = 3, halfSpanLatDeg = 4.0, forecastHours = 2)
        assertTrue(RainGridRepository.cacheName(lat, lon, spec) != RainGridRepository.cacheName(lat, lon, wide))
    }

    @Test
    fun `raster is bilinear in space and linear in time on the values`() {
        val grid = RainGrid(
            lats = doubleArrayOf(0.0, 1.0), lons = doubleArrayOf(0.0, 1.0),
            times = longArrayOf(0, 3_600_000),
            mm = arrayOf(floatArrayOf(0f, 0f, 0f, 20f), floatArrayOf(20f, 20f, 20f, 20f)),
            chance = arrayOf(FloatArray(4), FloatArray(4)), fetchedAtMs = 0,
        )
        val px = RainGridRaster.render(grid, RainLayer.AMOUNT, 0.0, 3, 3)
        assertEquals(0, px[2 * 3 + 0]) // south-west corner: 0 mm → transparent
        assertEquals(RainColorScale.color(RainLayer.AMOUNT, 20f), px[0 * 3 + 2]) // north-east = index 3
        assertEquals(RainColorScale.color(RainLayer.AMOUNT, 5f), px[1 * 3 + 1]) // centre: mean of 0,0,0,20
        val halfway = RainGridRaster.render(grid, RainLayer.AMOUNT, 0.5, 3, 3)
        assertEquals(RainColorScale.color(RainLayer.AMOUNT, 10f), halfway[2 * 3 + 0]) // (0 + 20) / 2
    }

    /** 5×5 grid, one 10 mm/h cell in the middle, everything else dry. */
    private fun isolatedCell(): RainGrid {
        val mm = FloatArray(25).also { it[12] = 10f }
        return RainGrid(
            lats = DoubleArray(5) { it.toDouble() }, lons = DoubleArray(5) { it.toDouble() },
            times = longArrayOf(0), mm = arrayOf(mm), chance = arrayOf(FloatArray(25)), fetchedAtMs = 0,
        )
    }

    @Test
    fun `bicubic field passes through every grid value and never overshoots`() {
        // 9 px over 4 cells → every second pixel sits on a grid point.
        val f = RainGridRaster.field(isolatedCell(), RainLayer.AMOUNT, 0.0, 9, 9)
        assertEquals(10f, f[4 * 9 + 4], 1e-4f) // the cell itself
        assertEquals(0f, f[4 * 9 + 2], 1e-4f) // neighbouring grid point stays dry
        assertTrue("no value above the peak", f.all { it <= 10f + 1e-4f })
        assertTrue("no negative rain", f.all { it >= 0f })
        assertEquals("cells not touching the peak stay exactly dry", 0f, f[0], 0f)
    }

    @Test
    fun `an isolated cell is round, not a plus sign`() {
        // 401 px over 4 cells: 100 px per cell; centre at (200, 200).
        val n = 401
        val f = RainGridRaster.field(isolatedCell(), RainLayer.AMOUNT, 0.0, n, n)
        val r = 50 // half a cell
        val d = (r / Math.sqrt(2.0)).roundToInt()
        val axis = f[200 * n + 200 + r]
        val diagonal = f[(200 - d) * n + 200 + d]
        // Bilinear gave 0.83 here (a plus sign); Catmull-Rom is within a few percent of round.
        assertEquals(1f, diagonal / axis, 0.06f)
    }

    @Test
    fun `colour scale - dry is transparent, ramps are monotonic in alpha, legend units by locale`() {
        assertEquals(0, RainColorScale.color(RainLayer.AMOUNT, 0.05f))
        val fading = RainColorScale.color(RainLayer.AMOUNT, 0.075f) ushr 24
        assertTrue("fades in below the first stop: $fading", fading in 1 until (RainColorScale.AMOUNT_STOPS[0].argb ushr 24))
        assertEquals(".004", RainColorScale.amountLabel(0.1f, useInches = true))
        assertEquals(0, RainColorScale.color(RainLayer.CHANCE, 5f))
        val alphas = listOf(0.1f, 1f, 4f, 9f, 25f).map { RainColorScale.color(RainLayer.AMOUNT, it) ushr 24 }
        assertEquals(alphas.sorted(), alphas)
        assertEquals("2", RainColorScale.amountLabel(2f, useInches = false))
        assertEquals(".08", RainColorScale.amountLabel(2f, useInches = true))
    }

    @Test
    fun `base map loads and covers both the Bay Area and Kyiv`() {
        assertTrue(BaseMap.lines.size > 500)
        val bay = BaseMap.linesIn(36.0, 39.0, -124.0, -120.0)
        assertTrue(bay.any { it.kind == BaseMap.Kind.COAST })
        assertTrue(bay.any { it.kind == BaseMap.Kind.STATE } || bay.any { it.kind == BaseMap.Kind.LAKE })
        assertTrue(BaseMap.linesIn(49.0, 52.0, 28.0, 33.0).any { it.kind == BaseMap.Kind.COUNTRY })
    }

    @Test
    fun `grid request has its own usage row, the normal forecast does not`() {
        assertEquals(
            "forecast/grid",
            ApiUsageClassifier.classify("api.open-meteo.com", "/v1/forecast", "latitude=36.4%2C36.6&longitude=-122%2C-122")!!.endpoint,
        )
        assertEquals("forecast", ApiUsageClassifier.classify("api.open-meteo.com", "/v1/forecast", "latitude=36.4&longitude=-122")!!.endpoint)
        assertEquals("forecast", ApiUsageClassifier.classify("api.open-meteo.com", "/v1/forecast")!!.endpoint)
    }

    @Test
    fun `grid request is never retried automatically - each try costs every point again`() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; throw java.io.IOException("timeout") }) {
            install(io.ktor.client.plugins.HttpRequestRetry) {
                maxRetries = 2
                retryOnExceptionIf { _, _ -> true }
                constantDelay(1)
            }
            install(io.ktor.client.plugins.HttpTimeout)
        }
        assertTrue(RainGridRepository(client, tmp.root, spec).load(lat, lon).isFailure)
        assertEquals(1, requests)
    }

    @Test
    fun `failed request is a failure, not an empty map`() = runBlocking {
        val r = RainGridRepository(HttpClient(MockEngine { respond("busy", HttpStatusCode.TooManyRequests) }), tmp.root, spec)
        assertTrue(r.load(lat, lon).isFailure)
    }
}
