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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SynopticApiRadiusTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `parseRadiusTimeseries parses multi station response and cloud layers from cloud_layer_1_set_1d`() {
        val payload = """
        {
          "SUMMARY": {
            "RESPONSE_CODE": 1,
            "RESPONSE_MESSAGE": "OK"
          },
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
                "cloud_layer_1_set_1d": [{"sky_condition": "overcast", "height_agl": 365.76}],
                "metar_set_1": ["KNUQ 251515Z AUTO 36003KT 10SM OVC012 17/14 A3006 RMK AO2"]
              }
            },
            {
              "STID": "PWS01",
              "NAME": "Personal Weather Station",
              "LATITUDE": "37.4200",
              "LONGITUDE": "-122.0800",
              "ELEVATION": "50",
              "DISTANCE": "0.8",
              "MNET_ID": "65",
              "OBSERVATIONS": {
                "date_time": ["2026-08-25T15:20:00Z"],
                "air_temp_set_1": [18.0],
                "weather_summary_set_1d": ["broken clouds"],
                "cloud_layer_1_set_1d": [{"sky_condition": "broken", "height_agl": 600.0}]
              }
            }
          ]
        }
        """.trimIndent()

        val outcome = SynopticApi.parseRadiusTimeseries(json, payload)
        assertTrue(outcome is FetchOutcome.Success)
        val stations = (outcome as FetchOutcome.Success).value
        assertEquals(2, stations.size)

        val knuq = stations[0]
        assertEquals("KNUQ", knuq.info.id)
        assertEquals(NwsApi.StationType.OFFICIAL, knuq.info.type)
        assertEquals(1, knuq.observations.size)
        assertEquals(1, knuq.observations[0].cloudLayers.size)
        assertEquals("OVC", knuq.observations[0].cloudLayers[0].amount)

        val pws = stations[1]
        assertEquals("PWS01", pws.info.id)
        assertEquals(NwsApi.StationType.PERSONAL, pws.info.type)
        assertEquals(1, pws.observations.size)
        assertEquals(1, pws.observations[0].cloudLayers.size)
        assertEquals("BKN", pws.observations[0].cloudLayers[0].amount)
        assertEquals(600.0, pws.observations[0].cloudLayers[0].baseMeters ?: 0.0, 0.01)
    }

    @Test
    fun `fetchRadiusTimeseries with blank token returns failed without calling network`() {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("{}", HttpStatusCode.OK)
        }
        val api = SynopticApi(HttpClient(engine), json) { "" }
        val outcome = runBlocking { api.fetchRadiusTimeseries(37.4, -122.0) }
        assertEquals(0, requests)
        assertTrue(outcome is FetchOutcome.Failed)
    }
}
