package com.weatherwidget.desktop

import com.weatherwidget.data.remote.NwsApi
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class DesktopSynopticFallbackTest {

    @Test
    fun `fetchObservationsOnly falls back to Synoptic when NWS observations are stale`() = runTest {
        // Create service targeting NWS
        val service = DesktopWeatherService(37.4220, -122.0841, "NWS")

        // 1. Mock NwsApi using MockK
        val mockNwsApi = mockk<NwsApi>()
        val station = NwsApi.StationInfo("KNUQ", "Moffett Field", 37.4058, -122.0480, NwsApi.StationType.OFFICIAL)
        
        // Mock getGridPoint and getObservationStations to resolve our mock station
        val dummyGrid = NwsApi.GridPointInfo("MTR", 80, 80, "http://dummy/forecast", "http://dummy/stations")
        coEvery { mockNwsApi.getGridPoint(any(), any()) } returns dummyGrid
        coEvery { mockNwsApi.getObservationStations(any()) } returns listOf(station)

        // Mock getObservations to return a non-empty list of historical data
        val oldTimestampStr = ZonedDateTime.now().minusHours(3).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val mockNwsObs = NwsApi.Observation(
            timestamp = oldTimestampStr,
            temperatureCelsius = 20.0f,
            textDescription = "Clear",
            stationName = "Moffett Field"
        )
        
        coEvery { mockNwsApi.getObservations(any(), any(), any()) } returns listOf(mockNwsObs)
        coEvery { mockNwsApi.getLatestObservationDetailed(any()) } returns mockNwsObs

        // Set the private field 'nwsApi' using reflection
        val nwsApiField = DesktopWeatherService::class.java.getDeclaredField("nwsApi")
        nwsApiField.isAccessible = true
        nwsApiField.set(service, mockNwsApi)

        // 2. Mock Ktor HttpClient using MockEngine to intercept the Synoptic API call
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
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
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

        // Set the private field 'httpClient' using reflection
        val httpClientField = DesktopWeatherService::class.java.getDeclaredField("httpClient")
        httpClientField.isAccessible = true
        httpClientField.set(service, mockHttpClient)

        // Set the private field 'synopticApi' using reflection
        val mockSynopticApi = com.weatherwidget.data.remote.SynopticApi(mockHttpClient, json)
        val synopticApiField = DesktopWeatherService::class.java.getDeclaredField("synopticApi")
        synopticApiField.isAccessible = true
        synopticApiField.set(service, mockSynopticApi)

        // 3. Invoke public fetchObservationsOnly()
        val result = service.fetchObservationsOnly()
        
        // 4. Verify that the Synoptic fallback was triggered and returned the fresh data (in Fahrenheit)
        assertNotNull(result.currentTemp)
        val expectedF = (21.5f * 1.8f) + 32f
        assertEquals(expectedF, result.currentTemp!!, 0.01f)
        assertEquals("clear", result.currentCondition)
    }

    @Test
    fun `parseTimestamp handles timezone offsets with and without colons`() {
        val service = DesktopWeatherService(37.4220, -122.0841, "NWS")
        
        val parseTimestampMethod = DesktopWeatherService::class.java.getDeclaredMethod("parseTimestamp", String::class.java)
        parseTimestampMethod.isAccessible = true
        
        val tsWithColon = "2026-06-28T17:55:00-07:00"
        val epochWithColon = parseTimestampMethod.invoke(service, tsWithColon) as Long
        
        val tsWithoutColon = "2026-06-28T17:55:00-0700"
        val epochWithoutColon = parseTimestampMethod.invoke(service, tsWithoutColon) as Long
        
        assertEquals(epochWithColon, epochWithoutColon)
        
        val expectedEpoch = ZonedDateTime.parse(tsWithColon).toInstant().toEpochMilli()
        assertEquals(expectedEpoch, epochWithColon)
    }
}
