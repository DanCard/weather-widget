package com.weatherwidget.desktop

import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsMetarTextApi
import com.weatherwidget.data.remote.SynopticApi
import com.weatherwidget.test.category.MediumDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.junit.experimental.categories.Category

/**
 * The desktop NWS web leg end to end: [DesktopWeatherService] + shared NwsMetarTextWeb + the real
 * [NwsMetarTextApi] over a mock HTTP engine. Fresh METAR data prevents any third-party request;
 * a separate case covers the user-approved Synoptic fallback when NWS and METAR are stale.
 */
@Category(MediumDuration::class)
class DesktopNwsWebLegTest {

    @Test
    fun `a stale NWS API is overtaken by NWS's own METAR text, and no other web host is asked`() = runBlocking {
        val mockNwsApi = mockk<NwsApi>()
        val station = NwsApi.StationInfo("KNUQ", "Moffett Field", 37.4058, -122.0480, StationType.OFFICIAL)

        val dummyGrid = NwsApi.GridPointInfo("MTR", 80, 80, "http://dummy/forecast", "http://dummy/stations")
        coEvery { mockNwsApi.getGridPoint(any(), any()) } returns dummyGrid
        coEvery { mockNwsApi.getObservationStations(any()) } returns listOf(station)

        val oldTimestampStr = ZonedDateTime.now().minusHours(3).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val mockNwsObs = NwsApi.Observation(
            timestamp = oldTimestampStr,
            temperatureCelsius = 20.0f,
            textDescription = "Clear",
            stationName = "Moffett Field",
        )
        coEvery { mockNwsApi.getObservations(any(), any(), any()) } returns listOf(mockNwsObs)
        coEvery { mockNwsApi.getLatestObservationDetailedResult(any(), any()) } returns
            FetchOutcome.Success(mockNwsObs)

        // NWS's text file, five minutes old: header + raw report with a T-group (21.5 °C).
        val fresh = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(5)
        val header = fresh.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
        val group = fresh.format(DateTimeFormatter.ofPattern("ddHHmm"))
        val body = "$header\nKNUQ ${group}Z AUTO 00000KT 10SM CLR 22/03 A3011 RMK AO2 T02150028\n"

        val hosts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val mockEngine = MockEngine { request ->
            hosts += request.url.host
            if (request.url.host == "tgftp.nws.noaa.gov" && request.url.encodedPath.endsWith("/KNUQ.TXT")) {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val service = DesktopWeatherService(
            37.4220, -122.0841, "NWS",
            injectedHttpClient = mockHttpClient,
            injectedNwsApi = mockNwsApi,
        )

        val result = service.fetchObservationsOnly(recentOnly = false)

        assertNotNull(result.providerCurrentTemp)
        assertEquals(21.5f * 1.8f + 32f, result.providerCurrentTemp!!, 0.01f)
        assertTrue("web hosts asked: $hosts", "tgftp.nws.noaa.gov" in hosts)
        assertTrue(
            "the NWS path must not call Synoptic or aviationweather.gov: $hosts",
            hosts.none { it == "api.synopticdata.com" || it == "aviationweather.gov" },
        )
    }

    @Test
    fun `stale personal NWS station uses fresher Synoptic fallback`() = runBlocking {
        val station = NwsApi.StationInfo("AW020", "AW020", 37.43, -122.08, StationType.PERSONAL)
        val mockNwsApi = mockk<NwsApi>()
        coEvery { mockNwsApi.getGridPoint(any(), any()) } returns
            NwsApi.GridPointInfo("MTR", 80, 80, "http://dummy/forecast", "http://dummy/stations")
        coEvery { mockNwsApi.getObservationStations(any()) } returns listOf(station)
        val stale = NwsApi.Observation(
            timestamp = ZonedDateTime.now(ZoneOffset.UTC).minusHours(4).format(DateTimeFormatter.ISO_INSTANT),
            temperatureCelsius = 10f,
            textDescription = "Clear",
        )
        coEvery { mockNwsApi.getObservations(any(), any(), any()) } returns listOf(stale)
        coEvery { mockNwsApi.getLatestObservationDetailedResult(any(), any()) } returns FetchOutcome.Success(stale)

        val fresh = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(5).format(DateTimeFormatter.ISO_INSTANT)
        val hosts = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            hosts += request.url.host
            if (request.url.host == "api.synopticdata.com") {
                respond(
                    """{"SUMMARY":{"RESPONSE_CODE":1},"STATION":[{"STID":"AW020","NAME":"AW020","OBSERVATIONS":{"date_time":["$fresh"],"air_temp_set_1":[14.4]}}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        })
        val json = Json { ignoreUnknownKeys = true }
        val service = DesktopWeatherService(
            37.4220, -122.0841, "NWS",
            injectedHttpClient = client,
            injectedNwsApi = mockNwsApi,
            injectedSynopticApi = SynopticApi(client, json, tokenProvider = { "configured-token" }),
        )

        val result = service.fetchObservationsOnly(recentOnly = false)

        assertEquals(57.92f, result.providerCurrentTemp!!, 0.05f)
        assertTrue("hosts asked: $hosts", "api.synopticdata.com" in hosts)
        assertTrue("token scrape must not run with a configured token: $hosts", "www.weather.gov" !in hosts)
    }

    @Test
    fun `parseTimestamp handles timezone offsets with and without colons`() {
        val service = DesktopWeatherService(37.4220, -122.0841, "NWS")

        val tsWithColon = "2026-06-28T17:55:00-07:00"
        val epochWithColon = service.parseTimestamp(tsWithColon)

        val tsWithoutColon = "2026-06-28T17:55:00-0700"
        val epochWithoutColon = service.parseTimestamp(tsWithoutColon)

        assertEquals(epochWithColon, epochWithoutColon)

        val expectedEpoch = ZonedDateTime.parse(tsWithColon).toInstant().toEpochMilli()
        assertEquals(expectedEpoch, epochWithColon)
    }
}
