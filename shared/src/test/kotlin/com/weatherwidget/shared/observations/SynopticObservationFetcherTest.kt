package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.SynopticApi
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SynopticObservationFetcherTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `fetchObservations maps stations to SYNOPTIC readings with cloud cover`() {
        val payload = """
        {
          "SUMMARY": { "RESPONSE_CODE": 1 },
          "STATION": [
            {
              "STID": "KNUQ",
              "NAME": "Moffett Field",
              "LATITUDE": "37.4161",
              "LONGITUDE": "-122.0494",
              "ELEVATION": "36",
              "DISTANCE": "2.5",
              "MNET_ID": "1",
              "OBSERVATIONS": {
                "date_time": ["2026-08-25T15:15:00Z"],
                "air_temp_set_1": [17.2],
                "weather_summary_set_1d": ["overcast"],
                "metar_set_1": ["KNUQ 251515Z AUTO 36003KT 10SM OVC012 17/14 A3006 RMK AO2"]
              }
            }
          ]
        }
        """.trimIndent()

        val engine = MockEngine {
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = SynopticApi(HttpClient(engine), json) { "mock-token" }
        val logs = mutableListOf<String>()
        val fetcher = SynopticObservationFetcher(api) { tag, message, _ ->
            logs.add("$tag $message")
        }

        val readings = runBlocking { fetcher.fetchObservations(37.4, -122.0) }
        assertEquals(1, readings.size)
        val reading = readings[0]
        assertEquals("KNUQ", reading.stationId)
        assertEquals(WeatherSource.SYNOPTIC.id, reading.api)
        assertEquals(100, reading.cloudCoverLow)
        assertTrue(logs.any { it.startsWith("SYNOPTIC_FETCH") })
    }
}
