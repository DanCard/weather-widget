package com.weatherwidget.data.repository

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
