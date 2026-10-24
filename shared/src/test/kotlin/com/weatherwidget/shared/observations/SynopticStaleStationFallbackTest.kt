package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.SynopticApi
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant

@Category(ShortDuration::class)
class SynopticStaleStationFallbackTest {
    private val now = Instant.parse("2026-10-23T18:00:00Z").toEpochMilli()
    private val station = NwsApi.StationInfo("AW020", "AW020", 37.42, -122.08, StationType.PERSONAL)
    private val logs = mutableListOf<String>()

    private fun primaryRow(minutesAgo: Long) = ObservationReading(
        stationId = station.id,
        stationName = station.name,
        timestamp = now - minutesAgo * 60_000L,
        temperature = 58f,
        condition = "",
        locationLat = 37.417,
        locationLon = -122.089,
        api = "NWS",
    )

    private fun primary(outcome: FetchOutcome<List<ObservationReading>>) =
        NwsStationLatestFetcher.WebSource("nws_metar_text") { _, _, _ -> outcome }

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, requests: MutableList<String> = mutableListOf()) =
        SynopticApi(
            HttpClient(MockEngine { request ->
                requests += request.url.toString()
                if (status != HttpStatusCode.OK) {
                    respondError(status)
                } else {
                    respond(
                        """{"SUMMARY":{"RESPONSE_CODE":1},"STATION":[{"STID":"AW020","NAME":"AW020","OBSERVATIONS":{"date_time":["2026-10-23T17:55:00Z"],"air_temp_set_1":[14.4]}}]}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }),
            Json { ignoreUnknownKeys = true },
            tokenProvider = { "configured-token" },
        )

    private suspend fun NwsStationLatestFetcher.WebSource.fetch(
        apiNewestMs: Long?,
        index: Int = 0,
    ) = rows(station, index, apiNewestMs)

    private fun wrapped(
        primarySource: NwsStationLatestFetcher.WebSource?,
        synopticApi: SynopticApi,
    ) = SynopticStaleStationFallback.wrap(
        primarySource, synopticApi, 37.417, -122.089, nowMs = { now },
        log = { tag, message, _ -> logs += "$tag $message" },
    )!!

    @Test
    fun `fresh NWS data never calls Synoptic`() = runBlocking {
        val requests = mutableListOf<String>()
        val original = primary(FetchOutcome.NoData)
        val result = wrapped(original, api(requests = requests)).fetch(now - 30 * 60_000L)

        assertSame(original.rows(station, 0, null), result)
        assertTrue(requests.isEmpty())
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `stale NWS and empty METAR use fresh Synoptic observations`() = runBlocking {
        val requests = mutableListOf<String>()
        val result = wrapped(primary(FetchOutcome.NoData), api(requests = requests))
            .fetch(now - 39 * 60 * 60_000L)

        val row = (result as FetchOutcome.Success).value.single()
        assertEquals(now - 5 * 60_000L, row.timestamp)
        assertEquals("NWS", row.api)
        assertTrue(row.isWebFallback)
        assertEquals(1, requests.size)
        assertTrue(requests.single().contains("STID=AW020"))
        assertTrue(logs.single().startsWith("OBS_SYNOPTIC_FALLBACK station=AW020 index=0 outcome=success"))
    }

    @Test
    fun `fresh METAR data also avoids Synoptic`() = runBlocking {
        val requests = mutableListOf<String>()
        val result = wrapped(primary(FetchOutcome.Success(listOf(primaryRow(10)))), api(requests = requests))
            .fetch(now - 39 * 60 * 60_000L)

        assertEquals(now - 10 * 60_000L, result.valueOrNull()?.single()?.timestamp)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `failed Synoptic preserves primary rows`() = runBlocking {
        val original = primary(FetchOutcome.Success(listOf(primaryRow(600))))
        val result = wrapped(original, api(status = HttpStatusCode.ServiceUnavailable)).fetch(now - 600 * 60_000L)

        assertEquals(now - 600 * 60_000L, result.valueOrNull()?.single()?.timestamp)
        assertTrue(logs.single().contains("outcome=failed:"))
    }

    @Test
    fun `newer Synoptic observations replace stale primary rows`() = runBlocking {
        val requests = mutableListOf<String>()
        val original = primary(FetchOutcome.Success(listOf(primaryRow(70))))
        val result = wrapped(original, api(requests = requests)).fetch(now - 70 * 60_000L)

        val row = (result as FetchOutcome.Success).value.single()
        assertEquals(now - 5 * 60_000L, row.timestamp)
        assertTrue(row.isWebFallback)
        assertEquals(1, requests.size)
    }

    @Test
    fun `older Synoptic observations do not replace primary rows`() = runBlocking {
        val requests = mutableListOf<String>()
        val original = primary(FetchOutcome.Success(listOf(primaryRow(70))))
        val client = HttpClient(MockEngine { request ->
            requests += request.url.toString()
            val older = Instant.ofEpochMilli(now - 90 * 60_000L).toString()
            respond(
                """{"SUMMARY":{"RESPONSE_CODE":1},"STATION":[{"STID":"AW020","NAME":"AW020","OBSERVATIONS":{"date_time":["$older"],"air_temp_set_1":[14.4]}}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val api = SynopticApi(client, Json { ignoreUnknownKeys = true }, tokenProvider = { "configured-token" })
        val result = wrapped(original, api).fetch(now - 70 * 60_000L)

        assertEquals(now - 70 * 60_000L, result.valueOrNull()?.single()?.timestamp)
        assertEquals(1, requests.size)
        assertTrue(logs.single().contains("outcome=success"))
    }

    @Test
    fun `stations beyond the fallback limit do not call Synoptic`() = runBlocking {
        val requests = mutableListOf<String>()
        wrapped(primary(FetchOutcome.NoData), api(requests = requests))
            .fetch(null, index = ObservationFallbackPolicy.MAX_WEB_FALLBACK_STATIONS)

        assertTrue(requests.isEmpty())
    }
}
