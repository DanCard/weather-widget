package com.weatherwidget.shared.rainmap

import com.weatherwidget.data.remote.weathernext.WeatherNextAvailability
import com.weatherwidget.data.remote.weathernext.WeatherNextDomain
import com.weatherwidget.data.remote.weathernext.WeatherNextForecastClient
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The WeatherNext client / Open-Meteo grid repository and the classifier together, against a mock host. */
@Category(ShortDuration::class)
class RainLoopLoadErrorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun weatherNextError(engine: MockEngine): RainLoopLoadError = runBlocking {
        val failure = WeatherNextForecastClient(HttpClient(engine), json).fetchManifest(WeatherNextDomain.CA).exceptionOrNull()!!
        WeatherNextAvailability.loadError(failure)
    }

    private fun answering(status: HttpStatusCode, body: String = "nope") = MockEngine { respond(body, status) }

    private fun throwing(e: Throwable) = MockEngine { throw e }

    @Test
    fun `WeatherNext 404 and 410 are GONE, name the host and status, and offer no retry`() {
        for (s in listOf(HttpStatusCode.NotFound, HttpStatusCode.Gone)) {
            val e = weatherNextError(answering(s))
            assertEquals(RainLoopLoadError.Kind.GONE, e.kind)
            assertEquals(s.value, e.status)
            assertEquals("ajoros.github.io", e.host)
            assertFalse(e.retryable)
        }
    }

    @Test
    fun `other statuses, timeouts, offline and bad JSON each get their own kind and keep Retry`() {
        val cases = listOf(
            answering(HttpStatusCode.ServiceUnavailable) to RainLoopLoadError.Kind.HTTP,
            answering(HttpStatusCode.TooManyRequests) to RainLoopLoadError.Kind.RATE_LIMITED,
            answering(HttpStatusCode.OK, "<html>not json</html>") to RainLoopLoadError.Kind.BAD_DATA,
            throwing(SocketTimeoutException("read timed out")) to RainLoopLoadError.Kind.TIMEOUT,
            throwing(UnknownHostException("ajoros.github.io")) to RainLoopLoadError.Kind.OFFLINE,
            throwing(ConnectException("refused")) to RainLoopLoadError.Kind.OFFLINE,
        )
        for ((engine, kind) in cases) {
            val e = weatherNextError(engine)
            assertEquals(kind, e.kind)
            assertTrue("$kind is retryable", e.retryable)
        }
        assertEquals(503, weatherNextError(answering(HttpStatusCode.ServiceUnavailable)).status)
    }

    @Test
    fun `a frame error page is a failure, not a frame on disk`() = runBlocking {
        val client = WeatherNextForecastClient(HttpClient(answering(HttpStatusCode.NotFound, "x".repeat(5000))), json)
        val file = tmp.root.resolve("frame.png")
        val result = client.downloadFrame(WeatherNextDomain.CA, "qpf/f001.png", file)
        assertTrue(result.isFailure)
        assertFalse("GitHub's 404 page must not be cached as a frame", file.exists())
    }

    @Test
    fun `Open-Meteo grid - a 404 is a plain HTTP error, never GONE, and names api open-meteo com`() = runBlocking {
        val repo = RainGridRepository(HttpClient(answering(HttpStatusCode.NotFound)), tmp.root)
        val e = RainLoopLoadError.of(repo.load(37.4, -122.1).exceptionOrNull()!!, RainGridRepository.HOST)
        assertEquals(RainLoopLoadError.Kind.HTTP, e.kind)
        assertEquals(404, e.status)
        assertEquals("api.open-meteo.com", e.host)
        assertTrue(e.retryable)
    }

    @Test
    fun `no frames carries the count`() {
        val e = RainLoopLoadError.noFrames(WeatherNextAvailability.HOST, 100)
        assertEquals(100, e.frameCount)
        assertTrue(e.logFields(), e.logFields().contains("kind=NO_FRAMES") && e.logFields().contains("frames=100"))
    }
}
