package com.weatherwidget.shared.rainmap

import com.weatherwidget.data.remote.weathernext.WeatherNextAvailability
import com.weatherwidget.data.remote.weathernext.WeatherNextDomain
import com.weatherwidget.data.remote.weathernext.WeatherNextForecastClient
import com.weatherwidget.data.remote.weathernext.WeatherNextFrame
import com.weatherwidget.data.remote.weathernext.WeatherNextManifest
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder

/** The WeatherNext loop viewer's shared rules (plans/261024-shared-weathernext-loop-player.md). */
@Category(ShortDuration::class)
class WeatherNextLoopTest {
    @get:Rule val tmp = TemporaryFolder()

    private val layer = "qpf1_imerg"
    private val domain = WeatherNextDomain.CA
    private val json = Json { ignoreUnknownKeys = true }

    private fun frame(lead: Int, layerId: String = layer) =
        WeatherNextFrame(lead = lead, valid = "2026-10-24T00:00:00Z", files = mapOf(layerId to "frames/$layerId/f$lead.jpg"))

    private val manifest = WeatherNextManifest(run = "2026-10-24T00Z", frames = (1..4).map { frame(it) })

    private class Store(override var goneAtMs: Long? = null, override var goneStatus: Int = 404) : WeatherNextLoop.GoneStore {
        override fun markGone(status: Int, nowMs: Long) { goneAtMs = nowMs; goneStatus = status }
        override fun clear() { goneAtMs = null }
    }

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, onRequest: (String) -> Unit = {}): WeatherNextForecastClient =
        WeatherNextForecastClient(
            HttpClient(
                MockEngine { request ->
                    val url = request.url.toString()
                    onRequest(url)
                    when {
                        status != HttpStatusCode.OK -> respond("", status)
                        url.endsWith("manifest.json") -> respond(json.encodeToString(WeatherNextManifest.serializer(), manifest), HttpStatusCode.OK)
                        else -> respond(ByteArray(2000) { 7 }, HttpStatusCode.OK)
                    }
                },
            ),
            json,
        )

    @Test
    fun `advance moves by speed and wraps past the last frame`() {
        // 35 ms per tick, 140 ms per frame at 1x: a quarter frame.
        assertEquals(1.25, WeatherNextLoop.advance(1.0, 4, 1.0f), 1e-9)
        assertEquals(1.625, WeatherNextLoop.advance(1.0, 4, 2.5f), 1e-6)
        assertEquals(0.0, WeatherNextLoop.advance(2.9, 4, 1.0f), 0.0)
        assertEquals(0.0, WeatherNextLoop.advance(5.0, 0, 1.0f), 0.0)
    }

    @Test
    fun `step moves one whole frame and holds at the ends`() {
        assertEquals(2.0, WeatherNextLoop.step(1.6, 4, +1), 0.0)
        assertEquals(0.0, WeatherNextLoop.step(1.6, 4, -1), 0.0)
        assertEquals(3.0, WeatherNextLoop.step(3.0, 4, +1), 0.0)
        assertEquals(0.0, WeatherNextLoop.step(0.0, 4, -1), 0.0)
    }

    @Test
    fun `framesFor keeps only frames carrying the layer`() {
        val m = WeatherNextManifest(frames = listOf(frame(1), frame(2, "pcp6"), frame(3)))
        assertEquals(listOf(1, 3), WeatherNextLoop.framesFor(m, layer).map { it.lead })
    }

    @Test
    fun `a host known gone answers without a request`() = runBlocking {
        var requests = 0
        val store = Store(goneAtMs = 1_000L, goneStatus = 410)
        val outcome = WeatherNextLoop.loadManifest(client { requests++ }, domain, store, nowMs = 2_000L)
        assertEquals(0, requests)
        assertEquals(RainLoopLoadError.gone(WeatherNextAvailability.HOST, 410), (outcome as WeatherNextLoop.ManifestOutcome.Failed).error)
    }

    @Test
    fun `a 404 marks the host gone and a success clears it`() = runBlocking {
        val store = Store()
        val gone = WeatherNextLoop.loadManifest(client(HttpStatusCode.NotFound), domain, store, nowMs = 5_000L)
        assertEquals(RainLoopLoadError.Kind.GONE, (gone as WeatherNextLoop.ManifestOutcome.Failed).error.kind)
        assertEquals(5_000L, store.goneAtMs)
        assertEquals(404, store.goneStatus)

        // A day later the next open asks again, and an answer ends the "gone" day.
        val back = WeatherNextLoop.loadManifest(client(), domain, store, nowMs = 5_000L + WeatherNextAvailability.GONE_HIDE_MS)
        assertEquals(manifest, (back as WeatherNextLoop.ManifestOutcome.Loaded).manifest)
        assertNull(store.goneAtMs)
    }

    @Test
    fun `prefetch hands every decoded frame over in order`() = runBlocking {
        val got = mutableListOf<Int>()
        val error = WeatherNextLoop.prefetch(
            client(), tmp.root, domain, layer, manifest, manifest.frames,
            stillWanted = { true }, decode = { it.length() }, onFrame = { idx, _ -> got += idx },
        )
        assertNull(error)
        assertEquals(listOf(0, 1, 2, 3), got)
    }

    @Test
    fun `prefetch with nothing decodable is noFrames`() = runBlocking {
        val error = WeatherNextLoop.prefetch<Long>(
            client(), tmp.root, domain, layer, manifest, manifest.frames,
            stillWanted = { true }, decode = { error("not an image") }, onFrame = { _, _ -> },
        )
        assertEquals(RainLoopLoadError.noFrames(WeatherNextAvailability.HOST, 4), error)
    }

    @Test
    fun `a layer switch mid-run stops it and discards the decode in flight`() = runBlocking {
        var wanted = true
        var requests = 0
        val got = mutableListOf<Int>()
        val discarded = mutableListOf<Long>()
        val error = WeatherNextLoop.prefetch(
            client { requests++ }, tmp.root, domain, layer, manifest, manifest.frames,
            stillWanted = { wanted },
            // The switch lands while frame 1 decodes.
            decode = { file -> file.length().also { if (got.size == 1) wanted = false } },
            onFrame = { idx, _ -> got += idx },
            onDiscard = { discarded += it },
        )
        assertNull(error)
        assertEquals(listOf(0), got)
        assertEquals(1, discarded.size)
        assertEquals("frames 2 and 3 are never requested", 2, requests)
    }

    @Test
    fun `log lines`() {
        val error = RainLoopLoadError.gone(WeatherNextAvailability.HOST, 410)
        assertTrue(WeatherNextLoop.errorLine(error, domain).endsWith(" domain=${domain.id}"))
        assertEquals("from=weathernext kind=GONE to=open_meteo", WeatherNextLoop.fallbackLine(error))
    }
}
