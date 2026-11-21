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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class GoogleGeocodingApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `search parses US rooftop match and strips trailing USA`() = runBlocking {
        var sentAddress: String? = null
        var sentKey: String? = null
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    sentAddress = request.url.parameters["address"]
                    sentKey = request.url.parameters["key"]
                    respond(
                        content = """
                            {
                              "status": "OK",
                              "results": [{
                                "formatted_address": "7900 E Princess Dr, Scottsdale, AZ 85255, USA",
                                "geometry": {"location": {"lat": 33.6451828, "lng": -111.9112613}},
                                "address_components": [
                                  {"long_name": "Scottsdale", "short_name": "Scottsdale", "types": ["locality", "political"]},
                                  {"long_name": "Arizona", "short_name": "AZ", "types": ["administrative_area_level_1", "political"]},
                                  {"long_name": "United States", "short_name": "US", "types": ["country", "political"]}
                                ]
                              }]
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }

        val api = GoogleGeocodingApi(client, json) { "test-key" }
        assertTrue(api.isConfigured())
        val results = api.search("7900 East Princess Drive, Scottsdale, 85255 AZ, Unit 1182.1")

        assertEquals("7900 East Princess Drive, Scottsdale, 85255 AZ, Unit 1182.1", sentAddress)
        assertEquals("test-key", sentKey)
        assertEquals(1, results.size)
        assertEquals("7900 E Princess Dr, Scottsdale, AZ 85255", results.single().shortAddress)
        assertEquals("Scottsdale, Arizona", results.single().shortName)
        assertEquals(33.6451828, results.single().lat, 0.000001)
        assertEquals(-111.9112613, results.single().lon, 0.000001)
    }

    @Test
    fun `search keeps country on non-US addresses`() = runBlocking {
        val client = mockClient(
            """
            {
              "status": "OK",
              "results": [{
                "formatted_address": "Kyiv, Ukraine, 02000",
                "geometry": {"location": {"lat": 50.4501, "lng": 30.5234}},
                "address_components": [
                  {"long_name": "Kyiv", "short_name": "Kyiv", "types": ["locality", "political"]},
                  {"long_name": "Ukraine", "short_name": "UA", "types": ["country", "political"]}
                ]
              }]
            }
            """.trimIndent(),
        )

        val result = GoogleGeocodingApi(client, json) { "test-key" }.search("Kyiv").single()
        assertEquals("Kyiv, Ukraine, 02000", result.shortAddress)
    }

    @Test
    fun `ZERO_RESULTS returns empty list`() = runBlocking {
        val client = mockClient("""{"status": "ZERO_RESULTS", "results": []}""")
        val results = GoogleGeocodingApi(client, json) { "test-key" }.search("nowhere-xyz")
        assertTrue(results.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `OVER_QUERY_LIMIT throws so resolver can fall back`() = runBlocking {
        val client = mockClient("""{"status": "OVER_QUERY_LIMIT", "error_message": "Quota exceeded", "results": []}""")
        GoogleGeocodingApi(client, json) { "test-key" }.search("Phoenix")
        Unit
    }

    @Test
    fun `blank key skips HTTP call`() = runBlocking {
        var called = false
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    called = true
                    respond("{}", HttpStatusCode.OK)
                }
            }
        }
        val api = GoogleGeocodingApi(client, json) { "" }
        assertFalse(api.isConfigured())
        assertTrue(api.search("Phoenix").isEmpty())
        assertFalse(called)
    }

    private fun mockClient(body: String): HttpClient =
        HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
}
