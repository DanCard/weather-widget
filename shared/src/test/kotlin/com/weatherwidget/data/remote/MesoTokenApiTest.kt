package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class MesoTokenApiTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun `parse reads the browser assignment without accepting nearby text`() {
        assertEquals(token, MesoTokenApi.parse("var mesoToken = '$token';"))
        assertEquals(token, MesoTokenApi.parse("mesoToken=\"$token\""))
        assertEquals(token, MesoTokenApi.parse("mesoToken='fedcba9876543210fedcba9876543210'; mesoToken='$token';"))
        assertNull(MesoTokenApi.parse("var otherToken = '$token';"))
        assertNull(MesoTokenApi.parse("mesoToken = 'too-short';"))
    }

    @Test
    fun `successful scrape is cached`() = runBlocking {
        var requests = 0
        var now = 1_000_000L
        val api = MesoTokenApi(
            HttpClient(MockEngine {
                requests++
                respond("var mesoToken = '$token';", headers = headersOf(HttpHeaders.ContentType, "text/javascript"))
            }),
            nowMs = { now },
        )

        assertEquals(token, api.token())
        now += 30 * 60 * 1000L
        assertEquals(token, api.token())
        assertEquals(1, requests)
    }

    @Test
    fun `a shared cache outlives a closed client - the next instance fetches with its own`() = runBlocking {
        val cache = MesoTokenApi.Cache()
        fun client() = HttpClient(MockEngine {
            respond("var mesoToken = '$token';", headers = headersOf(HttpHeaders.ContentType, "text/javascript"))
        })
        val first = client()
        assertEquals(token, MesoTokenApi(first, cache = cache).token())
        first.close()

        val second = MesoTokenApi(client(), cache = cache)
        assertTrue("token shared", second.hasCachedToken)
        assertEquals("refreshed through the live client, not the closed one", token, second.token(forceRefresh = true))
    }

    @Test
    fun `failure cache is short and retries automatically`() = runBlocking {
        var requests = 0
        var now = 1_000_000L
        val api = MesoTokenApi(
            HttpClient(MockEngine {
                requests++
                respond("no token here", HttpStatusCode.OK)
            }),
            nowMs = { now },
        )

        assertNull(api.token())
        now += 60_000L
        assertNull(api.token())
        assertEquals(1, requests)
        now += 5 * 60 * 1000L
        assertNull(api.token())
        assertEquals(2, requests)
    }

    @Test
    fun `SynopticApi scrapes only when no project token is configured`() = runBlocking {
        val hosts = mutableListOf<String>()
        var synopticToken: String? = null
        val client = HttpClient(MockEngine { request ->
            hosts += request.url.host
            if (request.url.host == "www.weather.gov") {
                respond("var mesoToken = '$token';", headers = headersOf(HttpHeaders.ContentType, "text/javascript"))
            } else {
                synopticToken = request.url.parameters["token"]
                respond(
                    """{"SUMMARY":{"RESPONSE_CODE":1},"STATION":[]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        })
        val api = SynopticApi(client, Json { ignoreUnknownKeys = true }, tokenProvider = { "" }, mesoTokenApi = MesoTokenApi(client))

        assertTrue(api.isConfigured)
        api.fetchSynopticObservations("AW020", 120)

        assertEquals(listOf("www.weather.gov", "api.synopticdata.com"), hosts)
        assertEquals(token, synopticToken)
    }

    @Test
    fun `SynopticApi uses cached token on next fetch without re-scraping`() = runBlocking {
        var scrapeRequests = 0
        var synopticRequests = 0
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "www.weather.gov") {
                scrapeRequests++
                respond("var mesoToken = '$token';", headers = headersOf(HttpHeaders.ContentType, "text/javascript"))
            } else {
                synopticRequests++
                respond(
                    """{"SUMMARY":{"RESPONSE_CODE":1},"STATION":[]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        })
        val mesoApi = MesoTokenApi(client)
        val api = SynopticApi(client, Json { ignoreUnknownKeys = true }, tokenProvider = { "" }, mesoTokenApi = mesoApi)

        api.fetchSynopticObservations("AW020", 120)
        assertEquals(1, scrapeRequests)
        assertEquals(1, synopticRequests)

        // Next fetch must use the cached token and NOT re-scrape weather.gov
        api.fetchSynopticObservations("AW020", 120)
        assertEquals(1, scrapeRequests)
        assertEquals(2, synopticRequests)
    }

    @Test
    fun `SynopticApi refetches token and retries when error occurs`() = runBlocking {
        var scrapeCount = 0
        val token1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val token2 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val tokensProvided = listOf(token1, token2)
        val tokensUsed = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "www.weather.gov") {
                val current = tokensProvided[scrapeCount++]
                respond("var mesoToken = '$current';", headers = headersOf(HttpHeaders.ContentType, "text/javascript"))
            } else {
                val tokenParam = request.url.parameters["token"] ?: ""
                tokensUsed += tokenParam
                if (tokenParam == token1) {
                    // First token rejected with 403 or Invalid request per token rules
                    respond(
                        """{"SUMMARY":{"RESPONSE_CODE":2,"RESPONSE_MESSAGE":"Invalid request per token rules"}}""",
                        HttpStatusCode.Forbidden,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        """{"SUMMARY":{"RESPONSE_CODE":1},"STATION":[{"STID":"AW020","NAME":"AW020","OBSERVATIONS":{"date_time":["2026-10-23T22:00:00Z"],"air_temp_set_1":[15.0]}}]}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        })
        val api = SynopticApi(client, Json { ignoreUnknownKeys = true }, tokenProvider = { "" }, mesoTokenApi = MesoTokenApi(client))

        val result = api.fetchSynopticObservations("AW020", 120)
        assertTrue(result is FetchOutcome.Success)
        assertEquals(2, scrapeCount)
        assertEquals(listOf(token1, token2), tokensUsed)

        // Subsequent call uses the fresh token from cache
        api.fetchSynopticObservations("AW020", 120)
        assertEquals(2, scrapeCount)
        assertEquals(listOf(token1, token2, token2), tokensUsed)
    }
}
