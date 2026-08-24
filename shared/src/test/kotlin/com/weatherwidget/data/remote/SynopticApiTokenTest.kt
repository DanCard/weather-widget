package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The token gate on [SynopticApi].
 *
 * Synoptic's free access is a 14-day trial, so "no token" is an ordinary steady state for this app,
 * not an error condition — the NWS web fallback simply stops and the API path carries on. What must
 * never happen is issuing a request that is certain to be rejected, or letting a blank token look
 * like a transport failure.
 */
@Category(ShortDuration::class)
class SynopticApiTokenTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun apiWith(token: String?, onRequest: () -> Unit = {}): SynopticApi {
        val engine = MockEngine {
            onRequest()
            respond(
                content = """{"SUMMARY":{"RESPONSE_CODE":1},"STATION":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return SynopticApi(HttpClient(engine), json) { token }
    }

    @Test
    fun `a blank token means not configured`() {
        assertFalse(apiWith("").isConfigured)
        assertFalse(apiWith(null).isConfigured)
        assertFalse(apiWith("   ").isConfigured)
        assertTrue(apiWith("a-real-token").isConfigured)
    }

    /** The point of the gate: no network call at all, rather than a guaranteed rejection. */
    @Test
    fun `no request is issued when the token is blank`() {
        var requests = 0
        val api = apiWith("", onRequest = { requests++ })
        val outcome = runBlocking { api.fetchSynopticObservations("KNUQ", 120) }
        assertEquals(0, requests)
        assertTrue("expected Failed, got $outcome", outcome is FetchOutcome.Failed)
        assertTrue(
            "reason should name the cause, got ${(outcome as FetchOutcome.Failed).reason}",
            outcome.reason.contains("no token configured"),
        )
    }

    @Test
    fun `a configured token does issue the request`() {
        var requests = 0
        val api = apiWith("a-real-token", onRequest = { requests++ })
        runBlocking { api.fetchSynopticObservations("KNUQ", 120) }
        assertEquals(1, requests)
    }
}
