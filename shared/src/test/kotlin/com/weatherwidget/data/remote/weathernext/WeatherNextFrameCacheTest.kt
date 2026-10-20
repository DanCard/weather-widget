package com.weatherwidget.data.remote.weathernext

import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder

/**
 * Cached loop frames are keyed by model run: a newer run must be downloaded, not served from the
 * first run's files. The first version replayed its first-ever download forever.
 */
@Category(ShortDuration::class)
class WeatherNextFrameCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val layer = "qpf1_imerg"
    private fun frame(lead: Int, path: String = "frames/$layer/f$lead.jpg", init: String? = null) =
        WeatherNextFrame(lead = lead, valid = "2026-10-20T${lead}:00:00Z", files = mapOf(layer to path), init = init)
    private fun manifest(run: String?, init: String? = null, vararg frames: WeatherNextFrame) =
        WeatherNextManifest(run = run, init = init, frames = frames.toList())

    @Test
    fun `different runs give different names for the same lead`() {
        val f = frame(6)
        val a = WeatherNextFrameCache.fileName(WeatherNextDomain.CA, layer, manifest("2026-10-20T00Z", null, f), f)
        val b = WeatherNextFrameCache.fileName(WeatherNextDomain.CA, layer, manifest("2026-10-20T06Z", null, f), f)
        assertNotEquals(a, b)
    }

    @Test
    fun `missing run falls back to init, then frame init, then the file path - never the old shared name`() {
        assertEquals("2026-10-20T0000Z", WeatherNextFrameCache.runKey(manifest(null, "2026-10-20T00:00Z"), frame(1), layer))
        assertEquals("2026-10-20T06Z", WeatherNextFrameCache.runKey(manifest("", null), frame(1, init = "2026-10-20T06Z"), layer))
        val keyA = WeatherNextFrameCache.runKey(manifest(null), frame(1, path = "runs/a/f1.jpg"), layer)
        val keyB = WeatherNextFrameCache.runKey(manifest(null), frame(1, path = "runs/b/f1.jpg"), layer)
        assertTrue(keyA.isNotEmpty())
        assertNotEquals(keyA, keyB)
        val legacy = "ca_${layer}_001.jpg"
        assertNotEquals(legacy, WeatherNextFrameCache.fileName(WeatherNextDomain.CA, layer, manifest(null), frame(1)))
    }

    @Test
    fun `a new run downloads again and pruning removes the old run and legacy files`() = runBlocking {
        var requests = 0
        val client = WeatherNextForecastClient(
            HttpClient(MockEngine { requests++; respond(ByteArray(2000) { 7 }, HttpStatusCode.OK) }),
            Json,
        )
        val dir = tmp.newFolder("loops")
        val legacy = java.io.File(dir, "ca_${layer}_006.jpg").apply { writeBytes(ByteArray(2000)) }
        val f = frame(6)
        val run1 = manifest("run1", null, f)
        val run2 = manifest("run2", null, f)

        val first = client.cachedFrame(dir, WeatherNextDomain.CA, layer, run1, f).getOrThrow()
        client.cachedFrame(dir, WeatherNextDomain.CA, layer, run1, f).getOrThrow()
        assertEquals("same run is served from disk", 1, requests)

        val second = client.cachedFrame(dir, WeatherNextDomain.CA, layer, run2, f).getOrThrow()
        assertEquals("a new run downloads its own frame", 2, requests)
        assertNotEquals(first, second)

        val pruned = WeatherNextFrameCache.pruneOtherRuns(dir, WeatherNextDomain.CA, layer, run2)
        assertEquals(2, pruned)
        assertFalse(first.exists())
        assertFalse(legacy.exists())
        assertTrue(second.exists())
    }
}
