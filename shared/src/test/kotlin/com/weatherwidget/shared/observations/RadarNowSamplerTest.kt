package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.WeatherSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import com.weatherwidget.test.category.ShortDuration
import java.util.Collections

/** The header's radar input (plans/261115-header-rain-now-dots.md): one GetFeatureInfo per run. */
@Category(ShortDuration::class)
class RadarNowSamplerTest {
    private val lat = 37.417
    private val lon = -122.089
    private val now = 1_794_804_000_000L

    /** Measured 2026-11-15 21:12 at the site while KNUQ reported -RA. */
    private val greenBody =
        """{"type":"FeatureCollection","features":[{"type":"Feature","id":"","geometry":null,""" +
            """"properties":{"RED_BAND":14,"GREEN_BAND":214,"BLUE_BAND":20,"ALPHA_BAND":255}}],"totalFeatures":"unknown","numberReturned":1}"""

    private class MemoryStore : RadarNowSampler.Store {
        var sample: RadarNowSample? = null
        override fun read() = sample
        override fun write(sample: RadarNowSample) { this.sample = sample }
    }

    private fun client(requests: MutableList<Url>, status: HttpStatusCode = HttpStatusCode.OK, body: String = greenBody) =
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += request.url
                    if (status != HttpStatusCode.OK) respondError(status)
                    else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }

    @Test
    fun `samples the NWS mosaic pixel at the site and stores it`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Url>())
        val store = MemoryStore()
        val sample = RadarNowSampler(client(requests), store) { now }.sample(lat, lon, "test")

        assertEquals(1, requests.size)
        val url = requests.single()
        println("RADAR_NOW request $url")
        assertEquals("/geoserver/conus/conus_bref_qcd/ows", url.encodedPath)
        assertEquals("GetFeatureInfo", url.parameters["request"])
        assertEquals("conus_bref_qcd", url.parameters["query_layers"])
        assertEquals("EPSG:4326", url.parameters["crs"])
        assertEquals("1", url.parameters["i"])
        assertEquals("application/json", url.parameters["info_format"])
        // WMS 1.3.0 EPSG:4326 is lat,lon order: the box straddles the site.
        val box = url.parameters["bbox"]!!.split(",").map(String::toDouble)
        assertTrue(box[0] < lat && lat < box[2] && box[1] < lon && lon < box[3])

        assertEquals(RadarNowSample(lat, lon, now, 14, 214, 20, 255), sample)
        assertEquals(sample, store.sample)
        assertEquals(RainNow.Level.RAINING, sample!!.level())
    }

    @Test
    fun `outside NWS coverage nothing is requested`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Url>())
        val sample = RadarNowSampler(client(requests), MemoryStore()) { now }.sample(51.5, -0.12, "test")
        assertNull(sample)
        assertEquals(0, requests.size)
    }

    @Test
    fun `a failure keeps the previous sample`() = runBlocking {
        val store = MemoryStore()
        val previous = RadarNowSample(lat, lon, now - 600_000, 86, 149, 193, 255)
        store.sample = previous
        val sampler = RadarNowSampler(client(mutableListOf(), HttpStatusCode.ServiceUnavailable), store) { now }
        assertNull(sampler.sample(lat, lon, "test"))
        assertEquals(previous, store.sample)
        assertEquals(previous, sampler.current(lat, lon))
    }

    @Test
    fun `an unreadable answer is a failure, not dry`() = runBlocking {
        val store = MemoryStore()
        val sampler = RadarNowSampler(client(mutableListOf(), body = """{"type":"FeatureCollection","features":[]}"""), store) { now }
        assertNull(sampler.sample(lat, lon, "test"))
        assertNull(store.sample)
    }

    @Test
    fun `a sample from another site is not this site's`() {
        val store = MemoryStore()
        store.sample = RadarNowSample(lat, lon, now, 14, 214, 20, 255)
        val sampler = RadarNowSampler(client(mutableListOf()), store) { now }
        assertEquals(store.sample, sampler.current(lat, lon))
        assertNull(sampler.current(37.77, -122.42))
    }

    @Test
    fun `store encoding round-trips`() {
        val s = RadarNowSample(lat, lon, now, 86, 149, 193, 255)
        assertEquals(s, RadarNowSample.decode(s.encode()))
        assertNull(RadarNowSample.decode("garbage"))
        assertNull(RadarNowSample.decode(null))
    }

    @Test
    fun `a current-conditions run stores one sample and bumps the version when the level changes`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Url>())
        val store = MemoryStore()
        val version = com.weatherwidget.shared.util.StoredDataVersion()
        val logs = mutableListOf<String>()
        val host = object : CurrentConditionsRun.Host {
            override fun visibleSources() = listOf(WeatherSource.NWS)
            override fun displayedSourceIds() = setOf(WeatherSource.NWS.id)
            override fun lastRunMs(latitude: Double, longitude: Double) = 0L
            override fun setLastRunMs(latitude: Double, longitude: Double, ms: Long) = Unit
            override fun lastAttemptMs(feedId: String) = 0L
            override fun markAttempt(feedId: String, ms: Long) = Unit
            override suspend fun fetch(feed: WeatherSource, latitude: Double, longitude: Double): CurrentConditionsRun.Reading? = null
            override fun recordSuccess(feed: WeatherSource) = Unit
            override fun recordFailure(feed: WeatherSource, error: Exception) = Unit
            override suspend fun log(tag: String, message: String, level: String) { logs += "$tag $message" }
        }
        val run = CurrentConditionsRun(host, RadarNowSampler(client(requests), store) { now }, version) { now }
        run.run(lat, lon, source = null, reason = "test", force = true)

        assertEquals(1, requests.size)
        assertEquals(RainNow.Level.RAINING, store.sample?.level())
        assertEquals(1L, version.current())
        assertTrue(logs.toString(), logs.any { it.startsWith("RADAR_NOW ") && "level=RAINING was=none" in it })

        // Same level again: stored, no repaint bump.
        run.run(lat, lon, source = null, reason = "test", force = true)
        assertEquals(2, requests.size)
        assertEquals(1L, version.current())
    }
}
