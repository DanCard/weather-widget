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

class IpGeolocationApiTest {
    @Test
    fun `locate maps ipapi response`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = """
                            {
                              "latitude": 37.77,
                              "longitude": -122.42,
                              "city": "San Francisco",
                              "region": "California",
                              "country_name": "United States"
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            install(ContentNegotiation) { json(json) }
        }

        val location = IpGeolocationApi(client, json).locate()

        assertEquals(37.77, location?.lat ?: 0.0, 0.000001)
        assertEquals(-122.42, location?.lon ?: 0.0, 0.000001)
        assertEquals("San Francisco", location?.city)
        assertEquals("California", location?.region)
        assertEquals("United States", location?.country)
    }
}
