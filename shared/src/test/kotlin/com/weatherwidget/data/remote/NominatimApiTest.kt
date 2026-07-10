package com.weatherwidget.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class NominatimApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `search maps json results`() = runBlocking {
        val client = mockClient(
            """
            [
              {"display_name":"Boulder, Colorado, United States","lat":"40.01499","lon":"-105.27055"}
            ]
            """.trimIndent(),
        )

        val results = NominatimApi(client, json).search("80301")

        assertEquals(1, results.size)
        assertEquals("Boulder, Colorado, United States", results[0].displayName)
        assertEquals(40.01499, results[0].lat, 0.000001)
        assertEquals(-105.27055, results[0].lon, 0.000001)
    }

    @Test
    fun `reverse maps json result`() = runBlocking {
        val client = mockClient(
            """{"display_name":"Googleplex","lat":"37.422","lon":"-122.0841"}""",
        )

        val result = NominatimApi(client, json).reverse(37.422, -122.0841)

        assertEquals("Googleplex", result?.displayName)
        assertEquals(37.422, result?.lat ?: 0.0, 0.000001)
        assertEquals(-122.0841, result?.lon ?: 0.0, 0.000001)
    }

    @Test
    fun `reverse composes short name from structured address`() = runBlocking {
        val client = mockClient(
            """
            {
              "display_name":"Google Building 40, 1600 Amphitheatre Parkway, Mountain View, Santa Clara County, California, 94043, United States",
              "lat":"37.422","lon":"-122.0841",
              "address":{"city":"Mountain View","county":"Santa Clara County","state":"California","country":"United States"}
            }
            """.trimIndent(),
        )

        val result = NominatimApi(client, json).reverse(37.422, -122.0841)

        assertEquals("Mountain View, California", result?.shortName)
        assertEquals("Mountain View, California", result?.compactName())
    }

    @Test
    fun `reverse falls back through settlement granularities`() = runBlocking {
        val client = mockClient(
            """
            {
              "display_name":"Somewhere, Rural County, Kansas, United States",
              "lat":"38.5","lon":"-98.5",
              "address":{"village":"Somewhere","county":"Rural County","state":"Kansas","country":"United States"}
            }
            """.trimIndent(),
        )

        assertEquals("Somewhere, Kansas", NominatimApi(client, json).reverse(38.5, -98.5)?.shortName)
    }

    @Test
    fun `compactName without address falls back to leading display name components`() {
        val result = GeocodeResult(
            displayName = "Boulder, Colorado, United States",
            lat = 40.01499,
            lon = -105.27055,
        )
        assertEquals("Boulder, Colorado", result.compactName())
    }

    private fun mockClient(body: String): HttpClient =
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    assertEquals("WeatherWidget/1.0 (contact@weatherwidget.app)", request.headers["User-Agent"])
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            install(ContentNegotiation) { json(json) }
        }
}
