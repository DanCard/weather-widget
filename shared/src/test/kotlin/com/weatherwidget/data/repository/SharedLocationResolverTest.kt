package com.weatherwidget.data.repository

import com.weatherwidget.data.remote.GoogleGeocodingApi
import com.weatherwidget.data.remote.IpGeolocationApi
import com.weatherwidget.data.remote.NominatimApi
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
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The resolver's `label` is what the pickers list, what a save stores, and what Settings shows
 * afterwards — so it is the short postal address, not Nominatim's county-and-country display name.
 */
@Category(ShortDuration::class)
class SharedLocationResolverTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val averyDrive = """
        {"display_name":"860, Avery Drive, Mountain View, Santa Clara County, California, 94043, United States",
         "lat":"37.4166014","lon":"-122.0888722",
         "address":{"house_number":"860","road":"Avery Drive","city":"Mountain View","state":"California",
           "ISO3166-2-lvl4":"US-CA","postcode":"94043","country":"United States","country_code":"us"}}
    """.trimIndent()

    @Test
    fun `search labels a result with its short address`() = runBlocking {
        val result = resolver("[$averyDrive]").searchText("860 Avery dr. 94043").single()

        assertEquals("860 Avery Drive, Mountain View, CA 94043", result.label)
    }

    @Test
    fun `reverse lookup of typed coordinates labels with the short address`() = runBlocking {
        val result = resolver(averyDrive).fromCoordinates(37.4166014, -122.0888722)

        assertEquals("860 Avery Drive, Mountain View, CA 94043", result.label)
    }

    @Test
    fun `a result without a structured address keeps the display name`() = runBlocking {
        val result = resolver("""[{"display_name":"Somewhere Far","lat":"1.0","lon":"2.0"}]""")
            .searchText("far").single()

        assertEquals("Somewhere Far", result.label)
    }

    @Test
    fun `multiline paste with unit number falls back to unit-stripped query`() = runBlocking {
        val scottsdale = """
            {"display_name":"7900, East Princess Drive, Scottsdale, Maricopa County, Arizona, 85255, United States",
             "lat":"33.6441650","lon":"-111.9096590",
             "address":{"house_number":"7900","road":"East Princess Drive","city":"Scottsdale","state":"Arizona",
               "ISO3166-2-lvl4":"US-AZ","postcode":"85255","country":"United States","country_code":"us"}}
        """.trimIndent()
        val seenQueries = mutableListOf<String>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val q = request.url.parameters["q"].orEmpty()
                    seenQueries.add(q)
                    val body = if (q == "7900 East Princess Drive, Scottsdale, 85255 AZ") "[$scottsdale]" else "[]"
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val resolver = SharedLocationResolver(NominatimApi(client, json), IpGeolocationApi(client, json))

        val results = resolver.searchText("7900 East Princess Drive\nScottsdale, 85255 AZ\nUnit 1182.1")

        assertEquals(
            listOf(
                "7900 East Princess Drive, Scottsdale, 85255 AZ, Unit 1182.1",
                "7900 East Princess Drive, Scottsdale, 85255 AZ",
            ),
            seenQueries,
        )
        assertEquals(1, results.size)
        assertEquals("7900 East Princess Drive, Scottsdale, AZ 85255", results.single().label)
    }

    @Test
    fun `unmapped street address falls back to city and postal code`() = runBlocking {
        val cityMatch = """
            {"display_name":"Scottsdale, Maricopa County, Arizona, United States",
             "lat":"33.5967034","lon":"-111.8910185",
             "address":{"city":"Scottsdale","state":"Arizona","ISO3166-2-lvl4":"US-AZ",
               "country":"United States","country_code":"us"}}
        """.trimIndent()
        val seenQueries = mutableListOf<String>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val q = request.url.parameters["q"].orEmpty()
                    seenQueries.add(q)
                    val body = if (q == "Scottsdale, 85255 AZ") "[$cityMatch]" else "[]"
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val resolver = SharedLocationResolver(NominatimApi(client, json), IpGeolocationApi(client, json))

        val results = resolver.searchText("99999 Unmapped Way\nScottsdale, 85255 AZ\nApt 5")

        assertEquals(
            listOf(
                "99999 Unmapped Way, Scottsdale, 85255 AZ, Apt 5",
                "99999 Unmapped Way, Scottsdale, 85255 AZ",
                "Scottsdale, 85255 AZ",
            ),
            seenQueries,
        )
        assertEquals("Scottsdale, AZ", results.single().label)
    }

    @Test
    fun `uses Google Geocoding first when configured and skips Nominatim on match`() = runBlocking {
        val hosts = mutableListOf<String>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    hosts.add(request.url.host)
                    val body =
                        """
                        {
                          "status": "OK",
                          "results": [{
                            "formatted_address": "7900 E Princess Dr, Scottsdale, AZ 85255, USA",
                            "geometry": {"location": {"lat": 33.6451828, "lng": -111.9112613}},
                            "address_components": [
                              {"long_name": "United States", "short_name": "US", "types": ["country", "political"]}
                            ]
                          }]
                        }
                        """.trimIndent()
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val resolver = SharedLocationResolver(
            nominatimApi = NominatimApi(client, json),
            ipGeolocationApi = IpGeolocationApi(client, json),
            googleGeocodingApi = GoogleGeocodingApi(client, json) { "test-key" },
        )

        val results = resolver.searchText("7900 East Princess Drive\nScottsdale, 85255 AZ\nUnit 1182.1")

        assertEquals(listOf("maps.googleapis.com"), hosts)
        assertEquals(1, results.size)
        assertEquals("7900 E Princess Dr, Scottsdale, AZ 85255", results.single().label)
        assertEquals("Google", results.single().source)
    }

    @Test
    fun `falls back to Nominatim when Google Geocoding fails or returns empty`() = runBlocking {
        val hosts = mutableListOf<String>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    hosts.add(request.url.host)
                    val body = if (request.url.host == "maps.googleapis.com") {
                        """{"status": "OVER_QUERY_LIMIT", "error_message": "Quota exceeded", "results": []}"""
                    } else {
                        "[$averyDrive]"
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val resolver = SharedLocationResolver(
            nominatimApi = NominatimApi(client, json),
            ipGeolocationApi = IpGeolocationApi(client, json),
            googleGeocodingApi = GoogleGeocodingApi(client, json) { "test-key" },
        )

        val results = resolver.searchText("860 Avery dr. 94043")

        assertEquals(listOf("maps.googleapis.com", "nominatim.openstreetmap.org"), hosts)
        assertEquals("860 Avery Drive, Mountain View, CA 94043", results.single().label)
        assertEquals("Nominatim", results.single().source)
    }

    private fun resolver(body: String): SharedLocationResolver {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        return SharedLocationResolver(NominatimApi(client, json), IpGeolocationApi(client, json))
    }
}
