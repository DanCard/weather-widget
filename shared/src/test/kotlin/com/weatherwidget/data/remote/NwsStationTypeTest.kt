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
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The NWS station list types RAWS from its own `provider` field, so the same station no longer reads
 * PERSONAL from NWS and RAWS from Synoptic. The fixture is trimmed from the live
 * `/gridpoints/MTR/93,87/stations` response of 2026-10-04. See plans/261004-nws-raws-provider.md.
 */
@Category(ShortDuration::class)
class NwsStationTypeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/nws/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    @Test
    fun `the recorded station list types RAWS by provider and keeps the id rule for the rest`() {
        val engine = MockEngine {
            respond(
                content = fixture("gridpoint-stations-MTR-93-87.json"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/geo+json"),
            )
        }
        val stations = runBlocking {
            NwsApi(HttpClient(engine), json).getObservationStations("https://api.weather.gov/gridpoints/MTR/93,87/stations")
        }

        assertEquals(
            mapOf(
                "AW020" to NwsApi.StationType.PERSONAL, // APRSWXNET
                "KNUQ" to NwsApi.StationType.OFFICIAL, // ASOS
                "KPAO" to NwsApi.StationType.OFFICIAL, // OTHER-MTR: an airport the provider rule would miss
                "LOAC1" to NwsApi.StationType.RAWS,
                "KSJC" to NwsApi.StationType.OFFICIAL, // ASOS-HFM
                "LAHC1" to NwsApi.StationType.RAWS,
            ),
            stations.associate { it.id to it.type },
        )
    }

    @Test
    fun `provider RAWS wins over the id, and no provider keeps the id rule`() {
        assertEquals(NwsApi.StationType.RAWS, NwsApi.classifyStationType("LOAC1", "RAWS"))
        assertEquals(NwsApi.StationType.RAWS, NwsApi.classifyStationType("LOAC1", "raws"))
        assertEquals(NwsApi.StationType.PERSONAL, NwsApi.classifyStationType("LOAC1"))
        assertEquals(NwsApi.StationType.PERSONAL, NwsApi.classifyStationType("LOAC1", ""))
        assertEquals(NwsApi.StationType.OFFICIAL, NwsApi.classifyStationType("KPAO", "OTHER-MTR"))
    }

    @Test
    fun `a cached RAWS station decodes back as RAWS`() {
        val station = NwsApi.StationInfo("LOAC1", "LOS ALTOS", 37.36, -122.14, NwsApi.StationType.RAWS)
        assertEquals(station, NwsApi.decodeStationInfo(NwsApi.encodeStationInfo(station)))
    }
}
