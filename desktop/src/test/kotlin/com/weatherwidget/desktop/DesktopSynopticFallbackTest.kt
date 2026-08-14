package com.weatherwidget.desktop

import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
class DesktopSynopticFallbackTest {

    @Test
    fun `fetchObservationsOnly falls back to Synoptic when NWS observations are stale`() = runTest {
        // 1. Mock NwsApi.
        val mockNwsApi = mockk<NwsApi>()
        val station = NwsApi.StationInfo("KNUQ", "Moffett Field", 37.4058, -122.0480, NwsApi.StationType.OFFICIAL)

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

        // 2. Mock Ktor HttpClient to serve the Synoptic API call.
        val freshTimestampStr = ZonedDateTime.now().minusMinutes(5).format(DateTimeFormatter.ISO_INSTANT)
        val synopticJsonPayload = """
            {
              "SUMMARY": {
                "RESPONSE_CODE": 1,
                "RESPONSE_MESSAGE": "OK"
              },
              "STATION": [
                {
                  "STID": "KNUQ",
                  "NAME": "Mountain View, Moffett Field",
                  "OBSERVATIONS": {
                    "date_time": ["$freshTimestampStr"],
                    "air_temp_set_1": [21.5],
                    "weather_summary_set_1d": ["clear"]
                  }
                }
              ]
            }
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            if (request.url.host == "api.synopticdata.com") {
                respond(
                    content = synopticJsonPayload,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
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
        val mockSynopticApi = SynopticApi(mockHttpClient, json)

        // 3. Constructor-inject the mocks (no reflection).
        val service = DesktopWeatherService(
            37.4220, -122.0841, "NWS",
            injectedHttpClient = mockHttpClient,
            injectedNwsApi = mockNwsApi,
            injectedSynopticApi = mockSynopticApi,
        )

        // 4. The Synoptic fallback must be triggered and return the fresh data (in Fahrenheit).
        val result = service.fetchObservationsOnly()

        assertNotNull(result.providerCurrentTemp)
        val expectedF = (21.5f * 1.8f) + 32f
        assertEquals(expectedF, result.providerCurrentTemp!!, 0.01f)
        assertEquals("clear", result.providerCurrentCondition)
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
