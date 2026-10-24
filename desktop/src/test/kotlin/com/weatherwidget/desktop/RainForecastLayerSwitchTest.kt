package com.weatherwidget.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.weatherwidget.data.remote.weathernext.WeatherNextFrame
import com.weatherwidget.data.remote.weathernext.WeatherNextManifest
import com.weatherwidget.shared.rainmap.WeatherNextLoop
import com.weatherwidget.test.category.LongDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * A layer switch while the first layer's frames are still loading. The prefetch ran in the window's
 * scope, so the switch did not stop it: the old layer kept downloading and wrote its frames into the
 * slots just cleared for the new one (plans/261024-shared-weathernext-loop-player.md).
 */
@Category(LongDuration::class)
@OptIn(ExperimentalTestApi::class)
class RainForecastLayerSwitchTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val frames = (1..4).map { lead ->
        WeatherNextFrame(lead, "2026-10-24T00:00:00Z", mapOf("qpf1_imerg" to "q/f$lead.png", "pcp6" to "p/f$lead.png"))
    }
    private val manifest = WeatherNextManifest(run = "2026-10-24T00Z", frames = frames)

    private class Store : WeatherNextLoop.GoneStore {
        override val goneAtMs: Long? = null
        override val goneStatus = 404
        override fun markGone(status: Int, nowMs: Long) {}
        override fun clear() {}
    }

    @Test
    fun `switching layer stops the old layer's prefetch`() {
        val firstFrameGate = CompletableDeferred<Unit>()
        val oldLayerRequests = AtomicInteger()
        val newLayerRequests = AtomicInteger()
        val client = HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    url.endsWith("manifest.json") -> respond(json.encodeToString(WeatherNextManifest.serializer(), manifest), HttpStatusCode.OK)
                    "/q/" in url -> {
                        // The first 1-hour frame is still downloading when the user switches layer.
                        if (oldLayerRequests.incrementAndGet() == 1) firstFrameGate.await()
                        respond(ByteArray(2000) { 7 }, HttpStatusCode.OK)
                    }
                    else -> {
                        newLayerRequests.incrementAndGet()
                        respond(ByteArray(2000) { 7 }, HttpStatusCode.OK)
                    }
                }
            },
        )

        compose.setContent {
            RainForecastContent(
                config = DesktopConfig(lat = 37.42, lon = -122.08, label = "Mountain View"),
                httpClient = client,
                onError = {},
                onUseOpenMeteo = {},
                goneStore = Store(),
                cacheDir = tmp.root,
            )
        }
        compose.waitUntil(10_000) { oldLayerRequests.get() == 1 }

        compose.onNodeWithText("🌧 6-Hour Fronts").performClick()
        compose.waitUntil(10_000) { newLayerRequests.get() == frames.size }
        firstFrameGate.complete(Unit)

        // The old prefetch, had it survived, would fetch frames 2–4 right after the gate opens.
        Thread.sleep(1_000)
        assertEquals("only the frame in flight at the switch", 1, oldLayerRequests.get())
    }
}
