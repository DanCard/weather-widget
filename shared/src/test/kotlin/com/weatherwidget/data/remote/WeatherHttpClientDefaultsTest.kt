package com.weatherwidget.data.remote

import com.weatherwidget.shared.util.Log
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.concurrent.atomic.AtomicInteger

/** The one client policy Android and both desktop clients install (plans/261116-desktop-okhttp-engine.md). */
@Category(ShortDuration::class)
class WeatherHttpClientDefaultsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val warnings = mutableListOf<String>()

    @Before
    fun captureLog() {
        Log.install { priority, tag, msg, _ ->
            println("${priority.name.first()}/$tag: $msg")
            if (priority == Log.Priority.WARN) synchronized(warnings) { warnings += "$tag: $msg" }
        }
    }

    @After
    fun restoreLog() = Log.resetToDefault()

    private fun client(engine: MockEngine) = HttpClient(engine) {
        WeatherHttpClientDefaults.install(this, json, retryDelay = {})
    }

    @Test
    fun `socket timeouts are retried and logged until a success`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            if (calls.incrementAndGet() <= WeatherHttpClientDefaults.MAX_RETRIES) {
                throw SocketTimeoutException("no bytes")
            }
            respond("ok", HttpStatusCode.OK)
        }

        val body = client(engine).get("https://api.open-meteo.com/v1/forecast").bodyAsText()

        println("calls=${calls.get()} warnings=$warnings")
        assertEquals("ok", body)
        assertEquals(WeatherHttpClientDefaults.MAX_RETRIES + 1, calls.get())
        assertEquals(WeatherHttpClientDefaults.MAX_RETRIES, warnings.size)
        assertTrue(warnings.all { it.startsWith("HttpRetry: retryOnException host=api.open-meteo.com path=/v1/forecast cause=SocketTimeoutException") })
    }

    @Test
    fun `a persistent timeout gives up after MAX_RETRIES retries`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            throw SocketTimeoutException("no bytes")
        }

        try {
            client(engine).get("https://api.open-meteo.com/v1/forecast")
            fail("expected the timeout to surface")
        } catch (e: SocketTimeoutException) {
            println("surfaced ${e::class.simpleName} after calls=${calls.get()}")
        }
        assertEquals(WeatherHttpClientDefaults.MAX_RETRIES + 1, calls.get())
    }

    @Test
    fun `a client error is returned without retrying`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("bad", HttpStatusCode.BadRequest)
        }

        val response: HttpResponse = client(engine).get("https://api.open-meteo.com/v1/forecast")

        println("status=${response.status.value} calls=${calls.get()}")
        assertEquals(400, response.status.value)
        assertEquals(1, calls.get())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `retryable causes are transport failures only`() {
        assertTrue(WeatherHttpClientDefaults.isRetryable(java.io.IOException("reset")))
        assertTrue(WeatherHttpClientDefaults.isRetryable(SocketTimeoutException("x")))
        assertEquals(false, WeatherHttpClientDefaults.isRetryable(IllegalStateException("parse")))
    }

    @Test
    fun `installApiUsageInterceptor counts 200, 429 quota refusal, and thrown transport errors`() = runTest {
        val recorded = mutableListOf<String>()
        var step = 0
        val engine = MockEngine {
            when (step++) {
                0 -> respond("ok", HttpStatusCode.OK)
                1 -> respond("limited", HttpStatusCode.TooManyRequests)
                else -> throw SocketTimeoutException("stalled")
            }
        }
        val http = HttpClient(engine) {
            WeatherHttpClientDefaults.install(this, json, retryDelay = {})
        }.apply {
            WeatherHttpClientDefaults.installApiUsageInterceptor(this) { _, source, endpoint, isError, isQuotaRefusal ->
                recorded += "$source:$endpoint:err=$isError:quota=$isQuotaRefusal"
            }
        }

        http.get("https://api.open-meteo.com/v1/forecast")
        http.get("https://api.open-meteo.com/v1/forecast")
        runCatching { http.get("https://api.open-meteo.com/v1/forecast") }

        assertEquals(
            listOf(
                "OPEN_METEO:forecast:err=false:quota=false",
                "OPEN_METEO:forecast:err=true:quota=true",
            ) + List(WeatherHttpClientDefaults.MAX_RETRIES + 1) { "OPEN_METEO:forecast:err=true:quota=false" },
            recorded,
        )
    }
}
