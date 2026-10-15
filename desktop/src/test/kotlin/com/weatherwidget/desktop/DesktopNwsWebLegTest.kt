package com.weatherwidget.desktop

import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsMetarTextApi
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
import kotlinx.coroutines.test.runTest
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
 * [NwsMetarTextApi] over a mock HTTP engine. NWS's own latest-METAR file is the only web transport
 * the NWS path may call — no Synoptic, no aviationweather.gov (user, 2026-10-15;
 * plans/261015-nws-web-leg-from-nws-tgftp-metar-text.md).
 */
@Category(MediumDuration::class)
class DesktopNwsWebLegTest {

    @Test
    fun `a stale NWS API is overtaken by NWS's own METAR text, and no other web host is asked`() = runTest {
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
